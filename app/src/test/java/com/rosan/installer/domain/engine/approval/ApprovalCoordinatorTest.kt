// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.InstallAssessment
import com.rosan.installer.domain.ai.model.InstallModeSignal
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.engine.policy.EvaluatedPolicy
import com.rosan.installer.domain.engine.policy.HumanApprovalState
import com.rosan.installer.domain.engine.policy.PolicyAdvice
import com.rosan.installer.domain.engine.policy.PolicyDecision
import com.rosan.installer.domain.engine.policy.PolicyDisposition
import com.rosan.installer.domain.engine.policy.PolicyInput
import com.rosan.installer.domain.engine.policy.PolicyResult
import com.rosan.installer.domain.settings.model.config.Authorizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies: REQ-IX-FR-001, REQ-IX-FR-005, REQ-IX-FR-006, REQ-IX-FR-007,
 *           REQ-IX-INV-003, REQ-IX-INV-004
 *
 * Coordinator behavior for the decision and Continue/Cancel paths, including
 * the critical re-read revalidation (ADR-002.7) that drives DR-09 vs DR-10.
 */
class ApprovalCoordinatorTest {
    private val facts = BindingFacts(
        apkSha256 = "a".repeat(64),
        targetPackageName = "com.example.target",
        targetUserId = 0,
        installMode = InstallModeSignal.AutoDialog,
        installationBackend = Authorizer.None,
        configSnapshotHash = "cfg-1",
        policyVersion = "policy-v1",
    )

    private val manualPolicy = EvaluatedPolicy("policy-v1", hardBlock = false, baselineAllowsAuto = false, autoRulesSatisfied = false)
    private val autoPolicy = EvaluatedPolicy("policy-v1", hardBlock = false, baselineAllowsAuto = true, autoRulesSatisfied = true)
    private val hardBlockPolicy = manualPolicy.copy(hardBlock = true)
    private val favorableAdvice = validAdvice()

    private fun reviewInput() = PolicyInput(
        policy = manualPolicy,
        evidence = EvidenceCompleteness.COMPLETE,
        compatibility = LocalCompatibility.COMPATIBLE,
        advice = favorableAdvice,
        human = HumanApprovalState.NOT_OBTAINED,
    )

    private class Harness(
        gateway: FakeInstallationGateway = FakeInstallationGateway(),
        provider: BindingFactsProvider,
    ) {
        val store = InMemoryApprovalStateStore()
        val ledger = SubmissionLedger()
        val gateway = gateway
        val coordinator = ApprovalCoordinator(store, ledger, gateway, provider)
    }

    @Test
    fun receivedCreatesIdsAndNoSubmission() = runTest {
        // Verifies: REQ-IX-FR-001 — before a decision, submissions are 0.
        val h = Harness(provider = FakeBindingFactsProvider(facts))
        val outcome = h.coordinator.decide("op-1", "attempt-1", reviewInput())
        assertIs<ApprovalCoordinator.Outcome.Decided>(outcome)
        assertEquals(0, h.gateway.submissions.size)
    }

    @Test
    fun hardBlockWithApprovalAndContinueSubmitsZero() = runTest {
        // Verifies: REQ-IX-INV-002/004 — a hard block stays BLOCK even on Continue.
        val h = Harness(provider = FakeBindingFactsProvider(facts, facts))
        val blocked = h.coordinator.decide("op-1", "attempt-1", reviewInput().copy(policy = hardBlockPolicy))
        assertEquals(ApprovalState.BLOCKED, assertIs<ApprovalCoordinator.Outcome.Decided>(blocked).state)
        // Continue is rejected because the stored state is BLOCKED, not AWAIT_HUMAN.
        val cont = h.coordinator.onContinue("op-1", "attempt-1", reviewInput().copy(policy = hardBlockPolicy))
        assertEquals(ApprovalCoordinator.Outcome.ContinueRejected, cont)
        assertEquals(0, h.gateway.submissions.size)
    }

    @Test
    fun continueWithIncompleteEvidenceDoesNotSubmit() = runTest {
        // Verifies: REQ-IX-FR-005 DR-03 — RECOLLECT disables Continue.
        val h = Harness(provider = FakeBindingFactsProvider(facts, facts))
        val recollect = h.coordinator.decide(
            "op-1",
            "attempt-1",
            reviewInput().copy(evidence = EvidenceCompleteness.KEY_UNKNOWN),
        )
        assertEquals(ApprovalState.RECOLLECT, assertIs<ApprovalCoordinator.Outcome.Decided>(recollect).state)
        assertEquals(
            ApprovalCoordinator.Outcome.ContinueRejected,
            h.coordinator.onContinue("op-1", "attempt-1", reviewInput().copy(evidence = EvidenceCompleteness.KEY_UNKNOWN)),
        )
        assertEquals(0, h.gateway.submissions.size)
    }

