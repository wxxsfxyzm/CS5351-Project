// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.ui.page.miuix.installer.sheetcontent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rosan.installer.R
import com.rosan.installer.domain.ai.model.AiReviewModel
import com.rosan.installer.domain.ai.model.AiUiState
import com.rosan.installer.domain.ai.model.AiUnavailable
import com.rosan.installer.ui.page.main.installer.dialog.inner.blockCauseTexts
import com.rosan.installer.ui.page.main.installer.dialog.inner.modelFailureText
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Implements: ADR-002.4
 * Enforces: REQ-IX-FR-006, REQ-IX-INV-002
 *
 * Miuix advisory card, fed only by the pure [AiUiState]. It shows the advisory
 * opinion, the unavailable reason, or the policy outcome with an explicit
 * "advisory only" label. It holds no business logic and cannot decide.
 */
@Composable
fun MiuixAiAdvisoryCard(uiState: AiUiState, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (uiState) {
                is AiUiState.Loading -> {
                    InfiniteProgressIndicator()
                    Text(text = stringResource(R.string.ai_advisor_card_loading))
                }

                is AiUiState.Review -> {
                    uiState.model?.let { AdvisoryContent(it) }
                    uiState.failure?.let { UnavailableText(it) }
                    Text(
                        text = stringResource(R.string.ai_advisor_card_policy_decision) + ": " +
                            stringResource(R.string.ai_advisor_policy_review),
                    )
                }

                is AiUiState.Unavailable -> {
                    UnavailableText(uiState.reason)
                    Text(
                        text = stringResource(R.string.ai_advisor_card_policy_decision) + ": " +
                            stringResource(R.string.ai_advisor_policy_review),
                    )
                }

                is AiUiState.Blocked -> {
                    Text(
                        text = stringResource(R.string.ai_advisor_policy_blocked),
                        color = MiuixTheme.colorScheme.error,
                    )
                    blockCauseTexts(uiState).forEach { Text(text = it) }
                    Text(
                        text = stringResource(R.string.ai_advisor_block_not_overridable),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                is AiUiState.Recollect -> {
                    Text(text = stringResource(R.string.ai_advisor_policy_recollect))
                    Text(text = stringResource(R.string.ai_advisor_recollect_detail))
                }

                is AiUiState.Invalidated -> {
                    Text(text = stringResource(R.string.ai_advisor_policy_invalidated))
                    Text(text = stringResource(R.string.ai_advisor_invalidated_detail))
                }

                is AiUiState.AutoExecute -> Text(text = stringResource(R.string.ai_advisor_policy_auto))

                is AiUiState.Executing -> Text(text = stringResource(R.string.ai_advisor_policy_review))

                is AiUiState.Error -> {
                    Text(
                        text = stringResource(R.string.ai_advisor_error_title),
                        color = MiuixTheme.colorScheme.error,
                    )
                    Text(text = uiState.reason)
                }
            }
            Text(
                text = stringResource(R.string.ai_advisor_card_advisory_only),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun AdvisoryContent(model: AiReviewModel) {
    Text(text = stringResource(R.string.ai_advisor_card_likelihood) + ": " + model.likelihood.name)
    Text(text = stringResource(R.string.ai_advisor_card_risk) + ": " + model.risk.name)
    if (model.reasons.isNotEmpty()) {
        Text(text = stringResource(R.string.ai_advisor_card_reasons) + ":")
        model.reasons.take(5).forEach { reason ->
            Text(text = "• $reason")
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
    Text(text = text)
}
