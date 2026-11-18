// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.live

import com.rosan.installer.data.ai.provider.GroqAiModelClient
import com.rosan.installer.data.ai.provider.OkHttpAiTransport
import com.rosan.installer.domain.ai.fixture.EvidenceFixtures
import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.repository.AiNetworkGate
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import com.rosan.installer.domain.ai.usecase.DefaultAiRiskAdvisor
import com.rosan.installer.domain.ai.usecase.RequestInstallRiskAssessmentUseCase
import com.rosan.installer.domain.ai.usecase.StrictAiOutputValidator
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Live model test over the fixed evidence sets in [EvidenceFixtures].
 *
 * **Not** part of the deterministic offline suite
 * (`verification-constraints.md` §4): it calls the real Groq API, so this
 * class lives outside the tested packages and only runs when asked.
 *
 *   GROQ_API_KEY=gsk_... ./gradlew :app:testUnstableDebugUnitTest \
 *       --tests "com.rosan.installer.live.*"
 *
 * The test returns early when `GROQ_API_KEY` is unset, so a checkout without
 * a key stays green.
 *
 * What it asserts is deliberately narrow, because a live model's *judgement*
 * is not stable and must not be asserted:
 *
 * - every scenario produces either a fully-formed assessment or an explicit
 *   failure status — never a crash and never a partial object;
 * - each scenario costs at most one dispatch;
 * - if the model did answer usefully, its citations are real;
 * - a live transport failure is reported as a classified failure, not a
 *   thrown exception or a favorable default.
 *
 * Verifies: REQ-IX-FR-003, REQ-IX-FR-004, REQ-IX-INV-004, REQ-IX-MOD-001
 */
class GroqLiveAdvisorTest {

    private val apiKey: String? = System.getenv("GROQ_API_KEY")

    private fun gate() = object : AiNetworkGate {
        override suspend fun isEgressAllowed() = true
    }

    @Test
    fun liveGroqCall_overEveryFixture_returnsFixedFormatOrExplicitFailure() {
        if (apiKey.isNullOrBlank()) {
            println("[live] GROQ_API_KEY not set; skipping live model test.")
            return
        }

        val client = GroqAiModelClient(
            transport = OkHttpAiTransport(
                apiKeyProvider = { apiKey },
                egressAllowed = { true },
            ),
        )
        val useCase = RequestInstallRiskAssessmentUseCase(
            builder = AiRequestBuilder(),
            advisor = DefaultAiRiskAdvisor(
                modelClient = client,
                networkGate = gate(),
                outputValidator = StrictAiOutputValidator(),
            ),
        )

        val successes = mutableListOf<String>()
        val failures = mutableListOf<String>()

        for (scenario in EvidenceFixtures.all) {
            val status = runBlocking { useCase.invoke(scenario.evidence) }
            println(
                "[live] ${scenario.name}: usable=${status.isUsable} " +
                    "dispatches=${status.providerDispatches} " +
                    "failure=${status.failure?.code} detail=${status.failure?.detail} " +
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
            // A provider-side failure is a legitimate outcome of a live run;
            // the invariant is that it is *reported*, never swallowed.
            status.failure?.let { failure ->
                assertTrue(
                    failure.code in FailureCode.entries,
                    "${scenario.name}: failure must carry a catalogued code",
                )
                assertTrue(failure.detail.isNotBlank(), "${scenario.name}: failure needs a detail")
            }

            val assessment = status.assessment?.assessment
            if (assessment != null) {
                // The response is well formed: confirm the few things the
                // parser cannot check for itself.
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
                successes += scenario.name
            } else {
                failures += "${scenario.name} -> ${status.failure?.code}"
            }
        }

        println("[live] well-formed: ${successes.size}/${EvidenceFixtures.all.size} -> $successes")
        println("[live] explicit failures: ${failures.size} -> $failures")

        // Not a quality assertion: if the key is bad, the model is
        // unreachable, or every reply is unparseable, the run told us so.
        assertTrue(
            successes.isNotEmpty() || failures.isNotEmpty(),
            "every scenario must be classified one way or the other",
        )
    }

    /** Skips unless a key is present, then checks one raw call end to end. */
    @Test
    fun liveGroqCall_rawAdvisorPathReturnsParsedResult() {
        if (apiKey.isNullOrBlank()) {
            println("[live] GROQ_API_KEY not set; skipping live model test.")
            return
        }

        val client = GroqAiModelClient(
            transport = OkHttpAiTransport(apiKeyProvider = { apiKey }, egressAllowed = { true }),
        )
        val advisor = DefaultAiRiskAdvisor(client, gate(), StrictAiOutputValidator())
        val structured = AiRequestBuilder().build(EvidenceFixtures.normalUpdate)
        val outcome = runBlocking { advisor.assessDetailed(structured.request) }

        println("[live] dispatches=${outcome.providerDispatches} result=${outcome.result}")
        assertTrue(outcome.providerDispatches == 1, "expected exactly one provider dispatch")
        assertTrue(
            outcome.result is AdvisorOutcome.Ok,
            "a dispatched request must yield a parsed result or a failure, got ${outcome.result}",
        )
        val parsed = outcome.result.result
        println("[live] parsed=${parsed::class.simpleName}")
        assertTrue(
            parsed is AssessmentResult.Valid ||
                parsed is AssessmentResult.Invalid ||
                parsed is AssessmentResult.Missing,
        )
    }
}
