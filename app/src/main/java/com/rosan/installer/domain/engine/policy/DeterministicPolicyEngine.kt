// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.policy

import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.model.SecurityRisk

/** Implements: ADR-001. Enforces: REQ-IX-FR-005, REQ-IX-INV-002/004/006/007. */
class DeterministicPolicyEngine {
    /**
     * Implements: ADR-001
     * Enforces: REQ-IX-FR-005, REQ-IX-FR-007, REQ-IX-INV-002/003/004/006/007
     *
     * Evaluates D1 rev.6 in first-match order using only the supplied facts.
     */
    fun evaluate(input: PolicyInput): PolicyResult {
        val assessment = (input.advice as? PolicyAdvice.Valid)?.result?.assessment
        val favorable = assessment != null &&
            assessment.installSuccessLikelihood == InstallSuccessLikelihood.HIGH &&
            assessment.securityRisk == SecurityRisk.LOW
        val complete = input.evidence == EvidenceCompleteness.COMPLETE
        val compatible = input.compatibility == LocalCompatibility.COMPATIBLE
        val autoRulesMet = input.policy.baselineAllowsAuto && input.policy.autoRulesSatisfied
        val noApproval = input.human == HumanApprovalState.NOT_OBTAINED

        return when {
            input.policy.hardBlock ->
                PolicyResult(PolicyDecision.BLOCK, PolicyDisposition.BLOCKED, "DR-01", "HARD_BLOCK")

            input.human == HumanApprovalState.REVALIDATION_FAILED_AFTER_APPROVAL ->
                review(PolicyDisposition.INVALIDATED, "DR-10", "APPROVAL_INVALIDATED")

            input.evidence == EvidenceCompleteness.UNREADABLE ||
                input.evidence == EvidenceCompleteness.KEY_UNKNOWN ||
                input.evidence == EvidenceCompleteness.TRUNCATED ->
                review(PolicyDisposition.RECOLLECT, "DR-03", "EVIDENCE_INCOMPLETE")

            noApproval && input.advice == PolicyAdvice.Unusable.CONSENT_DECLINED ->
                review(PolicyDisposition.AWAIT_HUMAN, "DR-02", "CONSENT_DECLINED")

            noApproval && input.evidence == EvidenceCompleteness.CONTRADICTORY ->
                review(PolicyDisposition.AWAIT_HUMAN, "DR-04", "EVIDENCE_CONTRADICTORY")

            noApproval && complete && compatible && favorable && autoRulesMet ->
                PolicyResult(PolicyDecision.ALLOW_AUTO, PolicyDisposition.EXECUTE_ONCE, "DR-05", "AUTO_APPROVED")

            noApproval && complete && compatible && favorable && !autoRulesMet ->
                review(PolicyDisposition.AWAIT_HUMAN, "DR-06", "AUTO_RULES_NOT_MET")

            noApproval && complete && assessment != null ->
                review(PolicyDisposition.AWAIT_HUMAN, "DR-07", "ADVISORY_OR_COMPATIBILITY_REQUIRES_REVIEW")

            noApproval && assessment == null ->
                review(PolicyDisposition.AWAIT_HUMAN, "DR-08", "ADVICE_UNUSABLE")

            input.human == HumanApprovalState.VALID_APPROVAL_AND_REVALIDATION_PASSED ->
                review(PolicyDisposition.EXECUTE_ONCE, "DR-09", "APPROVED_AFTER_REVIEW")

            else -> error("Policy input did not match a D1 decision rule")
        }
    }

    private fun review(disposition: PolicyDisposition, ruleId: String, reasonCode: String) = PolicyResult(PolicyDecision.REQUIRE_REVIEW, disposition, ruleId, reasonCode)
}
