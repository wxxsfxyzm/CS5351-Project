// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Implements: ADR-002.2
 * Enforces: REQ-IX-MOD-001
 *
 * Request body for an OpenAI-compatible chat-completions endpoint. Shared
 * by the Android adapter and the live test so both send the same shape.
 */
internal object OpenAiCompatiblePayload {
    fun build(
        json: Json,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
        jsonObjectMode: Boolean,
        temperature: Double = 0.1,
    ): String = buildJsonObject {
        put("model", model)
        put("temperature", temperature)
        put("max_tokens", maxOutputTokens)
        if (jsonObjectMode) {
            put("response_format", buildJsonObject { put("type", "json_object") })
        }
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    },
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    },
                )
            },
        )
    }.let { json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it) }
}

/**
 * Implements: ADR-002.2
 * Enforces: REQ-IX-INV-004, REQ-IX-SEC-004
 *
 * Transport for the model service. Implementations perform exactly one
 * HTTP exchange and translate every failure into
 * [com.rosan.installer.domain.ai.model.AiAdvisorFailure].
 */
interface OpenAiCompatibleTransport {
    /** @return the assistant message content, not the whole envelope. */
    suspend fun post(path: String, body: String): String
}
