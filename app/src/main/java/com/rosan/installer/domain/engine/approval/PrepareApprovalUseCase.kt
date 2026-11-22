// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.ai.model.AiReviewModel
import com.rosan.installer.domain.ai.model.AiUnavailable
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.InstallEvidence
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.ai.usecase.AiAdvisorSettings
import com.rosan.installer.domain.ai.usecase.AutoDialogInstallEvidenceMapper
import com.rosan.installer.domain.ai.usecase.GateInput
import com.rosan.installer.domain.ai.usecase.RequestInstallRiskAssessmentUseCase
import com.rosan.installer.domain.engine.model.packageinfo.AppEntity
import com.rosan.installer.domain.engine.model.packageinfo.PackageAnalysisResult
import com.rosan.installer.domain.engine.policy.HumanApprovalState
import com.rosan.installer.domain.engine.policy.PolicyAdvice
import com.rosan.installer.domain.engine.policy.PolicyDecision
import com.rosan.installer.domain.engine.policy.PolicyDisposition
import com.rosan.installer.domain.engine.policy.PolicyInput
import com.rosan.installer.domain.engine.policy.PolicyResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Implements: ADR-002.4, ADR-002.6
 * Enforces: REQ-IX-FR-003, REQ-IX-FR-005, REQ-IX-FR-007, REQ-IX-FR-012,
 *           REQ-IX-ARCH-001, REQ-IX-INV-004, REQ-IX-COST-001
 *
 * The single assessment trigger for the prepare dialog. With the advisor **on**
 * it runs exactly one advisory call per operation and routes the result through
 * the [ApprovalCoordinator] (the only path to the gateway, ARCH-001). With the
 * advisor **off** it returns [Outcome.Baseline] immediately — no evidence
 * mapping, no model call, and the coordinator is **never touched** (FR-012,
 * Q9 Option (b)).
 *
 * One trigger, no re-dispatch: a per-operation `started` guard makes a second
 * call for the same operation a no-op ([Outcome.AlreadyStarted]) so
 * recomposition or re-entering the stage cannot cost a second model call
 * (COST-001).
 */
