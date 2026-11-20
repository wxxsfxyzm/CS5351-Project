// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.InstallEvidence
import com.rosan.installer.domain.ai.model.code
import com.rosan.installer.domain.ai.repository.AIRiskAdvisor
import com.rosan.installer.domain.ai.repository.ModelRequestGate

/**
 * Implements: ADR-001; ADR-002.2; ADR-002.6
 * Enforces: REQ-IX-FR-003, REQ-IX-INV-001, REQ-IX-INV-004, REQ-IX-INV-006
 *
 * One-call entry point for the advisory path:
 *
 *   [ModelRequestGate] -> [AiRequestBuilder] -> [AIRiskAdvisor] -> [Status]
 *
 * This class **only reports**. It never decides whether to install, never
 * proposes a disposition, and never routes to a human: those belong to the
 * deterministic policy layer, which reads [Status] and decides
 * (`architecture-blueprint.md` section 3, REQ-IX-INV-006).
 *
 * It depends on the [AIRiskAdvisor] interface, not on a concrete provider,
 * so the data-layer implementation (or a test double) can be supplied
 * without changing this class (REQ-IX-MOD-001).
 *
 * Every failure is a [Status.Failure] carrying a [FailureCode] from
 * `runtime-policy-catalog.md` section 3, so the policy layer can tell
 * "network down" (F-02/F-03) from "model answered badly" (F-05) without
 * re-inspecting exception types. There is no path that yields
 * [AssessmentResult.Valid] from a failure (REQ-IX-INV-004).
 */
class RequestInstallRiskAssessmentUseCase(
    private val builder: AiRequestBuilder,
    private val advisor: AIRiskAdvisor,
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
        /** 0 for a known preflight rejection; otherwise 1 advisory attempt, not proof of delivery. */
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

    /**
     * One dispatch is the contract: this calls the advisor once and never
     * retries (REQ-IX-COST-001). A request the advisor blocks before
     * transmission (network disabled) consumes no dispatch (F-02).
     */
    suspend fun invoke(evidence: InstallEvidence, installedVersionCode: Long? = null): Status {
        if (!ModelRequestGate.hasCurrentConsent()) {
            val failure = AiAdvisorFailure.ConsentUnavailable()
            return Status(
                assessment = null,
                failure = Status.Failure(failure.code, requireNotNull(failure.message)),
                completeness = evidence.completeness(),
                providerDispatches = 0,
                transmittedPaths = emptySet(),
            )
        }

        val prepared = builder.build(evidence, installedVersionCode)
        val outcome = advisor.assessRequest(prepared.request, prepared.transmittedPaths)

        val assessment = (outcome as? AdvisorOutcome.Ok)
            ?.result
            .let { it as? AssessmentResult.Valid }
        val failure = when {
            outcome is AdvisorOutcome.Ok && assessment == null ->
                Status.Failure(
                    code = outcome.result.code ?: FailureCode.INVALID_OUTPUT,
                    detail = describeParseFailure(outcome.result),
                )

            outcome is AdvisorOutcome.Failure ->
                Status.Failure(
                    code = outcome.failure.code,
                    detail = outcome.failure.message
                        ?: outcome.failure::class.simpleName
                        ?: "failure",
                )

            else -> null
        }

        val blockedBeforeSending = outcome is AdvisorOutcome.Failure &&
            outcome.failure is AiAdvisorFailure.BeforeDispatch

        return Status(
            assessment = assessment,
            failure = failure,
            completeness = prepared.completeness,
            providerDispatches = if (blockedBeforeSending) 0 else 1,
            transmittedPaths = if (blockedBeforeSending) emptySet() else prepared.transmittedPaths,
        )
    }

    private fun describeParseFailure(result: AssessmentResult): String = when (result) {
        is AssessmentResult.Valid -> ""
        is AssessmentResult.Invalid -> result.reason
        is AssessmentResult.Missing -> "response omitted required field '${result.field}'"
    }
}
