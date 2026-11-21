// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.ai.model.InstallModeSignal
import com.rosan.installer.domain.settings.model.config.Authorizer

/**
 * Implements: ADR-002.7
 * Enforces: REQ-IX-INV-003
 *
 * The raw inputs an [ApprovalBinding] is built from. A `null` field means
 * "not currently known"; [ApprovalBinding.create] maps any null to a null
 * binding, so a missing fact blocks dispatch instead of defaulting.
 */
data class BindingFacts(
    val apkSha256: String?,
    val targetPackageName: String?,
    val targetUserId: Int?,
    val installMode: InstallModeSignal?,
    val installationBackend: Authorizer?,
    val configSnapshotHash: String?,
    val policyVersion: String?,
)

/**
 * Implements: ADR-002.7
 * Enforces: REQ-IX-INV-003, REQ-IX-SEC-003
 *
 * Re-reads the current installation facts so the coordinator can rebuild an
 * [ApprovalBinding] and compare it field by field to the stored bound object.
 * The coordinator reads this provider at least twice on Continue: once to
 * build the binding and again immediately before acquiring the submission
 * permit. Reusing the first snapshot would let an APK replacement or config
 * change between evidence read and submission go undetected (INV-003,
 * ADR-002.7).
 *
 * The provider only observes current local facts; it holds no AI, policy, or
 * backend capability.
 */
fun interface BindingFactsProvider {
    /** Current facts for [operationId]. Called at least twice on Continue. */
    suspend fun currentFacts(operationId: String): BindingFacts?
}