class PrepareApprovalUseCase(
    private val assessment: RequestInstallRiskAssessmentUseCase,
    private val mapper: AutoDialogInstallEvidenceMapper = AutoDialogInstallEvidenceMapper(),
) {
    /** What the prepare step produced for the caller to render. */
    sealed interface Outcome {
        /** Advisor off or out-of-scope: run the baseline `session.install(true)`. */
        data object Baseline : Outcome

        /** A prior call for this operation already started; do not dispatch again. */
        data object AlreadyStarted : Outcome

        /** Advice is unavailable; REQUIRE_REVIEW, no model call happened. */
        data class Unavailable(val reason: AiUnavailable) : Outcome

        /** The coordinator routed a decision; render it. */
        data class Decided(
            val state: ApprovalState,
            val decision: PolicyResult,
            val model: AiReviewModel?,
            /** Why advice was withheld, if the gate blocked the call (else null). */
            val unavailable: AiUnavailable?,
            /** The engine input; retained so Continue can revalidate with no model call. */
            val baseInput: PolicyInput,
        ) : Outcome
    }

    private val started = ConcurrentHashMap.newKeySet<String>()

    /**
     * Runs the prepare sequence once for [operationId]. The [coordinator] is the
     * per-session coordinator; it is used only on the advisor-on path.
     */
    suspend fun prepare(
        operationId: String,
        attemptId: String,
        advisorEnabled: Boolean,
        gate: GateInput,
        analysis: PackageAnalysisResult,
        selectedApk: AppEntity.BaseEntity?,
        coordinator: ApprovalCoordinator,
    ): Outcome {
        // Advisor off => baseline InstallerX. Do nothing advisory; no coordinator.
        if (!advisorEnabled) return Outcome.Baseline

        // Out-of-scope (splits/modules/multi-APK) => baseline, 0 AI calls.
        val mapped = mapper.map(analysis, selectedApk)
        if (mapped !is AutoDialogInstallEvidenceMapper.Result.Mapped) {
            return Outcome.Baseline
        }
        val evidence = mapped.evidence
        val policy = EvaluatedPolicyFactory.from(evidence)
        val compatibility = compatibilityOf(evidence)

        // Gate withheld the call (consent off / unconfigured / network off): do
        // not dispatch, but still run the deterministic engine so the decision
        // and Continue go through the coordinator (never an Unavailable shortcut
        // that bypasses the engine). 0 model dispatches.
        if (!AiAdvisorSettings.allowsDispatch(gate)) {
            if (!started.add(operationId)) return Outcome.AlreadyStarted
            val unavailable = gate.toUnavailable()
            val withheldInput = PolicyInput(
                policy = policy,
                evidence = evidence.completeness(),
                compatibility = compatibility,
                advice = withheldAdvice(gate),
                human = HumanApprovalState.NOT_OBTAINED,
            )
            return route(operationId, attemptId, coordinator, withheldInput, model = null, unavailable)
        }

        // One trigger per operation: guard BEFORE dispatch.
        if (!started.add(operationId)) return Outcome.AlreadyStarted

        val status = assessment.invoke(evidence)
        val reviewModel = status.assessment?.let { fromAssessment(it) }

        val policyInput = PolicyInput(
            policy = policy,
            evidence = status.completeness,
            compatibility = compatibility,
            advice = if (status.assessment != null) {
                PolicyAdvice.Valid(status.assessment)
            } else {
                PolicyAdvice.Failed(status.failure?.code ?: FailureCode.INVALID_OUTPUT)
            },
            human = HumanApprovalState.NOT_OBTAINED,
        )

        return route(operationId, attemptId, coordinator, policyInput, reviewModel, unavailable = null)
    }

    private suspend fun route(
        operationId: String,
        attemptId: String,
        coordinator: ApprovalCoordinator,
        input: PolicyInput,
        model: AiReviewModel?,
        unavailable: AiUnavailable?,
    ): Outcome {
        val outcome = coordinator.decide(operationId, attemptId, input)
        return when (outcome) {
            is ApprovalCoordinator.Outcome.Decided -> Outcome.Decided(
                state = outcome.state,
                decision = outcome.result,
                model = model,
                unavailable = unavailable,
                baseInput = input,
            )

            is ApprovalCoordinator.Outcome.Invalidated -> Outcome.Decided(
                state = ApprovalState.INVALIDATED,
                decision = outcome.result,
                model = model,
                unavailable = unavailable,
                baseInput = input,
            )

            ApprovalCoordinator.Outcome.PersistenceFailed ->
                Outcome.Unavailable(AiUnavailable.PersistenceFailed)

            else -> Outcome.Decided(
                state = ApprovalState.AWAIT_HUMAN,
                decision = FALLBACK_REVIEW,
                model = model,
                unavailable = unavailable,
                baseInput = input,
            )
        }
    }

    /** PolicyAdvice for a gate-withheld call: consent-off vs engine failure code. */
    private fun withheldAdvice(gate: GateInput): PolicyAdvice = when {
        !gate.consentGranted -> PolicyAdvice.Unusable.CONSENT_DECLINED

        !gate.networkAllowed -> PolicyAdvice.Failed(FailureCode.NETWORK_DISABLED)

        // Unconfigured: the call cannot be made; no advice is available.
        else -> PolicyAdvice.Failed(FailureCode.SERVICE_ERROR)
    }

    private fun compatibilityOf(evidence: InstallEvidence): LocalCompatibility {
        val platform = evidence.platformCompatibility
        val abi = evidence.abiCompatibility
        return when {
            platform == LocalCompatibility.INCOMPATIBLE || abi == LocalCompatibility.INCOMPATIBLE ->
                LocalCompatibility.INCOMPATIBLE

            platform == LocalCompatibility.COMPATIBLE && abi == LocalCompatibility.COMPATIBLE ->
                LocalCompatibility.COMPATIBLE

            else -> LocalCompatibility.UNKNOWN
        }
    }

    private fun GateInput.toUnavailable(): AiUnavailable = when {
        !networkAllowed -> AiUnavailable.NetworkOff
        !endpointConfigured -> AiUnavailable.Unconfigured
        !consentGranted -> AiUnavailable.ConsentOff
        else -> AiUnavailable.ModelFailure(FailureCode.NETWORK_DISABLED)
    }

    private fun fromAssessment(valid: AssessmentResult.Valid): AiReviewModel {
        val a = valid.assessment
        val favorable = a.installSuccessLikelihood == InstallSuccessLikelihood.HIGH &&
            a.securityRisk == SecurityRisk.LOW
        return AiReviewModel(
            likelihood = a.installSuccessLikelihood,
            risk = a.securityRisk,
            reasons = a.reasons,
            agreement = favorable,
            modelId = a.modelId,
            promptVersion = a.promptVersion,
        )
    }

    private companion object {
        val FALLBACK_REVIEW = PolicyResult(
            PolicyDecision.REQUIRE_REVIEW,
            PolicyDisposition.AWAIT_HUMAN,
            "DR-08",
            "ADVICE_UNUSABLE",
        )
    }
}
