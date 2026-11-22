// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.data.ai.advisory.DefaultAiRiskAdvisor
import com.rosan.installer.domain.ai.fixture.EvidenceFixtures
import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.InstallEvidence
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.repository.AiModelClient
import com.rosan.installer.domain.ai.repository.AiNetworkGate
import com.rosan.installer.domain.ai.repository.ModelRequestGate
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
 * Verifies: REQ-IX-INV-004, REQ-IX-FR-005, REQ-IX-FR-011, ADR-002.6
 * Offline and deterministic: scripted clients, no network.
 *
 * End-to-end escalation evidence. `AdvisorFailureReportingTest` stops at the
 * `Status` boundary; this class carries each failure through the
 * deterministic policy engine and asserts the observable outcome:
 *
 * > Every failure returns a status that lets Policy escalate to human review.
 *
 * Covered failures (runtime-policy-catalog.md section 3):
 * F-01 timeout, F-02 network disabled, DR-02 consent declined, F-05 invalid
 * JSON, and a model reply that tries to add an installation action.
 *
 * The rule asserted is deliberately narrow: `REQUIRE_REVIEW` with a
 * non-executing disposition. `BLOCK` would also be safe, but none of these
 * failures is a hard block, so review is the expected route.
 */
class FailureEscalationTest {

    private val engine = DeterministicPolicyEngine()

    /** Permissive policy on purpose: even so, no failure may auto-approve. */
    private val permissivePolicy = EvaluatedPolicy(
        version = "escalation-test-v1",
        hardBlock = false,
        baselineAllowsAuto = true,
        autoRulesSatisfied = true,
    )

    private class Client(private val behaviour: suspend () -> String) : AiModelClient {
        override val modelId = "escalation-stub"
        override suspend fun complete(request: AiInstallRiskRequest): String = behaviour()
    }

    private class Gate(private val allowed: Boolean) : AiNetworkGate {
        override suspend fun isEgressAllowed() = allowed
    }

    private fun useCase(client: AiModelClient, allowed: Boolean = true) = RequestInstallRiskAssessmentUseCase(
        builder = AiRequestBuilder(),
        advisor = DefaultAiRiskAdvisor(client, Gate(allowed), StrictAiOutputValidator()),
    )

    /** Consent is installed per call so declined/withdrawn is the default otherwise. */
    private suspend fun assess(
        useCase: RequestInstallRiskAssessmentUseCase,
        evidence: InstallEvidence,
        consent: Boolean = true,
    ) = withContext(ModelRequestGate { consent }) { useCase.invoke(evidence) }

    private fun plan(status: RequestInstallRiskAssessmentUseCase.Status): PolicyResult = engine.evaluate(
        PolicyInput(
            policy = permissivePolicy,
            evidence = status.completeness,
            compatibility = LocalCompatibility.COMPATIBLE,
            advice = status.assessment
                ?.let { PolicyAdvice.Valid(it) }
                ?: PolicyAdvice.Failed(status.failure?.code ?: FailureCode.INVALID_OUTPUT),
            human = HumanApprovalState.NOT_OBTAINED,
        ),
    )

    /**
     * Verifies: REQ-IX-INV-004, REQ-IX-FR-005.
     * The single assertion this class exists for: a failure is escalated,
     * never executed and never approved.
     */
    private fun assertEscalatedToHuman(status: RequestInstallRiskAssessmentUseCase.Status, label: String) {
        assertFalse(status.isUsable, "$label: a failure must not be usable advice")
        assertNull(status.assessment, "$label: no assessment may be attached to a failure")
        val failure = assertNotNull(status.failure, "$label: a failure must be reported")
        assertTrue(status.providerDispatches in 0..1, "$label: at most one dispatch")

        val result = plan(status)
        assertEquals(PolicyDecision.REQUIRE_REVIEW, result.decision, "$label: $result")
        assertEquals(PolicyDisposition.AWAIT_HUMAN, result.disposition, "$label: $result")
        assertEquals("DR-08", result.ruleId, "$label: $result; code=${failure.code}")
    }

    private val validReply = """
        {"install_success_likelihood":"HIGH","security_risk":"LOW",
         "reasons":["target_sdk 35 is current"],"evidence_refs":["evidence.target_sdk"]}
    """.trimIndent()

    // --- The five required failure paths --------------------------------

    /** F-01: the model call exceeded the budget. */
    @Test
    fun timeout_isEscalatedToHumanReview() = runTest {
        val status = assess(
            useCase(Client { throw AiAdvisorFailure.Timeout("7s budget expired") }),
            EvidenceFixtures.normalUpdate,
        )
        assertEquals(FailureCode.TIMEOUT, status.failure?.code)
        assertEquals(1, status.providerDispatches, "a timeout consumed a dispatch")
        assertEscalatedToHuman(status, "timeout")
    }

    /** F-02: the in-app network switch is off; nothing leaves the device. */
    @Test
    fun networkDisabled_isEscalatedWithZeroDispatches() = runTest {
        val status = assess(
            useCase(Client { validReply }, allowed = false),
            EvidenceFixtures.normalUpdate,
        )
        assertEquals(FailureCode.NETWORK_DISABLED, status.failure?.code)
        assertEquals(0, status.providerDispatches)
        assertTrue(status.transmittedPaths.isEmpty(), "nothing may be reported as sent")
        assertEscalatedToHuman(status, "network disabled")
    }

    /** DR-02/REQ-IX-FR-011: consent refused means no dispatch and no advice. */
    @Test
    fun consentRefused_isEscalatedWithZeroDispatches() = runTest {
        var calls = 0
        val status = assess(
            useCase(
                Client {
                    calls++
                    validReply
                },
            ),
            EvidenceFixtures.normalUpdate,
            consent = false,
        )
        assertEquals(FailureCode.CONSENT_UNAVAILABLE, status.failure?.code)
        assertEquals(0, status.providerDispatches)
        assertEquals(0, calls, "the model must never be called without consent")
        assertEscalatedToHuman(status, "consent refused")
    }

