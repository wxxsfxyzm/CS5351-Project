// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.advisory

import com.rosan.installer.domain.ai.fixture.EvidenceFixtures
import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.InstallEvidence
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.repository.AIRiskAdvisor
import com.rosan.installer.domain.ai.repository.ModelRequestGate
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import com.rosan.installer.domain.ai.usecase.RequestInstallRiskAssessmentUseCase
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Verifies: REQ-IX-FR-003, REQ-IX-FR-005, REQ-IX-INV-001, REQ-IX-INV-002,
 *   REQ-IX-INV-004, REQ-IX-INV-007, REQ-IX-MOD-001
 * Offline end-to-end check for the A/D integration seam:
 *
 *   fixed [AIRiskAdvisor] -> `RequestInstallRiskAssessmentUseCase.Status`
 *   -> `PolicyAdvice` -> `DeterministicPolicyEngine`
 *
 * Nothing here reaches the network, reads a setting, or supplies a real key,
 * so the A/D suites can run the full advisory-to-decision path offline.
 *
 * Consent is supplied per assessment through [ModelRequestGate], the same way
 * the production caller must (ADR-002.5).
 */
class FixedResponseAdvisorIntegrationTest {

    private val engine = DeterministicPolicyEngine()
    private val builder = AiRequestBuilder()

    private val policy = EvaluatedPolicy(
        version = "fixed-response-test",
        hardBlock = false,
        baselineAllowsAuto = true,
        autoRulesSatisfied = true,
    )

    /** Runs one assessment with current consent and one provider attempt. */
    private suspend fun statusOf(advisor: AIRiskAdvisor, evidence: InstallEvidence = EvidenceFixtures.normalUpdate) = withContext(ModelRequestGate { true }) {
        RequestInstallRiskAssessmentUseCase(builder, advisor).invoke(evidence)
    }

