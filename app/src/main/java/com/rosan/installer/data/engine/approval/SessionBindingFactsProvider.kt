// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.engine.approval

import com.rosan.installer.domain.ai.model.InstallModeSignal
import com.rosan.installer.domain.engine.approval.BindingFacts
import com.rosan.installer.domain.engine.approval.BindingFactsProvider
import com.rosan.installer.domain.engine.model.packageinfo.AppEntity
import com.rosan.installer.domain.session.repository.InstallerSessionRepository
import com.rosan.installer.domain.settings.model.config.ConfigModel
import java.security.MessageDigest

/**
 * Implements: ADR-002.7
 * Enforces: REQ-IX-INV-003, REQ-IX-SEC-003
 *
 * Per-session [BindingFactsProvider]. It reads the facts of **one**
 * `InstallerSessionRepository` directly (that session's `analysisResults` and
 * `config`), so two concurrent sessions cannot overwrite each other's facts
 * through a shared mutable source (rev.3 §2.9.1). It is constructed per session
 * (Koin `factory`), not as a singleton.
 *
 * It only observes current local facts; it holds no AI, policy, or backend
 * capability.
 */
class SessionBindingFactsProvider(
    private val session: InstallerSessionRepository,
) : BindingFactsProvider {

    override suspend fun currentFacts(operationId: String): BindingFacts? {
        val analysis = session.analysisResults.firstOrNull() ?: return null
        val base = selectedBaseEntity(analysis.appEntities) ?: return null
        val config = session.config
        return BindingFacts(
            apkSha256 = base.fileHash?.takeIf { SHA256_HEX.matches(it) },
            targetPackageName = base.packageName.takeIf { it.isNotBlank() },
            targetUserId = config.targetUserId,
            installMode = InstallModeSignal.AutoDialog,
            installationBackend = config.authorizer,
            configSnapshotHash = configSnapshotHash(config),
            policyVersion = POLICY_VERSION,
        )
    }

    private fun selectedBaseEntity(entities: List<com.rosan.installer.domain.session.model.SelectInstallEntity>): AppEntity.BaseEntity? = entities.firstOrNull { it.selected }?.app as? AppEntity.BaseEntity

    /** Stable hash of the install-affecting configuration for INV-003 / DR-10. */
    private fun configSnapshotHash(config: ConfigModel): String {
        val material = listOf(
            config.authorizer.value,
            config.customizeAuthorizer,
            config.installMode.name,
            config.targetUserId.toString(),
            config.installerMode.name,
            config.installer ?: "",
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** The D1 rev.6 decision-table version (runtime-policy-catalog). */
        const val POLICY_VERSION = "policy-v1"
        val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
    }
}
