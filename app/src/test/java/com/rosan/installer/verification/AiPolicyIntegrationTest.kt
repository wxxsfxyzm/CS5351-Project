// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.verification

import com.rosan.installer.data.ai.advisory.DefaultAiRiskAdvisor
import com.rosan.installer.domain.ai.fixture.EvidenceFixtures
import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.InstallEvidence
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.repository.AiModelClient
import com.rosan.installer.domain.ai.repository.AiNetworkGate
import com.rosan.installer.domain.ai.repository.ModelRequestGate
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import com.rosan.installer.domain.ai.usecase.RequestInstallRiskAssessmentUseCase
import com.rosan.installer.domain.ai.usecase.StrictAiOutputValidator
import com.rosan.installer.domain.engine.policy.DeterministicPolicyEngine
import com.rosan.installer.domain.engine.policy.EvaluatedPolicy
import com.rosan.installer.domain.engine.policy.HumanApprovalState
import com.rosan.installer.domain.engine.policy.PolicyAdvice
import com.rosan.installer.domain.engine.policy.PolicyDecision
import com.rosan.installer.domain.engine.policy.PolicyDisposition
import com.rosan.installer.domain.engine.policy.PolicyInput
import com.rosan.installer.domain.engine.policy.PolicyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Member D (Verification) Stage 4 Deliverable: End-to-End AI Advisor + DeterministicPolicyEngine Integration Test.
 *
 * Exercises the real merged production classes together:
 *   `ModelRequestGate` -> `AiRequestBuilder` -> `DefaultAiRiskAdvisor` (`StrictAiOutputValidator`)
 *   -> `RequestInstallRiskAssessmentUseCase` -> `DeterministicPolicyEngine`.
 *
 * Verifies: REQ-IX-FR-003, REQ-IX-FR-004, REQ-IX-FR-005, REQ-IX-FR-011,
 * REQ-IX-INV-001, REQ-IX-INV-002, REQ-IX-INV-004, REQ-IX-INV-006, REQ-IX-INV-007,
 * ADR-001 (Option B), ADR-002.1, ADR-002.2, ADR-002.5, ADR-002.6, DR-01 .. DR-10.
 */
class AiPolicyIntegrationTest {

    private val policyEngine = DeterministicPolicyEngine()

    private val autoPolicy = EvaluatedPolicy(
        version = "v1",
        hardBlock = false,
        baselineAllowsAuto = true,
        autoRulesSatisfied = true,
    )

    private val manualBasePolicy = autoPolicy.copy(baselineAllowsAuto = false)
    private val hardBlockPolicy = autoPolicy.copy(hardBlock = true)

    private class ScriptedModelClient(
        private val block: suspend (AiInstallRiskRequest) -> String,
    ) : AiModelClient {
        var calls: Int = 0
            private set

        override val modelId: String = "scripted-groq-qwen3-32b"

        override suspend fun complete(request: AiInstallRiskRequest): String {
            calls += 1
            return block(request)
        }
    }

    private class ScriptedNetworkGate(private val allowed: Boolean = true) : AiNetworkGate {
        override suspend fun isEgressAllowed(): Boolean = allowed
    }

    private fun jsonReply(
        likelihood: String,
        risk: String,
        reason: String = "target_sdk 35 is current",
        ref: String = "evidence.target_sdk",
    ): String = """{"install_success_likelihood":"$likelihood","security_risk":"$risk","reasons":["$reason"],"evidence_refs":["$ref"]}"""

