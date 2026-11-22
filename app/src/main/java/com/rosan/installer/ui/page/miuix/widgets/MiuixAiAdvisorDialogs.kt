// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.ui.page.miuix.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rosan.installer.R
import com.rosan.installer.data.ai.provider.AiEndpointValidator
import com.rosan.installer.domain.ai.usecase.AiAdvisorSettings
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Implements: ADR-002.5
 * Enforces: REQ-IX-FR-011, REQ-IX-SEC-004
 *
 * Miuix variant of the first-use configuration dialog. Endpoint prefilled and
 * validated; key stored only via the credential store.
 */
@Composable
fun MiuixAiAdvisorConfigDialog(
    showState: MutableState<Boolean>,
    initialEndpoint: String,
    hasStoredKey: Boolean,
    onDismiss: () -> Unit,
    onSave: (endpoint: String, apiKey: String?) -> Unit,
) {
    var endpoint by remember(initialEndpoint) {
        mutableStateOf(initialEndpoint.ifBlank { AiEndpointValidator.DEFAULT_ENDPOINT })
    }
    var apiKey by remember { mutableStateOf("") }
    var endpointError by remember { mutableStateOf(false) }

    WindowDialog(
        show = showState.value,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ai_advisor_config_title),
        content = {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it
                        endpointError = false
                    },
                    label = stringResource(R.string.ai_advisor_endpoint_label),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                if (endpointError) {
                    Text(text = stringResource(R.string.ai_advisor_endpoint_invalid))
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = stringResource(R.string.ai_advisor_api_key_label),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = stringResource(R.string.ai_advisor_api_key_helper))
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.save),
                        onClick = {
                            val normalized = AiEndpointValidator.normalizeOrNull(endpoint)
                            if (normalized == null) {
                                endpointError = true
                            } else {
                                onSave(normalized, apiKey.ifBlank { null })
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

/**
 * Implements: ADR-002.4; ADR-002.5
 * Enforces: REQ-IX-FR-011
 *
 * Miuix variant of the consent dialog listing every allowlisted field.
 */
@Composable
fun MiuixAiAdvisorConsentDialog(
    showState: MutableState<Boolean>,
    recipientHost: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val fields = remember { AiAdvisorSettings.consentAllowlist() }

    WindowDialog(
        show = showState.value,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ai_advisor_consent_dialog_title),
        content = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text = stringResource(R.string.ai_advisor_consent_dialog_intro))
                if (recipientHost.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.ai_advisor_consent_dialog_recipient) + ": " + recipientHost,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                fields.forEach { path ->
                    Text(text = "• $path")
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.ai_advisor_consent_dialog_cancel),
                    onClick = onDismiss,
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.confirm),
                    onClick = {
                        onConfirm()
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}
