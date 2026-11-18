// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.repository.AiModelClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Implements: ADR-001; ADR-002.2
 * Enforces: REQ-IX-MOD-001, REQ-IX-FR-003, REQ-IX-COST-001
 *
 * Single-provider adapter for the **Groq** OpenAI-compatible chat
 * completions API (`https://api.groq.com/openai/v1/chat/completions`).
 * One provider only for v1; the [AiModelClient] interface keeps the
 * provider replaceable without touching the policy gate (REQ-IX-MOD-001).
 *
 * A response cut off by the token cap is reported as
 * [AiAdvisorFailure.TokenLimitExceeded] rather than being passed on as text,
 * so a truncated reply is a failure status and never a partial assessment
 * (`runtime-policy-catalog.md` F-08).
 */
class GroqAiModelClient(
    private val transport: OpenAiCompatibleTransport,
    override val modelId: String = DEFAULT_MODEL,
    private val json: Json = Json,
) : AiModelClient {

    override suspend fun complete(request: AiInstallRiskRequest): String {
        val body = OpenAiCompatiblePayload.build(
            json = json,
            model = modelId,
            systemPrompt = request.systemPrompt,
            userPrompt = request.userPrompt,
            maxOutputTokens = request.maxOutputTokens,
            jsonObjectMode = true,
        )
        val raw = transport.post(path = CHAT_COMPLETIONS_PATH, body = body)
        return guardedContent(raw, request.maxOutputTokens)
    }

    /**
     * Rejects a completion that the provider reports as truncated or
     * filtered. If the envelope cannot be read far enough to tell, the raw
     * body is returned unchanged and the strict parser reports the failure
     * instead (F-05), so there is one honest failure path.
     */
    internal fun guardedContent(raw: String, maxOutputTokens: Int): String {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return raw
        val firstChoice = runCatching {
            (root["choices"] as JsonArray)[0].jsonObject
        }.getOrNull() ?: return raw

        val finishReason = (firstChoice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        if (finishReason == null || finishReason == "stop") return raw

        if (finishReason == "content_filter") {
            throw AiAdvisorFailure.TokenLimitExceeded("completion blocked by content filter")
        }
        throw AiAdvisorFailure.TokenLimitExceeded(
            "completion truncated after $maxOutputTokens output tokens (finish_reason=$finishReason)",
        )
    }

    companion object {
        const val BASE_URL = "https://api.groq.com/openai/v1"
        const val CHAT_COMPLETIONS_PATH = "chat/completions"

        /** Default model; override with [AiModelClient.modelId]. */
        const val DEFAULT_MODEL = "qwen/qwen3-32b"

        /** Model tokens and endpoints are never logged (REQ-IX-FR-009). */
        const val ENV_API_KEY = "GROQ_API_KEY"
    }
}
