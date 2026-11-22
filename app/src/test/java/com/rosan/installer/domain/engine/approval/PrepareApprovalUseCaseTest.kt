// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.InstallAssessment
import com.rosan.installer.domain.ai.model.InstallModeSignal
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.ai.repository.AIRiskAdvisor
import com.rosan.installer.domain.ai.repository.ModelRequestGate
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import com.rosan.installer.domain.ai.usecase.GateInput
import com.rosan.installer.domain.ai.usecase.RequestInstallRiskAssessmentUseCase
import com.rosan.installer.domain.settings.model.config.Authorizer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Verifies: REQ-IX-FR-001/003/005/007/012, REQ-IX-ARCH-001, REQ-IX-INV-004,
 * REQ-IX-COST-001.
 *
 * Offline. The coordinator/gateway are fakes; the advisor double records
 * dispatches so the tests can prove one-trigger and advisor-off behaviour. The
 * assessment runs under a current-consent gate, as in the app.
 */
class PrepareApprovalUseCaseTest {

    private class RecordingAdvisor(
        private val outcome: () -> AdvisorOutcome,
    ) : AIRiskAdvisor {
        val calls = AtomicInteger()
        override suspend fun assessRequest(
            request: AiInstallRiskRequest,
            knownEvidencePaths: Set<String>,
        ): AdvisorOutcome {
            calls.incrementAndGet()
            return outcome()
        }
    }

    private fun validAssessment() = AssessmentResult.Valid(
        InstallAssessment(
            InstallSuccessLikelihood.HIGH,
            SecurityRisk.LOW,
            listOf("evidence.target_sdk"),
            listOf("evidence.target_sdk"),
            "CS5351/DeepSeek-V4-Flash",
            "ai-risk-prompt-v1.1",
        ),
    )

    private fun gate() = GateInput(
        advisorEnabled = true,
        networkAllowed = true,
        endpointConfigured = true,
        consentGranted = true,
    )

    private fun useCaseWith(advisor: AIRiskAdvisor) = PrepareApprovalUseCase(
        assessment = RequestInstallRiskAssessmentUseCase(AiRequestBuilder(), advisor),
    )

    private fun coordinator(store: ApprovalStateStore, gateway: FakeInstallationGateway) = ApprovalCoordinator(
        store = store,
        ledger = SubmissionLedger(),
        gateway = gateway,
        factsProvider = FakeBindingFactsProvider(
            BindingFacts(
                apkSha256 = "a".repeat(64),
                targetPackageName = "com.example.a",
                targetUserId = 0,
                installMode = InstallModeSignal.AutoDialog,
                installationBackend = Authorizer.None,
                configSnapshotHash = "cfg-1",
                policyVersion = "policy-v1",
            ),
        ),
    )

    private val analysis = FakeInstallerSession.singleApk("com.example.a", "a".repeat(64))
    private val selectedApk = analysis.appEntities.first().app as
        com.rosan.installer.domain.engine.model.packageinfo.AppEntity.BaseEntity

    @Test
    fun decisionRouting() = runTest {
        // A usable assessment routes through the coordinator to a decision.
        val store = InMemoryApprovalStateStore()
        val gateway = FakeInstallationGateway()
        val advisor = RecordingAdvisor { AdvisorOutcome.Ok(validAssessment()) }
        val coord = coordinator(store, gateway)
        val useCase = useCaseWith(advisor)

        val decided = withContext(ModelRequestGate { true }) {
            useCase.prepare("op-1", "attempt-1", true, gate(), analysis, selectedApk, coord)
        }
        assertIs<PrepareApprovalUseCase.Outcome.Decided>(decided)
        assertEquals(1, advisor.calls.get())
        // decide persists a snapshot; no submission yet.
        assertIs<com.rosan.installer.domain.engine.approval.ApprovalSnapshot>(store.load("op-1"))
        assertEquals(0, gateway.submissions.size)
    }

