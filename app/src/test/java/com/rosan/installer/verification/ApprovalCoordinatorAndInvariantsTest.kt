// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.verification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Stage 3 Verification Suites: `ApprovalCoordinatorTest`, `ApprovalBindingTest`,
 * `IdempotencyTest`, `ControlledInstallTest`, `RecoveryTest`, and `StatePersistenceTest`.
 *
 * Verifies: REQ-IX-FR-006, REQ-IX-FR-007, REQ-IX-INV-003, REQ-IX-INV-005,
 * REQ-IX-REL-002, REQ-IX-REL-003, REQ-IX-REL-004, NFC-OUT-004, DR-09, DR-10.
 * Plan reference: `docs/verification/test-plan.md` §5 cases C-01 .. C-16.
 */
class ApprovalCoordinatorTest {

    private fun reviewFixture(operationId: String = "op-review-1") = AssessmentEvidence(
        operationId = operationId,
        attemptId = "att-1",
        targetPackageName = "com.example.targetapp",
        installSourcePackageName = "org.fdroid.fdroid",
        apkSha256 = "sha256-initial-apk",
        targetUserId = 0,
        installMode = "AutoDialog",
        installationBackend = "None",
        configSnapshot = "authorizer=None;allowDowngrade=false",
        policyVersion = "v1",
        autoRulesMet = false, // routes to DR-06 REQUIRE_REVIEW / AWAIT_HUMAN
    )

    /** Verifies: REQ-IX-FR-006, REQ-IX-FR-007 (Case C-01: ControlledInstallTest) */
    @Test
    fun `C-01 REQUIRE_REVIEW before user acts produces zero backend submissions`() = runTest {
        val gateway = FakeInstallationGateway()
        val hasher = FakeApkHashProvider("sha256-initial-apk")
        val coordinator = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(),
            gateway = gateway,
            apkHasher = hasher,
        )

