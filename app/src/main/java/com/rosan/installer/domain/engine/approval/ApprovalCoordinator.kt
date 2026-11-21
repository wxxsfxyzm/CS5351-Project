// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.engine.install.GatewayResult
import com.rosan.installer.domain.engine.install.InstallationGateway
import com.rosan.installer.domain.engine.policy.DeterministicPolicyEngine
import com.rosan.installer.domain.engine.policy.HumanApprovalState
import com.rosan.installer.domain.engine.policy.PolicyDisposition
import com.rosan.installer.domain.engine.policy.PolicyInput
import com.rosan.installer.domain.engine.policy.PolicyResult

/**
 * Implements: ADR-002.6, ADR-002.7
 * Enforces: REQ-IX-FR-005, REQ-IX-FR-006, REQ-IX-FR-007, REQ-IX-INV-003,
 *           REQ-IX-INV-004, REQ-IX-INV-005, REQ-IX-REL-003, REQ-IX-REL-004
 *
 * The sequencer that owns the chain evidence -> advice -> policy -> human ->
 * submission. It is the only caller of [InstallationGateway.submit] and the
 * only producer of [HumanApprovalState.VALID_APPROVAL_AND_REVALIDATION_PASSED].
 *
 * Critical ordering (DR-09, the INV-003 boundary): on Continue the coordinator
 * reads current facts once to build the binding, then re-reads the provider
 * **again** and compares field by field against the stored binding before it
 * acquires the submission permit. Reusing the first snapshot would miss an APK
 * replacement or config change (ADR-002.7). Submission happens at most once
 * per operation via [SubmissionLedger].
 *
 * The coordinator holds no model SDK, no network, and no backend except the
 * gateway and facts provider (permission-model.md section 9.1).
 */
