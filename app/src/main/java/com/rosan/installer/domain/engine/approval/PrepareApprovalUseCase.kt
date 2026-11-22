// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.ai.model.AiReviewModel
import com.rosan.installer.domain.ai.model.AiUnavailable
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.HardBlockCause
import com.rosan.installer.domain.ai.model.InstallEvidence
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.ai.usecase.AiAdvisorSettings
import com.rosan.installer.domain.ai.usecase.ApkContentHasher
import com.rosan.installer.domain.ai.usecase.AutoDialogFactsDeriver
import com.rosan.installer.domain.ai.usecase.AutoDialogInstallEvidenceMapper
import com.rosan.installer.domain.ai.usecase.DeviceFacts
import com.rosan.installer.domain.ai.usecase.GateInput
import com.rosan.installer.domain.ai.usecase.RequestInstallRiskAssessmentUseCase
import com.rosan.installer.domain.engine.model.packageinfo.AppEntity
import com.rosan.installer.domain.engine.model.packageinfo.PackageAnalysisResult
import com.rosan.installer.domain.engine.model.source.DataEntity
import com.rosan.installer.domain.engine.policy.HumanApprovalState
import com.rosan.installer.domain.engine.policy.PolicyAdvice
import com.rosan.installer.domain.engine.policy.PolicyDecision
import com.rosan.installer.domain.engine.policy.PolicyDisposition
import com.rosan.installer.domain.engine.policy.PolicyInput
import com.rosan.installer.domain.engine.policy.PolicyResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Implements: ADR-002.4, ADR-002.6
 * Enforces: REQ-IX-FR-003, REQ-IX-FR-005, REQ-IX-FR-007, REQ-IX-FR-012,
 *           REQ-IX-ARCH-001, REQ-IX-INV-004, REQ-IX-COST-001
 *
 * The single assessment trigger for the prepare dialog. With the advisor **on**
 * it runs exactly one advisory call per operation and routes the result through
 * the [ApprovalCoordinator] (the only path to the gateway, ARCH-001). With the
 * advisor **off** it returns [Outcome.Baseline] immediately — no evidence
 * mapping, no model call, and the coordinator is **never touched** (FR-012,
 * Q9 Option (b)).
 *
 * One trigger, no re-dispatch: a per-operation `started` guard makes a second
 * call for the same operation a no-op ([Outcome.AlreadyStarted]) so
 * recomposition or re-entering the stage cannot cost a second model call
 * (COST-001).
 */