        val out = coordinator.assess(reviewFixture())
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, out.disposition)
        assertEquals(0, gateway.submissions.get())
    }

    /** Verifies: REQ-IX-FR-006 (Case C-02) */
    @Test
    fun `C-02 user Cancel makes zero submissions and prevents later Continue from submitting`() = runTest {
        val gateway = FakeInstallationGateway()
        val hasher = FakeApkHashProvider("sha256-initial-apk")
        val coordinator = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(),
            gateway = gateway,
            apkHasher = hasher,
        )

        coordinator.assess(reviewFixture("op-cancel"))
        coordinator.onCancel("op-cancel")
        assertEquals(0, gateway.submissions.get())

        // Even if Continue is triggered after Cancel, no submission occurs.
        coordinator.onContinue("op-cancel")
        assertEquals(0, gateway.submissions.get())
    }

    /** Verifies: REQ-IX-FR-006, DR-09 (Case C-03) */
    @Test
    fun `C-03 first Continue creates binding revalidates and submits once with zero additional model calls`() = runTest {
        val gateway = FakeInstallationGateway()
        val advisor = FixedResponseAdvisor()
        val hasher = FakeApkHashProvider("sha256-initial-apk")
        val coordinator = ApprovalCoordinator(
            advisor = advisor,
            gateway = gateway,
            apkHasher = hasher,
        )

        val initial = coordinator.assess(reviewFixture("op-continue"))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, initial.decision)
        assertEquals(1, advisor.providerDispatches.get())
        assertEquals(0, gateway.submissions.get())

        val afterContinue = coordinator.onContinue("op-continue")
        assertEquals(PolicyDecision.REQUIRE_REVIEW, afterContinue.decision)
        assertEquals(PolicyDisposition.EXECUTE_ONCE, afterContinue.disposition)
        assertEquals("DR-09", afterContinue.ruleId)
        assertEquals(1, gateway.submissions.get())
        // Zero additional model calls on Continue:
        assertEquals(1, advisor.providerDispatches.get())
    }

    /** Verifies: REQ-IX-FR-006, DR-03 (Case C-12) */
    @Test
    fun `C-12 Continue requested while in RECOLLECT is rejected with zero submissions`() = runTest {
        val gateway = FakeInstallationGateway()
        val coordinator = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(),
            gateway = gateway,
        )

        val incompleteEvidence = reviewFixture("op-recollect").copy(
            completeness = EvidenceCompleteness.TRUNCATED,
        )
        val initial = coordinator.assess(incompleteEvidence)
        assertEquals(PolicyDisposition.RECOLLECT, initial.disposition)

        val afterContinue = coordinator.onContinue("op-recollect")
        assertEquals(PolicyDisposition.RECOLLECT, afterContinue.disposition)
        assertEquals(0, gateway.submissions.get())
    }

    /** Verifies: REQ-IX-FR-007, DR-05 (Case C-13: ControlledInstallTest) */
    @Test
    fun `C-13 ALLOW_AUTO submits automatically exactly once`() = runTest {
        val gateway = FakeInstallationGateway()
        val hasher = FakeApkHashProvider("sha256-auto")
        val coordinator = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(),
            gateway = gateway,
            apkHasher = hasher,
        )

        val autoEvidence = reviewFixture("op-auto").copy(
            apkSha256 = "sha256-auto",
            autoRulesMet = true,
        )
        val out = coordinator.assess(autoEvidence)
        assertEquals(PolicyDecision.ALLOW_AUTO, out.decision)
        assertEquals(PolicyDisposition.EXECUTE_ONCE, out.disposition)
        assertEquals("DR-05", out.ruleId)
        assertEquals(1, gateway.submissions.get())
    }

    /** Verifies: REQ-IX-FR-006, REQ-IX-REL-003 (Case C-14: RecoveryTest) */
    @Test
    fun `C-14 process recovery before submission stays AWAIT_HUMAN and never auto-submits`() = runTest {
        val gateway = FakeInstallationGateway()
        val store = InMemoryApprovalStateStore()
        val coordinator = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(),
            stateStore = store,
            gateway = gateway,
        )

        coordinator.assess(reviewFixture("op-recover-pending"))
        val recovered = coordinator.recoverAfterRestart("op-recover-pending")

        assertEquals(PolicyDecision.REQUIRE_REVIEW, recovered.outcome.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, recovered.outcome.disposition)
        assertEquals(0, gateway.submissions.get())
    }

    /** Verifies: REQ-IX-FR-007, REQ-IX-REL-003 (Case C-15: RecoveryTest) */
    @Test
    fun `C-15 process recovery after submission with unknown outcome preserves RESULT_UNKNOWN and forbids replay`() = runTest {
        val gateway = FakeInstallationGateway(simulateUnknownOutcome = true)
        val store = InMemoryApprovalStateStore()
        val hasher = FakeApkHashProvider("sha256-initial-apk")
        val coordinator = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(),
            stateStore = store,
            gateway = gateway,
            apkHasher = hasher,
        )

        coordinator.assess(reviewFixture("op-recover-unknown"))
        coordinator.onContinue("op-recover-unknown")
        assertEquals(1, gateway.submissions.get())

        val recovered = coordinator.recoverAfterRestart("op-recover-unknown")
        assertEquals(SubmissionResultState.RESULT_UNKNOWN, recovered.submissionState)
        assertEquals(1, gateway.submissions.get())

        // Even another Continue after recovery with RESULT_UNKNOWN must not replay submission.
        coordinator.onContinue("op-recover-unknown")
        assertEquals(1, gateway.submissions.get())
    }

    /** Verifies: REQ-IX-REL-002 (Case C-16: StatePersistenceTest) */
    @Test
    fun `C-16 ApprovalStateStore write failure stops automatic submission`() = runTest {
        val gateway = FakeInstallationGateway()
        val store = InMemoryApprovalStateStore(failWrites = true)
        val hasher = FakeApkHashProvider("sha256-auto")
        val coordinator = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(),
            stateStore = store,
            gateway = gateway,
            apkHasher = hasher,
        )

        val autoEvidence = reviewFixture("op-store-fail").copy(
            apkSha256 = "sha256-auto",
            autoRulesMet = true,
        )
        val out = coordinator.assess(autoEvidence)
        assertEquals(PolicyDecision.ALLOW_AUTO, out.decision)
        // Store write failed, so automatic submission must be stopped:
        assertEquals(0, gateway.submissions.get())
    }
}

/**
 * Stage 3 Negative Invariant Suite: `ApprovalBindingTest`.
 *
 * Verifies: REQ-IX-INV-003 (approval binds the installation object),
 * REQ-IX-SEC-003, ADR-002.7, NFC-OUT-004, DR-10, F-16.
 * Plan reference: `docs/verification/test-plan.md` §5 cases C-04 .. C-07.
 */
class ApprovalBindingTest {

    private fun baseFixture(operationId: String) = AssessmentEvidence(
        operationId = operationId,
        attemptId = "att-bind-1",
        targetPackageName = "com.example.bound.target",
        installSourcePackageName = "com.example.distinct.installer",
        apkSha256 = "sha256-bound-original",
        targetUserId = 0,
        installMode = "AutoDialog",
        installationBackend = "None",
        configSnapshot = "authorizer=None;allowDowngrade=false",
        policyVersion = "v1",
        autoRulesMet = false,
    )

