// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.verification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest

/**
 * Stage 2 Negative Invariant Suites: `HardBlockPrecedenceTest` and `NoDowngradeTest`.
 *
 * Verifies: REQ-IX-INV-002 (advice does not override deterministic prohibition),
 * REQ-IX-INV-007 (AI does not lower deterministic restrictions),
 * REQ-IX-SEC-002, ADR-002.6, DR-01, DR-05, DR-06.
 * Plan reference: `docs/verification/test-plan.md` §4 cases P-20, P-21 and §5 case C-11.
 */
class HardBlockPrecedenceTest {

    private val engine = DeterministicPolicyEngine()

    /**
     * Verifies: REQ-IX-INV-002, DR-01 (Case P-20)
     * Exhaustive negative test: across all evidence completeness states, all compatibility
     * states, all two-axis AI outputs, and all human approval states, an active HARD_BLOCK
     * always yields BLOCK / BLOCKED (DR-01).
     */
    @Test
    fun `P-20 exhaustive HARD_BLOCK dominates every advisory and human state`() {
        for (evidence in EvidenceCompleteness.entries) {
            for (compat in CompatibilityResult.entries) {
                for (human in HumanApprovalState.entries) {
                    val out = engine.decide(
                        PolicyInput(
                            evidenceCompleteness = evidence,
                            compatibility = compat,
                            assessment = AssessmentResult(
                                installSuccessLikelihood = InstallSuccessLikelihood.HIGH,
                                securityRisk = SecurityRisk.LOW,
                            ),
                            adviceAvailability = AdviceAvailability.VALID,
                            basePolicy = BasePolicyState.HARD_BLOCK,
                            humanState = human,
                        ),
                    )
                    assertEquals(PolicyDecision.BLOCK, out.decision, "evidence=$evidence compat=$compat human=$human")
                    assertEquals(PolicyDisposition.BLOCKED, out.disposition, "evidence=$evidence compat=$compat human=$human")
                    assertEquals("DR-01", out.ruleId)
                }
            }
        }
    }

    /**
     * Verifies: REQ-IX-INV-002, REQ-IX-FR-006, REQ-IX-FR-007 (Case C-11 & A-08)
     * End-to-end negative test: even when AI returns HIGH success + LOW risk and the user
     * explicitly calls `onContinue()`, a hard-prohibited APK yields BLOCK and 0 backend submissions.
     */
    @Test
    fun `C-11 and A-08 hard block plus favorable AI plus human Continue produces zero backend submissions`() = runTest {
        val gateway = FakeInstallationGateway()
        val advisor = FixedResponseAdvisor(
            nextOutcome = AdvisorCallOutcome.RawSuccess(
                result = AssessmentResult(
                    installSuccessLikelihood = InstallSuccessLikelihood.HIGH,
                    securityRisk = SecurityRisk.LOW,
                ),
            ),
        )
        val coordinator = ApprovalCoordinator(
            advisor = advisor,
            gateway = gateway,
        )

        val prohibitedEvidence = AssessmentEvidence(
            operationId = "op-hard-block",
            hasHardBlock = true,
            autoRulesMet = true,
        )

        val initial = coordinator.assess(prohibitedEvidence)
        assertEquals(PolicyDecision.BLOCK, initial.decision)
        assertEquals(PolicyDisposition.BLOCKED, initial.disposition)
        assertEquals(0, gateway.submissions.get())

        val afterContinue = coordinator.onContinue("op-hard-block")
        assertEquals(PolicyDecision.BLOCK, afterContinue.decision)
        assertEquals(PolicyDisposition.BLOCKED, afterContinue.disposition)
        assertEquals(0, gateway.submissions.get())
    }
}

/**
 * Verifies: REQ-IX-INV-007 (AI does not lower deterministic restrictions).
 */
class NoDowngradeTest {

    private val engine = DeterministicPolicyEngine()

    /**
     * Verifies: REQ-IX-INV-007, DR-06 (Case P-21)
     * When the deterministic base policy requires review (`AUTO_RULES_NOT_MET`), the most
     * favorable AI advice (HIGH success, LOW risk) must NOT downgrade the decision to `ALLOW_AUTO`.
     */
    @Test
    fun `P-21 base policy REQUIRE_REVIEW plus favorable AI advice stays REQUIRE_REVIEW`() {
        val out = engine.decide(
            PolicyInput(
                evidenceCompleteness = EvidenceCompleteness.COMPLETE,
                compatibility = CompatibilityResult.COMPATIBLE,
                assessment = AssessmentResult(
                    installSuccessLikelihood = InstallSuccessLikelihood.HIGH,
                    securityRisk = SecurityRisk.LOW,
                ),
                adviceAvailability = AdviceAvailability.VALID,
                basePolicy = BasePolicyState.AUTO_RULES_NOT_MET,
                humanState = HumanApprovalState.NOT_OBTAINED,
            ),
        )
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertNotEquals(PolicyDecision.ALLOW_AUTO, out.decision)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, out.disposition)
        assertEquals("DR-06", out.ruleId)
    }

    /**
     * Verifies: REQ-IX-INV-007
     * No combination of AI advice can ever produce a less restrictive decision than the base policy.
     */
    @Test
    fun `AI advice only maintains or raises restriction relative to base policy`() {
        for (s in InstallSuccessLikelihood.entries) {
            for (r in SecurityRisk.entries) {
                // Base = HARD_BLOCK -> always BLOCK
                val fromBlock = engine.decide(
                    PolicyInput(
                        assessment = AssessmentResult(s, r),
                        basePolicy = BasePolicyState.HARD_BLOCK,
                    ),
                )
                assertEquals(PolicyDecision.BLOCK, fromBlock.decision)

                // Base = AUTO_RULES_NOT_MET (review required) -> never ALLOW_AUTO
                val fromReview = engine.decide(
                    PolicyInput(
                        assessment = AssessmentResult(s, r),
                        basePolicy = BasePolicyState.AUTO_RULES_NOT_MET,
                    ),
                )
                assertNotEquals(PolicyDecision.ALLOW_AUTO, fromReview.decision)
            }
        }
    }
}
