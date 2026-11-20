// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.policy

import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.LocalCompatibility

/**
 * Implements: ADR-001
 * Enforces: REQ-IX-FR-005, REQ-IX-INV-002, REQ-IX-INV-007
 *
 * The effective version and already evaluated local policy facts. Automatic
 * eligibility requires both affirmative baseline permission and satisfied rules.
 */
data class EvaluatedPolicy(
    val version: String,
    val hardBlock: Boolean,
    val baselineAllowsAuto: Boolean,
    val autoRulesSatisfied: Boolean,
)

/** Implements: ADR-001. Enforces: REQ-IX-FR-005, REQ-IX-INV-003. */
enum class HumanApprovalState {
    NOT_OBTAINED,
    VALID_APPROVAL_AND_REVALIDATION_PASSED,
    REVALIDATION_FAILED_AFTER_APPROVAL,
}

/**
 * Implements: ADR-001
 * Enforces: REQ-IX-FR-005, REQ-IX-INV-004
 *
 * Inbound advisory report or caller-reported unavailability. None is an approval.
 */
sealed interface PolicyAdvice {
    /** Implements: ADR-001. Enforces: REQ-IX-FR-005. */
    data class Valid(val result: AssessmentResult.Valid) : PolicyAdvice

    /** Implements: ADR-001. Enforces: REQ-IX-INV-004. */
    data class Failed(val code: FailureCode) : PolicyAdvice

    /** Implements: ADR-001. Enforces: REQ-IX-INV-004. */
    enum class Unusable : PolicyAdvice {
        CONSENT_DECLINED,
        UNAVAILABLE,
        BUDGET_REJECTED,
        OVERAGE,
        BLOCKED_THIRD,
    }
}

/**
 * Implements: ADR-001
 * Enforces: REQ-IX-FR-005, REQ-IX-INV-003, REQ-IX-INV-006
 *
 * Facts for one evaluation. The caller owns pending revalidation and must not
 * represent a pending or unbound approval as having passed revalidation.
 */
data class PolicyInput(
    val policy: EvaluatedPolicy,
    val evidence: EvidenceCompleteness,
    val compatibility: LocalCompatibility,
    val advice: PolicyAdvice,
    val human: HumanApprovalState,
)

/** Implements: ADR-001. Enforces: REQ-IX-FR-005. */
enum class PolicyDecision { ALLOW_AUTO, REQUIRE_REVIEW, BLOCK }

/** Implements: ADR-001. Enforces: REQ-IX-FR-005, REQ-IX-FR-007. */
enum class PolicyDisposition { EXECUTE_ONCE, AWAIT_HUMAN, RECOLLECT, INVALIDATED, BLOCKED }

/**
 * Implements: ADR-001
 * Enforces: REQ-IX-FR-005, REQ-IX-FR-007
 *
 * A decision and its next disposition. EXECUTE_ONCE does not perform a
 * submission; the caller enforces final revalidation and single submission.
 */
data class PolicyResult(
    val decision: PolicyDecision,
    val disposition: PolicyDisposition,
    val ruleId: String,
    val reasonCode: String,
)