    /** Maps the advisory status onto the policy input the engine consumes. */
    private fun plan(status: RequestInstallRiskAssessmentUseCase.Status): PolicyResult {
        val advice = status.assessment
            ?.let { PolicyAdvice.Valid(it) }
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

    /** HIGH success + LOW risk, complete evidence: the one auto-approvable case. */
    @Test
    fun highSuccessLowRisk_reachesAutoApprovalOffline() = runTest {
        val status = statusOf(FixedResponseAdvisor.highSuccessLowRisk())

        assertTrue(status.isUsable)
        assertNull(status.failure)
        assertEquals(1, status.providerDispatches)
        assertEquals(EvidenceCompleteness.COMPLETE, status.completeness)
        assertEquals(
            PolicyResult(PolicyDecision.ALLOW_AUTO, PolicyDisposition.EXECUTE_ONCE, "DR-05", "AUTO_APPROVED"),
            plan(status),
        )
    }

    /**
     * REQ-IX-INV-002 / REQ-IX-INV-007: a usable but non-favorable advisory
     * cannot lower the deterministic restriction, and never auto-approves.
     */
    @Test
    fun lowSuccess_requiresReview() = runTest {
        val status = statusOf(FixedResponseAdvisor.lowSuccess())

        assertTrue(status.isUsable, "a LOW estimate is still a valid assessment")
        val result = plan(status)
        assertEquals(PolicyDecision.REQUIRE_REVIEW, result.decision)
        assertEquals("DR-07", result.ruleId)
    }

    @Test
    fun highRisk_requiresReview() = runTest {
        val status = statusOf(FixedResponseAdvisor.highRisk())

        assertTrue(status.isUsable)
        val result = plan(status)
        assertEquals(PolicyDecision.REQUIRE_REVIEW, result.decision)
        assertEquals("DR-07", result.ruleId)
    }

    /** UNKNOWN is reported, never read as LOW risk and never auto-approved. */
    @Test
    fun unassessable_requiresReviewAndStaysUnknown() = runTest {
        val status = statusOf(FixedResponseAdvisor.unassessable())

        assertTrue(status.isUsable)
        assertEquals(
            InstallSuccessLikelihood.UNKNOWN,
            status.assessment?.assessment?.installSuccessLikelihood,
        )
        assertEquals("DR-07", plan(status).ruleId)
    }

    /** REQ-IX-INV-004 negative test: INVALID never becomes a usable advice. */
    @Test
    fun invalid_reportsInvalidOutputAndRequiresReview() = runTest {
        val status = statusOf(FixedResponseAdvisor.invalid())

        assertNull(status.assessment, "INVALID must not produce an assessment")
        assertEquals(FailureCode.INVALID_OUTPUT, status.failure?.code)
        assertEquals(1, status.providerDispatches)
        assertEquals(
            PolicyResult(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.AWAIT_HUMAN, "DR-08", "ADVICE_UNUSABLE"),
            plan(status),
        )
    }

    @Test
    fun missing_requiresReview() = runTest {
        val status = statusOf(FixedResponseAdvisor.missing())
        assertNull(status.assessment)
        assertEquals(FailureCode.INVALID_OUTPUT, status.failure?.code)
        assertEquals("DR-08", plan(status).ruleId)
    }

    /** A pre-dispatch failure consumes no provider dispatch (F-02). */
    @Test
    fun unavailableBeforeDispatch_reportsFailureWithZeroDispatches() = runTest {
        val status = statusOf(FixedResponseAdvisor.unavailable())

        assertNull(status.assessment)
        assertEquals(FailureCode.NETWORK_DISABLED, status.failure?.code)
        assertEquals(0, status.providerDispatches)
        assertEquals("DR-08", plan(status).ruleId)
    }

    @Test
    fun consentDeclined_matchesPolicyConsentPath() = runTest {
        val advisor = FixedResponseAdvisor.unavailable(AiAdvisorFailure.ConsentUnavailable())
        val status = statusOf(advisor)

        assertEquals(FailureCode.CONSENT_UNAVAILABLE, status.failure?.code)
        val result = engine.evaluate(
            PolicyInput(
                policy = policy,
                evidence = status.completeness,
                compatibility = LocalCompatibility.COMPATIBLE,
                advice = PolicyAdvice.Unusable.CONSENT_DECLINED,
                human = HumanApprovalState.NOT_OBTAINED,
            ),
        )
        assertEquals(
            PolicyResult(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.AWAIT_HUMAN, "DR-02", "CONSENT_DECLINED"),
            result,
        )
    }

    /** A caller that omits consent gets the default-deny path, not a model call. */
    @Test
    fun withoutConsent_blocksBeforeAnyAdvisorCall() = runTest {
        val advisor = FixedResponseAdvisor.highSuccessLowRisk()
        val status = RequestInstallRiskAssessmentUseCase(builder, advisor)
            .invoke(EvidenceFixtures.normalUpdate)

        assertNotNull(status.failure)
        assertEquals(FailureCode.CONSENT_UNAVAILABLE, status.failure.code)
        assertEquals(0, status.providerDispatches)
        assertEquals(0, advisor.callCount, "the advisor must not be invoked without consent")
    }

    /**
     * REQ-IX-INV-004 negative test: across every fixed fixture no failure
     * shape yields ALLOW_AUTO, whether the failure is a parse failure (one
     * dispatch) or a pre-dispatch failure (zero dispatches).
     */
    @Test
    fun noFailureFixture_everAutoApproves() = runTest {
        val failing = listOf(
            FixedResponseAdvisor.invalid() to 1,
            FixedResponseAdvisor.missing() to 1,
            FixedResponseAdvisor.unavailable() to 0,
            FixedResponseAdvisor.unavailable(AiAdvisorFailure.Timeout("fixed")) to 1,
        )
        for ((advisor, expectedDispatches) in failing) {
            val status = statusOf(advisor)
            val result = plan(status)
            assertFalse(status.isUsable, "a failure fixture must not be usable: $result")
            assertEquals(expectedDispatches, status.providerDispatches)
            assertEquals(PolicyDisposition.AWAIT_HUMAN, result.disposition)
            assertFalse(result.decision == PolicyDecision.ALLOW_AUTO, "failure must never auto-approve: $result")
        }
    }

    /** The listed fixed fixtures are deterministic: two runs produce the same plan. */
    @Test
    fun fixedAdvisor_isDeterministicAcrossCalls() = runTest {
        val fixtures = listOf(
            FixedResponseAdvisor.highSuccessLowRisk(),
            FixedResponseAdvisor.lowSuccess(),
            FixedResponseAdvisor.highRisk(),
            FixedResponseAdvisor.unassessable(),
        )
        for (advisor in fixtures) {
            assertEquals(plan(statusOf(advisor)), plan(statusOf(advisor)))
        }
    }
}
