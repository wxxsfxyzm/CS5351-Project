// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.ui.page.main.installer.mapper

import com.rosan.installer.domain.ai.model.AiReviewModel
import com.rosan.installer.domain.ai.model.AiUiState
import com.rosan.installer.domain.ai.model.AiUnavailable
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.engine.approval.ApprovalState
import com.rosan.installer.domain.engine.policy.PolicyDecision
import com.rosan.installer.domain.engine.policy.PolicyDisposition
import com.rosan.installer.domain.engine.policy.PolicyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-FR-006, REQ-IX-INV-002, REQ-IX-INV-004, REQ-IX-INV-007.
 *
 * Pure, offline. Every engine result maps to a UI state, `continueEnabled` is
 * true only for reviewable/unavailable outcomes, BLOCK never enables Continue,
 * and no UI state can be more permissive than the engine decided.
 */
class AiDecisionUiMapperTest {

    private fun result(decision: PolicyDecision, disposition: PolicyDisposition, rule: String, reason: String) = PolicyResult(decision, disposition, rule, reason)

    private val model = AiReviewModel(
        likelihood = InstallSuccessLikelihood.LOW,
        risk = SecurityRisk.HIGH,
        reasons = listOf("evidence.declared_permissions"),
        agreement = false,
        modelId = "CS5351/DeepSeek-V4-Flash",
        promptVersion = "ai-risk-prompt-v1.1",
    )

    @Test
    fun decisionToUiState() {
        // BLOCK => Blocked, Continue disabled.
        val blocked = AiDecisionUiMapper.toUiState(
            ApprovalState.BLOCKED,
            result(PolicyDecision.BLOCK, PolicyDisposition.BLOCKED, "DR-01", "HARD_BLOCK"),
            null,
            null,
        )
        assertIs<AiUiState.Blocked>(blocked)
        assertFalse(blocked.continueEnabled)

        // REQUIRE_REVIEW / AWAIT_HUMAN => Review, Continue enabled.
        val review = AiDecisionUiMapper.toUiState(
            ApprovalState.AWAIT_HUMAN,
            result(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.AWAIT_HUMAN, "DR-02", "CONSENT_DECLINED"),
            model,
            null,
        )
        assertIs<AiUiState.Review>(review)
        assertTrue(review.continueEnabled)

        // RECOLLECT => disabled.
        val recollect = AiDecisionUiMapper.toUiState(
            ApprovalState.RECOLLECT,
            result(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.RECOLLECT, "DR-03", "EVIDENCE_INCOMPLETE"),
            null,
            null,
        )
        assertIs<AiUiState.Recollect>(recollect)
        assertFalse(recollect.continueEnabled)

        // INVALIDATED => disabled.
        val invalidated = AiDecisionUiMapper.toUiState(
            ApprovalState.INVALIDATED,
            result(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.INVALIDATED, "DR-10", "APPROVAL_INVALIDATED"),
            null,
            null,
        )
        assertIs<AiUiState.Invalidated>(invalidated)
        assertFalse(invalidated.continueEnabled)

        // DR-09 (REQUIRE_REVIEW / EXECUTE_ONCE) => Executing.
        val executing = AiDecisionUiMapper.toUiState(
            ApprovalState.EXECUTE_ONCE,
            result(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.EXECUTE_ONCE, "DR-09", "APPROVED_AFTER_REVIEW"),
            model,
            null,
        )
        assertIs<AiUiState.Executing>(executing)
        assertFalse(executing.continueEnabled)

        // Loading => disabled.
        assertFalse(AiDecisionUiMapper.loading().continueEnabled)
        // AutoExecute => disabled (engine drives it).
        assertFalse(AiDecisionUiMapper.autoExecute().continueEnabled)
    }

    @Test
    fun unavailableIsReviewNotAuto() {
        val reasons = listOf(
            AiUnavailable.ConsentOff,
            AiUnavailable.Unconfigured,
            AiUnavailable.NetworkOff,
            AiUnavailable.ModelFailure(FailureCode.NETWORK_ERROR),
            AiUnavailable.InvalidOutput,
        )
        for (reason in reasons) {
            val ui = AiDecisionUiMapper.toUiState(
                ApprovalState.AWAIT_HUMAN,
                result(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.AWAIT_HUMAN, "DR-08", "ADVICE_UNUSABLE"),
                null,
                reason,
            )
            assertIs<AiUiState.Unavailable>(ui)
            // Never auto; the human may still proceed (Continue enabled).
            assertTrue(ui.continueEnabled, "unavailable $reason must allow human continue")
        }
    }

    @Test
    fun persistenceFailedDisablesContinue() {
        // REL-002: persistence failure stops submission; Continue is disabled.
        val ui = AiDecisionUiMapper.toUiState(
            ApprovalState.AWAIT_HUMAN,
            result(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.AWAIT_HUMAN, "DR-08", "ADVICE_UNUSABLE"),
            null,
            AiUnavailable.PersistenceFailed,
        )
        assertIs<AiUiState.Unavailable>(ui)
        assertFalse(ui.continueEnabled)
    }

    /** INV-007: only reviewable/unavailable states enable Continue. */
    @Test
    fun noUiStateExceedsEnginePermission() {
        // BLOCK and RECOLLECT must never enable Continue.
        val blocked = AiDecisionUiMapper.toUiState(
            ApprovalState.BLOCKED,
            result(PolicyDecision.BLOCK, PolicyDisposition.BLOCKED, "DR-01", "HARD_BLOCK"),
            model,
            null,
        )
        assertFalse(blocked.continueEnabled)
    }
}