    @Test
    fun advisorOffIsBaselineNoCoordinator() = runTest {
        // FR-012 / Q9(b): advisor off => 0 model calls and the coordinator is untouched.
        val store = InMemoryApprovalStateStore()
        val gateway = FakeInstallationGateway()
        val advisor = RecordingAdvisor { error("model must not be called") }
        val coord = coordinator(store, gateway)
        val useCase = useCaseWith(advisor)

        val outcome = useCase.prepare("op-1", "attempt-1", false, gate(), analysis, selectedApk, coord)
        assertIs<PrepareApprovalUseCase.Outcome.Baseline>(outcome)
        assertEquals(0, advisor.calls.get(), "advisor-off must make 0 model calls")
        assertNull(store.load("op-1"), "advisor-off must not construct/use the coordinator")
        assertEquals(0, gateway.submissions.size)
    }

    @Test
    fun outOfScopeIsBaselineZeroCalls() = runTest {
        val store = InMemoryApprovalStateStore()
        val gateway = FakeInstallationGateway()
        val advisor = RecordingAdvisor { error("model must not be called") }
        val coord = coordinator(store, gateway)
        val useCase = useCaseWith(advisor)

        val outOfScope = analysis.copy(appEntities = emptyList())
        val outcome = withContext(ModelRequestGate { true }) {
            useCase.prepare("op-1", "attempt-1", true, gate(), outOfScope, null, coord)
        }
        assertIs<PrepareApprovalUseCase.Outcome.Baseline>(outcome)
        assertEquals(0, advisor.calls.get())
    }

    @Test
    fun consentOffGoesThroughEngineToAwaitHuman() = runTest {
        // REQ-IX-FR-011 / fix 2: consent off withholds the model call but STILL
        // runs the engine; the decision (AWAIT_HUMAN via DR-02) comes from the
        // coordinator, with 0 dispatches.
        val store = InMemoryApprovalStateStore()
        val gateway = FakeInstallationGateway()
        val advisor = RecordingAdvisor { error("model must not be called") }
        val coord = coordinator(store, gateway)
        val useCase = useCaseWith(advisor)

        val outcome = withContext(ModelRequestGate { true }) {
            useCase.prepare(
                "op-1",
                "attempt-1",
                true,
                gate().copy(consentGranted = false),
                analysis,
                selectedApk,
                coord,
            )
        }
        val decided = assertIs<PrepareApprovalUseCase.Outcome.Decided>(outcome)
        assertEquals(ApprovalState.AWAIT_HUMAN, decided.state)
        assertEquals(com.rosan.installer.domain.ai.model.AiUnavailable.ConsentOff, decided.unavailable)
        assertEquals(0, advisor.calls.get(), "consent off must not dispatch")
        // The coordinator decided (a snapshot exists) and no submission happened.
        assertIs<ApprovalSnapshot>(store.load("op-1"))
        assertEquals(0, gateway.submissions.size)
    }

    @Test
    fun persistenceFailedYieldsNoInstall() = runTest {
        // REL-002: a persistence failure stops automatic submission.
        val failingStore = object : ApprovalStateStore {
            override suspend fun persist(snapshot: ApprovalSnapshot): Boolean = false
            override suspend fun load(operationId: String): ApprovalSnapshot? = null
        }
        val gateway = FakeInstallationGateway()
        val advisor = RecordingAdvisor { AdvisorOutcome.Ok(validAssessment()) }
        val coord = coordinator(failingStore, gateway)
        val useCase = useCaseWith(advisor)

        val outcome = withContext(ModelRequestGate { true }) {
            useCase.prepare("op-1", "attempt-1", true, gate(), analysis, selectedApk, coord)
        }
        val unavailable = assertIs<PrepareApprovalUseCase.Outcome.Unavailable>(outcome)
        assertEquals(com.rosan.installer.domain.ai.model.AiUnavailable.PersistenceFailed, unavailable.reason)
        assertEquals(0, gateway.submissions.size)
    }

    @Test
    fun modelFailureIsReviewNotAuto() = runTest {
        val store = InMemoryApprovalStateStore()
        val gateway = FakeInstallationGateway()
        val advisor = RecordingAdvisor { AdvisorOutcome.Failure(AiAdvisorFailure.ServiceError(503, "HTTP 503")) }
        val coord = coordinator(store, gateway)
        val useCase = useCaseWith(advisor)

        val outcome = withContext(ModelRequestGate { true }) {
            useCase.prepare("op-1", "attempt-1", true, gate(), analysis, selectedApk, coord)
        }
        // A model failure routes to the engine (review), never auto-submit.
        assertIs<PrepareApprovalUseCase.Outcome.Decided>(outcome)
        assertEquals(0, gateway.submissions.size)
        assertEquals(1, advisor.calls.get())
    }

