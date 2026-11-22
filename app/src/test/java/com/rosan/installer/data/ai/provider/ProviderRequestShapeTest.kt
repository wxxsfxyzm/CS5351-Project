// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Verifies: REQ-IX-MOD-001, REQ-IX-SEC-004 (outbound request shape).
 *
 * Offline. The client must send the configured model, a JSON-object response
 * format, the token budget, and only the expected top-level keys — so a
 * provider change is a configuration change and the payload cannot silently
 * grow new fields.
 */
class ProviderRequestShapeTest {

    private class CapturingTransport(private val reply: String) : OpenAiCompatibleTransport {
        var baseUrl: String? = null
        var path: String? = null
        var body: String? = null
        var dispatches = 0
        override suspend fun post(baseUrl: String, path: String, body: String): String {
            dispatches++
            this.baseUrl = baseUrl
            this.path = path
            this.body = body
            return reply
        }
    }

    private fun request() = AiInstallRiskRequest(
        modelId = "ignored-by-client",
        promptVersion = "ai-risk-prompt-v1.1",
        systemPrompt = "system",
        userPrompt = "user",
        evidencePayloadJson = "{}",
        maxOutputTokens = 512,
    )

    private fun client(transport: OpenAiCompatibleTransport) = OpenAiCompatibleModelClient(
        transport = transport,
        configSource = AiEndpointConfigSource {
            AiEndpointConfig(
                endpoint = "https://llm.cs.cityu.edu.hk/v1",
                apiKey = "sentinel-key",
                modelId = "CS5351/DeepSeek-V4-Flash",
                promptVersion = "ai-risk-prompt-v1.1",
            )
        },
    )

    @Test
    fun recordsCourseStyleRequest() = runTest {
        val transport = CapturingTransport("""{"choices":[{"message":{"content":"{\"a\":1}"},"finish_reason":"stop"}],"model":"CS5351/DeepSeek-V4-Flash"}""")
        client(transport).complete(request())

        assertEquals(1, transport.dispatches)
        assertEquals("https://llm.cs.cityu.edu.hk/v1", transport.baseUrl)
        assertEquals("chat/completions", transport.path)

        val body = Json.parseToJsonElement(transport.body!!).jsonObject
        assertEquals("CS5351/DeepSeek-V4-Flash", body["model"]!!.jsonPrimitive.content)
        assertEquals(512, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            "json_object",
            body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertEquals(
            setOf("model", "temperature", "max_tokens", "response_format", "messages"),
            body.keys,
            "outbound body must carry only the expected keys",
        )
    }

    @Test
    fun missingEndpointFailsBeforeDispatch() = runTest {
        val transport = CapturingTransport("{}")
        val broken = OpenAiCompatibleModelClient(
            transport = transport,
            configSource = AiEndpointConfigSource {
                AiEndpointConfig(endpoint = null, apiKey = "k", modelId = "m", promptVersion = "p")
            },
        )
        val result = runCatching { broken.complete(request()) }
        assertTrue(result.isFailure)
        assertEquals(0, transport.dispatches)
        assertFalse(transport.body != null)
    }
}
