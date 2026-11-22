// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.data.ai.advisory.DefaultAiRiskAdvisor
import com.rosan.installer.data.ai.provider.AiEndpointConfig
import com.rosan.installer.data.ai.provider.AiEndpointConfigSource
import com.rosan.installer.data.ai.provider.OkHttpAiTransport
import com.rosan.installer.data.ai.provider.OpenAiCompatibleModelClient
import com.rosan.installer.domain.ai.fixture.EvidenceFixtures
import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.code
import com.rosan.installer.domain.ai.repository.AIRiskAdvisor
import com.rosan.installer.domain.ai.repository.AiNetworkGate
import com.rosan.installer.domain.ai.repository.ModelRequestGate
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Verifies: REQ-IX-FR-003/009/011/012, REQ-IX-SEC-004, REQ-IX-INV-004, DR-02/08. */
class ModelRequestGateTest {
    private val evidence = EvidenceFixtures.normalUpdate

    @Test
    fun entryWithoutCurrentConsentDoesNotBuildOrCallAdvisor() = runTest {
        var advisorCalls = 0
        val advisor = object : AIRiskAdvisor {
            override suspend fun assessRequest(
                request: AiInstallRiskRequest,
                knownEvidencePaths: Set<String>,
            ): AdvisorOutcome {
                advisorCalls++
                error("advisor must not run")
            }
        }
        val withoutReadablePermissions = evidence.copy(
            declaredPermissions = object : AbstractList<String>() {
                override val size: Int get() = error("request must not read permissions")
                override fun get(index: Int): String = error("request must not read permissions")
            },
        )
        val useCase = RequestInstallRiskAssessmentUseCase(AiRequestBuilder(), advisor)
        val missing = useCase.invoke(withoutReadablePermissions)
        val denied = withContext(ModelRequestGate { false }) { useCase.invoke(withoutReadablePermissions) }
        val unreadable = withContext(ModelRequestGate { error("private consent details") }) {
            useCase.invoke(withoutReadablePermissions)
        }

        for (status in listOf(missing, denied, unreadable)) {
            assertBlocked(status, FailureCode.CONSENT_UNAVAILABLE)
            assertEquals("model request consent is unavailable", status.failure?.detail)
            assertEquals(evidence.completeness(), status.completeness)
        }
        assertEquals(0, advisorCalls)
    }

