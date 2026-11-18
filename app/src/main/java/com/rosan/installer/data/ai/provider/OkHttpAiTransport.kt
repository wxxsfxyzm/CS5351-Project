// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Implements: ADR-002.2; ADR-002.5
 * Enforces: REQ-IX-INV-004, REQ-IX-SEC-004, REQ-IX-FR-009
 *
 * OkHttp transport for OpenAI-compatible endpoints.
 *
 * **Network boundary.** It re-checks [egressAllowed] immediately before the
 * request leaves the process, so a caller that skips the gate still cannot
 * egress while the in-app network setting is off (`AGENTS.md`).
 *
 * **Secrets.** The API key is read through a lambda and is never logged,
 * stored in the request object, or included in failure messages
 * (REQ-IX-FR-009).
 *
 * **Bounded.** One exchange, a hard read timeout, and a response-size cap;
 * no automatic retry (REQ-IX-COST-001).
 *
 * **Shared stack.** When a base [OkHttpClient] is supplied (the app's
 * existing one from `di/NetworkModule.kt`), it is reused through
 * `newBuilder()` with only the model-call timeouts overridden, so the
 * advisory path adds no second HTTP stack and no new framework.
 */
class OkHttpAiTransport(
    private val baseUrl: String = GroqAiModelClient.BASE_URL,
    private val modelId: String = GroqAiModelClient.DEFAULT_MODEL,
    private val apiKeyProvider: () -> String?,
    private val egressAllowed: suspend () -> Boolean = { true },
    baseClient: OkHttpClient? = null,
    readTimeoutSeconds: Long = READ_TIMEOUT_SECONDS,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : OpenAiCompatibleTransport {

    private val client: OkHttpClient = (baseClient?.newBuilder()
        ?: OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    override suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
        if (!egressAllowed()) {
            throw AiAdvisorFailure.NetworkDisabled("network egress is disabled in app settings")
        }

        val apiKey = apiKeyProvider()
            ?: throw AiAdvisorFailure.NetworkError("no API key configured")

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/$path")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 429 -> {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()
                        throw AiAdvisorFailure.RateLimited(retryAfter)
                    }

                    // Preflight token rejection: the request itself is too
                    // large for the model. No completion was produced, but a
                    // dispatch was consumed (F-07 / F-08).
                    response.code == 413 -> {
                        throw AiAdvisorFailure.TokenLimitExceeded(
                            "request rejected as too large (HTTP 413)",
                        )
                    }

                    response.code == 400 && isTokenLimitError(response) -> {
                        throw AiAdvisorFailure.TokenLimitExceeded(
                            "provider rejected the request for exceeding a token limit (HTTP 400)",
                        )
                    }

                    !response.isSuccessful -> {
                        // Response bodies of error replies may echo request data;
                        // never log them, keep only the status code.
                        throw AiAdvisorFailure.ServiceError(response.code, "HTTP ${response.code}")
                    }

                    else -> {
                        val source = response.body.source()
                        val read = source.readByteArray(maxResponseBytes.toLong() + 1)
                        if (read.size > maxResponseBytes) {
                            throw AiAdvisorFailure.OutputOversize(read.size, maxResponseBytes)
                        }
                        extractContent(read.decodeToString(), modelId)
                    }
                }
            }
        } catch (io: InterruptedIOException) {
            throw AiAdvisorFailure.Timeout(io.message ?: "request timed out")
        } catch (io: IOException) {
            throw AiAdvisorFailure.NetworkError(io.message ?: "network error")
        }
    }

    /**
     * Detects an OpenAI-compatible token-limit error without logging the body.
     * Only the error `code`/`type` fields are inspected, never free text that
     * could echo the prompt (REQ-IX-FR-009).
     */
    private fun isTokenLimitError(response: Response): Boolean = runCatching {
        val body = response.body.string()
        if (body.length > MAX_ERROR_SCAN_BYTES) return false
        val root = json.parseToJsonElement(body) as? JsonObject ?: return false
        val error = root["error"] as? JsonObject ?: return false
        val markers = listOf("code", "type")
        markers.any { key ->
            val value = (error[key] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: return@any false
            TOKEN_LIMIT_MARKERS.any { value.contains(it) }
        }
    }.getOrDefault(false)

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
        const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_SECONDS = 10L

        /** Error bodies are only scanned for a code; never logged. */
        private const val MAX_ERROR_SCAN_BYTES = 8 * 1024

        private val TOKEN_LIMIT_MARKERS = listOf(
            "context_length", "too_large", "too_many_tokens", "max_tokens",
            "token_limit", "request_too_large", "string_above_max_length",
        )

        /** Model-call budget from `runtime-policy-catalog.md` F-01. */
        const val READ_TIMEOUT_SECONDS = 7L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