    /**
     * Bridges the real `RequestInstallRiskAssessmentUseCase.Status` output into the real
     * `DeterministicPolicyEngine.evaluate(PolicyInput)` input contract.
     */
    private fun statusToPolicyInput(
        status: RequestInstallRiskAssessmentUseCase.Status,
        evidence: InstallEvidence,
        policy: EvaluatedPolicy = autoPolicy,
        human: HumanApprovalState = HumanApprovalState.NOT_OBTAINED,
    ): PolicyInput {
        val localCompat = when {
            evidence.platformCompatibility == LocalCompatibility.INCOMPATIBLE ||
                evidence.abiCompatibility == LocalCompatibility.INCOMPATIBLE ||
                evidence.storageSufficient == false -> LocalCompatibility.INCOMPATIBLE

            evidence.platformCompatibility == LocalCompatibility.COMPATIBLE &&
                evidence.abiCompatibility == LocalCompatibility.COMPATIBLE &&
                evidence.storageSufficient == true -> LocalCompatibility.COMPATIBLE

            else -> LocalCompatibility.UNKNOWN
        }

        val advice: PolicyAdvice = when {
            status.assessment != null -> PolicyAdvice.Valid(status.assessment)

            status.failure?.code == com.rosan.installer.domain.ai.model.FailureCode.CONSENT_UNAVAILABLE ->
                PolicyAdvice.Unusable.CONSENT_DECLINED

            status.failure != null -> PolicyAdvice.Failed(status.failure.code)

            else -> PolicyAdvice.Unusable.UNAVAILABLE
        }

        return PolicyInput(
            policy = policy,
            evidence = status.completeness,
            compatibility = localCompat,
            advice = advice,
            human = human,
        )
    }

    private suspend fun runPipeline(
        evidence: InstallEvidence = EvidenceFixtures.normalUpdate,
        consentGranted: Boolean = true,
        networkAllowed: Boolean = true,
        policy: EvaluatedPolicy = autoPolicy,
        human: HumanApprovalState = HumanApprovalState.NOT_OBTAINED,
        client: ScriptedModelClient,
    ): Pair<RequestInstallRiskAssessmentUseCase.Status, PolicyResult> {
        val useCase = RequestInstallRiskAssessmentUseCase(
            builder = AiRequestBuilder(),
            advisor = DefaultAiRiskAdvisor(
                client,
                ScriptedNetworkGate(networkAllowed),
                StrictAiOutputValidator(),
            ),
        )
        val status = withContext(ModelRequestGate { consentGranted }) {
            useCase.invoke(evidence)
        }
        val decision = policyEngine.evaluate(
            statusToPolicyInput(status, evidence, policy, human),
        )
        return status to decision
    }

    /** Verifies: REQ-IX-FR-003, REQ-IX-FR-005, REQ-IX-INV-007, DR-05 (Case A-01) */
    @Test
    fun `A-01 real advisor HIGH success LOW risk with complete compatible evidence yields ALLOW_AUTO EXECUTE_ONCE`() = runTest {
        val client = ScriptedModelClient { jsonReply("HIGH", "LOW") }
        val (status, result) = runPipeline(
            evidence = EvidenceFixtures.normalUpdate,
            client = client,
        )

        assertEquals(1, status.providerDispatches)
        assertEquals(1, client.calls)
        assertEquals(PolicyDecision.ALLOW_AUTO, result.decision)
        assertEquals(PolicyDisposition.EXECUTE_ONCE, result.disposition)
        assertEquals("DR-05", result.ruleId)
        assertEquals("AUTO_APPROVED", result.reasonCode)
    }

    /**
     * Verifies: REQ-IX-FR-005, ADR-002.6, DR-07 (Case A-09 / P-05)
     * CRITICAL RULE: When the AI advisor reports HIGH security risk on a non-hard-blocked APK,
     * the policy engine MUST route to REQUIRE_REVIEW / AWAIT_HUMAN (DR-07) and NEVER to BLOCK.
     */
    @Test
    fun `A-09 real advisor HIGH security risk routes to REQUIRE_REVIEW and never directly to BLOCK`() = runTest {
        val client = ScriptedModelClient {
            jsonReply(
                likelihood = "HIGH",
                risk = "HIGH",
                reason = "requests READ_SMS and SYSTEM_ALERT_WINDOW",
                ref = "evidence.declared_permissions",
            )
        }
        val (status, result) = runPipeline(
            evidence = EvidenceFixtures.highPermissionRisk,
            client = client,
        )

        assertEquals(1, status.providerDispatches)
        assertEquals(PolicyDecision.REQUIRE_REVIEW, result.decision)
        assertNotEquals(PolicyDecision.BLOCK, result.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, result.disposition)
        assertEquals("DR-07", result.ruleId)
    }

