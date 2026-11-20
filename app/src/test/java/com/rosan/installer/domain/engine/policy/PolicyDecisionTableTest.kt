// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.policy

import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.InstallAssessment
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.model.SecurityRisk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Verifies: REQ-IX-FR-005, REQ-IX-INV-002/003/004/006/007. */
class PolicyDecisionTableTest {
    private val engine = DeterministicPolicyEngine()
    private val favorable = PolicyInput(
        policy = EvaluatedPolicy("test-v1", hardBlock = false, baselineAllowsAuto = true, autoRulesSatisfied = true),
        evidence = EvidenceCompleteness.COMPLETE,
        compatibility = LocalCompatibility.COMPATIBLE,
        advice = validAdvice(),
        human = HumanApprovalState.NOT_OBTAINED,
    )
    private val approved = HumanApprovalState.VALID_APPROVAL_AND_REVALIDATION_PASSED
    private val invalidated = HumanApprovalState.REVALIDATION_FAILED_AFTER_APPROVAL
    private val hardBlock = favorable.policy.copy(hardBlock = true)
    private val manualPolicy = favorable.policy.copy(baselineAllowsAuto = false)
    private val incomplete = listOf(
        EvidenceCompleteness.UNREADABLE,
        EvidenceCompleteness.KEY_UNKNOWN,
        EvidenceCompleteness.TRUNCATED,
    )

    @Test
    fun everyRuleReturnsTheExactD1Result() {
        val cases = listOf(
            favorable.copy(policy = hardBlock, human = approved) to
                PolicyResult(PolicyDecision.BLOCK, PolicyDisposition.BLOCKED, "DR-01", "HARD_BLOCK"),
            favorable.copy(human = invalidated) to
                PolicyResult(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.INVALIDATED, "DR-10", "APPROVAL_INVALIDATED"),
            favorable.copy(evidence = EvidenceCompleteness.KEY_UNKNOWN) to
                PolicyResult(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.RECOLLECT, "DR-03", "EVIDENCE_INCOMPLETE"),
            favorable.copy(advice = PolicyAdvice.Unusable.CONSENT_DECLINED) to
                PolicyResult(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.AWAIT_HUMAN, "DR-02", "CONSENT_DECLINED"),
            favorable.copy(evidence = EvidenceCompleteness.CONTRADICTORY) to
                PolicyResult(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.AWAIT_HUMAN, "DR-04", "EVIDENCE_CONTRADICTORY"),
            favorable to
                PolicyResult(PolicyDecision.ALLOW_AUTO, PolicyDisposition.EXECUTE_ONCE, "DR-05", "AUTO_APPROVED"),
            favorable.copy(policy = manualPolicy) to
                PolicyResult(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.AWAIT_HUMAN, "DR-06", "AUTO_RULES_NOT_MET"),
            favorable.copy(compatibility = LocalCompatibility.UNKNOWN) to
                PolicyResult(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.AWAIT_HUMAN, "DR-07", "ADVISORY_OR_COMPATIBILITY_REQUIRES_REVIEW"),
            favorable.copy(advice = PolicyAdvice.Failed(FailureCode.INVALID_OUTPUT)) to
                PolicyResult(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.AWAIT_HUMAN, "DR-08", "ADVICE_UNUSABLE"),
            favorable.copy(human = approved, policy = manualPolicy, advice = PolicyAdvice.Unusable.UNAVAILABLE) to
                PolicyResult(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.EXECUTE_ONCE, "DR-09", "APPROVED_AFTER_REVIEW"),
        )
        for ((input, expected) in cases) {
            assertEquals(expected, engine.evaluate(input), expected.ruleId)
        }
    }

