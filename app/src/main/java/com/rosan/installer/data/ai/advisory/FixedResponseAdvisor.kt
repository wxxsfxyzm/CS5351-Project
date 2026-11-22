// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.advisory

import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.InstallAssessment
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.ai.repository.AIRiskAdvisor
import kotlinx.coroutines.CancellationException

/**
 * Implements: ADR-001; ADR-002.2; ADR-002.6
 * Enforces: REQ-IX-MOD-001, REQ-IX-INV-001, REQ-IX-INV-004, REQ-IX-INV-006
 *
 * Deterministic [AIRiskAdvisor] that returns pre-built, fixed results instead
 * of calling a model. It exists so integration tests can drive the whole
 * advisory path offline:
 *
 *   `RequestInstallRiskAssessmentUseCase` -> [AdvisorOutcome] -> `PolicyAdvice`
 *   -> `DeterministicPolicyEngine` / `ApprovalCoordinator`
 *
 * with **no network, no API key, and no consent backend**. This is the
 * fixed-response adapter named in `evaluation-table.md` QA-01 / NQA-06.
 *
 * It is an `AIRiskAdvisor` implementation (ADR-001 Option B: implementation in
 * `data`, interface in `domain`), so no domain type or provider seam changes.
 * Like every advisor it reports only: it holds no installer, session, or
 * privileged capability and can express no approval (REQ-IX-INV-001). A fixed
 * result is never inferred from a failure: [AdvisorOutcome.Failure] and
 * [AssessmentResult.Invalid] / [AssessmentResult.Missing] stay explicit
 * failures (REQ-IX-INV-004).
 *
 * Usage from a test:
 * ```
 * val status = withContext(ModelRequestGate { true }) {
 *     RequestInstallRiskAssessmentUseCase(AiRequestBuilder(), FixedResponseAdvisor.highSuccessLowRisk())
 *         .invoke(evidence)
 * }
 * ```
 */
class FixedResponseAdvisor private constructor(
    script: List<AdvisorOutcome>,
) : AIRiskAdvisor {

    /** Outcomes returned in order; once exhausted the last one repeats. */
    private val script: List<AdvisorOutcome> = script.ifEmpty {
        listOf(AdvisorOutcome.Failure(AiAdvisorFailure.InvalidOutput("no fixed scripted outcome")))
    }

    /** Number of completed [assessRequest] calls. */
    var callCount: Int = 0
        private set

    /** Request passed to the most recent [assessRequest] call. */
    var lastRequest: AiInstallRiskRequest? = null
        private set

    /** Evidence paths passed to the most recent [assessRequest] call. */
    var lastKnownEvidencePaths: Set<String>? = null
        private set

    override suspend fun assessRequest(
        request: AiInstallRiskRequest,
        knownEvidencePaths: Set<String>,
    ): AdvisorOutcome {
        try {
            lastRequest = request
            lastKnownEvidencePaths = knownEvidencePaths
            return script.getOrElse(callCount) { script.last() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            callCount += 1
        }
    }

    companion object {
        /** The fixed model identity used by every fixed assessment. */
        const val MODEL_ID = "fixed-response"

        /** The fixed prompt version used by every fixed assessment. */
        const val PROMPT_VERSION = "fixed-response-1"

        /** A citation that is always on the allowlist (`AiRequestBuilder`). */
        const val EVIDENCE_REF = "evidence.target_sdk"

        /** One fixed result, repeated on every call. */
        fun of(result: AssessmentResult): FixedResponseAdvisor = FixedResponseAdvisor(listOf(AdvisorOutcome.Ok(result)))

        /** Several fixed outcomes in order, for sequential-call scenarios. */
        fun scripted(vararg outcomes: AdvisorOutcome): FixedResponseAdvisor = FixedResponseAdvisor(outcomes.toList())

        /**
         * Fixed valid assessment with HIGH install success likelihood and LOW
         * security risk. The favorable case the policy table may auto-approve.
         */
        fun highSuccessLowRisk(): FixedResponseAdvisor = of(valid(InstallSuccessLikelihood.HIGH, SecurityRisk.LOW))

        /** Fixed valid assessment with a LOW install success likelihood. */
        fun lowSuccess(): FixedResponseAdvisor = of(valid(InstallSuccessLikelihood.LOW, SecurityRisk.LOW))

        /** Fixed valid assessment with a HIGH security risk. */
        fun highRisk(): FixedResponseAdvisor = of(valid(InstallSuccessLikelihood.HIGH, SecurityRisk.HIGH))

        /**
         * Fixed valid assessment that answers UNKNOWN on both axes: a usable
         * result that is not favorable. It is never treated as low risk.
         */
        fun unassessable(): FixedResponseAdvisor = of(valid(InstallSuccessLikelihood.UNKNOWN, SecurityRisk.UNKNOWN))

        /**
         * Fixed parse failure: a body arrived but does not match the fixed
         * format, so the caller reports `FailureCode.INVALID_OUTPUT`.
         */
        fun invalid(
            reason: String = "security_risk has an illegal value: ABSOLUTELY",
        ): FixedResponseAdvisor = of(AssessmentResult.Invalid(reason = reason, field = "security_risk"))

        /** Fixed parse failure: a required field of the fixed format is absent. */
        fun missing(field: String = "install_success_likelihood"): FixedResponseAdvisor = of(AssessmentResult.Missing(field))

        /**
         * Fixed transport-class failure with no usable body, e.g. network
         * disabled before dispatch or a consent denial.
         */
        fun unavailable(
            failure: AiAdvisorFailure = AiAdvisorFailure.NetworkDisabled("fixed response: network disabled"),
        ): FixedResponseAdvisor = FixedResponseAdvisor(listOf(AdvisorOutcome.Failure(failure)))

        private fun valid(
            likelihood: InstallSuccessLikelihood,
            risk: SecurityRisk,
            reason: String = "fixed response for integration testing",
        ): AssessmentResult.Valid = AssessmentResult.Valid(
            InstallAssessment(
                installSuccessLikelihood = likelihood,
                securityRisk = risk,
                reasons = listOf(reason),
                evidenceRefs = listOf(EVIDENCE_REF),
                modelId = MODEL_ID,
                promptVersion = PROMPT_VERSION,
            ),
        )
    }
}
