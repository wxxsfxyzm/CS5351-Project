// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.data.ai.advisory.DefaultAiRiskAdvisor
import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.repository.AiModelClient
import com.rosan.installer.domain.ai.repository.AiNetworkGate
import com.rosan.installer.domain.ai.repository.AiOutputValidator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-INV-004, REQ-IX-FR-004, REQ-IX-SEC-004, REQ-IX-REL-001
 * Offline and deterministic: a scripted [AiModelClient] stands in for the
 * live model. No network access happens in this test.
 *
 * The advisor under test is the data-layer implementation; the interface it
 * satisfies stays in `domain` (ADR-001 Option B).
 */
class DefaultAiRiskAdvisorTest {
    private val request = AiInstallRiskRequest(
        modelId = "stub",
        promptVersion = "v1",
        systemPrompt = "system",
        userPrompt = "user",
        evidencePayloadJson = """{"target_sdk":35}""",
        maxOutputTokens = 64,
    )

    private val validResponse = """
        {"install_success_likelihood":"HIGH","security_risk":"LOW",
         "reasons":["ok"],"evidence_refs":["evidence.target_sdk"]}
    """.trimIndent()

    /** Exactly the paths [request] carries, as the builder would report. */
    private companion object {
        val KNOWN_PATHS = setOf("evidence.target_sdk")
    }

    private class StubClient(
        private val behaviour: suspend () -> String,
    ) : AiModelClient {
        var calls = 0
            private set
        override val modelId = "stub"
        override suspend fun complete(request: AiInstallRiskRequest): String {
            calls++
            return behaviour()
        }
    }

    private class Gate(private val allowed: Boolean) : AiNetworkGate {
        override suspend fun isEgressAllowed() = allowed
    }

    private fun advisor(client: StubClient, allowed: Boolean = true) = DefaultAiRiskAdvisor(
        modelClient = client,
        networkGate = Gate(allowed),
        outputValidator = StrictAiOutputValidator(),
    )

    /** Verifies: REQ-IX-FR-003 (one dispatch, valid assessment returned). */
    @Test
    fun validModelReply_producesValidAssessmentWithOneDispatch() = runTest {
        val client = StubClient { validResponse }
        val outcome = advisor(client).assessDetailed(request, KNOWN_PATHS)
        val result = assertIs<AdvisorOutcome.Ok>(outcome.result).result
        assertIs<AssessmentResult.Valid>(result)
        assertEquals(1, outcome.providerDispatches)
        assertEquals(1, client.calls)
    }

    /** Verifies: REQ-IX-SEC-004, REQ-IX-INV-004 (network off -> 0 dispatches). */
    @Test
    fun networkDisabled_makesZeroDispatches() = runTest {
        val client = StubClient { validResponse }
        val outcome = advisor(client, allowed = false).assessDetailed(request, KNOWN_PATHS)
        val failure = assertIs<AdvisorOutcome.Failure>(outcome.result).failure
        assertIs<AiAdvisorFailure.NetworkDisabled>(failure)
        assertEquals(0, outcome.providerDispatches)
        assertEquals(0, client.calls)
    }

    /** Verifies: REQ-IX-INV-004 (timeout -> failure, never an assessment). */
    @Test
    fun timeout_neverReturnsAssessment() = runTest {
        val client = StubClient { throw AiAdvisorFailure.Timeout("7s") }
        val outcome = advisor(client).assessDetailed(request, KNOWN_PATHS)
        assertIs<AdvisorOutcome.Failure>(outcome.result)
        assertEquals(1, client.calls)
    }

    /** Verifies: REQ-IX-INV-004 (rate limit -> failure). */
    @Test
    fun rateLimit_neverReturnsAssessment() = runTest {
        val client = StubClient { throw AiAdvisorFailure.RateLimited(30) }
        assertIs<AdvisorOutcome.Failure>(advisor(client).assessRequest(request, KNOWN_PATHS))
    }

    /** Verifies: REQ-IX-INV-004 (service error -> failure). */
    @Test
    fun serviceError_neverReturnsAssessment() = runTest {
        val client = StubClient { throw AiAdvisorFailure.ServiceError(500, "HTTP 500") }
        assertIs<AdvisorOutcome.Failure>(advisor(client).assessRequest(request, KNOWN_PATHS))
    }

    /** Verifies: REQ-IX-INV-004 (unexpected exception -> failure). */
    @Test
    fun unexpectedException_neverReturnsAssessment() = runTest {
        val client = StubClient { throw IllegalStateException("boom") }
        assertIs<AdvisorOutcome.Failure>(advisor(client).assessRequest(request, KNOWN_PATHS))
    }

