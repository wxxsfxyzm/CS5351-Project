// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [AiCredentialStore] for tests. It mirrors the production masking
 * and export-exclusion rules without an Android DataStore.
 */
class FakeAiCredentialStore(
    initialEndpoint: String? = null,
    initialApiKey: String? = null,
) : AiCredentialStore,
    AiEndpointConfigSource {
    private val endpoint = MutableStateFlow(initialEndpoint)
    private val apiKey = MutableStateFlow(initialApiKey)

    override val endpointFlow: Flow<String?> = endpoint

    override suspend fun currentApiKey(): String? = apiKey.value

    override suspend fun saveEndpoint(endpoint: String) {
        this.endpoint.value = endpoint
    }

    override suspend fun saveApiKey(apiKey: String) {
        this.apiKey.value = apiKey
    }

    override suspend fun clear() {
        endpoint.value = null
        apiKey.value = null
    }

    override suspend fun maskedKey(): String = maskApiKey(apiKey.value)

    override suspend fun current(): AiEndpointConfig = AiEndpointConfig(
        endpoint = endpoint.value,
        apiKey = apiKey.value,
        modelId = AiRequestBuilder.DEFAULT_MODEL_ID,
        promptVersion = AiRequestBuilder.PROMPT_VERSION,
    )

    fun rawApiKey(): String? = apiKey.value
}
