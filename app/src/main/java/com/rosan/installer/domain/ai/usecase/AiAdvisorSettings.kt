// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

/**
 * Implements: ADR-002.5
 * Enforces: REQ-IX-FR-011, REQ-IX-FR-012, REQ-IX-SEC-004
 *
 * Pure decisions shared by the settings UI, the consent dialog, and the
 * assessment path. Keeping them here (not in Compose) makes the
 * settings-to-gate matrix testable without Robolectric (BUG-003).
 */
object AiAdvisorSettings {

    /**
     * The consent dialog lists exactly these fields, generated from
     * [AiRequestBuilder.ALLOWLISTED_LEAF_PATHS] so it cannot drift from what is
     * actually transmitted (REQ-IX-FR-011).
     */
    fun consentAllowlist(): List<String> = AiRequestBuilder.ALLOWLISTED_LEAF_PATHS.toList()

    /**
     * Why the advisor will not dispatch, if any. `null` means a dispatch is
     * permitted (all deterministic preconditions satisfied).
     *
     * The authorization boundary is unchanged: this only decides whether the
     * *advisory* call may happen; the deterministic policy decides the install.
     */
    fun unavailableReason(input: GateInput): GateUnavailable? = when {
        !input.advisorEnabled -> GateUnavailable.AdvisorOff
        !input.networkAllowed -> GateUnavailable.NetworkOff
        !input.endpointConfigured -> GateUnavailable.Unconfigured
        !input.consentGranted -> GateUnavailable.ConsentOff
        else -> null
    }

    /** Whether an advisory dispatch is permitted for this input. */
    fun allowsDispatch(input: GateInput): Boolean = unavailableReason(input) == null
}

/** The four switches that gate an advisory call (FR-011, FR-012, SEC-004). */
data class GateInput(
    val advisorEnabled: Boolean,
    val networkAllowed: Boolean,
    val endpointConfigured: Boolean,
    val consentGranted: Boolean,
)

/** Distinct reasons the advisory call is withheld; each maps to REQUIRE_REVIEW. */
enum class GateUnavailable {
    AdvisorOff,
    NetworkOff,
    Unconfigured,
    ConsentOff,
}
