// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.InstallAssessment
import com.rosan.installer.domain.ai.model.InstallModeSignal
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.engine.policy.EvaluatedPolicy
import com.rosan.installer.domain.engine.policy.HumanApprovalState
import com.rosan.installer.domain.engine.policy.PolicyAdvice
import com.rosan.installer.domain.engine.policy.PolicyInput
import com.rosan.installer.domain.settings.model.config.Authorizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Verifies: REQ-IX-FR-007, REQ-IX-ARCH-001
 *
 * Only the coordinator reaches the gateway, and only after a decision and
 * revalidation. The gateway spy records exactly what was submitted.
 */
class ChokePointTest {
    private val facts = BindingFacts(
        apkSha256 = "a".repeat(64),
        targetPackageName = "com.example.target",
        targetUserId = 0,
        installMode = InstallModeSignal.AutoDialog,
        installationBackend = Authorizer.None,
        configSnapshotHash = "cfg-1",
        policyVersion = "policy-v1",
    )

    private fun reviewInput() = PolicyInput(
        policy = EvaluatedPolicy("policy-v1", hardBlock = false, baselineAllowsAuto = false, autoRulesSatisfied = false),
        evidence = EvidenceCompleteness.COMPLETE,
        compatibility = LocalCompatibility.COMPATIBLE,
        advice = PolicyAdvice.Valid(
            AssessmentResult.Valid(
                InstallAssessment(
                    InstallSuccessLikelihood.HIGH,
                    SecurityRisk.LOW,
                    emptyList(),
                    listOf("evidence.target_sdk"),
                    "stub",
                    "v1",
                ),
            ),
        ),
        human = HumanApprovalState.NOT_OBTAINED,
    )

    @Test
    fun onlyGatewaySubmitsAfterRevalidation() = runTest {
        // Verifies: REQ-IX-ARCH-001 — decide alone submits nothing; Continue submits once via gateway.
        val store = InMemoryApprovalStateStore()
        val ledger = SubmissionLedger()
        val gateway = FakeInstallationGateway()
        val coordinator = ApprovalCoordinator(store, ledger, gateway, FakeBindingFactsProvider(facts, facts))

        coordinator.decide("op-1", "attempt-1", reviewInput())
        assertEquals(0, gateway.submissions.size)

        val outcome = coordinator.onContinue("op-1", "attempt-1", reviewInput())
        assertIs<ApprovalCoordinator.Outcome.Submitted>(outcome)
        assertEquals(1, gateway.submissions.size)
        // The submission carries the bound object, not a fresh/unbound one.
        assertEquals("op-1", gateway.submissions.single().operationId)
        assertEquals("com.example.target", gateway.submissions.single().targetPackageName)
    }

    @Test
    fun noDecisionNoSubmission() = runTest {
        // Verifies: REQ-IX-ARCH-001 — nothing reaches the gateway without a decision.
        val gateway = FakeInstallationGateway()
        val coordinator = ApprovalCoordinator(InMemoryApprovalStateStore(), SubmissionLedger(), gateway, FakeBindingFactsProvider(facts))
        coordinator.onContinue("op-1", "attempt-1", reviewInput())
        assertEquals(0, gateway.submissions.size)
    }
}