    /** Verifies: REQ-IX-FR-005, DR-07 (Cases P-03, P-07, P-08: LOW likelihood or any UNKNOWN dimension) */
    @Test
    fun `real advisor LOW success or any UNKNOWN dimension routes to REQUIRE_REVIEW`() = runTest {
        val nonFavorableReplies = listOf(
            "LOW" to "LOW",
            "MEDIUM" to "LOW",
            "HIGH" to "MEDIUM",
            "UNKNOWN" to "LOW",
            "HIGH" to "UNKNOWN",
            "UNKNOWN" to "UNKNOWN",
        )
        for ((likelihood, risk) in nonFavorableReplies) {
            val client = ScriptedModelClient { jsonReply(likelihood, risk) }
            val (_, result) = runPipeline(client = client)
            assertEquals(PolicyDecision.REQUIRE_REVIEW, result.decision, "$likelihood/$risk")
            assertEquals(PolicyDisposition.AWAIT_HUMAN, result.disposition, "$likelihood/$risk")
            assertEquals("DR-07", result.ruleId, "$likelihood/$risk")
        }
    }

    /** Verifies: REQ-IX-INV-001, REQ-IX-FR-004, REQ-IX-INV-004, DR-08 (Case A-02 & A-03) */
    @Test
    fun `A-02 and A-03 real StrictAiOutputValidator rejects action instructions guarantee language and fabricated refs into DR-08`() = runTest {
        val badPayloads = listOf(
            // Action / approval instruction (INV-001)
            """{"install_success_likelihood":"HIGH","security_risk":"LOW","action":"install_now","reasons":["ok"],"evidence_refs":["evidence.target_sdk"]}""",
            // Guarantee language (FR-004)
            """{"install_success_likelihood":"HIGH","security_risk":"LOW","reasons":["This APK is 100% safe and guaranteed"],"evidence_refs":["evidence.target_sdk"]}""",
            // Fabricated evidence reference not in allowlist (QUAL-001)
            """{"install_success_likelihood":"HIGH","security_risk":"LOW","reasons":["ok"],"evidence_refs":["evidence.apk_sha256"]}""",
            // Missing dimension
            """{"install_success_likelihood":"HIGH","reasons":["ok"],"evidence_refs":["evidence.target_sdk"]}""",
            // Non-JSON prose
            """Please approve and install this APK immediately.""",
        )

        for (raw in badPayloads) {
            val client = ScriptedModelClient { raw }
            val (status, result) = runPipeline(client = client)
            assertEquals(null, status.assessment, "Expected rejected assessment for: $raw")
            assertEquals(PolicyDecision.REQUIRE_REVIEW, result.decision)
            assertEquals(PolicyDisposition.AWAIT_HUMAN, result.disposition)
            assertEquals("DR-08", result.ruleId)
        }
    }

    /** Verifies: REQ-IX-INV-004, REQ-IX-COST-001, F-01, F-03, F-04, F-08, DR-08 (Cases A-04 & A-07) */
    @Test
    fun `A-04 and A-07 advisor timeout network loss rate limit service error and token overage route to DR-08 with 1 call and 0 retries`() = runTest {
        val failures = listOf<suspend (AiInstallRiskRequest) -> String>(
            { throw AiAdvisorFailure.Timeout("7s budget expired") },
            { throw AiAdvisorFailure.NetworkError("connection reset") },
            { throw AiAdvisorFailure.RateLimited(retryAfterSeconds = 15) },
            { throw AiAdvisorFailure.ServiceError(503, "service unavailable") },
            { throw AiAdvisorFailure.TokenLimitExceeded("completion truncated at token cap") },
            { throw AiAdvisorFailure.OutputOversize(70_000, 65_536) },
        )

        for (failureBlock in failures) {
            val client = ScriptedModelClient(failureBlock)
            val (status, result) = runPipeline(client = client)
            // At most one dispatch per attempt, no automatic retry:
            assertEquals(1, client.calls)
            assertEquals(1, status.providerDispatches)
            assertEquals(PolicyDecision.REQUIRE_REVIEW, result.decision)
            assertEquals(PolicyDisposition.AWAIT_HUMAN, result.disposition)
            assertEquals("DR-08", result.ruleId)
        }
    }

