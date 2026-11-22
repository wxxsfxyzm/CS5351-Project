// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.core.device.model.Architecture
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.engine.model.packageinfo.AppEntity
import com.rosan.installer.domain.engine.model.packageinfo.PackageAnalysisResult
import com.rosan.installer.domain.engine.model.state.InstallActionType

/**
 * Device facts the deriver needs. Supplied by the DI layer from `Build` and the
 * process user; [UNKNOWN] keeps every derived fact UNKNOWN (conservative).
 */
data class DeviceFacts(
    val sdkInt: Int?,
    val supportedArchitectures: List<Architecture>,
    val currentUserId: Int?,
) {
    companion object {
        val UNKNOWN = DeviceFacts(sdkInt = null, supportedArchitectures = emptyList(), currentUserId = null)
    }
}

/**
 * Implements: ADR-001 Option B
 * Requirements: REQ-IX-FR-002, REQ-IX-INV-006
 *
 * Builds [AutoDialogInstallEvidenceMapper.Facts] from the analysis the
 * installer already ran, so the evidence carries the version relation,
 * platform and ABI compatibility, and confirmed absence. Without these the
 * mapper left them UNKNOWN and DR-05 (ALLOW_AUTO) was unreachable.
 *
 * Every rule fails toward UNKNOWN: a fact is only asserted when the analysis
 * states it affirmatively. Pure and deterministic; no I/O.
 */
object AutoDialogFactsDeriver {
    fun derive(
        analysis: PackageAnalysisResult,
        selectedApk: AppEntity.BaseEntity,
        device: DeviceFacts,
        targetUserId: Int?,
        verifiedApkSha256: String?,
    ): AutoDialogInstallEvidenceMapper.Facts = AutoDialogInstallEvidenceMapper.Facts(
        versionAction = versionAction(analysis, selectedApk),
        platformCompatibility = platform(selectedApk, device),
        abiCompatibility = abi(selectedApk, device),
        verifiedApkSha256 = verifiedApkSha256,
        confirmedNotInstalledForTargetUser = confirmedAbsent(analysis, device, targetUserId),
        permissionsAddedSinceInstalled = permissionsAdded(analysis, selectedApk),
        installedIsSystemApp = analysis.installedAppInfo?.isSystemApp,
    )

    /**
     * For an update, the declared permissions the installed version does not
     * request. Null when there is no installed version or either list is
     * unknown, so "nothing added" is only claimed when both lists were read.
     */
    private fun permissionsAdded(analysis: PackageAnalysisResult, apk: AppEntity.BaseEntity): List<String>? {
        val installed = analysis.installedAppInfo ?: return null
        val held = installed.requestedPermissions ?: return null
        val declared = apk.permissions ?: return null
        val heldSet = held.toSet()
        return declared.filterNot { it in heldSet }.distinct().sorted()
    }

    /** Same comparison as `AnalyzeInstallStateUseCase` step 1; anything else stays null (UNKNOWN). */
    private fun versionAction(analysis: PackageAnalysisResult, apk: AppEntity.BaseEntity): InstallActionType? {
        val installed = analysis.installedAppInfo ?: return null
        return when {
            apk.versionCode > installed.versionCode -> InstallActionType.UPGRADE
            apk.versionCode < installed.versionCode -> InstallActionType.DOWNGRADE_INSTALL_ANYWAY
            installed.isArchived -> InstallActionType.UNARCHIVE
            apk.versionName == installed.versionName -> InstallActionType.REINSTALL
            else -> null
        }
    }

    private fun platform(apk: AppEntity.BaseEntity, device: DeviceFacts): LocalCompatibility {
        val minSdk = apk.minSdk?.toIntOrNull() ?: return LocalCompatibility.UNKNOWN
        val sdk = device.sdkInt ?: return LocalCompatibility.UNKNOWN
        return if (minSdk <= sdk) LocalCompatibility.COMPATIBLE else LocalCompatibility.INCOMPATIBLE
    }

    private fun abi(apk: AppEntity.BaseEntity, device: DeviceFacts): LocalCompatibility = when (val arch = apk.arch) {
        null, Architecture.UNKNOWN -> LocalCompatibility.UNKNOWN

        // No native code: runs on any ABI.
        Architecture.NONE -> LocalCompatibility.COMPATIBLE

        else -> when {
            device.supportedArchitectures.isEmpty() -> LocalCompatibility.UNKNOWN
            arch in device.supportedArchitectures -> LocalCompatibility.COMPATIBLE
            else -> LocalCompatibility.INCOMPATIBLE
        }
    }

    /**
     * The installed-app lookup queries the app's own user and returns null only
     * on NameNotFound, so absence is affirmative only when the install targets
     * that same user.
     */
    private fun confirmedAbsent(analysis: PackageAnalysisResult, device: DeviceFacts, targetUserId: Int?): Boolean = analysis.installedAppInfo == null &&
        device.currentUserId != null &&
        targetUserId == device.currentUserId
}
