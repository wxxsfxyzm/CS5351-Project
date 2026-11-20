// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.repository

import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.AssessmentResult

/**
 * Implements: ADR-001; ADR-002.2
 * Enforces: REQ-IX-MOD-001, REQ-IX-INV-001, REQ-IX-QUAL-001, REQ-IX-SEC-001
 *
 * The advisory boundary. Implementations may reach the model service and
 * nothing else: no installer backend, no privileged provider, no session
 * repository, no `ActionHandler` (`permission-model.md` section 9.2).
 *
 * The return type cannot express an install decision, so a model response
 * cannot approve anything (REQ-IX-INV-001).
 */
interface AIRiskAdvisor {
    /**
     * Performs at most **one** provider dispatch for [request]. No
     * automatic retry (REQ-IX-COST-001).
     *
     * @param knownEvidencePaths the `evidence.*` paths [request] actually
     *   carries, as reported by `AiRequestBuilder`. The implementation
     *   rejects a citation outside this set as fabricated
     *   (REQ-IX-QUAL-001). It is a parameter rather than something the
     *   implementation derives, so the advisor never needs to re-parse the
     *   payload it was handed.
     */
    suspend fun assessRequest(
        request: AiInstallRiskRequest,
        knownEvidencePaths: Set<String>,
    ): AdvisorOutcome
}

/**
 * Implements: ADR-001; ADR-002.2
 * Enforces: REQ-IX-FR-003, REQ-IX-MOD-001
 *
 * Model-service adapter. One implementation per provider; the advisor
 * depends only on this interface so the provider is replaceable without
 * touching the policy gate (REQ-IX-MOD-001).
 */
interface AiModelClient {
    val modelId: String

    /**
     * Sends [request] once and returns the raw response body text.
     * Throws [com.rosan.installer.domain.ai.model.AiAdvisorFailure] on
     * transport, HTTP, or size failures.
     */
    suspend fun complete(request: AiInstallRiskRequest): String
}

/**
 * Implements: ADR-001; ADR-002.5
 * Enforces: REQ-IX-SEC-004
 *
 * Egress gate. Never call the model when this returns `false`
 * (`AGENTS.md`: network requests stay disabled when the in-app setting
 * is off). An implementation must fail closed: an unreadable preference
 * means "not allowed", never "allowed by default".
 */
interface AiNetworkGate {
    suspend fun isEgressAllowed(): Boolean
}

/**
 * Implements: ADR-002.2
 * Enforces: REQ-IX-FR-004, REQ-IX-QUAL-001
 *
 * Structural and semantic validation of the raw model response.
 */
interface AiOutputValidator {
    /** @return a validated assessment, or an explicit failure. Never a default. */
    fun parse(
        rawResponse: String,
        modelId: String,
        promptVersion: String,
        knownEvidencePaths: Set<String>,
    ): AssessmentResult
}
