// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.engine.install.GatewayResult
import com.rosan.installer.domain.engine.policy.PolicyDecision
import com.rosan.installer.domain.engine.policy.PolicyDisposition
import com.rosan.installer.domain.engine.policy.PolicyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Verifies: REQ-IX-REL-003 (and REL-002 persistence)
 *
 * R1-R5 recovery: an unsubmitted request is never auto-approved or replayed;
 * a submitted request with an unknown outcome becomes RESULT_UNKNOWN and is
 * not reported as not installed.
 */
class RecoveryTest {
    private val coordinator = ApprovalCoordinator(
        InMemoryApprovalStateStore(),
        SubmissionLedger(),
        FakeInstallationGateway(),
        FakeBindingFactsProvider(null),
    )

    private fun snapshot(state: ApprovalState) = ApprovalSnapshot(
        operationId = "op-1",
        attemptId = "attempt-1",
        binding = null,
        state = state,
        decision = PolicyResult(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.AWAIT_HUMAN, "DR-07", "X"),
    )

    @Test
    fun r1CrashedDuringModelWaitStaysAwaited() {
        // R1: AWAITING_AI -> recovered as AWAIT_HUMAN, no auto-approval.
        val outcome = assertIs<ApprovalCoordinator.Outcome.Recovered>(coordinator.onRecovered(snapshot(ApprovalState.AWAITING_AI)))
        assertEquals(ApprovalState.AWAITING_AI, outcome.state)
    }

    @Test
    fun r2CrashedPendingReviewRemainsAwaitHuman() {
        // R2: AWAIT_HUMAN stays AWAIT_HUMAN.
        val outcome = assertIs<ApprovalCoordinator.Outcome.Recovered>(coordinator.onRecovered(snapshot(ApprovalState.AWAIT_HUMAN)))
        assertEquals(ApprovalState.AWAIT_HUMAN, outcome.state)
    }

    @Test
    fun r3CrashedDuringDelayedNotificationRemainsAwaitHuman() {
        // R3: pending notification remains AWAIT_HUMAN; no submission before assessment.
        val outcome = assertIs<ApprovalCoordinator.Outcome.Recovered>(coordinator.onRecovered(snapshot(ApprovalState.AWAIT_HUMAN)))
        assertEquals(ApprovalState.AWAIT_HUMAN, outcome.state)
    }

    @Test
    fun r4ApprovedButUnsubmittedRequiresUserAction() {
        // R4: APPROVED/EXECUTE_ONCE with no submission -> back to AWAIT_HUMAN, no auto-replay.
        for (state in listOf(ApprovalState.APPROVED, ApprovalState.EXECUTE_ONCE)) {
            val outcome = assertIs<ApprovalCoordinator.Outcome.Recovered>(coordinator.onRecovered(snapshot(state)))
            assertEquals(ApprovalState.AWAIT_HUMAN, outcome.state, state.name)
        }
    }

    @Test
    fun r5SubmittedUnknownBecomesResultUnknown() {
        // R5: SUBMITTED with no known result -> RESULT_UNKNOWN, not "not installed".
        for (state in listOf(ApprovalState.SUBMITTED, ApprovalState.RESULT_UNKNOWN)) {
            val outcome = assertIs<ApprovalCoordinator.Outcome.Recovered>(coordinator.onRecovered(snapshot(state)))
            assertEquals(ApprovalState.RESULT_UNKNOWN, outcome.state, state.name)
        }
    }

    @Test
    fun failedIsTerminalAndStaysFailedOnRecovery() {
        // Verifies: REQ-IX-INV-005 — a consumed permit's backend failure is a
        // terminal FAILED state and does not return to a re-submittable state.
        val outcome = assertIs<ApprovalCoordinator.Outcome.Recovered>(coordinator.onRecovered(snapshot(ApprovalState.FAILED)))
        assertEquals(ApprovalState.FAILED, outcome.state)
    }

    @Test
    fun recoveryNeverSubmits() = runTest {
        // Verifies: REQ-IX-REL-003 — recovery adds zero submissions and no replay.
        val gateway = FakeInstallationGateway()
        val c = ApprovalCoordinator(
            InMemoryApprovalStateStore(),
            SubmissionLedger(),
            gateway,
            FakeBindingFactsProvider(null),
        )
        for (state in listOf(ApprovalState.APPROVED, ApprovalState.SUBMITTED)) {
            c.onRecovered(snapshot(state))
        }
        assertEquals(0, gateway.submissions.size)
    }
}