class ApprovalCoordinator(
    private val store: ApprovalStateStore,
    private val ledger: SubmissionLedger,
    private val gateway: InstallationGateway,
    private val factsProvider: BindingFactsProvider,
    private val engine: DeterministicPolicyEngine = DeterministicPolicyEngine(),
) {
    /** Result of a coordinator step, for callers and the UI to display. */
    sealed interface Outcome {
        /** Engine decision routed to a gated state; no submission. */
        data class Decided(val state: ApprovalState, val result: PolicyResult) : Outcome

        /** Revalidation failed after approval (DR-10). No submission. */
        data class Invalidated(val result: PolicyResult) : Outcome

        /** Continue disabled (e.g. RECOLLECT or non-review state). */
        data object ContinueRejected : Outcome

        /** Submitted exactly once through the gateway. */
        data class Submitted(val gateway: GatewayResult) : Outcome

        /** A prior submission already consumed the permit; no new submission. */
        data object AlreadySubmitted : Outcome

        /** Cancelled before submission. */
        data object Cancelled : Outcome

        /** Persistence failed; automatic submission stopped (REL-002). */
        data object PersistenceFailed : Outcome

        /** Recovered state; see [state]. No automatic submission. */
        data class Recovered(val state: ApprovalState) : Outcome
    }

    /**
     * Runs the deterministic policy engine once and routes to a gated state.
     * Performs no submission; [PolicyDisposition.EXECUTE_ONCE] from DR-05 is
     * honoured by [autoSubmit] only.
     */
    suspend fun decide(
        operationId: String,
        attemptId: String,
        input: PolicyInput,
    ): Outcome {
        val result = engine.evaluate(input)
        val state = when (result.disposition) {
            PolicyDisposition.BLOCKED -> ApprovalState.BLOCKED
            PolicyDisposition.RECOLLECT -> ApprovalState.RECOLLECT
            PolicyDisposition.INVALIDATED -> ApprovalState.INVALIDATED
            PolicyDisposition.EXECUTE_ONCE -> ApprovalState.EXECUTE_ONCE
            PolicyDisposition.AWAIT_HUMAN -> ApprovalState.AWAIT_HUMAN
        }
        val persisted = store.persist(
            ApprovalSnapshot(operationId, attemptId, binding = null, state = state, decision = result),
        )
        if (!persisted) return Outcome.PersistenceFailed
        return Outcome.Decided(state, result)
    }

    /**
     * DR-05 auto path. The decision is **not** taken from a caller-supplied
     * [PolicyResult] (it could be forged to reach EXECUTE_ONCE and bypass the
     * engine: REQ-IX-ARCH-001). Instead this reloads the snapshot that
     * [decide] persisted and requires both:
     *
     *  - `state == EXECUTE_ONCE`, and
     *  - the persisted `decision.disposition == EXECUTE_ONCE`.
     *
     * Otherwise it rejects with zero submissions. It then binds the current
     * object, persists the binding **before** submitting (REQ-IX-REL-002), and
     * consumes the single permit. A persistence failure means no submission.
     */
    suspend fun autoSubmit(operationId: String, attemptId: String): Outcome {
        val snapshot = store.load(operationId) ?: return Outcome.ContinueRejected
        val decision = snapshot.decision ?: return Outcome.ContinueRejected
        if (snapshot.state != ApprovalState.EXECUTE_ONCE ||
            decision.disposition != PolicyDisposition.EXECUTE_ONCE
        ) {
            return Outcome.ContinueRejected
        }

        val facts = factsProvider.currentFacts(operationId) ?: return Outcome.PersistenceFailed
        val binding = ApprovalBinding.create(operationId, attemptId, facts)
            ?: return Outcome.PersistenceFailed

        // Persist the bound object before submitting; a failed write stops submission.
        val boundSnapshot = snapshot.copy(attemptId = attemptId, binding = binding)
        if (!store.persist(boundSnapshot)) return Outcome.PersistenceFailed

        return submitOnce(binding, decision)
    }

    /**
     * First Continue in AWAIT_HUMAN: create the binding from current facts,
     * re-read the facts and revalidate field by field, then submit exactly
     * once if revalidation passes (DR-09), or INVALIDATE with zero submissions
     * if it fails (DR-10). Makes no additional model calls.
     *
     * [baseInput] is the caller's original AWAIT_HUMAN [PolicyInput] (the same
     * facts that produced the review). Only its `human` field is replaced with
     * the revalidation result, so DR-01/DR-03/... precedence is preserved: a
     * hard block still returns BLOCK, never DR-09.
     */
    suspend fun onContinue(
        operationId: String,
        attemptId: String,
        baseInput: PolicyInput,
    ): Outcome {
        val snapshot = store.load(operationId) ?: return Outcome.ContinueRejected
        if (snapshot.state != ApprovalState.AWAIT_HUMAN) return Outcome.ContinueRejected

        // First read: build the bound object.
        val buildFacts = factsProvider.currentFacts(operationId) ?: return Outcome.PersistenceFailed
        val bound = ApprovalBinding.create(operationId, attemptId, buildFacts)
            ?: return Outcome.PersistenceFailed

        // Persist the approval binding before revalidation (REL-002 B5).
        val boundSnapshot = snapshot.copy(
            attemptId = attemptId,
            binding = bound,
            state = ApprovalState.APPROVED,
        )
        if (!store.persist(boundSnapshot)) return Outcome.PersistenceFailed

        // Second read: revalidate against current facts (ADR-002.7 TOCTOU).
        val recheckFacts = factsProvider.currentFacts(operationId)
        val recheck = recheckFacts?.let { ApprovalBinding.create(operationId, attemptId, it) }
        val passed = recheck != null && recheck == bound

        val revalidationInput = baseInput.copy(
            human = if (passed) {
                HumanApprovalState.VALID_APPROVAL_AND_REVALIDATION_PASSED
            } else {
                HumanApprovalState.REVALIDATION_FAILED_AFTER_APPROVAL
            },
        )
        val decision = engine.evaluate(revalidationInput)

        if (decision.disposition != PolicyDisposition.EXECUTE_ONCE || !passed) {
            val invalid = boundSnapshot.copy(state = ApprovalState.INVALIDATED, decision = decision)
            if (!store.persist(invalid)) return Outcome.PersistenceFailed
            return Outcome.Invalidated(decision)
        }

        // DR-09 passed: consume the single permit and submit once.
        return submitOnce(bound, decision)
    }

    /** Cancel before submission: no submission, no approval reuse (FR-006). */
    suspend fun onCancel(operationId: String, attemptId: String): Outcome {
        val snapshot = store.load(operationId)
        val state = snapshot?.state ?: ApprovalState.RECEIVED
        if (state == ApprovalState.SUBMITTED || state == ApprovalState.RESULT_UNKNOWN) {
            // Already submitted; cancellation cannot unrevert an unknown result.
            return Outcome.AlreadySubmitted
        }
        val cancelled = ApprovalSnapshot(
            operationId = operationId,
            attemptId = attemptId,
            binding = snapshot?.binding,
            state = ApprovalState.CANCELLED,
            decision = snapshot?.decision,
        )
        if (!store.persist(cancelled)) return Outcome.PersistenceFailed
        return Outcome.Cancelled
    }

    /**
     * Route a reported model/advice failure through the engine (INV-004). Never
     * auto-approves; a hard prohibition remains BLOCK.
     */
    suspend fun onModelFailure(
        operationId: String,
        attemptId: String,
        input: PolicyInput,
    ): Outcome = decide(operationId, attemptId, input)

    /**
     * Recover from persisted state after a restart (REL-003, R1-R5). Never
     * auto-approves and never replays a submission.
     */
    fun onRecovered(snapshot: ApprovalSnapshot): Outcome = when (snapshot.state) {
        ApprovalState.AWAIT_HUMAN,
        ApprovalState.APPROVED,
        ApprovalState.EXECUTE_ONCE,
        -> Outcome.Recovered(ApprovalState.AWAIT_HUMAN)

        ApprovalState.SUBMITTED,
        ApprovalState.RESULT_UNKNOWN,
        -> Outcome.Recovered(ApprovalState.RESULT_UNKNOWN)

        else -> Outcome.Recovered(snapshot.state)
    }

    private suspend fun submitOnce(bound: ApprovalBinding, decision: PolicyResult): Outcome {
        if (!ledger.tryAcquire(bound.operationId)) return Outcome.AlreadySubmitted
        val result = gateway.submit(bound)
        val nextState = when (result) {
            is GatewayResult.Submitted -> ApprovalState.SUBMITTED

            is GatewayResult.ResultUnknown -> ApprovalState.RESULT_UNKNOWN

            // The permit is consumed; a backend failure is terminal, not a
            // return to AWAIT_HUMAN, so the operation cannot be re-submitted.
            is GatewayResult.Failed -> ApprovalState.FAILED
        }
        store.persist(
            ApprovalSnapshot(
                operationId = bound.operationId,
                attemptId = bound.attemptId,
                binding = bound,
                state = nextState,
                decision = decision,
            ),
        )
        return Outcome.Submitted(result)
    }
}