    @Test
    fun directTransportCannotDispatchWithoutCurrentConsent() = runTest {
        val http = HttpSpy()
        val transport = http.transport()
        suspend fun assertDenied() {
            val failure = assertFailsWith<AiAdvisorFailure> { transport.post("https://model.invalid", "chat/completions", "{}") }
            assertEquals(FailureCode.CONSENT_UNAVAILABLE, failure.code)
            assertEquals("model request consent is unavailable", failure.message)
            // Coroutine stack recovery may wrap a failure in another copy.
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.all { it.message == failure.message })
            assertEquals(0, http.dispatches.get())
        }
        assertDenied()
        withContext(ModelRequestGate { null }) { assertDenied() }
        withContext(ModelRequestGate { false }) { assertDenied() }
        withContext(ModelRequestGate { error("private consent details") }) { assertDenied() }
    }

    @Test
    fun rechecksConsentAfterEntryAndBlocksWithdrawalBeforeHttp() = runTest {
        val http = HttpSpy()
        var consent = true
        val transport = http.transport(keyProvider = {
            consent = false
            "offline-test-key"
        })
        val status = withContext(ModelRequestGate { consent }) { useCase(transport).invoke(evidence) }
        assertBlocked(status, FailureCode.CONSENT_UNAVAILABLE)
        assertEquals(0, http.dispatches.get())
    }

    @Test
    fun transportNetworkOffAndUnavailableKeysAreZeroDispatchPreflightFailures() = runTest {
        val http = HttpSpy()
        withContext(ModelRequestGate { true }) {
            val networkOff = http.transport(networkAllowed = false, keyProvider = { error("must not read key") })
            assertBlocked(useCase(networkOff).invoke(evidence), FailureCode.NETWORK_DISABLED)

            val unavailableKeys = listOf<() -> String?>(
                { null },
                { "  \t" },
                { "private-key\ninvalid" },
                { error("private key source details") },
            )
            for (keyProvider in unavailableKeys) {
                val status = useCase(http.transport(keyProvider = keyProvider)).invoke(evidence)
                assertBlocked(status, FailureCode.NETWORK_ERROR)
                assertEquals("model API key is unavailable", status.failure?.detail)
            }
        }
        assertEquals(0, http.dispatches.get())
    }

    @Test
    fun allPreconditionsDispatchOnceAndPreserveProviderFailure() = runTest {
        val http = HttpSpy()
        val status = withContext(ModelRequestGate { true }) { useCase(http.transport()).invoke(evidence) }
        assertEquals(1, http.dispatches.get())
        assertEquals(1, status.providerDispatches)
        assertEquals(FailureCode.SERVICE_ERROR, status.failure?.code)
        assertEquals("HTTP 503", status.failure?.detail)
        assertNull(status.assessment)
        assertTrue("evidence.target_sdk" in status.transmittedPaths)
    }

    @Test
    fun attemptedHttpWithIoFailureIsNotReportedAsPreflightRejection() = runTest {
        val http = HttpSpy(fail = true)
        val status = withContext(ModelRequestGate { true }) { useCase(http.transport()).invoke(evidence) }
        assertEquals(1, http.dispatches.get())
        assertEquals(1, status.providerDispatches)
        assertEquals(FailureCode.NETWORK_ERROR, status.failure?.code)
        assertTrue(status.transmittedPaths.isNotEmpty())
        assertNull(status.assessment)
    }

    @Test
    fun cancellationFromReadersPropagatesAtBothBoundaries() = runTest {
        val http = HttpSpy()
        val cancelled = CancellationException("cancelled")
        val transport = http.transport()
        val useCase = useCase(transport)
        withContext(ModelRequestGate { throw cancelled }) {
            assertSame(cancelled, assertFailsWith<CancellationException> { useCase.invoke(evidence) })
            assertFailsWith<CancellationException> { transport.post("https://model.invalid", "chat/completions", "{}") }
        }
        withContext(ModelRequestGate { true }) {
            val keyCancelled = http.transport(keyProvider = { throw cancelled })
            assertFailsWith<CancellationException> { useCase(keyCancelled).invoke(evidence) }
        }
        assertEquals(0, http.dispatches.get())
    }

    private fun assertBlocked(status: RequestInstallRiskAssessmentUseCase.Status, code: FailureCode) {
        assertEquals(code, status.failure?.code)
        assertNull(status.assessment)
        assertEquals(0, status.providerDispatches)
        assertTrue(status.transmittedPaths.isEmpty())
    }

    private fun useCase(transport: OkHttpAiTransport) = RequestInstallRiskAssessmentUseCase(
        AiRequestBuilder(),
        DefaultAiRiskAdvisor(
            OpenAiCompatibleModelClient(
                transport = transport,
                configSource = AiEndpointConfigSource {
                    AiEndpointConfig(
                        endpoint = "https://model.invalid",
                        apiKey = "offline-test-key",
                        modelId = AiRequestBuilder.DEFAULT_MODEL_ID,
                        promptVersion = AiRequestBuilder.PROMPT_VERSION,
                    )
                },
            ),
            object : AiNetworkGate {
                override suspend fun isEgressAllowed() = true
            },
            StrictAiOutputValidator(),
        ),
    )

    private class HttpSpy(fail: Boolean = false) {
        val dispatches = AtomicInteger()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            dispatches.incrementAndGet()
            if (fail) throw IOException("simulated I/O failure")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Service Unavailable")
                .body("".toResponseBody())
                .build()
        }.build()

        fun transport(
            networkAllowed: Boolean = true,
            keyProvider: suspend () -> String? = { "offline-test-key" },
        ) = OkHttpAiTransport(
            apiKeyProvider = keyProvider,
            egressAllowed = { networkAllowed },
            baseClient = client,
        )
    }
}
