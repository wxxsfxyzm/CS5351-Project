// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.live

import com.rosan.installer.data.ai.advisory.DefaultAiRiskAdvisor
import com.rosan.installer.data.ai.provider.AiEndpointConfig
import com.rosan.installer.data.ai.provider.AiEndpointConfigSource
import com.rosan.installer.data.ai.provider.OpenAiCompatibleModelClient
import com.rosan.installer.data.ai.provider.OpenAiCompatibleTransport
import com.rosan.installer.domain.ai.fixture.EvidenceFixtures
import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.code
import com.rosan.installer.domain.ai.repository.AiNetworkGate
import com.rosan.installer.domain.ai.repository.ModelRequestGate
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import com.rosan.installer.domain.ai.usecase.RequestInstallRiskAssessmentUseCase
import com.rosan.installer.domain.ai.usecase.StrictAiOutputValidator
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Live model test over the fixed evidence sets in [EvidenceFixtures] against
 * the CS5351 course service (ADR-002.2).
 *
 * **Not** part of the deterministic offline suite
 * (`verification-constraints.md` §4): it calls the real model endpoint, so
 * this class lives outside the tested packages and only runs when asked.
 *
 *   AGENT_BASE_URL="https://llm.cs.cityu.edu.hk/v1" \
 *   AGENT_API_KEY=<key> \
 *   AGENT_DEPLOYMENT_MODEL="CS5351/DeepSeek-V4-Flash" \
 *     ./gradlew :app:testUnstableDebugUnitTest --tests "com.rosan.installer.live.*"
 *
 * The test returns early when any of the three variables is unset, so a
 * checkout without credentials stays green.
 *
 * **Test-only transport.** Android local unit tests run on the JVM against
 * `android.jar` stubs, where OkHttp 5 fails on `android.util.Log.isLoggable`
 * ("Method ... not mocked"), so production [com.rosan.installer.data.ai.provider.OkHttpAiTransport]
 * cannot run here. This test therefore supplies a thin [OpenAiCompatibleTransport]
 * backed by `java.net.http.HttpClient` doing only I/O (POST, Authorization
 * header, status/size mapping). The real [OpenAiCompatibleModelClient],
 * [StrictAiOutputValidator], and [DefaultAiRiskAdvisor] are used unchanged,
 * so the model/prompt/validation path is exercised end to end. The OkHttp
 * transport itself is verified on device, not here (see the UI demo checklist
 * in the plan); no production code is changed for this test.
 *
 * What it asserts is narrow, because a live model's *judgement* is not stable:
 *
 * - each scenario costs at most one dispatch;
 * - no scenario fails with a transport classification
 *   ([FailureCode.NETWORK_ERROR], [FailureCode.TIMEOUT],
 *   [FailureCode.SERVICE_ERROR]) — those mean the path is broken/vacuous, not
 *   that the model declined;
 * - at least 8 of the 10 fixtures return a usable assessment;
 * - every usable answer's citations are real (allowlisted and transmitted).
 *
 * Verifies: REQ-IX-FR-003, REQ-IX-FR-004, REQ-IX-INV-004, REQ-IX-MOD-001, VER-001
 */
class CourseServiceLiveAdvisorTest {

    private val baseUrl: String? = System.getenv("AGENT_BASE_URL")
    private val apiKey: String? = System.getenv("AGENT_API_KEY")
    private val deploymentModel: String? = System.getenv("AGENT_DEPLOYMENT_MODEL")

    private val configured: Boolean
        get() = !baseUrl.isNullOrBlank() && !apiKey.isNullOrBlank() && !deploymentModel.isNullOrBlank()

    private fun gate() = object : AiNetworkGate {
        override suspend fun isEgressAllowed() = true
    }

    private fun client(): OpenAiCompatibleModelClient {
        val endpoint = baseUrl!!
        val model = deploymentModel!!
        return OpenAiCompatibleModelClient(
            transport = JavaHttpTransport(apiKey = apiKey!!),
            configSource = AiEndpointConfigSource {
                AiEndpointConfig(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    modelId = model,
                    promptVersion = AiRequestBuilder.PROMPT_VERSION,
                )
            },
        )
    }

