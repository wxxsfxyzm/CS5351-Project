// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.install

import com.rosan.installer.domain.engine.approval.ApprovalBinding

/**
 * Implements: ADR-002.6
 * Enforces: REQ-IX-FR-007, REQ-IX-ARCH-001
 *
 * The single authorized submission seam. The coordinator calls [submit] only
 * after revalidation passed and the submission permit was consumed. The
 * gateway performs no AI or policy work and holds no model or network
 * capability; it is the *only* interface allowed to reach the installer
 * backend, and it may not be reachable by UI, AI, or policy (ARCH-001).
 *
 * The production implementation (later, separately approved) wraps
 * `AppInstallerRepository.doInstallWork` / `InstallerSessionRepository.install`.
 */
interface InstallationGateway {
    /**
     * Submits [binding]'s installation object. The gateway trusts that the
     * coordinator already performed pre-submission revalidation.
     */
    suspend fun submit(binding: ApprovalBinding): GatewayResult
}

/** Implements: ADR-002.6. Enforces: REQ-IX-FR-007, REQ-IX-FR-008. */
sealed interface GatewayResult {
    /** Backend accepted the submission. */
    data object Submitted : GatewayResult

    /** Backend reported a failure. */
    data class Failed(val reason: String) : GatewayResult

    /**
     * Submitted, but the outcome is unknown and cannot be reconciled
     * (`RESULT_UNKNOWN`; FR-008, REQ-IX-REL-003). Automatic replay is
     * forbidden and the result must not be shown as not installed.
     */
    data object ResultUnknown : GatewayResult
}
