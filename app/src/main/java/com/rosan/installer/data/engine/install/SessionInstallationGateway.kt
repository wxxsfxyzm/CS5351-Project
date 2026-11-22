// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.engine.install

import com.rosan.installer.domain.engine.approval.ApprovalBinding
import com.rosan.installer.domain.engine.install.GatewayResult
import com.rosan.installer.domain.engine.install.InstallationGateway
import com.rosan.installer.domain.engine.model.packageinfo.AppEntity
import com.rosan.installer.domain.session.model.ProgressEntity
import com.rosan.installer.domain.session.repository.InstallerSessionRepository
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Implements: ADR-002.6, ADR-002.7
 * Enforces: REQ-IX-FR-007, REQ-IX-FR-008, REQ-IX-INV-003, REQ-IX-INV-005, REQ-IX-ARCH-001
 *
 * The real [InstallationGateway] for one session. The coordinator is its only
 * caller (ARCH-001). It wraps the existing **`session.install(true)`** seam so
 * the whole session/ActionHandler lifecycle (biometric check, analyse,
 * PackageInstaller, notifications, post-install, progress) is unchanged
 * (FR-008); it does not re-implement installation.
 *
 * Two safety properties:
 *
 * 1. **INV-003 second belt.** Before dispatching, the bound
 *    [ApprovalBinding.targetPackageName] must equal the session's current
 *    selected package. A mismatch returns [GatewayResult.Failed] with zero
 *    `session.install` calls (the coordinator already revalidated).
 * 2. **`InstallWaitingUnknownSource` is not terminal.** When the "install
 *    unknown apps" settings return with permission granted, the Activity
 *    re-issues `session.install(false)`; the gateway keeps waiting across the
 *    prompt and classifies only on a terminal stage. Only a terminal success
 *    is `Submitted`; a terminal failure is `Failed`; an unreconcilable
 *    completion is `ResultUnknown`.
 *
 * The gateway holds no AI, policy, or privileged capability beyond the
 * session it was constructed with (per session, not a singleton).
 */
class SessionInstallationGateway(
    private val session: InstallerSessionRepository,
    private val resultTimeoutMillis: Long = DEFAULT_RESULT_TIMEOUT_MILLIS,
) : InstallationGateway {

    override suspend fun submit(binding: ApprovalBinding): GatewayResult {
        // INV-003 second belt: never dispatch a different package than bound.
        val currentPackage = session.analysisResults.firstOrNull()
            ?.appEntities?.firstOrNull { it.selected }?.app?.packageName
        if (currentPackage == null || currentPackage != binding.targetPackageName) {
            return GatewayResult.Failed(
                "bound package ${binding.targetPackageName} is not the session's current package",
            )
        }

        // Fire-and-forget dispatch through the existing seam.
        session.install(true)

        // Classify only on a terminal stage emitted after the dispatch. The
        // progress flow is a StateFlow, so its current value is replayed on
        // collection; drop that replay and any non-terminal stage, then take
        // the first terminal one the session emits.
        val terminal = withTimeoutOrNull(resultTimeoutMillis) {
            session.progress
                .drop(1)
                .mapNotNull { it.toTerminalInstallStage() }
                .first()
        }

        return when (terminal) {
            TerminalInstall.Success -> GatewayResult.Submitted
            TerminalInstall.Failure -> GatewayResult.Failed("session reported an install failure")
            null -> GatewayResult.ResultUnknown
        }
    }

    private enum class TerminalInstall { Success, Failure }

    /**
     * Maps a [ProgressEntity] to a terminal outcome, or `null` when it is not
     * terminal. `InstallWaitingUnknownSource` is explicitly **not** terminal:
     * the gateway keeps waiting for the continuation.
     */
    private fun ProgressEntity.toTerminalInstallStage(): TerminalInstall? = when (this) {
        is ProgressEntity.InstallSuccess,
        is ProgressEntity.InstallCompleted,
        -> TerminalInstall.Success

        is ProgressEntity.InstallFailed,
        is ProgressEntity.InstallAnalysedFailed,
        -> TerminalInstall.Failure

        // Waiting for the unknown-source permission is a wait, not a failure.
        is ProgressEntity.InstallWaitingUnknownSource -> null

        else -> null
    }

    companion object {
        /** Upper bound on waiting for a terminal stage before reporting unknown. */
        const val DEFAULT_RESULT_TIMEOUT_MILLIS = 5 * 60 * 1000L
    }
}
