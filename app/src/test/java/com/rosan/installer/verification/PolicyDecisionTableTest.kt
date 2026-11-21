// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.verification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Stage 2 Verification Suite: `PolicyDecisionTableTest`.
 *
 * Verifies: REQ-IX-FR-005, REQ-IX-FR-011, REQ-IX-INV-002, REQ-IX-INV-003,
 * REQ-IX-INV-004, REQ-IX-INV-006, REQ-IX-INV-007, DR-01 .. DR-10.
 * Plan reference: `docs/verification/test-plan.md` §4 cases P-01 .. P-19, P-22.
 */
class PolicyDecisionTableTest {

    private val engine = DeterministicPolicyEngine(defaultPolicyVersion = "v1")

    private fun input(
        evidence: EvidenceCompleteness = EvidenceCompleteness.COMPLETE,
        compatibility: CompatibilityResult = CompatibilityResult.COMPATIBLE,
        success: InstallSuccessLikelihood = InstallSuccessLikelihood.HIGH,
        risk: SecurityRisk = SecurityRisk.LOW,
        advice: AdviceAvailability = AdviceAvailability.VALID,
        basePolicy: BasePolicyState = BasePolicyState.AUTO_RULES_MET,
        human: HumanApprovalState = HumanApprovalState.NOT_OBTAINED,
        policyVersion: String = "v1",
    ) = PolicyInput(
        evidenceCompleteness = evidence,
        compatibility = compatibility,
        assessment = if (advice == AdviceAvailability.VALID) {
            AssessmentResult(installSuccessLikelihood = success, securityRisk = risk)
        } else {
            null
        },
        adviceAvailability = advice,
        basePolicy = basePolicy,
        humanState = human,
        policyVersion = policyVersion,
    )

    /** Verifies: REQ-IX-FR-005, REQ-IX-INV-007, DR-05 (Case P-01) */
    @Test
    fun `P-01 complete compatible HIGH success LOW risk and auto rules met yields ALLOW_AUTO`() {
        val out = engine.decide(input())
        assertEquals(PolicyDecision.ALLOW_AUTO, out.decision)
        assertEquals(PolicyDisposition.EXECUTE_ONCE, out.disposition)
        assertEquals("DR-05", out.ruleId)
        assertEquals("AUTO_APPROVED", out.reasonCode)
    }