    /** Verifies: REQ-IX-INV-001 (install instructions -> Invalid, not an assessment). */
    @Test
    fun installInstructions_neverReturnValidAssessment() = runTest {
        val client = StubClient {
            """{"install_success_likelihood":"HIGH","security_risk":"LOW",
                "action":"install_now","reasons":[],"evidence_refs":[]}"""
        }
        val result = assertIs<AdvisorOutcome.Ok>(advisor(client).assessRequest(request, KNOWN_PATHS)).result
        assertIs<AssessmentResult.Invalid>(result)
    }

    /** Verifies: REQ-IX-INV-004 (malformed reply -> Invalid, not "assume safe"). */
    @Test
    fun malformedReply_isInvalidNotSafe() = runTest {
        val client = StubClient { "Sure! It looks safe to install." }
        val result = assertIs<AdvisorOutcome.Ok>(advisor(client).assessRequest(request, KNOWN_PATHS)).result
        assertIs<AssessmentResult.Invalid>(result)
    }

    /** Verifies: REQ-IX-INV-004 (truncated reply -> never valid). */
    @Test
    fun truncatedReply_isNeverValid() = runTest {
        val client = StubClient { """{"install_success_likelihood":"HIGH","security_ris""" }
        val result = assertIs<AdvisorOutcome.Ok>(advisor(client).assessRequest(request, KNOWN_PATHS)).result
        assertTrue(
            result is AssessmentResult.Invalid || result is AssessmentResult.Missing,
            "a truncated body must never parse as valid, got $result",
        )
    }

    /** Verifies: REQ-IX-INV-004 (no automatic retry). */
    @Test
    fun failure_isNotRetried() = runTest {
        val client = StubClient { throw AiAdvisorFailure.Timeout("7s") }
        advisor(client).assessRequest(request, KNOWN_PATHS)
        assertEquals(1, client.calls)
    }

    /** Verifies: REQ-IX-INV-004 (the parser is always applied, even to a plausible body). */
    @Test
    fun parserIsAlwaysApplied() = runTest {
        val alwaysInvalid = object : AiOutputValidator {
            override fun parse(
                rawResponse: String,
                modelId: String,
                promptVersion: String,
                knownEvidencePaths: Set<String>,
            ) = AssessmentResult.Invalid("forced")
        }
        val outcome = DefaultAiRiskAdvisor(StubClient { validResponse }, Gate(true), alwaysInvalid)
            .assessDetailed(request, KNOWN_PATHS)
        val result = assertIs<AdvisorOutcome.Ok>(outcome.result).result
        assertIs<AssessmentResult.Invalid>(result)
    }

    /** Verifies: REQ-IX-INV-001 (a favorable assessment carries only advisory axes). */
    @Test
    fun validAssessmentIsDataOnly() = runTest {
        val result = assertIs<AdvisorOutcome.Ok>(advisor(StubClient { validResponse }).assessRequest(request, KNOWN_PATHS)).result
        val assessment = assertIs<AssessmentResult.Valid>(result).assessment
        assertEquals(InstallSuccessLikelihood.HIGH, assessment.installSuccessLikelihood)
    }

    /**
     * Verifies: REQ-IX-QUAL-001 (the caller's transmitted set is authoritative).
     *
     * The same reply is valid against [KNOWN_PATHS] and invalid when the
     * caller reports a narrower set, so the advisor cannot be made to accept a
     * citation for a field the request never carried.
     */
    @Test
    fun citationOutsideCallerReportedPaths_isRejected() = runTest {
        val result = assertIs<AdvisorOutcome.Ok>(
            advisor(StubClient { validResponse })
                .assessRequest(request, setOf("evidence.version_code")),
        ).result
        assertIs<AssessmentResult.Invalid>(result)
    }

    /** Verifies: REQ-IX-SEC-004 (a gate that throws denies egress, it does not allow it). */
    @Test
    fun gateThatThrows_deniesEgressWithZeroDispatches() = runTest {
        val client = StubClient { validResponse }
        val throwingGate = object : AiNetworkGate {
            override suspend fun isEgressAllowed(): Boolean = throw IllegalStateException("settings unavailable")
        }
        val outcome = DefaultAiRiskAdvisor(client, throwingGate, StrictAiOutputValidator())
            .assessDetailed(request, KNOWN_PATHS)
        val failure = assertIs<AdvisorOutcome.Failure>(outcome.result).failure
        assertIs<AiAdvisorFailure.NetworkDisabled>(failure)
        assertEquals(0, outcome.providerDispatches)
        assertEquals(0, client.calls)
    }

    /** Verifies: REQ-IX-REL-001 (cancellation propagates instead of becoming a failure). */
    @Test
    fun cancellation_isNotSwallowed() = runTest {
        val client = StubClient { throw kotlinx.coroutines.CancellationException("stop") }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            advisor(client).assessDetailed(request, KNOWN_PATHS)
        }
    }
}