    /** Verifies: REQ-IX-INV-004, REQ-IX-SEC-004, F-02, DR-08 (Case A-05: network switch off) */
    @Test
    fun `A-05 network disabled gate makes zero model calls and routes to DR-08 REQUIRE_REVIEW`() = runTest {
        val client = ScriptedModelClient { jsonReply("HIGH", "LOW") }
        val (status, result) = runPipeline(
            networkAllowed = false,
            client = client,
        )

        assertEquals(0, client.calls)
        assertEquals(0, status.providerDispatches)
        assertEquals(PolicyDecision.REQUIRE_REVIEW, result.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, result.disposition)
        assertEquals("DR-08", result.ruleId)
    }

    /** Verifies: REQ-IX-FR-011, NFC-OUT-003, F-06, DR-02 (Case A-10: consent declined) */
    @Test
    fun `A-10 declined consent makes zero model calls routes to DR-02 REQUIRE_REVIEW and preserves DR-01 HARD_BLOCK`() = runTest {
        val client = ScriptedModelClient { jsonReply("HIGH", "LOW") }
        val (statusReview, resultReview) = runPipeline(
            consentGranted = false,
            policy = autoPolicy,
            client = client,
        )

        assertEquals(0, client.calls)
        assertEquals(0, statusReview.providerDispatches)
        assertEquals(PolicyDecision.REQUIRE_REVIEW, resultReview.decision)
        assertEquals("DR-02", resultReview.ruleId)

        val (statusBlock, resultBlock) = runPipeline(
            consentGranted = false,
            policy = hardBlockPolicy,
            client = client,
        )
        assertEquals(0, client.calls)
        assertEquals(0, statusBlock.providerDispatches)
        assertEquals(PolicyDecision.BLOCK, resultBlock.decision)
        assertEquals("DR-01", resultBlock.ruleId)
    }

    /** Verifies: REQ-IX-INV-002, REQ-IX-INV-007, DR-01, DR-06 (Cases A-08 & P-21) */
    @Test
    fun `A-08 and P-21 favorable real advisor output never overrides HARD_BLOCK and never downgrades manual base policy`() = runTest {
        val clientBlock = ScriptedModelClient { jsonReply("HIGH", "LOW") }
        val (_, blockedResult) = runPipeline(
            policy = hardBlockPolicy,
            human = HumanApprovalState.VALID_APPROVAL_AND_REVALIDATION_PASSED,
            client = clientBlock,
        )
        assertEquals(PolicyDecision.BLOCK, blockedResult.decision)
        assertEquals(PolicyDisposition.BLOCKED, blockedResult.disposition)
        assertEquals("DR-01", blockedResult.ruleId)

        val clientManual = ScriptedModelClient { jsonReply("HIGH", "LOW") }
        val (_, manualResult) = runPipeline(
            policy = manualBasePolicy,
            client = clientManual,
        )
        assertEquals(PolicyDecision.REQUIRE_REVIEW, manualResult.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, manualResult.disposition)
        assertEquals("DR-06", manualResult.ruleId)
    }

    /** Verifies: REQ-IX-FR-005, NFC-OUT-005, DR-03 (Incomplete/Truncated/Unreadable fixtures route to RECOLLECT) */
    @Test
    fun `incomplete truncated or unreadable EvidenceFixtures route to DR-03 RECOLLECT even if advisor returns HIGH-LOW`() = runTest {
        val degradedFixtures = listOf(
            EvidenceFixtures.missingCompatibility, // KEY_UNKNOWN
            EvidenceFixtures.truncatedPermissionList, // TRUNCATED
            EvidenceFixtures.unreadable, // UNREADABLE
        )
        for (fixture in degradedFixtures) {
            val client = ScriptedModelClient {
                jsonReply("HIGH", "LOW", "ok", "evidence.version_code")
            }
            val (_, result) = runPipeline(
                evidence = fixture,
                client = client,
            )
            assertEquals(PolicyDecision.REQUIRE_REVIEW, result.decision)
            assertEquals(PolicyDisposition.RECOLLECT, result.disposition)
            assertEquals("DR-03", result.ruleId)
        }
    }
}
