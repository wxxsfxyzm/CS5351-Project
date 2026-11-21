// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.ai.model.InstallModeSignal
import com.rosan.installer.domain.settings.model.config.Authorizer

/**
 * Implements: ADR-002.7
 * Enforces: REQ-IX-INV-003, REQ-IX-SEC-003
 *
 * The local approval binding. It binds an approval to the exact installation
 * object so a replacement between evidence read and submission cannot install
 * unapproved bytes (`outbound_metadata_allowlist.local_approval_binding`).
 *
 * Every field is non-nullable and mandatory: a missing binding field must
 * prevent dispatch, never default. Construct through [create], which returns
 * `null` when any input fact is unknown.
 *
 * Fields stay local. The target package name in particular is never sent to
 * the model (PMA-OUT-001, approved).
 */
data class ApprovalBinding(
    /** Stable per request (REQ-IX-FR-001). */
    val operationId: String,
    /** One per analysis attempt (REQ-IX-FR-001). */
    val attemptId: String,
    /** Binds the exact APK bytes; hex, 64 chars (hex form checked by caller). */
    val apkSha256: String,
    /** Binds the object; local only, never transmitted (PMA-OUT-001). */
    val targetPackageName: String,
    /** Binds the target user. */
    val targetUserId: Int,
    /** `AutoDialog` or `AutoNotification`. */
    val installMode: InstallModeSignal,
    /** The authorizer used for the submission; v1 is `None`. */
    val installationBackend: Authorizer,
    /** Normalized hash of the install-affecting configuration. */
    val configSnapshotHash: String,
    /** The effective policy version used for the decision. */
    val policyVersion: String,
) {
    companion object {
        /**
         * Implements: ADR-002.7
         * Enforces: REQ-IX-INV-003
         *
         * Builds a binding from caller-supplied facts. Returns `null` if any
         * fact is unknown, so a missing field is structurally unable to reach
         * dispatch (Q3).
         */
        fun create(
            operationId: String,
            attemptId: String,
            facts: BindingFacts,
        ): ApprovalBinding? {
            val apkSha256 = facts.apkSha256?.takeIf { it.isNotBlank() } ?: return null
            val packageName = facts.targetPackageName?.takeIf { it.isNotBlank() } ?: return null
            val targetUserId = facts.targetUserId ?: return null
            val installMode = facts.installMode ?: return null
            val backend = facts.installationBackend ?: return null
            val configSnapshotHash = facts.configSnapshotHash?.takeIf { it.isNotBlank() } ?: return null
            val policyVersion = facts.policyVersion?.takeIf { it.isNotBlank() } ?: return null
            return ApprovalBinding(
                operationId = operationId,
                attemptId = attemptId,
                apkSha256 = apkSha256,
                targetPackageName = packageName,
                targetUserId = targetUserId,
                installMode = installMode,
                installationBackend = backend,
                configSnapshotHash = configSnapshotHash,
                policyVersion = policyVersion,
            )
        }
    }
}