    /** F-05: the body is prose, not the fixed four-key object. */
    @Test
    fun invalidJson_isEscalatedToHumanReview() = runTest {
        val status = assess(
            useCase(Client { "Sure! This APK is safe to install." }),
            EvidenceFixtures.normalUpdate,
        )
        assertEquals(FailureCode.INVALID_OUTPUT, status.failure?.code)
        assertEscalatedToHuman(status, "invalid json")
    }

    /** REQ-IX-INV-001: a reply that adds an installation action is unusable. */
    @Test
    fun extraActionField_isEscalatedToHumanReview() = runTest {
        val status = assess(
            useCase(
                Client {
                    """{"install_success_likelihood":"HIGH","security_risk":"LOW",
                        "action":"install_now","reasons":["ok"],
                        "evidence_refs":["evidence.target_sdk"]}"""
                },
            ),
            EvidenceFixtures.normalUpdate,
        )
        assertEquals(FailureCode.INVALID_OUTPUT, status.failure?.code)
        assertEscalatedToHuman(status, "extra action field")
    }

    /**
     * REQ-IX-INV-001: a reply that adds an installation action is an explicit
     * failure and is escalated, regardless of how favorable its other
     * fields look.
     */
    @Test
    fun actionKey_cannotUpgradeItselfToAutoApproval() = runTest {
        val actionReply = assess(
            useCase(
                Client {
                    """{"install_success_likelihood":"HIGH","security_risk":"LOW",
                        "action":"run: pm install -r /data/local/tmp/a.apk",
                        "reasons":["target_sdk 35 is current"],
                        "evidence_refs":["evidence.target_sdk"]}"""
                },
            ),
            EvidenceFixtures.normalUpdate,
        )
        assertEquals(FailureCode.INVALID_OUTPUT, actionReply.failure?.code)
        assertEscalatedToHuman(actionReply, "action key with favorable fields")
    }

    /**
     * REQ-IX-INV-001 bound: instruction text inside `reasons` is untrusted
     * prose, not a field. It cannot raise the advisory value, so it is
     * ignored by the parser and still must pass the deterministic gate.
     * This pins the actual behaviour: prose lanes are data, and the
     * decision is computed only from the enum fields.
     */
    @Test
    fun instructionInReasons_isTreatedAsDataNotAsADecision() = runTest {
        val instructional = assess(
            useCase(
                Client {
                    """{"install_success_likelihood":"HIGH","security_risk":"LOW",
                        "reasons":["run: pm install -r /data/local/tmp/a.apk"],
                        "evidence_refs":["evidence.target_sdk"]}"""
                },
            ),
            EvidenceFixtures.normalUpdate,
        )
        assertTrue(instructional.isUsable, "prose in reasons is data, not a failure field")
        val result = plan(instructional)
        // The decision comes from the enum fields plus the deterministic
        // gate; the prose neither approves nor blocks anything by itself.
        assertEquals(PolicyDecision.ALLOW_AUTO, result.decision, "$result")
        assertEquals("DR-05", result.ruleId, "$result")
    }

    /** REQ-IX-INV-004: a legal body with an illegal inner type still escalates. */
    @Test
    fun illTypedResponse_isEscalatedNotThrown() = runTest {
        val status = assess(
            useCase(
                Client {
                    """{"install_success_likelihood":"HIGH","security_risk":1,
                        "reasons":["ok"],"evidence_refs":["evidence.target_sdk"]}"""
                },
            ),
            EvidenceFixtures.normalUpdate,
        )
        assertEquals(FailureCode.INVALID_OUTPUT, status.failure?.code)
        assertEscalatedToHuman(status, "ill-typed response")
    }

    // --- Control cases ---------------------------------------------------

    /** Control: the happy path still auto-approves, so the rule is real. */
    @Test
    fun validReply_withPermissivePolicy_isTheOnlyAutoApproval() = runTest {
        val status = assess(useCase(Client { validReply }), EvidenceFixtures.normalUpdate)
        val result = plan(status)
        assertEquals(PolicyDecision.ALLOW_AUTO, result.decision)
        assertEquals("DR-05", result.ruleId)
    }

    /** Control: a usable but unfavorable advisory is escalated too (DR-07). */
    @Test
    fun lowLikelihood_reply_isEscalatedToHumanReview() = runTest {
        val lowReply = validReply.replace("\"install_success_likelihood\":\"HIGH\"", "\"install_success_likelihood\":\"LOW\"")
        val status = assess(useCase(Client { lowReply }), EvidenceFixtures.normalUpdate)
        assertTrue(status.isUsable, "a LOW likelihood is still valid advice")
        val result = plan(status)
        assertEquals(PolicyDecision.REQUIRE_REVIEW, result.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, result.disposition)
        assertEquals("DR-07", result.ruleId)
    }

    /** Control: UNKNOWN is advice, never a silent "safe". */
    @Test
    fun unknownReply_isEscalatedToHumanReview() = runTest {
        val unknownReply = validReply
            .replace("\"install_success_likelihood\":\"HIGH\"", "\"install_success_likelihood\":\"UNKNOWN\"")
            .replace("\"security_risk\":\"LOW\"", "\"security_risk\":\"UNKNOWN\"")
        val status = assess(useCase(Client { unknownReply }), EvidenceFixtures.normalUpdate)
        assertTrue(status.isUsable)
        val result = plan(status)
        assertEquals(PolicyDecision.REQUIRE_REVIEW, result.decision)
        assertEquals("DR-07", result.ruleId)
    }
}
