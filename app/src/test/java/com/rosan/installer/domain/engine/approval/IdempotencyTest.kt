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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies: REQ-IX-INV-005, REQ-IX-REL-004
 *
 * Repeated and concurrent Continue events for the same operation submit at
 * most once. A retry adds an attempt ID but never grants a second permit.
 */
class IdempotencyTest {
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
    fun repeatedContinueSubmitsAtMostOnce() = runTest {
        // Verifies: REQ-IX-FR-006, REQ-IX-INV-005 — a second Continue adds no submission.
        val store = InMemoryApprovalStateStore()
        val ledger = SubmissionLedger()
        val gateway = FakeInstallationGateway()
        val coordinator = ApprovalCoordinator(store, ledger, gateway, FakeBindingFactsProvider(facts))
        coordinator.decide("op-1", "attempt-1", reviewInput())

        val first = coordinator.onContinue("op-1", "attempt-1", reviewInput())
        assertTrue(first is ApprovalCoordinator.Outcome.Submitted)
        val second = coordinator.onContinue("op-1", "attempt-1", reviewInput())
        // The stored state is now SUBMITTED, so Continue is rejected.
        assertEquals(ApprovalCoordinator.Outcome.ContinueRejected, second)
        assertEquals(1, gateway.submissions.size)
    }

    @Test
    fun retryAddsAttemptNotSubmissionRight() = runTest {
        // Verifies: REQ-IX-REL-004 — a retry with a new attempt ID does not submit again.
        val store = InMemoryApprovalStateStore()
        val ledger = SubmissionLedger()
        val gateway = FakeInstallationGateway()
        val provider = FakeBindingFactsProvider(facts, facts, facts, facts)
        val coordinator = ApprovalCoordinator(store, ledger, gateway, provider)
        coordinator.decide("op-1", "attempt-1", reviewInput())
        coordinator.onContinue("op-1", "attempt-1", reviewInput())
        assertEquals(1, gateway.submissions.size)

        // A retry is a fresh assessment under the same operation, new attempt ID.
        val retry = coordinator.decide("op-1", "attempt-2", reviewInput())
        assertTrue(retry is ApprovalCoordinator.Outcome.Decided)
        // Even if a Continue is attempted again, the ledger blocks a second permit.
        assertFalse(ledger.tryAcquire("op-1"))
        assertEquals(1, gateway.submissions.size)
    }

    @Test
    fun twentyConcurrentExecutesSubmitAtMostOnce() {
        // Verifies: REQ-IX-INV-005, REQ-IX-REL-004 — 20 concurrent permits yield at most 1.
        val ledger = SubmissionLedger()
        val wins = AtomicInteger()
        val pool = Executors.newFixedThreadPool(20)
        val start = CountDownLatch(1)
        repeat(20) {
            pool.execute {
                start.await()
                if (ledger.tryAcquire("op-1")) wins.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers did not finish")
        assertEquals(1, wins.get())
    }

    @Test
    fun twentySequentialPermitsOnCoordinatorSubmitAtMostOnce() = runTest {
        // Verifies: REQ-IX-INV-005 via the coordinator + ledger: repeated events
        // for one operation never add a second submission.
        val store = InMemoryApprovalStateStore()
        val ledger = SubmissionLedger()
        val gateway = FakeInstallationGateway()
        val coordinator = ApprovalCoordinator(store, ledger, gateway, FakeBindingFactsProvider(facts))
        coordinator.decide("op-1", "attempt-1", reviewInput())
        repeat(20) { coordinator.onContinue("op-1", "attempt-1", reviewInput()) }
        assertEquals(1, gateway.submissions.size)
    }
}
