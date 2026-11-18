// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.model

/**
 * Implements: ADR-001; ADR-002.2
 * Enforces: REQ-IX-FR-004, REQ-IX-QUAL-001, REQ-IX-INV-001
 *
 * The structured advisory assessment parsed from one model response.
 *
 * Shape fixed by `architecture-blueprint.md` §7.1 and the component table
 * (`AIRiskAdvisor` row): `install_success_likelihood`, `security_risk`,
 * reasons, evidence references, model ID, and prompt version. It carries
 * no action, approval, or instruction field, so no instance of it can
 * authorize an install (REQ-IX-INV-001).
 */
data class InstallAssessment(
    val installSuccessLikelihood: InstallSuccessLikelihood,
    val securityRisk: SecurityRisk,
    val reasons: List<String>,
    /** Dotted `evidence.*` paths that were actually transmitted to the model. */
    val evidenceRefs: List<String>,
    val modelId: String,
    val promptVersion: String,
)

/**
 * Implements: ADR-001; ADR-002.2
 * Enforces: REQ-IX-FR-004, REQ-IX-INV-004
 *
 * Result of parsing and validating a model response.
 *
 * Only [Valid] can carry an assessment. [Invalid] and [Missing] are
 * explicit failures (`runtime-policy-catalog.md` F-05 → `REQUIRE_REVIEW` /
 * `AWAIT_HUMAN`). There is deliberately **no** "assume safe" arm and no
 * default value on any field, so a malformed response can never be read as
 * a favorable assessment (REQ-IX-INV-004).
 */
sealed interface AssessmentResult {
    /** The response matched the fixed format and passed every check. */
    data class Valid(val assessment: InstallAssessment) : AssessmentResult

    /**
     * The response was present but unacceptable: not JSON, wrong shape,
     * illegal enum value, fabricated evidence reference, forbidden action
     * field, guarantee language, or oversized content.
     */
    data class Invalid(val reason: String, val field: String? = null) : AssessmentResult

    /** A required field of the fixed format was absent from the response. */
    data class Missing(val field: String) : AssessmentResult
}
