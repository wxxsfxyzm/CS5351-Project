// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.repository.ModelRequestGate
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
 * egress while the in-app network setting is off (`AGENTS.md`). It also reads
 * the assessment's current consent immediately before dispatch (REQ-IX-FR-012).
 * Withdrawal cannot recall an in-flight request.
 *
 * **Host-only egress.** The request URL is built only from a [baseUrl] that
 * passed [AiEndpointValidator] (HTTPS, well-formed, no userinfo). The
 * transport never accepts a caller-supplied full URL, so only the configured
 * host is reachable. A rejected endpoint fails closed before dispatch.
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
    private val apiKeyProvider: suspend () -> String?,
    private val egressAllowed: suspend () -> Boolean = { true },
    baseClient: OkHttpClient? = null,
    readTimeoutSeconds: Long = READ_TIMEOUT_SECONDS,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : OpenAiCompatibleTransport {

    private val client: OkHttpClient = (
        baseClient?.newBuilder()
            ?: OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        )
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    override suspend fun post(baseUrl: String, path: String, body: String): String = withContext(Dispatchers.IO) {
        if (!egressAllowed()) {
            throw AiAdvisorFailure.NetworkDisabled("network egress is disabled in app settings")
        }

        // Build the URL only from a validated endpoint. The transport
        // never accepts a caller-supplied full URL, so only the
        // configured HTTPS host is reachable (REQ-IX-SEC-004).
        val validatedBase = AiEndpointValidator.normalizeOrNull(baseUrl)
            ?: throw AiAdvisorFailure.ServiceError(0, "endpoint is not a valid HTTPS URL")

        val apiKey = readApiKey()

        val request = Request.Builder()
            .url("$validatedBase/$path")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        if (!ModelRequestGate.hasCurrentConsent()) throw AiAdvisorFailure.ConsentUnavailable()

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
                        read.decodeToString()
                    }
                }
            }
        } catch (io: InterruptedIOException) {
            throw AiAdvisorFailure.Timeout(io.message ?: "request timed out")
        } catch (io: IOException) {
            throw AiAdvisorFailure.NetworkError(io.message ?: "network error")
        }
    }

    /** Reads a usable Bearer credential without exposing the key or the source error (REQ-IX-FR-009). */
    private suspend fun readApiKey(): String {
        val key = try {
            apiKeyProvider()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AiAdvisorFailure.ApiKeyUnavailable()
        }
        return key?.takeIf { it.isNotEmpty() && it.all { char -> char in '!'..'~' } }
            ?: throw AiAdvisorFailure.ApiKeyUnavailable()
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

    companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_SECONDS = 10L

        /** Error bodies are only scanned for a code; never logged. */
        private const val MAX_ERROR_SCAN_BYTES = 8 * 1024

        private val TOKEN_LIMIT_MARKERS = listOf(
            "context_length",
            "too_large",
            "too_many_tokens",
            "max_tokens",
            "token_limit",
            "request_too_large",
            "string_above_max_length",
        )

        /** Model-call budget from `runtime-policy-catalog.md` F-01. */
        const val READ_TIMEOUT_SECONDS = 7L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
