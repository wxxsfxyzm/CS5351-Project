// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.data.ai.advisory.DefaultAiRiskAdvisor
import com.rosan.installer.data.ai.advisory.FixedResponseAdvisor
import com.rosan.installer.domain.ai.fixture.EvidenceFixtures
import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.InstallEvidence
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.ai.repository.AIRiskAdvisor
import com.rosan.installer.domain.ai.repository.AiModelClient
import com.rosan.installer.domain.ai.repository.AiNetworkGate
import com.rosan.installer.domain.ai.repository.ModelRequestGate
import com.rosan.installer.domain.engine.policy.DeterministicPolicyEngine
import com.rosan.installer.domain.engine.policy.EvaluatedPolicy
import com.rosan.installer.domain.engine.policy.HumanApprovalState
import com.rosan.installer.domain.engine.policy.PolicyAdvice
import com.rosan.installer.domain.engine.policy.PolicyInput
import com.rosan.installer.domain.engine.policy.PolicyResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Verifies: REQ-IX-MOD-001, REQ-IX-INV-001, REQ-IX-INV-004, REQ-IX-FR-003,
 *   REQ-IX-FR-005
 * Offline and deterministic: no network, no model call.
 *
 * Adapter contract test for REQ-IX-MOD-001. Its fit criterion is:
 *
 * > After replacing the online model with a fixed-response adapter, policy and
 * > approval tests pass without changing business rules.
 *
 * This class is the adapter-swap evidence for that criterion:
 *
 * 1. both advisors satisfy the same `AIRiskAdvisor` boundary and produce the
 *    same `Status` shape;
 * 2. swapping one for the other leaves the deterministic decision rules
 *    unchanged;
 * 3. no advisor implementation can express a decision or reach the installer
 *    backend (REQ-IX-INV-001).
 */
class AdapterContractTest {

    private val builder = AiRequestBuilder()
    private val engine = DeterministicPolicyEngine()

    private val policy = EvaluatedPolicy(
        version = "adapter-contract-v1",
        hardBlock = false,
        baselineAllowsAuto = true,
        autoRulesSatisfied = true,
    )

    /** A scripted online provider that answers with the fixed-format body. */
    private class ScriptedClient(private val reply: String) : AiModelClient {
        override val modelId = "adapter-contract-model"
        override suspend fun complete(request: AiInstallRiskRequest): String = reply
    }

    private class AllowAll : AiNetworkGate {
        override suspend fun isEgressAllowed() = true
    }

    /** The network-backed advisor, driven by a scripted provider. */
    private fun onlineAdvisor(likelihood: String = "HIGH", risk: String = "LOW"): AIRiskAdvisor = DefaultAiRiskAdvisor(
        modelClient = ScriptedClient(
            """{"install_success_likelihood":"$likelihood","security_risk":"$risk",""" +
                """"reasons":["concrete reason"],"evidence_refs":["evidence.target_sdk"]}""",
        ),
        networkGate = AllowAll(),
        outputValidator = StrictAiOutputValidator(),
    )

    private suspend fun statusOf(advisor: AIRiskAdvisor, evidence: InstallEvidence) = withContext(ModelRequestGate { true }) {
        RequestInstallRiskAssessmentUseCase(builder, advisor).invoke(evidence)
    }

    private fun plan(status: RequestInstallRiskAssessmentUseCase.Status): PolicyResult {
        val advice = status.assessment?.let { PolicyAdvice.Valid(it) }
            ?: PolicyAdvice.Failed(status.failure?.code ?: FailureCode.INVALID_OUTPUT)
        return engine.evaluate(
            PolicyInput(
                policy = policy,
                evidence = status.completeness,
                compatibility = LocalCompatibility.COMPATIBLE,
                advice = advice,
                human = HumanApprovalState.NOT_OBTAINED,
            ),
        )
    }

    /**
     * Verifies: REQ-IX-MOD-001 (both implementations fit the same boundary).
     * The assignment is the compile-time half of the contract test; the
     * runtime half is that each returns a well-formed [AdvisorOutcome].
     */
    @Test
    fun bothAdvisors_satisfyTheSameInterface() = runTest {
        val advisors: List<AIRiskAdvisor> = listOf(
            onlineAdvisor(),
            FixedResponseAdvisor.highSuccessLowRisk(),
        )
        val prepared = builder.build(EvidenceFixtures.normalUpdate)
        for (advisor in advisors) {
            val outcome = advisor.assessRequest(prepared.request, prepared.transmittedPaths)
            assertTrue(
                outcome is AdvisorOutcome.Ok || outcome is AdvisorOutcome.Failure,
                "${advisor::class.simpleName} returned an unexpected outcome: $outcome",
            )
        }
    }

