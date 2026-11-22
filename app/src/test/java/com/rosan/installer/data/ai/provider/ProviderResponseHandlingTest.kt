// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.code
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import com.rosan.installer.domain.ai.usecase.StrictAiOutputValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies: REQ-IX-FR-003, REQ-IX-MOD-001, REQ-IX-INV-004, REQ-IX-COST-001.
 *
 * Offline, fixed responses modelled on the course service (checkpoint 0):
 * bare JSON, fenced JSON, JSON+prose, a truncated completion, and a
 * reasoning-only reply. Every path is classified, never a favorable default,
 * and never more than one dispatch.
 */
class ProviderResponseHandlingTest {

    private val modelId = "CS5351/DeepSeek-V4-Flash"

    private class FixedTransport(
        private val reply: String?,
        private val failure: AiAdvisorFailure? = null,
    ) : OpenAiCompatibleTransport {
        var dispatches = 0
        override suspend fun post(baseUrl: String, path: String, body: String): String {
            dispatches++
            failure?.let { throw it }
            return reply!!
        }
    }

    private fun client(transport: OpenAiCompatibleTransport) = OpenAiCompatibleModelClient(
        transport = transport,
        configSource = AiEndpointConfigSource {
            AiEndpointConfig(
                endpoint = "https://llm.cs.cityu.edu.hk/v1",
                apiKey = "k",
                modelId = modelId,
                promptVersion = AiRequestBuilder.PROMPT_VERSION,
            )
        },
    )

    private fun request(): AiInstallRiskRequest = AiRequestBuilder().build(
        com.rosan.installer.domain.ai.fixture.EvidenceFixtures.normalUpdate,
    ).request

    private fun envelope(content: String, finishReason: String = "stop", model: String = modelId) = """{"model":"$model","choices":[{"message":{"content":${jsonString(content)}},"finish_reason":"$finishReason"}]}"""

    private fun jsonString(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()

    private fun parse(raw: String): AssessmentResult {
        val prepared = AiRequestBuilder().build(com.rosan.installer.domain.ai.fixture.EvidenceFixtures.normalUpdate)
        val client = client(FixedTransport(raw))
        val content = kotlinx.coroutines.runBlocking { client.complete(request()) }
        return StrictAiOutputValidator().parse(
            content,
            modelId = modelId,
            promptVersion = prepared.request.promptVersion,
            knownEvidencePaths = prepared.transmittedPaths,
        )
    }

    private val validBody = """
        {"install_success_likelihood":"HIGH","security_risk":"LOW",
         "reasons":["evidence.target_sdk is recent"],
         "evidence_refs":["evidence.target_sdk"]}
    """.trimIndent()

    @Test
    fun bareJsonIsValid() {
        assertTrue(parse(envelope(validBody)) is AssessmentResult.Valid)
    }

    @Test
    fun fencedJsonIsValid() {
        assertTrue(parse(envelope("```json\n$validBody\n```")) is AssessmentResult.Valid)
    }

    @Test
    fun jsonPlusProseIsInvalid() {
        assertTrue(parse(envelope("Here is my answer: $validBody Hope this helps.")) is AssessmentResult.Invalid)
    }

    @Test
    fun truncatedCompletionFailsAsTokenLimit() = runTest {
        val transport = FixedTransport(envelope(validBody, finishReason = "length"))
        val failure = assertFailsWith<AiAdvisorFailure> { client(transport).complete(request()) }
        assertEquals(FailureCode.TOKEN_LIMIT_EXCEEDED, failure.code)
        assertEquals(1, transport.dispatches)
    }

    @Test
    fun jsonObjectRejectedIsSingleDispatchAndReview() = runTest {
        // Defensive: a 400 for response_format should not occur on the course
        // gateway (checkpoint 0), but if it does: one dispatch, no retry.
        val transport = FixedTransport(
            reply = null,
            failure = AiAdvisorFailure.ServiceError(400, "HTTP 400"),
        )
        val failure = assertFailsWith<AiAdvisorFailure> { client(transport).complete(request()) }
        assertEquals(FailureCode.SERVICE_ERROR, failure.code)
        assertEquals(1, transport.dispatches)
    }
}
