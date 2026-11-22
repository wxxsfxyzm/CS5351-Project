// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.data.engine.approval.InMemoryApprovalStateStore
import com.rosan.installer.data.engine.approval.SessionBindingFactsProvider
import com.rosan.installer.data.engine.install.SessionInstallationGateway
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.InstallAssessment
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.engine.policy.EvaluatedPolicy
import com.rosan.installer.domain.engine.policy.HumanApprovalState
import com.rosan.installer.domain.engine.policy.PolicyAdvice
import com.rosan.installer.domain.engine.policy.PolicyInput
import com.rosan.installer.domain.session.model.ProgressEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Verifies: REQ-IX-INV-003, REQ-IX-INV-005 (two concurrent sessions).
 *
 * Two sessions for **different** packages/hashes, each with its own
 * facts provider and gateway (as in production per-session wiring), share only
 * the store and the submission ledger. Each gateway must submit only its own
 * session's package, per-session facts must be independent, and the shared
 * ledger must still allow exactly one permit per operation ID.
 *
 * This test fails if a single global facts source is used: the second session's
 * facts would overwrite the first's and the bound package would be wrong.
 */
class GatewaySessionIsolationTest {

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
    fun twoSessionsDoNotCrossContaminate() = runTest {
        val store = InMemoryApprovalStateStore() // shared (single)
        val ledger = SubmissionLedger() // shared (single)

        val sessionA = FakeInstallerSession(
            analysisResults = listOf(FakeInstallerSession.singleApk("com.example.a", "a".repeat(64))),
            initialProgress = ProgressEntity.Ready,
        )
        val sessionB = FakeInstallerSession(
            analysisResults = listOf(FakeInstallerSession.singleApk("com.example.b", "b".repeat(64))),
            initialProgress = ProgressEntity.Ready,
        )

        val gatewayA = SessionInstallationGateway(sessionA, resultTimeoutMillis = 5_000)
        val gatewayB = SessionInstallationGateway(sessionB, resultTimeoutMillis = 5_000)
        // Per-session facts providers: each reads only its own repository.
        val factsA = SessionBindingFactsProvider(sessionA)
        val factsB = SessionBindingFactsProvider(sessionB)

        val coordinatorA = ApprovalCoordinator(store, ledger, gatewayA, factsA)
        val coordinatorB = ApprovalCoordinator(store, ledger, gatewayB, factsB)

        // Each submit produces its own success.
        val emitA = launch {
            kotlinx.coroutines.yield()
            sessionA.emit(ProgressEntity.InstallSuccess)
        }
        val emitB = launch {
            kotlinx.coroutines.yield()
            sessionB.emit(ProgressEntity.InstallSuccess)
        }

        coordinatorA.decide("op-a", "attempt-a", reviewInput())
        coordinatorB.decide("op-b", "attempt-b", reviewInput())

        // Per-session facts use their own package and hash.
        val boundA = factsA.currentFacts("op-a")!!
        val boundB = factsB.currentFacts("op-b")!!
        assertEquals("com.example.a", boundA.targetPackageName)
        assertEquals("com.example.b", boundB.targetPackageName)
        assertEquals("a".repeat(64), boundA.apkSha256)
        assertEquals("b".repeat(64), boundB.apkSha256)

        // Continue on each: each gateway submits only its own package.
        val outcomeA = coordinatorA.onContinue("op-a", "attempt-a", reviewInput())
        val outcomeB = coordinatorB.onContinue("op-b", "attempt-b", reviewInput())
        assertIs<ApprovalCoordinator.Outcome.Submitted>(outcomeA)
        assertIs<ApprovalCoordinator.Outcome.Submitted>(outcomeB)
        emitA.join()
        emitB.join()

        // Neither session saw the other's package: each install(true) once.
        assertEquals(1, sessionA.installCalls.size)
        assertEquals(1, sessionB.installCalls.size)

        // The shared ledger still allows one permit per operation ID.
        assertEquals(true, ledger.isConsumed("op-a"))
        assertEquals(true, ledger.isConsumed("op-b"))
        // A second continue for the same operation does not re-submit.
        val again = coordinatorA.onContinue("op-a", "attempt-a2", reviewInput())
        assertIs<ApprovalCoordinator.Outcome.ContinueRejected>(again)
        assertEquals(1, sessionA.installCalls.size)
    }
}