    /**
     * Prompt v1.3 (lead decision 2026-11-23): an update that adds no
     * permission, or a system-app update with a matching signature, must not
     * be rated HIGH security risk only for permissions it already holds.
     */
    @Test
    fun liveCourseCall_updateRelativeScenarios_areNotHighRisk() {
        if (!configured) {
            println("[live] AGENT_BASE_URL/AGENT_API_KEY/AGENT_DEPLOYMENT_MODEL not all set; skipping.")
            return
        }
        val useCase = RequestInstallRiskAssessmentUseCase(
            builder = AiRequestBuilder(),
            advisor = DefaultAiRiskAdvisor(
                modelClient = client(),
                networkGate = gate(),
                outputValidator = StrictAiOutputValidator(),
            ),
        )
        for (scenario in EvidenceFixtures.updateRelative) {
            val status = runBlocking(ModelRequestGate { true }) { useCase.invoke(scenario.evidence) }
            val assessment = status.assessment?.assessment
            println(
                "[live] ${scenario.name}: usable=${status.isUsable} failure=${status.failure?.code} " +
                    "likelihood=${assessment?.installSuccessLikelihood} risk=${assessment?.securityRisk} " +
                    "reasons=${assessment?.reasons}",
            )
            assertTrue(status.isUsable, "${scenario.name}: expected a usable assessment, got ${status.failure}")
            assertTrue(
                assessment?.securityRisk != com.rosan.installer.domain.ai.model.SecurityRisk.HIGH,
                "${scenario.name}: rated HIGH although the update adds no permission",
            )
        }
    }

    @Test
    fun liveCourseCall_overEveryFixture_returnsFixedFormatOrExplicitFailure() {
        if (!configured) {
            println("[live] AGENT_BASE_URL/AGENT_API_KEY/AGENT_DEPLOYMENT_MODEL not all set; skipping.")
            return
        }

        val useCase = RequestInstallRiskAssessmentUseCase(
            builder = AiRequestBuilder(),
            advisor = DefaultAiRiskAdvisor(
                modelClient = client(),
                networkGate = gate(),
                outputValidator = StrictAiOutputValidator(),
            ),
        )

        val usable = mutableListOf<String>()
        val nonUsable = mutableListOf<String>()
        val transportFailures = mutableListOf<String>()
        val total = EvidenceFixtures.all.size

        for (scenario in EvidenceFixtures.all) {
            val status = runBlocking(ModelRequestGate { true }) { useCase.invoke(scenario.evidence) }
            val failureCode = status.failure?.code
            println(
                "[live] ${scenario.name}: usable=${status.isUsable} " +
                    "dispatches=${status.providerDispatches} " +
                    "failure=$failureCode detail=${status.failure?.detail} " +
                    "assessment=${status.assessment?.assessment}",
            )

            assertTrue(
                status.providerDispatches <= 1,
                "${scenario.name}: no retry is allowed, got ${status.providerDispatches} dispatches",
            )
            assertTrue(
                status.isUsable != (status.failure != null),
                "${scenario.name}: exactly one of assessment/failure must be set",
            )

            if (failureCode in TRANSPORT_FAILURES) {
                // A broken path (bad key, unreachable host, stubbed HTTP) makes
                // the run vacuous; fail loudly instead of reporting a pass.
                transportFailures += "${scenario.name} -> $failureCode (${status.failure?.detail})"
            }

            status.failure?.let { failure ->
                assertTrue(
                    failure.code in FailureCode.entries,
                    "${scenario.name}: failure must carry a catalogued code",
                )
                assertTrue(failure.detail.isNotBlank(), "${scenario.name}: failure needs a detail")
            }

            val assessment = status.assessment?.assessment
            if (assessment != null) {
                assertTrue(assessment.reasons.isNotEmpty(), "${scenario.name}: empty reasons")
                assertTrue(assessment.evidenceRefs.isNotEmpty(), "${scenario.name}: empty refs")
                assertTrue(
                    AiRequestBuilder.ALLOWLISTED_LEAF_PATHS.containsAll(assessment.evidenceRefs),
                    "${scenario.name}: cited a non-allowlisted path",
                )
                assertTrue(
                    status.transmittedPaths.containsAll(assessment.evidenceRefs),
                    "${scenario.name}: cited a path this scenario never sent",
                )
                usable += scenario.name
            } else {
                nonUsable += "${scenario.name} -> $failureCode"
            }
        }

        println("[live] usable: ${usable.size}/$total -> $usable")
        println("[live] non-usable: ${nonUsable.size} -> $nonUsable")

        assertTrue(
            transportFailures.isEmpty(),
            "the model path is broken (vacuous run); transport failures: $transportFailures",
        )
        assertTrue(
            usable.size >= MIN_USABLE,
            "expected at least $MIN_USABLE/$total usable assessments, got ${usable.size}: $nonUsable",
        )
    }

