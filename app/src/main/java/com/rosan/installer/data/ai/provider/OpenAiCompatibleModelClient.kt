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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Implements: ADR-001; ADR-002.2
 * Enforces: REQ-IX-MOD-001, REQ-IX-FR-003, REQ-IX-COST-001
 *
 * Provider-neutral adapter for any OpenAI-compatible chat-completions API.
 * The advisor, validator, policy, and UI never see the provider; the endpoint
 * and model are resolved per call from [configSource], so switching providers
 * is a configuration change, not a code change (REQ-IX-MOD-001).
 *
 * One provider dispatch only; a truncated reply is reported as
 * [AiAdvisorFailure.TokenLimitExceeded] rather than passed on as text, so a
 * truncated completion is a failure status and never a partial assessment
 * (`runtime-policy-catalog.md` F-08).
 */
class OpenAiCompatibleModelClient(
    private val transport: OpenAiCompatibleTransport,
    private val configSource: AiEndpointConfigSource,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AiModelClient {

    /** Reported from the most recent call's configuration; informational. */
    override val modelId: String
        get() = lastModelId

    @Volatile
    private var lastModelId: String = ""

    override suspend fun complete(request: AiInstallRiskRequest): String {
        val config = configSource.current()
        val endpoint = config.endpoint
            ?: throw AiAdvisorFailure.ServiceError(0, "endpoint is not configured")
        lastModelId = config.modelId

        val body = OpenAiCompatiblePayload.build(
            json = json,
            model = config.modelId,
            systemPrompt = request.systemPrompt,
            userPrompt = request.userPrompt,
            maxOutputTokens = request.maxOutputTokens,
            jsonObjectMode = true,
        )
        val raw = transport.post(baseUrl = endpoint, path = CHAT_COMPLETIONS_PATH, body = body)
        guardedContent(raw, request.maxOutputTokens)
        return extractContent(raw, config.modelId)
    }

    /**
     * Rejects a completion that the provider reports as truncated or
     * filtered. If the envelope cannot be read far enough to tell, the raw
     * body is returned unchanged and the strict parser reports the failure
     * instead (F-05), so there is one honest failure path.
     */
    internal fun guardedContent(raw: String, maxOutputTokens: Int) {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return
        val firstChoice = runCatching {
            (root["choices"] as JsonArray)[0].jsonObject
        }.getOrNull() ?: return

        val finishReason = (firstChoice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        if (finishReason == null || finishReason == "stop") return

        if (finishReason == "content_filter") {
            throw AiAdvisorFailure.TokenLimitExceeded("completion blocked by content filter")
        }
        throw AiAdvisorFailure.TokenLimitExceeded(
            "completion truncated after $maxOutputTokens output tokens (finish_reason=$finishReason)",
        )
    }

    /**
     * Pulls `choices[0].message.content` out of the envelope and validates
     * that the reply came from the requested model.
     */
    internal fun extractContent(raw: String, expectedModelId: String): String {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: throw AiAdvisorFailure.InvalidOutput("envelope is not JSON")
        val reportedModel = (root["model"] as? JsonPrimitive)?.contentOrNull
        if (reportedModel != null && reportedModel != expectedModelId) {
            throw AiAdvisorFailure.InvalidOutput("unexpected model in response: $reportedModel")
        }
        val content = runCatching {
            root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        }.getOrNull() ?: throw AiAdvisorFailure.InvalidOutput("response has no choices[0].message.content")
        if (content.isBlank()) {
            throw AiAdvisorFailure.InvalidOutput("assistant content is empty")
        }
        return content
    }

    companion object {
        const val CHAT_COMPLETIONS_PATH = "chat/completions"
    }
}
