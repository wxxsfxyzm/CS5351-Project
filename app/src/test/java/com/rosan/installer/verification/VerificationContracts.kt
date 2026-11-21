// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.verification

/**
 * Contract Mirror & Reference Models for Member D (Verification).
 *
 * Implements: ADR-001 (Option B), ADR-002.6 (Authority Separation), ADR-002.7 (Binding & Tamper Resistance)
 * Enforces: REQ-IX-INV-001..007, REQ-IX-FR-002..007
 *
 * NOTE: Because the production packages (`domain/ai/...`) from Members C, A, and E are not yet
 * merged into this branch, these test-scoped contracts and reference implementations mirror the
 * exact specifications in `docs/req/requirements.yaml` (rev.6) and `docs/architecture/runtime-policy-catalog.md`.
 * Once C/A/E merge their production types, point these typealiases/imports directly to `domain.ai.*`.
 */

enum class EvidenceCompleteness {
    COMPLETE,
    KEY_UNKNOWN,
    TRUNCATED,
    CONTRADICTORY,
    UNREADABLE,
}

enum class CompatibilityResult {
    COMPATIBLE,
    INCOMPATIBLE,
    UNKNOWN,
}

enum class InstallSuccessLikelihood {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
}

enum class SecurityRisk {
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN,
}

enum class PolicyDecision {
    ALLOW_AUTO,
    REQUIRE_REVIEW,
    BLOCK,
}

enum class PolicyDisposition {
    AWAIT_HUMAN,
    BLOCKED,
    EXECUTE_ONCE,
    INVALIDATED,
    RECOLLECT,
}

enum class BasePolicyState {
    HARD_BLOCK,
    AUTO_RULES_MET,
    AUTO_RULES_NOT_MET,
}

enum class HumanApprovalState {
    NOT_OBTAINED,
    VALID_APPROVAL_AND_REVALIDATION_PASSED,
    REVALIDATION_FAILED_AFTER_APPROVAL,
}

enum class AdviceAvailability {
    VALID,
    UNAVAILABLE_CONSENT_DECLINED,
    INVALID,
    TIMEOUT,
    NETWORK_DISABLED,
    NETWORK_LOSS,
    RATE_LIMITED,
    SERVICE_ERROR,
    BUDGET_REJECTED,
    POST_CALL_OVERAGE,
    BLOCKED_THIRD_REQUEST,
}

/**
 * Contract 1: AssessmentEvidence (local evidence + allowlisted derived signals).
 * Covers REQ-IX-FR-002, REQ-IX-INV-003, and `outbound_metadata_allowlist`.
 */
data class AssessmentEvidence(
    val operationId: String = "op-001",
    val attemptId: String = "att-001",
    val targetPackageName: String = "com.example.target",
    val installSourcePackageName: String = "com.android.vending",
    val apkSha256: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    val targetUserId: Int = 0,
    val installMode: String = "AutoDialog",
    val installationBackend: String = "None",
    val configSnapshot: String = "authorizer=None;allowDowngrade=false",
    val policyVersion: String = "v1",
    val completeness: EvidenceCompleteness = EvidenceCompleteness.COMPLETE,
    val platformCompatibility: CompatibilityResult = CompatibilityResult.COMPATIBLE,
    val abiCompatibility: CompatibilityResult = CompatibilityResult.COMPATIBLE,
    val storageSufficient: Boolean = true,
    val declaredPermissions: List<String> = listOf("android.permission.INTERNET"),
    val hasHardBlock: Boolean = false,
    val autoRulesMet: Boolean = true,
) {
    val combinedCompatibility: CompatibilityResult
        get() = when {
            platformCompatibility == CompatibilityResult.INCOMPATIBLE ||
                abiCompatibility == CompatibilityResult.INCOMPATIBLE ||
                !storageSufficient -> CompatibilityResult.INCOMPATIBLE

            platformCompatibility == CompatibilityResult.UNKNOWN ||
                abiCompatibility == CompatibilityResult.UNKNOWN -> CompatibilityResult.UNKNOWN

            else -> CompatibilityResult.COMPATIBLE
        }

    val basePolicyState: BasePolicyState
        get() = when {
            hasHardBlock -> BasePolicyState.HARD_BLOCK
            autoRulesMet -> BasePolicyState.AUTO_RULES_MET
            else -> BasePolicyState.AUTO_RULES_NOT_MET
        }
}

/**
 * Contract 2: AssessmentResult (AI two-axis advisory result).
 * Covers REQ-IX-FR-003, REQ-IX-FR-004, REQ-IX-QUAL-001.
 */
data class AssessmentResult(
    val installSuccessLikelihood: InstallSuccessLikelihood,
    val securityRisk: SecurityRisk,
    val reasons: List<String> = listOf("Standard permissions and compatible SDK"),
    val evidenceReferences: List<String> = listOf("declared_permissions", "platform_compatibility"),
    val modelId: String = "mock-advisor-v1",
    val promptVersion: String = "v1",
    val inputTokens: Int = 350,
    val outputTokens: Int = 120,
    val forbiddenActionField: String? = null,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
    val isFavorable: Boolean
        get() = installSuccessLikelihood == InstallSuccessLikelihood.HIGH &&
            securityRisk == SecurityRisk.LOW
}

/**
 * Contract 3: PolicyEvaluationOutcome (Decision, Disposition, DR rule ID, reason code).
 * Covers REQ-IX-FR-005.
 */
data class PolicyEvaluationOutcome(
    val decision: PolicyDecision,
    val disposition: PolicyDisposition,
    val ruleId: String,
    val reasonCode: String,
    val policyVersion: String = "v1",
)

/**
 * Input envelope for `DeterministicPolicyEngine.decide(...)`.
 */
data class PolicyInput(
    val evidenceCompleteness: EvidenceCompleteness = EvidenceCompleteness.COMPLETE,
    val compatibility: CompatibilityResult = CompatibilityResult.COMPATIBLE,
    val assessment: AssessmentResult? = AssessmentResult(
        installSuccessLikelihood = InstallSuccessLikelihood.HIGH,
        securityRisk = SecurityRisk.LOW,
    ),
    val adviceAvailability: AdviceAvailability = AdviceAvailability.VALID,
    val basePolicy: BasePolicyState = BasePolicyState.AUTO_RULES_MET,
    val humanState: HumanApprovalState = HumanApprovalState.NOT_OBTAINED,
    val policyVersion: String = "v1",
)