    @Test
    fun firstContinueBindsRevalidatesAndSubmitsOnce() = runTest {
        // Verifies: REQ-IX-FR-005 DR-09, REQ-IX-FR-006 — one submission, DR-09.
        val h = Harness(provider = FakeBindingFactsProvider(facts, facts))
        h.coordinator.decide("op-1", "attempt-1", reviewInput())
        val outcome = h.coordinator.onContinue("op-1", "attempt-1", reviewInput())
        val submitted = assertIs<ApprovalCoordinator.Outcome.Submitted>(outcome)
        assertEquals(com.rosan.installer.domain.engine.install.GatewayResult.Submitted, submitted.gateway)
        assertEquals(1, h.gateway.submissions.size)
        assertEquals(facts.apkSha256, h.gateway.submissions.single().apkSha256)
        assertEquals(ApprovalState.SUBMITTED, h.store.load("op-1")?.state)
    }

    @Test
    fun providerSecondReadDiffersInvalidates() = runTest {
        // Verifies: REQ-IX-INV-003, DR-10 (ADR-002.7 re-read) — changed facts after
        // the binding is built invalidate the approval with 0 submissions.
        val variants = listOf(
            facts.copy(apkSha256 = "b".repeat(64)),
            facts.copy(configSnapshotHash = "cfg-2"),
            facts.copy(targetUserId = 10),
        )
        for (changed in variants) {
            val h = Harness(provider = FakeBindingFactsProvider(facts, changed))
            h.coordinator.decide("op-1", "attempt-1", reviewInput())
            val outcome = h.coordinator.onContinue("op-1", "attempt-1", reviewInput())
            val invalid = assertIs<ApprovalCoordinator.Outcome.Invalidated>(outcome, "change must invalidate: $changed")
            assertEquals(PolicyDisposition.INVALIDATED, invalid.result.disposition)
            assertEquals("DR-10", invalid.result.ruleId)
            assertEquals(0, h.gateway.submissions.size)
        }
    }

    @Test
    fun secondReadMissingFactInvalidates() = runTest {
        // Verifies: REQ-IX-INV-003 — a fact that becomes unknown on re-read invalidates.
        val h = Harness(provider = FakeBindingFactsProvider(facts, facts.copy(apkSha256 = null)))
        h.coordinator.decide("op-1", "attempt-1", reviewInput())
        val outcome = h.coordinator.onContinue("op-1", "attempt-1", reviewInput())
        assertIs<ApprovalCoordinator.Outcome.Invalidated>(outcome)
        assertEquals(0, h.gateway.submissions.size)
    }

    @Test
    fun callerCannotForgeValidApproval() = runTest {
        // Verifies: REQ-IX-INV-003 — Continue on a non-relre-revalidated path does
        // not submit; the only submission path re-reads facts.
        val h = Harness(provider = FakeBindingFactsProvider(facts))
        // No prior decide(): state is not AWAIT_HUMAN, so Continue is rejected.
        assertEquals(
            ApprovalCoordinator.Outcome.ContinueRejected,
            h.coordinator.onContinue("op-1", "attempt-1", reviewInput()),
        )
        assertEquals(0, h.gateway.submissions.size)
    }

    @Test
    fun everyFailureCodeDoesNotSubmit() = runTest {
        // Verifies: REQ-IX-INV-004 — each failure routes to AWAIT_HUMAN, never submits.
        for (code in FailureCode.entries) {
            val h = Harness(provider = FakeBindingFactsProvider(facts))
            val input = reviewInput().copy(advice = PolicyAdvice.Failed(code))
            val outcome = h.coordinator.onModelFailure("op-1", "attempt-1", input)
            assertEquals(ApprovalState.AWAIT_HUMAN, assertIs<ApprovalCoordinator.Outcome.Decided>(outcome).state, code.name)
            assertEquals(0, h.gateway.submissions.size, code.name)
        }
    }

    @Test
    fun unusableAdviceDoesNotSubmit() = runTest {
        // Verifies: REQ-IX-INV-004 — Unusable advice (consent/timeout/overage) never auto-submits.
        val unusables = listOf(
            PolicyAdvice.Unusable.CONSENT_DECLINED,
            PolicyAdvice.Unusable.UNAVAILABLE,
            PolicyAdvice.Unusable.BUDGET_REJECTED,
            PolicyAdvice.Unusable.OVERAGE,
            PolicyAdvice.Unusable.BLOCKED_THIRD,
        )
        for (advice in unusables) {
            val h = Harness(provider = FakeBindingFactsProvider(facts))
            val outcome = h.coordinator.onModelFailure("op-1", "attempt-1", reviewInput().copy(advice = advice))
            assertEquals(ApprovalState.AWAIT_HUMAN, assertIs<ApprovalCoordinator.Outcome.Decided>(outcome).state, advice.toString())
            assertEquals(0, h.gateway.submissions.size)
        }
    }

    @Test
    fun allowAutoPathRoutesToExecuteOnceWithoutSubmission() = runTest {
        // Verifies: REQ-IX-FR-005 DR-05 — decide routes to EXECUTE_ONCE; decide
        // itself never submits (submission is a separate single-permit step).
        val h = Harness(provider = FakeBindingFactsProvider(facts))
        val input = reviewInput().copy(policy = autoPolicy)
        val outcome = h.coordinator.decide("op-1", "attempt-1", input)
        assertEquals(ApprovalState.EXECUTE_ONCE, assertIs<ApprovalCoordinator.Outcome.Decided>(outcome).state)
        assertEquals(0, h.gateway.submissions.size)
    }