    /** Verifies: REQ-IX-INV-003, ADR-002.7, DR-10, F-16 (Case C-04: TOCTOU APK replacement) */
    @Test
    fun `C-04 APK SHA-256 changed between assessment and Continue invalidates approval with zero submissions`() = runTest {
        val gateway = FakeInstallationGateway()
        val hasher = FakeApkHashProvider("sha256-bound-original")
        val coordinator = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(),
            gateway = gateway,
            apkHasher = hasher,
        )

        val fixture = baseFixture("op-toctou-hash")
        coordinator.assess(fixture)

        // Simulate TOCTOU tampering: APK file replaced on disk before Continue re-reads its hash.
        hasher.currentSha256 = "sha256-tampered-apk"

        val out = coordinator.onContinue("op-toctou-hash")
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals(PolicyDisposition.INVALIDATED, out.disposition)
        assertEquals("DR-10", out.ruleId)
        assertEquals(0, gateway.submissions.get())
    }

    /** Verifies: REQ-IX-INV-003 (Case C-05: target user change) */
    @Test
    fun `C-05 target user changed after approval invalidates approval with zero submissions`() = runTest {
        val gateway = FakeInstallationGateway()
        val hasher = FakeApkHashProvider("sha256-bound-original")
        val coordinator = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(),
            gateway = gateway,
            apkHasher = hasher,
        )

        val fixture = baseFixture("op-user-change")
        coordinator.assess(fixture)

        val out = coordinator.onContinue(
            operationId = "op-user-change",
            currentEvidence = fixture.copy(targetUserId = 10),
        )
        assertEquals(PolicyDisposition.INVALIDATED, out.disposition)
        assertEquals("DR-10", out.ruleId)
        assertEquals(0, gateway.submissions.get())
    }

    /**
     * Verifies: REQ-IX-INV-003, NFC-OUT-004 (Case C-06)
     * Using a fixture where targetPackageName and installSourcePackageName are distinct:
     * 1) With binding unchanged, revalidation succeeds and submits once.
     * 2) Modifying only the bound targetPackageName in the stored approval causes revalidation
     *    to fail (DR-10 INVALIDATED) and produces zero backend submissions; the distinct
     *    installSourcePackageName is not mistaken for the bound target package.
     */
    @Test
    fun `C-06 NFC-OUT-004 modifying only bound targetPackageName with distinct source package invalidates approval`() = runTest {
        val fixtureOk = baseFixture("op-nfc-004-ok")
        assertNotEquals(fixtureOk.targetPackageName, fixtureOk.installSourcePackageName)

        // Part 1: unchanged binding succeeds
        val gwOk = FakeInstallationGateway()
        val storeOk = InMemoryApprovalStateStore()
        val coordOk = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(),
            stateStore = storeOk,
            gateway = gwOk,
            apkHasher = FakeApkHashProvider(fixtureOk.apkSha256),
        )
        coordOk.assess(fixtureOk)
        val okOut = coordOk.onContinue("op-nfc-004-ok")
        assertEquals(PolicyDisposition.EXECUTE_ONCE, okOut.disposition)
        assertEquals(1, gwOk.submissions.get())

        // Part 2: modify only bound targetPackageName (including trying to swap it with installSourcePackageName)
        val fixtureTampered = baseFixture("op-nfc-004-tampered")
        val gwTampered = FakeInstallationGateway()
        val storeTampered = InMemoryApprovalStateStore()
        val coordTampered = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(),
            stateStore = storeTampered,
            gateway = gwTampered,
            apkHasher = FakeApkHashProvider(fixtureTampered.apkSha256),
        )
        coordTampered.assess(fixtureTampered)
        storeTampered.mutateBinding("op-nfc-004-tampered") { binding ->
            binding.copy(targetPackageName = fixtureTampered.installSourcePackageName)
        }

        val tamperedOut = coordTampered.onContinue("op-nfc-004-tampered")
        assertEquals(PolicyDisposition.INVALIDATED, tamperedOut.disposition)
        assertEquals("DR-10", tamperedOut.ruleId)
        assertEquals(0, gwTampered.submissions.get())
    }

    /**
     * Verifies: REQ-IX-INV-003, REQ-IX-SEC-003 (Case C-07)
     * Exhaustively mutates or blanks each of the 9 INV-003 binding fields one by one and
     * verifies that every single mutation produces DR-10 INVALIDATED and 0 backend submissions.
     */
    @Test
    fun `C-07 mutating or blanking any of the 9 INV-003 binding fields invalidates approval`() = runTest {
        val mutations: List<Pair<String, (ApprovalBinding) -> ApprovalBinding>> = listOf(
            "operation_id" to { it.copy(operationId = "op-other") },
            "attempt_id" to { it.copy(attemptId = "att-other") },
            "apk_sha256" to { it.copy(apkSha256 = "sha256-other") },
            "target_package_name" to { it.copy(targetPackageName = "com.other.pkg") },
            "target_user_id" to { it.copy(targetUserId = 99) },
            "install_mode" to { it.copy(installMode = "AutoNotification") },
            "installation_backend" to { it.copy(installationBackend = "Shizuku") },
            "config_snapshot" to { it.copy(configSnapshot = "authorizer=Shizuku;allowDowngrade=true") },
            "policy_version" to { it.copy(policyVersion = "v2") },
            "blank_package_name" to { it.copy(targetPackageName = "") },
            "blank_sha256" to { it.copy(apkSha256 = "") },
        )

        for ((label, mutate) in mutations) {
            val opId = "op-bind-$label"
            val fixture = baseFixture(opId)
            val gateway = FakeInstallationGateway()
            val store = InMemoryApprovalStateStore()
            val coordinator = ApprovalCoordinator(
                advisor = FixedResponseAdvisor(),
                stateStore = store,
                gateway = gateway,
                apkHasher = FakeApkHashProvider(fixture.apkSha256),
            )

            coordinator.assess(fixture)
            store.mutateBinding(opId, mutate)

            val out = coordinator.onContinue(opId)
            assertEquals(PolicyDisposition.INVALIDATED, out.disposition, "Field mutation '$label' must invalidate approval")
            assertEquals("DR-10", out.ruleId, "Field mutation '$label'")
            assertEquals(0, gateway.submissions.get(), "Field mutation '$label' must produce 0 submissions")
        }
    }
}

