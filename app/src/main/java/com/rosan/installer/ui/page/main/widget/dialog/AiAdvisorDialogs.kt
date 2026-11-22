// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.ui.page.main.widget.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rosan.installer.R
import com.rosan.installer.data.ai.provider.AiEndpointValidator
import com.rosan.installer.domain.ai.usecase.AiAdvisorSettings

/**
 * Implements: ADR-002.5
 * Enforces: REQ-IX-FR-011, REQ-IX-SEC-004
 *
 * First-use configuration dialog. The endpoint is prefilled with the course
 * service and validated before save; the key field is masked. There is no
 * "Test connection" button (Q1). The key is stored only through the credential
 * store and never placed in UI state (REQ-IX-FR-009).
 */
@Composable
fun AiAdvisorConfigDialog(
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_advisor_config_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it
                        endpointError = false
                    },
                    label = { Text(stringResource(R.string.ai_advisor_endpoint_label)) },
                    isError = endpointError,
                    supportingText = {
                        Text(
                            stringResource(
                                if (endpointError) {
                                    R.string.ai_advisor_endpoint_invalid
                                } else {
                                    R.string.ai_advisor_endpoint_helper
                                },
                            ),
                        )
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.ai_advisor_api_key_label)) },
                    // A stored key is never loaded into the field; dots only show that one exists.
                    placeholder = if (hasStoredKey) {
                        { Text(STORED_KEY_PLACEHOLDER) }
                    } else {
                        null
                    },
                    supportingText = {
                        Text(
                            stringResource(
                                if (hasStoredKey) {
                                    R.string.ai_advisor_api_key_stored_helper
                                } else {
                                    R.string.ai_advisor_api_key_helper
                                },
                            ),
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalized = AiEndpointValidator.normalizeOrNull(endpoint)
                    if (normalized == null) {
                        endpointError = true
                    } else {
                        onSave(normalized, apiKey.ifBlank { null })
                    }
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Implements: ADR-002.4; ADR-002.5
 * Enforces: REQ-IX-FR-011
 *
 * Consent dialog listing every allowlisted field (single source of truth:
 * [AiAdvisorSettings.consentAllowlist], generated from
 * `AiRequestBuilder.ALLOWLISTED_LEAF_PATHS`). Cancel leaves consent off.
 */
@Composable
fun AiAdvisorConsentDialog(
    recipientHost: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val fields = remember { AiAdvisorSettings.consentAllowlist() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_advisor_consent_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.ai_advisor_consent_dialog_intro))
                if (recipientHost.isNotBlank()) {
                    Text(
                        stringResource(R.string.ai_advisor_consent_dialog_recipient) + ": " + recipientHost,
                    )
                }
                fields.forEach { path ->
                    Text("• $path")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ai_advisor_consent_dialog_cancel))
            }
        },
    )
}

/** Shown in an empty key field when a key is already stored; not derived from the key. */
internal const val STORED_KEY_PLACEHOLDER = "••••••••••••"