    @Test
    fun singleDispatchAcrossRecomposition() = runTest {
        // COST-001: repeated prepare() for the same operation dispatches once.
        val store = InMemoryApprovalStateStore()
        val gateway = FakeInstallationGateway()
        val advisor = RecordingAdvisor { AdvisorOutcome.Ok(validAssessment()) }
        val coord = coordinator(store, gateway)
        val useCase = useCaseWith(advisor)

        withContext(ModelRequestGate { true }) {
            useCase.prepare("op-1", "attempt-1", true, gate(), analysis, selectedApk, coord)
            val second = useCase.prepare("op-1", "attempt-1", true, gate(), analysis, selectedApk, coord)
            val third = useCase.prepare("op-1", "attempt-1", true, gate(), analysis, selectedApk, coord)
            assertIs<PrepareApprovalUseCase.Outcome.AlreadyStarted>(second)
            assertIs<PrepareApprovalUseCase.Outcome.AlreadyStarted>(third)
        }
        assertEquals(1, advisor.calls.get(), "only one dispatch across re-entry")
    }

    @Test
    fun retryAfterUninstallUsesNewOperationIdAndCanSubmitOnce() = runTest {
        // Reusing the operationId after submission would (a) hit the started
        // guard (AlreadyStarted) and (b) find the permit already consumed
        // (AlreadySubmitted). The retry must be a NEW operation: it decides
        // afresh and can submit once, while the old operation cannot submit again.
        val store = InMemoryApprovalStateStore()
        val ledger = SubmissionLedger()
        val gateway = FakeInstallationGateway()
        val factsProvider = FakeBindingFactsProvider(
            BindingFacts(
                apkSha256 = "a".repeat(64),
                targetPackageName = "com.example.a",
                targetUserId = 0,
                installMode = InstallModeSignal.AutoDialog,
                installationBackend = Authorizer.None,
                configSnapshotHash = "cfg-1",
                policyVersion = "policy-v1",
            ),
        )
        val coord = ApprovalCoordinator(store, ledger, gateway, factsProvider)
        val advisor = RecordingAdvisor { AdvisorOutcome.Ok(validAssessment()) }
        val useCase = useCaseWith(advisor)
        val op1 = "op-1"
        val opRetry = "op-1-retry-1"

        withContext(ModelRequestGate { true }) {
            // First operation: decide -> AWAIT_HUMAN -> Continue submits once.
            val firstDecision = assertIs<PrepareApprovalUseCase.Outcome.Decided>(
                useCase.prepare(op1, "attempt-1", true, gate(), analysis, selectedApk, coord),
            )
            assertEquals(ApprovalState.AWAIT_HUMAN, firstDecision.state)
            val firstContinue = coord.onContinue(op1, "attempt-1", firstDecision.baseInput)
            assertIs<ApprovalCoordinator.Outcome.Submitted>(firstContinue)
            assertEquals(1, gateway.submissions.size)
            assertEquals(op1, gateway.submissions.first().operationId)

            // The old operation can no longer submit (permit spent).
            val replay = coord.onContinue(op1, "attempt-1", firstDecision.baseInput)
            assertTrue(
                replay is ApprovalCoordinator.Outcome.AlreadySubmitted ||
                    replay is ApprovalCoordinator.Outcome.ContinueRejected,
                "the old operation must not submit twice, got $replay",
            )
            assertEquals(1, gateway.submissions.size)

            // Retry is a NEW operation with a fresh id: it decides afresh
            // (not AlreadyStarted) and submits exactly once.
            val retryDecision = assertIs<PrepareApprovalUseCase.Outcome.Decided>(
                useCase.prepare(opRetry, "attempt-1", true, gate(), analysis, selectedApk, coord),
            )
            assertEquals(ApprovalState.AWAIT_HUMAN, retryDecision.state)
            val retryContinue = coord.onContinue(opRetry, "attempt-1", retryDecision.baseInput)
            assertIs<ApprovalCoordinator.Outcome.Submitted>(retryContinue)
            assertEquals(2, gateway.submissions.size)
            assertEquals(opRetry, gateway.submissions.last().operationId)
        }
        // Two dispatches: one per operation (the retry re-assessed).
        assertEquals(2, advisor.calls.get())
    }
}
