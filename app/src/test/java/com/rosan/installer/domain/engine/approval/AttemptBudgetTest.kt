// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.InstallModeSignal
import com.rosan.installer.domain.ai.repository.AIRiskAdvisor
import com.rosan.installer.domain.ai.repository.ModelRequestGate
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import com.rosan.installer.domain.ai.usecase.GateInput
import com.rosan.installer.domain.ai.usecase.RequestInstallRiskAssessmentUseCase
import com.rosan.installer.domain.engine.model.packageinfo.AppEntity
import com.rosan.installer.domain.engine.policy.PolicyAdvice
import com.rosan.installer.domain.engine.policy.PolicyDisposition
import com.rosan.installer.domain.settings.model.config.Authorizer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Verifies: REQ-IX-PER-001 (attempt budget, no automatic retry), REQ-IX-INV-004
 * (a failed or absent assessment never auto-approves).
 *
 * Virtual time: the hanging model is never awaited for real.
 */
class AttemptBudgetTest {
    private class HangingAdvisor : AIRiskAdvisor {
        val calls = AtomicInteger()
        var cancelled = false
        override suspend fun assessRequest(request: AiInstallRiskRequest, knownEvidencePaths: Set<String>): AdvisorOutcome {
            calls.incrementAndGet()
            try {
                delay(1.hours)
                error("the budget must end the call first")
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
    }

    private val gate = GateInput(advisorEnabled = true, networkAllowed = true, endpointConfigured = true, consentGranted = true)

    private fun coordinator(gateway: FakeInstallationGateway) = ApprovalCoordinator(
        store = InMemoryApprovalStateStore(),
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

    @Test
    fun hangingModelEndsInReviewWithinTheBudget() = runTest {
        val advisor = HangingAdvisor()
        val gateway = FakeInstallationGateway()
        val useCase = PrepareApprovalUseCase(
            assessment = RequestInstallRiskAssessmentUseCase(AiRequestBuilder(), advisor),
            apkHasher = { null },
            assessmentBudget = 12.seconds,
        )
        val analysis = FakeInstallerSession.singleApk("com.example.a", "a".repeat(64))

        val outcome = withContext(ModelRequestGate { true }) {
            useCase.prepare(
                "op-1",
                "attempt-1",
                true,
                gate,
                analysis,
                analysis.appEntities.first().app as AppEntity.BaseEntity,
                coordinator(gateway),
            )
        }

        val decided = assertIs<PrepareApprovalUseCase.Outcome.Decided>(outcome)
        assertEquals("DR-08", decided.decision.ruleId)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, decided.decision.disposition)
        assertEquals(PolicyAdvice.Failed(FailureCode.TIMEOUT), decided.baseInput.advice)
        // The card states why advice is missing instead of a bare "Needs your review".
        assertEquals(com.rosan.installer.domain.ai.model.AiUnavailable.ModelFailure(FailureCode.TIMEOUT), decided.unavailable)
        assertEquals(1, advisor.calls.get(), "no automatic retry")
        assertTrue(advisor.cancelled, "the hanging call is cancelled, not left running")
        assertTrue(currentTime <= 15_000, "decision within the 15 s attempt budget, took ${currentTime}ms")
        assertEquals(0, gateway.submissions.size, "a timeout never auto-approves")
    }
}