    /** Skips unless configured, then checks one raw call end to end. */
    @Test
    fun liveCourseCall_rawAdvisorPathReturnsParsedResult() {
        if (!configured) {
            println("[live] AGENT_BASE_URL/AGENT_API_KEY/AGENT_DEPLOYMENT_MODEL not all set; skipping.")
            return
        }

        val advisor = DefaultAiRiskAdvisor(client(), gate(), StrictAiOutputValidator())
        val prepared = AiRequestBuilder().build(EvidenceFixtures.normalUpdate)
        val result = runBlocking(ModelRequestGate { true }) {
            advisor.assessRequest(prepared.request, prepared.transmittedPaths)
        }

        println("[live] single-call result=$result")
        assertTrue(result is AdvisorOutcome.Ok || result is AdvisorOutcome.Failure, "unexpected $result")
        if (result is AdvisorOutcome.Failure) {
            assertTrue(
                result.failure.code !in TRANSPORT_FAILURES,
                "transport failure on the raw path: ${result.failure.code} (${result.failure.message})",
            )
        }
        val parsed = (result as? AdvisorOutcome.Ok)?.result
        if (parsed != null) {
            println("[live] single-call parsed=${parsed::class.simpleName}")
            assertTrue(
                parsed is AssessmentResult.Valid ||
                    parsed is AssessmentResult.Invalid ||
                    parsed is AssessmentResult.Missing,
            )
        }
    }

    /**
     * Test-only I/O adapter over `java.net.http.HttpClient`. It performs the
     * exchange the OkHttp transport would, mapping failures to the same
     * [AiAdvisorFailure] types and enforcing the same response-size cap, so
     * only the HTTP stack differs from production.
     */
    private class JavaHttpTransport(
        private val apiKey: String,
        private val timeoutSeconds: Long = 30,
        private val maxResponseBytes: Int = 64 * 1024,
    ) : OpenAiCompatibleTransport {

        private val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        override suspend fun post(baseUrl: String, path: String, body: String): String = withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/$path"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = try {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (_: java.net.http.HttpTimeoutException) {
                throw AiAdvisorFailure.Timeout("request timed out")
            } catch (io: IOException) {
                throw AiAdvisorFailure.NetworkError(io.message ?: "network error")
            } catch (io: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AiAdvisorFailure.NetworkError("interrupted")
            }

            when {
                response.statusCode() == 429 -> throw AiAdvisorFailure.RateLimited(null)

                response.statusCode() == 413 ->
                    throw AiAdvisorFailure.TokenLimitExceeded("request rejected as too large (HTTP 413)")

                response.statusCode() !in 200..299 -> {
                    val errorBody = response.body()
                    if (response.statusCode() == 400 && isTokenLimitError(errorBody)) {
                        throw AiAdvisorFailure.TokenLimitExceeded(
                            "provider rejected the request for exceeding a token limit (HTTP 400)",
                        )
                    }
                    throw AiAdvisorFailure.ServiceError(response.statusCode(), "HTTP ${response.statusCode()}")
                }

                response.body().size > maxResponseBytes ->
                    throw AiAdvisorFailure.OutputOversize(response.body().size, maxResponseBytes)

                else -> response.body().decodeToString()
            }
        }

        private fun isTokenLimitError(body: ByteArray): Boolean = runCatching {
            val root = json.parseToJsonElement(body.decodeToString()) as? kotlinx.serialization.json.JsonObject
                ?: return false
            val error = root["error"] as? kotlinx.serialization.json.JsonObject ?: return false
            listOf("code", "type").any { key ->
                val value = (error[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.lowercase()
                    ?: return@any false
                TOKEN_LIMIT_MARKERS.any { value.contains(it) }
            }
        }.getOrDefault(false)
    }

    companion object {
        /** At least this many fixtures must yield a usable assessment. */
        private const val MIN_USABLE = 8

        /** Failure codes that mean the path itself is broken, not the model. */
        private val TRANSPORT_FAILURES = setOf(
            FailureCode.NETWORK_ERROR,
            FailureCode.TIMEOUT,
            FailureCode.SERVICE_ERROR,
        )

        private val TOKEN_LIMIT_MARKERS = listOf(
            "context_length",
            "too_large",
            "too_many_tokens",
            "max_tokens",
            "token_limit",
            "request_too_large",
            "string_above_max_length",
        )
    }
}