    /**
     * Verifies: REQ-IX-MOD-001, REQ-IX-INV-004 (the `Status` shape is adapter
     * independent: exactly one of assessment/failure, at most one dispatch,
     * transmitted paths inside the allowlist).
     */
    @Test
    fun sameEvidenceSet_producesTheSameStatusShapeFromEitherAdapter() = runTest {
        for (scenario in EvidenceFixtures.all) {
            for (advisor in listOf(onlineAdvisor(), FixedResponseAdvisor.highSuccessLowRisk())) {
                val status = statusOf(advisor, scenario.evidence)
                val label = "${advisor::class.simpleName} / ${scenario.name}"

                assertTrue(
                    status.isUsable != (status.failure != null),
                    "$label: exactly one of assessment/failure must be set",
                )
                assertTrue(status.providerDispatches in 0..1, "$label: at most one dispatch")
                assertTrue(
                    AiRequestBuilder.ALLOWLISTED_LEAF_PATHS.containsAll(status.transmittedPaths),
                    "$label: transmitted a non-allowlisted path",
                )
                assertNotNull(status.completeness, "$label: completeness is mandatory")
            }
        }
    }

    /**
     * Verifies: REQ-IX-MOD-001, REQ-IX-FR-005 (swapping the adapter does not
     * change the deterministic rule). The same advisory value yields the same
     * `PolicyResult` whether it came from the online advisor or the fixed one.
     */
    @Test
    fun swappingTheAdapter_doesNotChangeTheDecisionRule() = runTest {
        val online = plan(statusOf(onlineAdvisor("HIGH", "LOW"), EvidenceFixtures.normalUpdate))
        val fixed = plan(statusOf(FixedResponseAdvisor.highSuccessLowRisk(), EvidenceFixtures.normalUpdate))

        assertEquals("DR-05", online.ruleId)
        assertEquals(online, fixed, "the same advisory value must produce the same decision")

        val onlineLow = plan(statusOf(onlineAdvisor("LOW", "LOW"), EvidenceFixtures.normalUpdate))
        val fixedLow = plan(statusOf(FixedResponseAdvisor.lowSuccess(), EvidenceFixtures.normalUpdate))
        assertEquals(onlineLow, fixedLow)
        assertTrue(onlineLow.ruleId == "DR-07", "an unfavorable advisory must not auto-approve")

        val onlineInvalid = plan(statusOf(onlineAdvisor("ABSOLUTELY", "LOW"), EvidenceFixtures.normalUpdate))
        val fixedInvalid = plan(statusOf(FixedResponseAdvisor.invalid(), EvidenceFixtures.normalUpdate))
        assertEquals(onlineInvalid, fixedInvalid)
        assertEquals("DR-08", fixedInvalid.ruleId)
    }

    /**
     * Verifies: REQ-IX-INV-004 (across both adapters, no failure shape is ever
     * read as a favorable assessment).
     */
    @Test
    fun noAdapter_failureIsEverFavorable() = runTest {
        val failing = listOf(
            DefaultAiRiskAdvisor(
                modelClient = ScriptedClient("not json at all"),
                networkGate = AllowAll(),
                outputValidator = StrictAiOutputValidator(),
            ),
            FixedResponseAdvisor.invalid(),
            FixedResponseAdvisor.missing(),
            FixedResponseAdvisor.unavailable(),
        )
        for (advisor in failing) {
            val status = statusOf(advisor, EvidenceFixtures.normalUpdate)
            assertFalse(status.isUsable, "${advisor::class.simpleName} produced a usable assessment from a failure")
            assertEquals("DR-08", plan(status).ruleId)
        }
    }

    /**
     * Verifies: REQ-IX-INV-001, REQ-IX-SEC-001 (static).
     *
     * Every advisor implementation and the advisor boundary are checked for
     * decision and installer-backend references. `AdvisorIsolationTest` covers
     * the whole package; this narrows the same rule to the adapter seam so an
     * adapter swap cannot quietly introduce a backend call.
     */
    @Test
    fun noAdapterExposesDecisionOrBackend() {
        val root = findMainSourceRoot()
        val seam = listOf(
            root.resolve("com/rosan/installer/data/ai/advisory"),
            root.resolve("com/rosan/installer/domain/ai/repository"),
            root.resolve("com/rosan/installer/domain/ai/model"),
        )
        val files = seam.flatMap { dir ->
            assertTrue(dir.isDirectory, "expected the adapter seam at $dir")
            dir.walkTopDown().filter { it.extension == "kt" }.toList()
        }
        assertTrue(files.size >= 5, "expected the adapter seam to be scanned, saw ${files.size}")

        val forbidden = listOf(
            "AppInstallerRepository",
            "InstallationGateway",
            "ActionHandler",
            "domain.privileged",
            "framework.privileged",
            "data.session",
            "data.engine",
        )
        val violations = files.flatMap { file ->
            file.readLines().withIndex().mapNotNull { (index, line) ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@mapNotNull null
                val hit = forbidden.firstOrNull { trimmed.contains(it) }
                hit?.let { "${file.name}:${index + 1}: $it" }
            }
        }
        assertTrue(violations.isEmpty(), "the adapter seam references a decision or backend type: $violations")
    }

    private fun findMainSourceRoot(): File {
        val workingDir = requireNotNull(System.getProperty("user.dir"))
        var dir: File? = File(workingDir).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("could not locate app/src/main/java from $workingDir")
    }
}
