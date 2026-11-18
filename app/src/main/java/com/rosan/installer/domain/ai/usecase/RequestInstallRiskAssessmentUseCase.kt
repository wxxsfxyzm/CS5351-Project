// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.code
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.InstallEvidence

/**
 * Implements: ADR-001; ADR-002.2; ADR-002.6
 * Enforces: REQ-IX-FR-003, REQ-IX-INV-001, REQ-IX-INV-004
 *
 * One-call entry point for the advisory path:
 *
 *   [InstallEvidence] -> [AiRequestBuilder] -> [DefaultAiRiskAdvisor] -> [Status]
 *
 * This class **only reports**. It never decides whether to install, never
 * proposes a disposition, and never routes to a human: those belong to the
 * deterministic policy layer, which reads [Status] and decides
 * (`architecture-blueprint.md` section 3, REQ-IX-INV-006).
 *
 * Every failure is a [Status.Failed] carrying a [FailureCode] from
 * `runtime-policy-catalog.md` section 3, so the policy layer can tell
 * "network down" (F-02/F-03) from "model answered badly" (F-05) without
 * re-inspecting exception types. There is no path that yields
 * [AssessmentResult.Valid] from a failure (REQ-IX-INV-004).
 */
class RequestInstallRiskAssessmentUseCase(
    private val builder: AiRequestBuilder,
    private val advisor: DefaultAiRiskAdvisor,
) {
    /**
     * What the advisory step produced. Exactly one of [assessment] and
     * [failure] is set, and neither arm expresses a decision.
     */
    data class Status(
        /** Present only when the model returned a fully valid assessment. */
        val assessment: AssessmentResult.Valid?,
        /** Present for every failure; null on success. */
        val failure: Failure?,
        val completeness: EvidenceCompleteness,
        /** 1 when a request left the device, 0 when blocked before sending. */
        val providerDispatches: Int,
        val transmittedPaths: Set<String>,
    ) {
        val isUsable: Boolean get() = assessment != null

        /**
         * Failure report for the policy layer. [code] follows
         * `runtime-policy-catalog.md` section 3; [detail] is a short,
         * non-sensitive reason for the audit log.
         */
        data class Failure(
            val code: FailureCode,
            val detail: String,
        )
    }

    suspend fun invoke(evidence: InstallEvidence, installedVersionCode: Long? = null): Status {
        val structured = builder.build(evidence, installedVersionCode)
        val outcome = advisor.assessDetailed(structured.request)
        return when (val result = outcome.result) {
            is AdvisorOutcome.Ok -> {
                val assessment = result.result as? AssessmentResult.Valid
                Status(
                    assessment = assessment,
                    failure = if (assessment == null) {
                        Status.Failure(
                            code = result.result.code ?: FailureCode.INVALID_OUTPUT,
                            detail = describeParseFailure(result.result),
                        )
                    } else {
                        null
                    },
                    completeness = structured.completeness,
                    providerDispatches = outcome.providerDispatches,
                    transmittedPaths = structured.transmittedPaths,
                )
            }

            is AdvisorOutcome.Failure -> Status(
                assessment = null,
                failure = Status.Failure(
                    code = result.failure.code,
                    detail = result.failure.message ?: result.failure::class.simpleName ?: "failure",
                ),
                completeness = structured.completeness,
                providerDispatches = outcome.providerDispatches,
                transmittedPaths = structured.transmittedPaths,
            )
        }
    }

    private fun describeParseFailure(result: AssessmentResult): String = when (result) {
        is AssessmentResult.Valid -> ""
        is AssessmentResult.Invalid -> result.reason
        is AssessmentResult.Missing -> "response omitted required field '${result.field}'"
    }
}
