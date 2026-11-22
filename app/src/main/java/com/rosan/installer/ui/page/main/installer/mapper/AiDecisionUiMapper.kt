// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.ui.page.main.installer.mapper

import com.rosan.installer.domain.ai.model.AiReviewModel
import com.rosan.installer.domain.ai.model.AiUiState
import com.rosan.installer.domain.ai.model.AiUnavailable
import com.rosan.installer.domain.ai.model.HardBlockCause
import com.rosan.installer.domain.engine.approval.ApprovalState
import com.rosan.installer.domain.engine.policy.PolicyResult

/**
 * Implements: ADR-002.4
 * Enforces: REQ-IX-FR-006, REQ-IX-INV-002, REQ-IX-INV-007
 *
 * Pure mapping from a deterministic decision to the UI state. Because it is a
 * total function of the engine result, no UI state can exceed the engine's
 * permission (INV-007), and BLOCK is never mapped to an enabled Continue
 * (INV-002). The Install button uses [AiUiState.continueEnabled] directly.
 *
 * Testable without Robolectric (BUG-003).
 */
object AiDecisionUiMapper {

    /**
     * @param state the approval state the coordinator routed to.
     * @param result the engine's decision.
     * @param model the advisory content, if a usable assessment exists.
     * @param unavailable why advice is unavailable, if it is.
     */
    fun toUiState(
        state: ApprovalState,
        result: PolicyResult,
        model: AiReviewModel?,
        unavailable: AiUnavailable?,
        blockCauses: List<HardBlockCause> = emptyList(),
    ): AiUiState = when (state) {
        ApprovalState.BLOCKED -> AiUiState.Blocked(result.reasonCode, blockCauses)

        ApprovalState.RECOLLECT -> AiUiState.Recollect(result.reasonCode)

        ApprovalState.INVALIDATED -> AiUiState.Invalidated(result.reasonCode)

        ApprovalState.EXECUTE_ONCE -> if (result.disposition ==
            com.rosan.installer.domain.engine.policy.PolicyDisposition.EXECUTE_ONCE
        ) {
            AiUiState.Executing
        } else {
            AiUiState.Review(model, unavailable)
        }

        ApprovalState.AWAIT_HUMAN ->
            if (unavailable != null && model == null) {
                // Advice unavailable but the human may still proceed (model
                // failure / invalid output keep Continue enabled; the rest too).
                AiUiState.Unavailable(unavailable)
            } else {
                AiUiState.Review(model, unavailable)
            }

        ApprovalState.FAILED,
        ApprovalState.RESULT_UNKNOWN,
        ApprovalState.SUBMITTED,
        -> AiUiState.Executing

        else -> AiUiState.Review(model, unavailable)
    }

    /** DR-05 auto-execute state (card shown transiently before Installing). */
    fun autoExecute(): AiUiState = AiUiState.AutoExecute

    /** Loading state on entry while the assessment is in flight. */
    fun loading(): AiUiState = AiUiState.Loading
}
