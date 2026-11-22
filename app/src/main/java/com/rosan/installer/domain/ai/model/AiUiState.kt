// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.model

/**
 * Implements: ADR-002.4
 * Enforces: REQ-IX-FR-006, REQ-IX-INV-002, REQ-IX-INV-007
 *
 * The **pure** UI decision state shown in the install dialog. It is produced
 * only by mapping a deterministic engine result; it never carries a decision
 * of its own. `continueEnabled` is the single source of truth for the Install
 * button (which acts as Continue for `REVIEW`), so no UI state can be more
 * permissive than the engine decided.
 */
sealed interface AiUiState {
    /** The Install button (Continue) may proceed. */
    val continueEnabled: Boolean

    /** Assessment in flight; the card shows a spinner and Continue is disabled. */
    data object Loading : AiUiState {
        override val continueEnabled: Boolean = false
    }

    /** REQUIRE_REVIEW / AWAIT_HUMAN: the human decides; Continue is enabled. */
    data class Review(
        val model: AiReviewModel?,
        val failure: AiUnavailable?,
    ) : AiUiState {
        override val continueEnabled: Boolean = true
    }

    /** BLOCK: hard prohibition. Continue is disabled; the model cannot override. */
    data class Blocked(val reason: String) : AiUiState {
        override val continueEnabled: Boolean = false
    }

    /** RECOLLECT: evidence incomplete. Continue is disabled until re-collected. */
    data class Recollect(val reason: String) : AiUiState {
        override val continueEnabled: Boolean = false
    }

    /** INVALIDATED: approval failed revalidation. Continue is disabled. */
    data class Invalidated(val reason: String) : AiUiState {
        override val continueEnabled: Boolean = false
    }

    /** ALLOW_AUTO / EXECUTE_ONCE (DR-05): the engine auto-executes; card transient. */
    data object AutoExecute : AiUiState {
        override val continueEnabled: Boolean = false
    }

    /** REQUIRE_REVIEW / EXECUTE_ONCE (DR-09): already submitting. */
    data object Executing : AiUiState {
        override val continueEnabled: Boolean = false
    }

    /** Persistence failed or advice unavailable; the human may still proceed. */
    data class Unavailable(val reason: AiUnavailable) : AiUiState {
        /**
         * REL-002: a persistence failure stops automatic submission, so Continue
         * is disabled. Every other unavailability is a REQUIRE_REVIEW the human
         * may proceed past (the model could not advise, but the engine decided).
         */
        override val continueEnabled: Boolean =
            reason != AiUnavailable.PersistenceFailed
    }

    /**
     * The assessment path threw. Fail closed: the Install button is disabled and
     * only Cancel is available, so an error never lets an unassessed install
     * through.
     */
    data class Error(val reason: String) : AiUiState {
        override val continueEnabled: Boolean = false
    }
}

/** The advisory content shown on the card (never a decision). */
data class AiReviewModel(
    val likelihood: InstallSuccessLikelihood,
    val risk: SecurityRisk,
    val reasons: List<String>,
    val agreement: Boolean?,
    val modelId: String,
    val promptVersion: String,
)

/** Why advice is unavailable. Every subtype maps to REQUIRE_REVIEW, never auto. */
sealed interface AiUnavailable {
    data object ConsentOff : AiUnavailable
    data object Unconfigured : AiUnavailable
    data object NetworkOff : AiUnavailable
    data class ModelFailure(val code: FailureCode) : AiUnavailable
    data object InvalidOutput : AiUnavailable

    /** Persistence of the approval state failed (REL-002): no auto submit. */
    data object PersistenceFailed : AiUnavailable
}