    @Test
    fun autoSubmitWithoutDecideIsRejectedAndSubmitsZero() = runTest {
        // Verifies: REQ-IX-ARCH-001, DR-05 — autoSubmit trusts only persisted
        // state; without a prior decide it cannot submit.
        val h = Harness(provider = FakeBindingFactsProvider(facts))
        val outcome = h.coordinator.autoSubmit("op-1", "attempt-1")
        assertEquals(ApprovalCoordinator.Outcome.ContinueRejected, outcome)
        assertEquals(0, h.gateway.submissions.size)
    }

    @Test
    fun autoSubmitAfterAwaitHumanIsRejectedAndSubmitsZero() = runTest {
        // Verifies: REQ-IX-ARCH-001 — a forged EXECUTE_ONCE cannot be produced
        // when the persisted decision is AWAIT_HUMAN; autoSubmit is rejected.
        val h = Harness(provider = FakeBindingFactsProvider(facts))
        h.coordinator.decide("op-1", "attempt-1", reviewInput()) // AWAIT_HUMAN
        val outcome = h.coordinator.autoSubmit("op-1", "attempt-1")
        assertEquals(ApprovalCoordinator.Outcome.ContinueRejected, outcome)
        assertEquals(0, h.gateway.submissions.size)
    }

    @Test
    fun autoSubmitAfterExecuteOnceSubmitsExactlyOnce() = runTest {
        // Verifies: REQ-IX-FR-005 DR-05 — a persisted EXECUTE_ONCE decision
        // submits exactly once through the gateway.
        val h = Harness(provider = FakeBindingFactsProvider(facts))
        h.coordinator.decide("op-1", "attempt-1", reviewInput().copy(policy = autoPolicy))
        val outcome = h.coordinator.autoSubmit("op-1", "attempt-1")
        assertIs<ApprovalCoordinator.Outcome.Submitted>(outcome)
        assertEquals(1, h.gateway.submissions.size)
        assertEquals(facts.apkSha256, h.gateway.submissions.single().apkSha256)
        assertEquals(ApprovalState.SUBMITTED, h.store.load("op-1")?.state)
    }

    @Test
    fun twoAutoSubmitsSubmitAtMostOnce() = runTest {
        // Verifies: REQ-IX-INV-005 — the single permit is consumed by the first
        // autoSubmit; the second does not submit again.
        val h = Harness(provider = FakeBindingFactsProvider(facts, facts))
        h.coordinator.decide("op-1", "attempt-1", reviewInput().copy(policy = autoPolicy))
        h.coordinator.autoSubmit("op-1", "attempt-1")
        val second = h.coordinator.autoSubmit("op-1", "attempt-1")
        // The stored state is now SUBMITTED, so the permit/state guard rejects it.
        assertEquals(ApprovalCoordinator.Outcome.ContinueRejected, second)
        assertEquals(1, h.gateway.submissions.size)
    }

    @Test
    fun autoSubmitPersistenceFailureSubmitsZero() = runTest {
        // Verifies: REQ-IX-REL-002 — a failed durable write of the bound object
        // stops submission.
        val h = Harness(provider = FakeBindingFactsProvider(facts))
        h.coordinator.decide("op-1", "attempt-1", reviewInput().copy(policy = autoPolicy))
        h.store.failWrites = true
        val outcome = h.coordinator.autoSubmit("op-1", "attempt-1")
        assertEquals(ApprovalCoordinator.Outcome.PersistenceFailed, outcome)
        assertEquals(0, h.gateway.submissions.size)
    }

    @Test
    fun gatewayFailureLeavesTerminalFailedStateAndDoesNotResubmit() = runTest {
        // Verifies: REQ-IX-INV-005 — a backend failure consumes the permit; the
        // state is the terminal FAILED, not AWAIT_HUMAN, and no replay occurs.
        val h = Harness(
            gateway = FakeInstallationGateway(com.rosan.installer.domain.engine.install.GatewayResult.Failed("boom")),
            provider = FakeBindingFactsProvider(facts),
        )
        h.coordinator.decide("op-1", "attempt-1", reviewInput().copy(policy = autoPolicy))
        h.coordinator.autoSubmit("op-1", "attempt-1")
        assertEquals(ApprovalState.FAILED, h.store.load("op-1")?.state)
        val replay = h.coordinator.autoSubmit("op-1", "attempt-1")
        // FAILED is terminal, so the state guard rejects a replay; no second submit.
        assertEquals(ApprovalCoordinator.Outcome.ContinueRejected, replay)
        assertEquals(1, h.gateway.submissions.size)
    }

    private fun validAdvice() = PolicyAdvice.Valid(
        AssessmentResult.Valid(
            InstallAssessment(
                InstallSuccessLikelihood.HIGH,
                SecurityRisk.LOW,
                listOf("fixture"),
                listOf("evidence.target_sdk"),
                "stub",
                "v1",
            ),
        ),
    )
}