class PrepareApprovalUseCase(
    private val assessment: RequestInstallRiskAssessmentUseCase,
    private val mapper: AutoDialogInstallEvidenceMapper = AutoDialogInstallEvidenceMapper(),
    /** SHA-256 of the selected APK bytes; the parser does not provide it. */
    private val apkHasher: suspend (DataEntity) -> String? = ApkContentHasher::sha256,
    /** SDK, ABIs, and process user; UNKNOWN keeps the derived facts UNKNOWN. */
    private val deviceFacts: () -> DeviceFacts = { DeviceFacts.UNKNOWN },
    /**
     * Budget for the model assessment within one attempt (REQ-IX-PER-001:
     * 15 s from AWAITING_AI to the persisted decision; the call itself is
     * capped at 7 s). The margin leaves room to decide and persist.
     */
    private val assessmentBudget: Duration = 12.seconds,
) {
    /** What the prepare step produced for the caller to render. */
    sealed interface Outcome {
        /** Advisor off or out-of-scope: run the baseline `session.install(true)`. */
        data object Baseline : Outcome

        /** A prior call for this operation already started; do not dispatch again. */
        data object AlreadyStarted : Outcome

        /** Advice is unavailable; REQUIRE_REVIEW, no model call happened. */
        data class Unavailable(val reason: AiUnavailable) : Outcome

        /** The coordinator routed a decision; render it. */
        data class Decided(
            val state: ApprovalState,
            val decision: PolicyResult,
            val model: AiReviewModel?,
            /** Why advice was withheld, if the gate blocked the call (else null). */
            val unavailable: AiUnavailable?,
            /** The engine input; retained so Continue can revalidate with no model call. */
            val baseInput: PolicyInput,
            /** Which hard-block rules fired; shown when the decision is BLOCK. */
            val blockCauses: List<HardBlockCause> = emptyList(),
        ) : Outcome
    }

    private val started = ConcurrentHashMap.newKeySet<String>()

    /**
     * Runs the prepare sequence once for [operationId]. The [coordinator] is the
     * per-session coordinator; it is used only on the advisor-on path.
     */
    suspend fun prepare(
        operationId: String,
        attemptId: String,
        advisorEnabled: Boolean,
        gate: GateInput,
        analysis: PackageAnalysisResult,
        selectedApk: AppEntity.BaseEntity?,
        coordinator: ApprovalCoordinator,
        targetUserId: Int? = null,
    ): Outcome {
        // Advisor off => baseline InstallerX. Do nothing advisory; no coordinator.
        if (!advisorEnabled) return Outcome.Baseline

        // Out-of-scope (splits/modules/multi-APK) => baseline, 0 AI calls.
        val mapped = mapper.map(
            analysis,
            selectedApk,
            selectedApk?.let { apk ->
                AutoDialogFactsDeriver.derive(
                    analysis = analysis,
                    selectedApk = apk,
                    device = deviceFacts(),
                    targetUserId = targetUserId,
                    verifiedApkSha256 = apkHasher(apk.data),
                )
            } ?: AutoDialogInstallEvidenceMapper.Facts(),
        )
        if (mapped !is AutoDialogInstallEvidenceMapper.Result.Mapped) {
            return Outcome.Baseline
        }
        val evidence = mapped.evidence
        val policy = EvaluatedPolicyFactory.from(evidence)
        val blockCauses = EvaluatedPolicyFactory.hardBlockCauses(evidence)
        val compatibility = compatibilityOf(evidence)

        // Gate withheld the call (consent off / unconfigured / network off): do
        // not dispatch, but still run the deterministic engine so the decision
        // and Continue go through the coordinator (never an Unavailable shortcut
        // that bypasses the engine). 0 model dispatches.
        if (!AiAdvisorSettings.allowsDispatch(gate)) {
            if (!started.add(operationId)) return Outcome.AlreadyStarted
            val unavailable = gate.toUnavailable()
            val withheldInput = PolicyInput(
                policy = policy,
                evidence = evidence.completeness(),
                compatibility = compatibility,
                advice = withheldAdvice(gate),
                human = HumanApprovalState.NOT_OBTAINED,
            )
            return route(operationId, attemptId, coordinator, withheldInput, model = null, unavailable, blockCauses)
        }

        // One trigger per operation: guard BEFORE dispatch.
        if (!started.add(operationId)) return Outcome.AlreadyStarted

        // REQ-IX-PER-001: the attempt has a fixed budget. On expiry the call is
        // cancelled and the decision still goes through the engine (DR-08,
        // REQUIRE_REVIEW); nothing is retried automatically.
        val status = withTimeoutOrNull(assessmentBudget) { assessment.invoke(evidence) }
        val reviewModel = status?.assessment?.let { fromAssessment(it) }

        val policyInput = PolicyInput(
            policy = policy,
            evidence = status?.completeness ?: evidence.completeness(),
            compatibility = compatibility,
            advice = when {
                status == null -> PolicyAdvice.Failed(FailureCode.TIMEOUT)
                status.assessment != null -> PolicyAdvice.Valid(status.assessment)
                else -> PolicyAdvice.Failed(status.failure?.code ?: FailureCode.INVALID_OUTPUT)
            },
            human = HumanApprovalState.NOT_OBTAINED,
        )

        // Tell the user why advice is missing; the decision itself is unchanged.
        val adviceUnavailable = when {
            status == null -> AiUnavailable.ModelFailure(FailureCode.TIMEOUT)
            status.assessment != null -> null
            status.failure?.code == FailureCode.INVALID_OUTPUT -> AiUnavailable.InvalidOutput
            else -> AiUnavailable.ModelFailure(status.failure?.code ?: FailureCode.INVALID_OUTPUT)
        }
        return route(operationId, attemptId, coordinator, policyInput, reviewModel, adviceUnavailable, blockCauses)
    }

    private suspend fun route(
        operationId: String,
        attemptId: String,
        coordinator: ApprovalCoordinator,
        input: PolicyInput,
        model: AiReviewModel?,
        unavailable: AiUnavailable?,
        blockCauses: List<HardBlockCause> = emptyList(),
    ): Outcome {
        val outcome = coordinator.decide(operationId, attemptId, input)
        return when (outcome) {
            is ApprovalCoordinator.Outcome.Decided -> Outcome.Decided(
                state = outcome.state,
                decision = outcome.result,
                model = model,
                unavailable = unavailable,
                baseInput = input,
                blockCauses = blockCauses,
            )

            is ApprovalCoordinator.Outcome.Invalidated -> Outcome.Decided(
                state = ApprovalState.INVALIDATED,
                decision = outcome.result,
                model = model,
                unavailable = unavailable,
                baseInput = input,
                blockCauses = blockCauses,
            )

            ApprovalCoordinator.Outcome.PersistenceFailed ->
                Outcome.Unavailable(AiUnavailable.PersistenceFailed)

            else -> Outcome.Decided(
                state = ApprovalState.AWAIT_HUMAN,
                decision = FALLBACK_REVIEW,
                model = model,
                unavailable = unavailable,
                baseInput = input,
                blockCauses = blockCauses,
            )
        }
    }

    /** PolicyAdvice for a gate-withheld call: consent-off vs engine failure code. */
    private fun withheldAdvice(gate: GateInput): PolicyAdvice = when {
        !gate.consentGranted -> PolicyAdvice.Unusable.CONSENT_DECLINED

        !gate.networkAllowed -> PolicyAdvice.Failed(FailureCode.NETWORK_DISABLED)

        // Unconfigured: the call cannot be made; no advice is available.
        else -> PolicyAdvice.Failed(FailureCode.SERVICE_ERROR)
    }

    private fun compatibilityOf(evidence: InstallEvidence): LocalCompatibility {
        val platform = evidence.platformCompatibility
        val abi = evidence.abiCompatibility
        return when {
            platform == LocalCompatibility.INCOMPATIBLE || abi == LocalCompatibility.INCOMPATIBLE ->
                LocalCompatibility.INCOMPATIBLE

            platform == LocalCompatibility.COMPATIBLE && abi == LocalCompatibility.COMPATIBLE ->
                LocalCompatibility.COMPATIBLE

            else -> LocalCompatibility.UNKNOWN
        }
    }

    private fun GateInput.toUnavailable(): AiUnavailable = when {
        !networkAllowed -> AiUnavailable.NetworkOff
        !endpointConfigured -> AiUnavailable.Unconfigured
        !consentGranted -> AiUnavailable.ConsentOff
        else -> AiUnavailable.ModelFailure(FailureCode.NETWORK_DISABLED)
    }

    private fun fromAssessment(valid: AssessmentResult.Valid): AiReviewModel {
        val a = valid.assessment
        val favorable = a.installSuccessLikelihood == InstallSuccessLikelihood.HIGH &&
            a.securityRisk == SecurityRisk.LOW
        return AiReviewModel(
            likelihood = a.installSuccessLikelihood,
            risk = a.securityRisk,
            reasons = a.reasons,
            agreement = favorable,
            modelId = a.modelId,
            promptVersion = a.promptVersion,
        )
    }

    private companion object {
        val FALLBACK_REVIEW = PolicyResult(
            PolicyDecision.REQUIRE_REVIEW,
            PolicyDisposition.AWAIT_HUMAN,
            "DR-08",
            "ADVICE_UNUSABLE",
        )
    }
}