    @Test
    fun earlierRulesWinWhenFactsOverlap() {
        val contradictory = favorable.copy(evidence = EvidenceCompleteness.CONTRADICTORY)
        val declined = PolicyAdvice.Unusable.CONSENT_DECLINED
        val cases = listOf(
            favorable.copy(policy = hardBlock) to "DR-01",
            favorable.copy(policy = hardBlock, human = invalidated) to "DR-01",
            favorable.copy(evidence = EvidenceCompleteness.TRUNCATED, human = invalidated) to "DR-10",
            contradictory.copy(advice = declined) to "DR-02",
            contradictory.copy(advice = PolicyAdvice.Unusable.UNAVAILABLE) to "DR-04",
            contradictory.copy(human = approved, compatibility = LocalCompatibility.INCOMPATIBLE) to "DR-09",
        ) + incomplete.flatMap { evidence ->
            listOf(
                favorable.copy(evidence = evidence),
                favorable.copy(evidence = evidence, human = approved),
                favorable.copy(evidence = evidence, advice = declined),
                favorable.copy(evidence = evidence, advice = PolicyAdvice.Unusable.UNAVAILABLE),
            ).map { it to "DR-03" }
        }
        for ((input, rule) in cases) {
            val result = engine.evaluate(input)
            assertEquals(rule, result.ruleId, input.toString())
            if (rule != "DR-09") assertNotEquals(PolicyDisposition.EXECUTE_ONCE, result.disposition)
        }
    }

    @Test
    fun eitherAdvisoryAxisOrLocalCompatibilityCanPreventAutoApproval() {
        val cases = InstallSuccessLikelihood.entries.filter { it != InstallSuccessLikelihood.HIGH }.map {
            favorable.copy(advice = validAdvice(likelihood = it))
        } + SecurityRisk.entries.filter { it != SecurityRisk.LOW }.map {
            favorable.copy(advice = validAdvice(risk = it))
        } + listOf(LocalCompatibility.UNKNOWN, LocalCompatibility.INCOMPATIBLE).map {
            favorable.copy(compatibility = it)
        }
        for (input in cases) {
            val result = engine.evaluate(input)
            assertEquals("DR-07", result.ruleId, input.toString())
            assertEquals(PolicyDisposition.AWAIT_HUMAN, result.disposition)
        }
        val unmet = favorable.copy(policy = favorable.policy.copy(autoRulesSatisfied = false))
        assertEquals("DR-06", engine.evaluate(unmet).ruleId)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, engine.evaluate(unmet).disposition)
    }

    @Test
    fun unusableAdviceNeverGrantsAutomaticExecutionAndCannotRemoveHardBlock() {
        val failures = FailureCode.entries.map { PolicyAdvice.Failed(it) } + listOf(
            PolicyAdvice.Unusable.UNAVAILABLE,
            PolicyAdvice.Unusable.BUDGET_REJECTED,
            PolicyAdvice.Unusable.OVERAGE,
            PolicyAdvice.Unusable.BLOCKED_THIRD,
        )
        for (advice in failures) {
            val input = favorable.copy(advice = advice)
            assertEquals(
                PolicyResult(PolicyDecision.REQUIRE_REVIEW, PolicyDisposition.AWAIT_HUMAN, "DR-08", "ADVICE_UNUSABLE"),
                engine.evaluate(input),
                advice.toString(),
            )
            assertEquals(
                PolicyResult(PolicyDecision.BLOCK, PolicyDisposition.BLOCKED, "DR-01", "HARD_BLOCK"),
                engine.evaluate(input.copy(policy = hardBlock, human = approved)),
                advice.toString(),
            )
        }
    }

    @Test
    fun identicalInputsAndPolicyVersionGiveIdenticalResultsWithoutAnyAdvisor() {
        val inputs = listOf(favorable, favorable.copy(human = invalidated), favorable.copy(policy = hardBlock))
        for (input in inputs) {
            assertEquals(engine.evaluate(input), DeterministicPolicyEngine().evaluate(input.copy()))
        }
    }

    private fun validAdvice(
        likelihood: InstallSuccessLikelihood = InstallSuccessLikelihood.HIGH,
        risk: SecurityRisk = SecurityRisk.LOW,
    ) = PolicyAdvice.Valid(
        AssessmentResult.Valid(InstallAssessment(likelihood, risk, listOf("fixture"), listOf("evidence.target_sdk"), "stub", "v1")),
    )
}
