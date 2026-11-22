// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.ui.page.main.installer

import com.rosan.installer.domain.ai.model.AiUiState
import com.rosan.installer.domain.engine.approval.PrepareApprovalUseCase
import com.rosan.installer.ui.page.main.installer.mapper.AiDecisionUiMapper

/**
 * Implements: ADR-002.6
 * Enforces: REQ-IX-ARCH-001, REQ-IX-FR-012
 *
 * The pure decision the ViewModel takes after [PrepareApprovalUseCase] returns.
 * This is the **only** place that may yield [BaselineInstall], so the
 * advisor-on `session.install` call is reachable only on the baseline branch
 * (advisor off, or out-of-scope). Every other outcome shows a UI state; a
 * thrown error fails closed with the Install button disabled.
 *
 * Testable without Robolectric (BUG-003).
 */
sealed interface AdvisorDecision {
    /** Advisor off or out-of-scope: run the baseline `session.install(true)`. */
    data object BaselineInstall : AdvisorDecision

    /** Show an advisory/error state; the Install button follows its `continueEnabled`. */
    data class Show(val uiState: AiUiState) : AdvisorDecision

    /** A prior trigger already started; keep the current stage, do nothing. */
    data object AlreadyStarted : AdvisorDecision

    /**
     * A retry (e.g. after uninstall-then-reinstall) must re-run the advisor,
     * because the facts changed (the installed app and signature are gone).
     * Never a baseline install.
     */
    data object RetryAssessment : AdvisorDecision

    companion object {
        /**
         * The decision for the retry-after-uninstall continuation. With the
         * advisor active it must re-assess (a second, distinct attempt), never
         * submit directly; with the advisor off it keeps the baseline retry.
         */
        fun forRetry(advisorActive: Boolean): AdvisorDecision = if (advisorActive) RetryAssessment else BaselineInstall

        /**
         * @param outcome the prepare result, or null when it threw.
         * @param thrown the thrown error message, when the prepare call failed.
         */
        fun from(outcome: PrepareApprovalUseCase.Outcome?, thrown: Throwable?): AdvisorDecision {
            if (thrown != null || outcome == null) {
                // Fail closed: an error shows an error state; Install is disabled.
                return Show(AiUiState.Error(thrown?.message ?: "advisor error"))
            }
            return when (outcome) {
                PrepareApprovalUseCase.Outcome.Baseline -> BaselineInstall

                PrepareApprovalUseCase.Outcome.AlreadyStarted -> AlreadyStarted

                is PrepareApprovalUseCase.Outcome.Unavailable -> Show(
                    AiUiState.Unavailable(outcome.reason),
                )

                is PrepareApprovalUseCase.Outcome.Decided -> Show(
                    AiDecisionUiMapper.toUiState(
                        state = outcome.state,
                        result = outcome.decision,
                        model = outcome.model,
                        unavailable = outcome.unavailable,
                    ),
                )
            }
        }
    }
}