/**
 * Stage 3 Negative Invariant Suite: `IdempotencyTest`.
 *
 * Verifies: REQ-IX-INV-005 (at most one submission per repeated event),
 * REQ-IX-REL-004 (deduplication).
 * Plan reference: `docs/verification/test-plan.md` §5 cases C-08 .. C-10.
 */
class IdempotencyTest {

    private fun fixture(operationId: String) = AssessmentEvidence(
        operationId = operationId,
        attemptId = "att-idem-1",
        apkSha256 = "sha256-idem",
        autoRulesMet = false,
    )

    /** Verifies: REQ-IX-INV-005, REQ-IX-FR-006 (Case C-08) */
    @Test
    fun `C-08 repeated Continue clicks and duplicate notification events submit at most once`() = runTest {
        val gateway = FakeInstallationGateway()
        val coordinator = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(),
            gateway = gateway,
            apkHasher = FakeApkHashProvider("sha256-idem"),
        )

        coordinator.assess(fixture("op-dup-click"))
        repeat(5) {
            coordinator.onContinue("op-dup-click")
        }
        assertEquals(1, gateway.submissions.get())
    }

    /** Verifies: REQ-IX-INV-005 fit criterion (Case C-09: 20 concurrent execution events) */
    @Test
    fun `C-09 twenty concurrent Continue execution events for the same operationId produce at most 1 submission`() = runTest {
        val gateway = FakeInstallationGateway()
        val coordinator = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(),
            gateway = gateway,
            apkHasher = FakeApkHashProvider("sha256-idem"),
        )

        coordinator.assess(fixture("op-concurrent-20"))

        coroutineScope {
            repeat(20) {
                launch(Dispatchers.Default) {
                    coordinator.onContinue("op-concurrent-20")
                }
            }
        }

        assertTrue(
            gateway.submissions.get() == 1,
            "Expected exactly 1 backend submission across 20 concurrent events, got ${gateway.submissions.get()}",
        )
    }

    /** Verifies: REQ-IX-INV-005, REQ-IX-FR-005 (Case C-10: Retry semantics) */
    @Test
    fun `C-10 user Retry adds a new attemptId and grants no second submission right`() = runTest {
        val gateway = FakeInstallationGateway()
        val store = InMemoryApprovalStateStore()
        val advisor = FixedResponseAdvisor()
        val coordinator = ApprovalCoordinator(
            advisor = advisor,
            stateStore = store,
            gateway = gateway,
            apkHasher = FakeApkHashProvider("sha256-idem"),
        )

        // First attempt auto-submits once:
        val autoFixture = fixture("op-retry").copy(autoRulesMet = true)
        coordinator.assess(autoFixture)
        assertEquals(1, gateway.submissions.get())

        // User triggers Retry on the same operationId: new attemptId recorded, but cumulative submissions remain 1.
        coordinator.onRetry("op-retry", newAttemptId = "att-idem-2")
        val state = store.get("op-retry")!!
        assertEquals("att-idem-2", state.latestAttemptId)
        assertEquals(2, state.attemptCount)
        assertEquals(1, gateway.submissions.get())
    }
}
