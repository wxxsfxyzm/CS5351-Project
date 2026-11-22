// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.ui.page.main.installer

import com.rosan.installer.domain.ai.model.AiUiState
import com.rosan.installer.domain.ai.model.AiUnavailable
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.engine.approval.ApprovalState
import com.rosan.installer.domain.engine.approval.PrepareApprovalUseCase
import com.rosan.installer.domain.engine.policy.EvaluatedPolicy
import com.rosan.installer.domain.engine.policy.HumanApprovalState
import com.rosan.installer.domain.engine.policy.PolicyAdvice
import com.rosan.installer.domain.engine.policy.PolicyDecision
import com.rosan.installer.domain.engine.policy.PolicyDisposition
import com.rosan.installer.domain.engine.policy.PolicyInput
import com.rosan.installer.domain.engine.policy.PolicyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-ARCH-001, REQ-IX-FR-012 (fail-closed ViewModel decision).
 *
 * Pure, offline. Only the `Baseline` outcome yields [AdvisorDecision.BaselineInstall]
 * (the sole advisor-on `session.install` branch). AlreadyStarted does nothing;
 * a thrown error fails closed with Install disabled; decided states show a UI
 * state; a persistence failure disables Continue.
 */
class AdvisorDecisionTest {

    private val reviewInput = PolicyInput(
        policy = EvaluatedPolicy("policy-v1", hardBlock = false, baselineAllowsAuto = true, autoRulesSatisfied = false),
        evidence = EvidenceCompleteness.COMPLETE,
        compatibility = LocalCompatibility.COMPATIBLE,
        advice = PolicyAdvice.Unusable.CONSENT_DECLINED,
        human = HumanApprovalState.NOT_OBTAINED,
    )

    private fun reviewResult() = PolicyResult(
        PolicyDecision.REQUIRE_REVIEW,
        PolicyDisposition.AWAIT_HUMAN,
        "DR-02",
        "CONSENT_DECLINED",
    )

    private fun decided(unavailable: AiUnavailable?) = PrepareApprovalUseCase.Outcome.Decided(
        state = ApprovalState.AWAIT_HUMAN,
        decision = reviewResult(),
        model = null,
        unavailable = unavailable,
        baseInput = reviewInput,
    )

    @Test
    fun onlyBaselineYieldsBaselineInstall() {
        assertEquals(
            AdvisorDecision.BaselineInstall,
            AdvisorDecision.from(PrepareApprovalUseCase.Outcome.Baseline, null),
        )
        // Every other outcome must NOT be BaselineInstall.
        assertFalse(AdvisorDecision.from(PrepareApprovalUseCase.Outcome.AlreadyStarted, null) is AdvisorDecision.BaselineInstall)
        assertFalse(AdvisorDecision.from(decided(AiUnavailable.ConsentOff), null) is AdvisorDecision.BaselineInstall)
        assertFalse(
            AdvisorDecision.from(PrepareApprovalUseCase.Outcome.Unavailable(AiUnavailable.PersistenceFailed), null) is
                AdvisorDecision.BaselineInstall,
        )
        assertFalse(AdvisorDecision.from(null, RuntimeException("boom")) is AdvisorDecision.BaselineInstall)
    }

    @Test
    fun alreadyStartedDoesNothing() {
        assertEquals(
            AdvisorDecision.AlreadyStarted,
            AdvisorDecision.from(PrepareApprovalUseCase.Outcome.AlreadyStarted, null),
        )
    }

    @Test
    fun thrownErrorFailsClosed() {
        val decision = AdvisorDecision.from(null, IllegalStateException("prepare failed"))
        val show = assertIs<AdvisorDecision.Show>(decision)
        assertIs<AiUiState.Error>(show.uiState)
        assertFalse(show.uiState.continueEnabled, "an error must disable the Install button")
    }

    @Test
    fun persistenceFailureDisablesContinue() {
        val decision = AdvisorDecision.from(
            PrepareApprovalUseCase.Outcome.Unavailable(AiUnavailable.PersistenceFailed),
            null,
        )
        val show = assertIs<AdvisorDecision.Show>(decision)
        assertIs<AiUiState.Unavailable>(show.uiState)
        assertFalse(show.uiState.continueEnabled, "REL-002 persistence failure must disable Continue")
    }

    @Test
    fun consentOffShowsReviewableWithContinue() {
        val decision = AdvisorDecision.from(decided(AiUnavailable.ConsentOff), null)
        val show = assertIs<AdvisorDecision.Show>(decision)
        assertIs<AiUiState.Unavailable>(show.uiState)
        // Consent off still lets the human proceed via Continue (through onContinue).
        assertTrue(show.uiState.continueEnabled)
    }

    @Test
    fun advisorOnRetryNeverYieldsBaselineInstall() {
        // After uninstall the facts changed; an advisor-on retry must re-assess.
        val retry = AdvisorDecision.forRetry(advisorActive = true)
        assertEquals(AdvisorDecision.RetryAssessment, retry)
        assertFalse(retry is AdvisorDecision.BaselineInstall)
        // Advisor off keeps the baseline retry.
        assertEquals(AdvisorDecision.BaselineInstall, AdvisorDecision.forRetry(advisorActive = false))
    }
}
