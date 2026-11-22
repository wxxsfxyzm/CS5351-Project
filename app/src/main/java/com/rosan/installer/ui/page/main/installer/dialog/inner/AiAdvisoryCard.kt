// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.ui.page.main.installer.dialog.inner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rosan.installer.R
import com.rosan.installer.domain.ai.model.AiReviewModel
import com.rosan.installer.domain.ai.model.AiUiState
import com.rosan.installer.domain.ai.model.AiUnavailable
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.HardBlockCause

/**
 * Implements: ADR-002.4
 * Enforces: REQ-IX-FR-006, REQ-IX-INV-002
 *
 * Material 3 advisory card. It is fed **only** by the pure [AiUiState], so it
 * holds no business logic and cannot change the decision (the engine decides).
 * It shows a loading spinner, the advisory chips and reasons, the unavailable
 * reason, or the policy outcome, with an explicit "advisory only" label.
 */
@Composable
fun AiAdvisoryCard(uiState: AiUiState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (uiState) {
                is AiUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(text = stringResource(R.string.ai_advisor_card_loading))
                }

                is AiUiState.Review -> {
                    uiState.model?.let { AdvisoryContent(it) }
                    uiState.failure?.let { UnavailableText(it) }
                    PolicyLine(decisionRes(uiState))
                }

                is AiUiState.Unavailable -> {
                    UnavailableText(uiState.reason)
                    PolicyLine(R.string.ai_advisor_policy_review)
                }

                is AiUiState.Blocked -> {
                    Text(
                        text = stringResource(R.string.ai_advisor_policy_blocked),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    blockCauseTexts(uiState).forEach { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                    Text(
                        text = stringResource(R.string.ai_advisor_block_not_overridable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is AiUiState.Recollect -> {
                    Text(text = stringResource(R.string.ai_advisor_policy_recollect))
                    Text(text = stringResource(R.string.ai_advisor_recollect_detail), style = MaterialTheme.typography.bodySmall)
                }

                is AiUiState.Invalidated -> {
                    Text(text = stringResource(R.string.ai_advisor_policy_invalidated))
                    Text(text = stringResource(R.string.ai_advisor_invalidated_detail), style = MaterialTheme.typography.bodySmall)
                }

                is AiUiState.AutoExecute -> {
                    Text(text = stringResource(R.string.ai_advisor_policy_auto))
                }

                is AiUiState.Executing -> {
                    Text(text = stringResource(R.string.ai_advisor_policy_review))
                }

                is AiUiState.Error -> {
                    Text(
                        text = stringResource(R.string.ai_advisor_error_title),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(text = uiState.reason, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                text = stringResource(R.string.ai_advisor_card_advisory_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AdvisoryContent(model: AiReviewModel) {
    Text(
        text = stringResource(R.string.ai_advisor_card_likelihood) + ": " + model.likelihood.name,
    )
    Text(
        text = stringResource(R.string.ai_advisor_card_risk) + ": " + model.risk.name,
    )
    if (model.reasons.isNotEmpty()) {
        Text(text = stringResource(R.string.ai_advisor_card_reasons) + ":", style = MaterialTheme.typography.labelMedium)
        model.reasons.take(5).forEach { reason ->
            Text(text = "• $reason", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UnavailableText(reason: AiUnavailable) {
    val text = when (reason) {
        AiUnavailable.ConsentOff -> stringResource(R.string.ai_advisor_unavailable_consent_off)
        AiUnavailable.Unconfigured -> stringResource(R.string.ai_advisor_unavailable_unconfigured)
        AiUnavailable.NetworkOff -> stringResource(R.string.ai_advisor_unavailable_network_off)
        is AiUnavailable.ModelFailure -> modelFailureText(reason.code)
        AiUnavailable.InvalidOutput -> stringResource(R.string.ai_advisor_unavailable_invalid_output)
        AiUnavailable.PersistenceFailed -> stringResource(R.string.ai_advisor_unavailable_model_failure)
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PolicyLine(textRes: Int) {
    Text(
        text = stringResource(R.string.ai_advisor_card_policy_decision) + ": " + stringResource(textRes),
        style = MaterialTheme.typography.titleSmall,
    )
}

private fun decisionRes(uiState: AiUiState): Int = when (uiState) {
    is AiUiState.Review -> R.string.ai_advisor_policy_review
    is AiUiState.Blocked -> R.string.ai_advisor_policy_blocked
    is AiUiState.Recollect -> R.string.ai_advisor_policy_recollect
    is AiUiState.Invalidated -> R.string.ai_advisor_policy_invalidated
    is AiUiState.AutoExecute -> R.string.ai_advisor_policy_auto
    is AiUiState.Executing -> R.string.ai_advisor_policy_review
    is AiUiState.Error -> R.string.ai_advisor_policy_review
    is AiUiState.Unavailable -> R.string.ai_advisor_policy_review
    is AiUiState.Loading -> R.string.ai_advisor_policy_review
}

/** Readable text for each hard-block rule that fired; a generic line if none is known. */
@Composable
internal fun blockCauseTexts(state: AiUiState.Blocked): List<String> = state.causes.map { cause ->
    when (cause) {
        HardBlockCause.SIGNATURE_MISMATCH -> stringResource(R.string.ai_advisor_block_signature_mismatch)
        HardBlockCause.SIGNATURE_VERIFICATION_FAILED -> stringResource(R.string.ai_advisor_block_verification_failed)
    }
}.ifEmpty { listOf(stringResource(R.string.ai_advisor_block_generic)) }

/** Readable text for a failed model call; the code is kept for the rarer cases. */
@Composable
internal fun modelFailureText(code: FailureCode): String = when (code) {
    FailureCode.NETWORK_ERROR -> stringResource(R.string.ai_advisor_unavailable_network_error)
    FailureCode.TIMEOUT -> stringResource(R.string.ai_advisor_unavailable_timeout)
    else -> stringResource(R.string.ai_advisor_unavailable_model_failure) + " (" + code.name + ")"
}