    /** Verifies: REQ-IX-FR-005, REQ-IX-INV-007, DR-06 (Case P-02) */
    @Test
    fun `P-02 favorable advice when auto rules not met yields REQUIRE_REVIEW`() {
        val out = engine.decide(input(basePolicy = BasePolicyState.AUTO_RULES_NOT_MET))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, out.disposition)
        assertEquals("DR-06", out.ruleId)
    }

    /** Verifies: REQ-IX-FR-005, DR-07 (Case P-03) */
    @Test
    fun `P-03 install success likelihood LOW yields REQUIRE_REVIEW`() {
        val out = engine.decide(input(success = InstallSuccessLikelihood.LOW))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, out.disposition)
        assertEquals("DR-07", out.ruleId)
    }

    /** Verifies: REQ-IX-FR-005, DR-07 (Case P-04) */
    @Test
    fun `P-04 install success likelihood MEDIUM yields REQUIRE_REVIEW`() {
        val out = engine.decide(input(success = InstallSuccessLikelihood.MEDIUM))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, out.disposition)
        assertEquals("DR-07", out.ruleId)
    }

    /**
     * Verifies: REQ-IX-FR-005, ADR-002.6, DR-07 (Case P-05)
     * CRITICAL RULE: AI high security risk must route to REQUIRE_REVIEW, NEVER directly to BLOCK.
     * Only deterministic hard prohibitions (DR-01) may produce BLOCK.
     */
    @Test
    fun `P-05 AI security risk HIGH yields REQUIRE_REVIEW and never BLOCK`() {
        val out = engine.decide(input(risk = SecurityRisk.HIGH))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertNotEquals(PolicyDecision.BLOCK, out.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, out.disposition)
        assertEquals("DR-07", out.ruleId)
    }

    /** Verifies: REQ-IX-FR-005, DR-07 (Case P-06) */
    @Test
    fun `P-06 security risk MEDIUM yields REQUIRE_REVIEW`() {
        val out = engine.decide(input(risk = SecurityRisk.MEDIUM))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, out.disposition)
        assertEquals("DR-07", out.ruleId)
    }

    /** Verifies: REQ-IX-FR-005, DR-07 (Case P-07) */
    @Test
    fun `P-07 install success likelihood UNKNOWN yields REQUIRE_REVIEW`() {
        val out = engine.decide(input(success = InstallSuccessLikelihood.UNKNOWN))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, out.disposition)
        assertEquals("DR-07", out.ruleId)
    }

    /** Verifies: REQ-IX-FR-005, DR-07 (Case P-08) */
    @Test
    fun `P-08 security risk UNKNOWN yields REQUIRE_REVIEW`() {
        val out = engine.decide(input(risk = SecurityRisk.UNKNOWN))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, out.disposition)
        assertEquals("DR-07", out.ruleId)
    }

    /** Verifies: REQ-IX-FR-005, DR-07 (Case P-09) */
    @Test
    fun `P-09 compatibility UNKNOWN yields REQUIRE_REVIEW and never ALLOW_AUTO`() {
        val out = engine.decide(input(compatibility = CompatibilityResult.UNKNOWN))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertNotEquals(PolicyDecision.ALLOW_AUTO, out.decision)
        assertEquals("DR-07", out.ruleId)
    }

    /** Verifies: REQ-IX-FR-005, DR-07 (Case P-10) */
    @Test
    fun `P-10 compatibility INCOMPATIBLE yields REQUIRE_REVIEW and never ALLOW_AUTO`() {
        val out = engine.decide(input(compatibility = CompatibilityResult.INCOMPATIBLE))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertNotEquals(PolicyDecision.ALLOW_AUTO, out.decision)
        assertEquals("DR-07", out.ruleId)
    }

    /** Verifies: REQ-IX-INV-004, REQ-IX-FR-005, DR-08 (Case P-11) */
    @Test
    fun `P-11 every unusable advice failure mode routes to DR-08 REQUIRE_REVIEW`() {
        val failureModes = listOf(
            AdviceAvailability.INVALID,
            AdviceAvailability.TIMEOUT,
            AdviceAvailability.NETWORK_DISABLED,
            AdviceAvailability.NETWORK_LOSS,
            AdviceAvailability.RATE_LIMITED,
            AdviceAvailability.SERVICE_ERROR,
            AdviceAvailability.BUDGET_REJECTED,
            AdviceAvailability.POST_CALL_OVERAGE,
            AdviceAvailability.BLOCKED_THIRD_REQUEST,
        )
        for (failure in failureModes) {
            val out = engine.decide(input(advice = failure))
            assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision, "failure=$failure")
            assertEquals(PolicyDisposition.AWAIT_HUMAN, out.disposition, "failure=$failure")
            assertEquals("DR-08", out.ruleId, "failure=$failure")
        }
    }

    /** Verifies: REQ-IX-FR-011, NFC-OUT-003, DR-02 (Case P-12) */
    @Test
    fun `P-12 outbound consent declined routes to DR-02 REQUIRE_REVIEW`() {
        val out = engine.decide(input(advice = AdviceAvailability.UNAVAILABLE_CONSENT_DECLINED))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, out.disposition)
        assertEquals("DR-02", out.ruleId)
        assertEquals("CONSENT_DECLINED", out.reasonCode)
    }

    /** Verifies: REQ-IX-FR-005, NFC-OUT-005, DR-03 (Case P-13) */
    @Test
    fun `P-13 incomplete evidence KEY_UNKNOWN TRUNCATED or UNREADABLE yields DR-03 RECOLLECT`() {
        val incompleteStates = listOf(
            EvidenceCompleteness.KEY_UNKNOWN,
            EvidenceCompleteness.TRUNCATED,
            EvidenceCompleteness.UNREADABLE,
        )
        for (state in incompleteStates) {
            val out = engine.decide(input(evidence = state))
            assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision, "state=$state")
            assertEquals(PolicyDisposition.RECOLLECT, out.disposition, "state=$state")
            assertEquals("DR-03", out.ruleId, "state=$state")
        }
    }

    /** Verifies: REQ-IX-FR-005, F-14, DR-04 (Case P-14) */
    @Test
    fun `P-14 contradictory local evidence yields DR-04 REQUIRE_REVIEW AWAIT_HUMAN`() {
        val out = engine.decide(input(evidence = EvidenceCompleteness.CONTRADICTORY))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, out.disposition)
        assertEquals("DR-04", out.ruleId)
    }

    /** Verifies: REQ-IX-INV-002, REQ-IX-SEC-002, DR-01 (Case P-15) */
    @Test
    fun `P-15 hard prohibition beats HIGH-LOW advice and human approval`() {
        val out = engine.decide(
            input(
                basePolicy = BasePolicyState.HARD_BLOCK,
                human = HumanApprovalState.VALID_APPROVAL_AND_REVALIDATION_PASSED,
            ),
        )
        assertEquals(PolicyDecision.BLOCK, out.decision)
        assertEquals(PolicyDisposition.BLOCKED, out.disposition)
        assertEquals("DR-01", out.ruleId)
    }

    /** Verifies: REQ-IX-FR-005, DR-09 (Case P-16) */
    @Test
    fun `P-16 valid human approval and passed revalidation yields DR-09 EXECUTE_ONCE`() {
        val out = engine.decide(
            input(
                advice = AdviceAvailability.UNAVAILABLE_CONSENT_DECLINED,
                human = HumanApprovalState.VALID_APPROVAL_AND_REVALIDATION_PASSED,
            ),
        )
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals(PolicyDisposition.EXECUTE_ONCE, out.disposition)
        assertEquals("DR-09", out.ruleId)
    }

    /** Verifies: REQ-IX-INV-003, DR-10 (Case P-17) */
    @Test
    fun `P-17 revalidation failure after approval yields DR-10 INVALIDATED even if evidence is KEY_UNKNOWN`() {
        val out = engine.decide(
            input(
                evidence = EvidenceCompleteness.KEY_UNKNOWN,
                human = HumanApprovalState.REVALIDATION_FAILED_AFTER_APPROVAL,
            ),
        )
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals(PolicyDisposition.INVALIDATED, out.disposition)
        assertEquals("DR-10", out.ruleId)
    }

    /** Verifies: REQ-IX-FR-005 determinism (Case P-18) */
    @Test
    fun `P-18 identical inputs and policy version produce identical decisions`() {
        val sample = input(
            success = InstallSuccessLikelihood.MEDIUM,
            risk = SecurityRisk.LOW,
            policyVersion = "v1.2",
        )
        val first = engine.decide(sample)
        val second = engine.decide(sample.copy())
        assertEquals(first, second)
    }

    /** Verifies: REQ-IX-FR-005 exhaustive non-favorable check (Case P-19) */
    @Test
    fun `P-19 exhaustive check over all non-favorable two-axis and compatibility combinations never yields ALLOW_AUTO`() {
        for (s in InstallSuccessLikelihood.entries) {
            for (r in SecurityRisk.entries) {
                for (c in CompatibilityResult.entries) {
                    if (s == InstallSuccessLikelihood.HIGH &&
                        r == SecurityRisk.LOW &&
                        c == CompatibilityResult.COMPATIBLE
                    ) {
                        continue
                    }
                    val out = engine.decide(input(success = s, risk = r, compatibility = c))
                    assertNotEquals(
                        PolicyDecision.ALLOW_AUTO,
                        out.decision,
                        "Expected REQUIRE_REVIEW for s=$s r=$r c=$c",
                    )
                    assertEquals("DR-07", out.ruleId, "s=$s r=$r c=$c")
                }
            }
        }
    }

    /** Verifies: REQ-IX-INV-006 behavioral independence from model (Case P-22) */
    @Test
    fun `P-22 policy engine runs without any model or advisor and produces deterministic BLOCK or REQUIRE_REVIEW`() {
        val noModelBlocked = engine.decide(
            PolicyInput(
                assessment = null,
                adviceAvailability = AdviceAvailability.NETWORK_DISABLED,
                basePolicy = BasePolicyState.HARD_BLOCK,
            ),
        )
        assertEquals(PolicyDecision.BLOCK, noModelBlocked.decision)

        val noModelReview = engine.decide(
            PolicyInput(
                assessment = null,
                adviceAvailability = AdviceAvailability.NETWORK_DISABLED,
                basePolicy = BasePolicyState.AUTO_RULES_MET,
            ),
        )
        assertEquals(PolicyDecision.REQUIRE_REVIEW, noModelReview.decision)
    }
}
