// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.engine.install.GatewayResult
import com.rosan.installer.domain.engine.install.InstallationGateway

/**
 * Verifies: REQ-IX-FR-007, REQ-IX-ARCH-001 (test double)
 *
 * Records every submission for assertions. It is the only implementation in
 * this slice; the production gateway wrapping the real installer backend is
 * later, separately-approved work. [result] is scriptable so tests can model
 * `Submitted`, `Failed`, and `ResultUnknown`.
 */
class FakeInstallationGateway(
    private val result: GatewayResult = GatewayResult.Submitted,
) : InstallationGateway {
    val submissions = mutableListOf<ApprovalBinding>()

    override suspend fun submit(binding: ApprovalBinding): GatewayResult {
        submissions += binding
        return result
    }
}
