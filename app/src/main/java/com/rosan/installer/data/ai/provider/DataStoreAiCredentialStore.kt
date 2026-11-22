// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Implements: ADR-002.2; ADR-002.5
 * Enforces: REQ-IX-SEC-004, REQ-IX-FR-009, REQ-IX-MOD-001
 *
 * [AiCredentialStore] and [AiEndpointConfigSource] over a **separate**
 * app-private Preferences DataStore file (`ai_credentials`). Keeping it out of
 * `AppDataStore` means `BackupRepositoryImpl` never exports it by
 * construction.
 *
 * The file is plaintext this slice (Q2: threat model excludes rooted/backed-up
 * devices; no new dependency). Recorded as guardrail debt by the verify role.
 */
class DataStoreAiCredentialStore(
    private val dataStore: DataStore<Preferences>,
) : AiCredentialStore,
    AiEndpointConfigSource {

    override val endpointFlow: Flow<String?> = dataStore.data.map { it[KEY_ENDPOINT] }

    override suspend fun currentApiKey(): String? = dataStore.data.first()[KEY_API_KEY]

    override suspend fun saveEndpoint(endpoint: String) {
        dataStore.edit { it[KEY_ENDPOINT] = endpoint }
    }

    override suspend fun saveApiKey(apiKey: String) {
        dataStore.edit { it[KEY_API_KEY] = apiKey }
    }

    override suspend fun clear() {
        dataStore.edit {
            it.remove(KEY_ENDPOINT)
            it.remove(KEY_API_KEY)
        }
    }

    override suspend fun maskedKey(): String = maskApiKey(currentApiKey())

    override suspend fun current(): AiEndpointConfig {
        val prefs = dataStore.data.first()
        return AiEndpointConfig(
            endpoint = prefs[KEY_ENDPOINT],
            apiKey = prefs[KEY_API_KEY],
            modelId = AiRequestBuilder.DEFAULT_MODEL_ID,
            promptVersion = AiRequestBuilder.PROMPT_VERSION,
        )
    }

    private companion object {
        val KEY_ENDPOINT = stringPreferencesKey("ai_endpoint")
        val KEY_API_KEY = stringPreferencesKey("ai_api_key")
    }
}
