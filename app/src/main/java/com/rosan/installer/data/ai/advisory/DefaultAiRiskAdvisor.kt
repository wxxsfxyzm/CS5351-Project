// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.advisory

import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.InstallEvidence
import com.rosan.installer.domain.ai.repository.AIRiskAdvisor
import com.rosan.installer.domain.ai.repository.AiModelClient
import com.rosan.installer.domain.ai.repository.AiNetworkGate
import com.rosan.installer.domain.ai.repository.AiOutputValidator
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Implements: ADR-001; ADR-002.2; ADR-002.5
 * Enforces: REQ-IX-INV-001, REQ-IX-INV-004, REQ-IX-INV-006, REQ-IX-SEC-001,
 *   REQ-IX-SEC-004
 *
 * Default [AIRiskAdvisor] implementation (ADR-001 Option B: the interface is
 * in `domain`, the real model request and JSON handling are in `data`).
 *
 * One assessment is exactly:
 *
 *   network gate -> one provider dispatch -> strict parse -> [AssessmentResult]
 *
 * It returns advisory data only. It holds no installer, session, or
 * privileged capability and cannot approve, submit, or execute anything
 * (`permission-model.md` section 9.1). It also makes no decision: the
 * deterministic policy engine decides what to do with the result
 * (REQ-IX-INV-006, ADR-002.6).
 *
 * Failure policy (REQ-IX-INV-004): a transport failure yields
 * [AdvisorOutcome.Failure]; a reply that cannot be parsed into the fixed
 * format yields [AssessmentResult.Invalid] or [AssessmentResult.Missing].
 * Nothing here converts a failure into a favorable assessment. No automatic
 * retry is performed.
 */
class DefaultAiRiskAdvisor(
    private val modelClient: AiModelClient,
    private val networkGate: AiNetworkGate,
    private val outputValidator: AiOutputValidator,
) : AIRiskAdvisor {

    data class Outcome(
        val result: AdvisorOutcome,
        /** `0` for a known preflight rejection; otherwise `1`, not proof of delivery. */
        val providerDispatches: Int,
    )

    /** Advisory outcome plus the completeness of the evidence that was sent. */
    data class Assessed(
        val outcome: Outcome,
        val completeness: EvidenceCompleteness,
    ) {
        val result: AdvisorOutcome get() = outcome.result
    }

    override suspend fun assessRequest(
        request: AiInstallRiskRequest,
        knownEvidencePaths: Set<String>,
    ): AdvisorOutcome = assessDetailed(request, knownEvidencePaths).result

    /**
     * Same as [assessRequest] but also returns the dispatch counter the audit
     * needs (`runtime-policy-catalog.md` section 6.2).
     */
    suspend fun assessDetailed(
        request: AiInstallRiskRequest,
        knownEvidencePaths: Set<String>,
    ): Outcome {
        val allowed = try {
            networkGate.isEgressAllowed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            return Outcome(
                AdvisorOutcome.Failure(AiAdvisorFailure.NetworkDisabled(t.message ?: "gate error")),
                providerDispatches = 0,
            )
        }
        if (!allowed) {
            return Outcome(
                AdvisorOutcome.Failure(AiAdvisorFailure.NetworkDisabled("network egress is disabled")),
                providerDispatches = 0,
            )
        }

        val raw = try {
            modelClient.complete(request)
        } catch (failure: AiAdvisorFailure) {
            // Diagnostic only: the failure class and its short message (no body, no key).
            Timber.w("AI advisor call failed: %s %s", failure::class.simpleName, failure.message)
            return Outcome(
                AdvisorOutcome.Failure(failure),
                providerDispatches = if (failure is AiAdvisorFailure.BeforeDispatch) 0 else 1,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Timber.w("AI advisor call failed: %s", t::class.simpleName)
            return Outcome(
                AdvisorOutcome.Failure(
                    AiAdvisorFailure.NetworkError(t.message ?: t::class.simpleName ?: "unknown"),
                ),
                providerDispatches = 1,
            )
        }

        // Parsing is total: it returns Valid, Invalid, or Missing, never null
        // and never a default assessment (REQ-IX-INV-004).
        val parsed = outputValidator.parse(
            rawResponse = raw,
            modelId = modelClient.modelId,
            promptVersion = request.promptVersion,
            knownEvidencePaths = knownEvidencePaths,
        )
        if (parsed !is com.rosan.installer.domain.ai.model.AssessmentResult.Valid) {
            Timber.w("AI advisor reply rejected by the validator: %s", parsed)
        }
        return Outcome(AdvisorOutcome.Ok(parsed), providerDispatches = 1)
    }

    /**
     * Builds a request from evidence, assesses it, and reports how complete
     * the evidence was. `completeness` describes the evidence, so it is
     * carried alongside the advisory result rather than folded into it.
     */
    suspend fun assess(evidence: InstallEvidence, builder: AiRequestBuilder): Assessed {
        val prepared = builder.build(evidence)
        return Assessed(
            assessDetailed(prepared.request, prepared.transmittedPaths),
            prepared.completeness,
        )
    }
}
