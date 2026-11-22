// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import android.app.Activity
import android.content.IntentSender
import com.rosan.installer.core.device.model.Architecture
import com.rosan.installer.domain.engine.model.install.SessionMode
import com.rosan.installer.domain.engine.model.packageinfo.AppEntity
import com.rosan.installer.domain.engine.model.packageinfo.PackageAnalysisResult
import com.rosan.installer.domain.engine.model.packageinfo.PackageIdentityStatus
import com.rosan.installer.domain.engine.model.packageinfo.SignatureMatchStatus
import com.rosan.installer.domain.engine.model.source.DataEntity
import com.rosan.installer.domain.engine.model.source.DataType
import com.rosan.installer.domain.session.model.ConfirmationDetails
import com.rosan.installer.domain.session.model.ConfirmationRequestType
import com.rosan.installer.domain.session.model.ConfirmationState
import com.rosan.installer.domain.session.model.InstallResult
import com.rosan.installer.domain.session.model.ProgressEntity
import com.rosan.installer.domain.session.model.SelectInstallEntity
import com.rosan.installer.domain.session.model.UnarchiveErrorInfo
import com.rosan.installer.domain.session.model.UnarchiveInfo
import com.rosan.installer.domain.session.model.UninstallInfo
import com.rosan.installer.domain.session.repository.InstallerSessionRepository
import com.rosan.installer.domain.settings.model.config.ConfigModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Verifies: REQ-IX-FR-007, REQ-IX-INV-003 (test double)
 *
 * Minimal [InstallerSessionRepository] for gateway/facts tests. It exposes the
 * members the gateway and facts provider use (`analysisResults`, `config`,
 * `progress`, `install`) and records `install` calls. Unused members throw so a
 * test fails loudly if the gateway touches something it should not.
 */
class FakeInstallerSession(
    override var analysisResults: List<PackageAnalysisResult> = emptyList(),
    initialProgress: ProgressEntity = ProgressEntity.Ready,
) : InstallerSessionRepository {
    override val id: String = "fake-session"
    override var error: Throwable = Throwable("unset")
    override var config: ConfigModel = com.rosan.installer.domain.settings.model.config.ConfigModel(
        description = "fake",
        authorizer = com.rosan.installer.domain.settings.model.config.Authorizer.None,
        customizeAuthorizer = "",
        installMode = com.rosan.installer.domain.settings.model.config.InstallMode.AutoDialog,
        installer = null,
        targetUserId = 0,
    )
    override var data: List<DataEntity> = emptyList()
    override var sourceUris: List<String> = emptyList()
    override var referrerUri: String? = null

    override var multiInstallQueue: List<SelectInstallEntity> = emptyList()
    override var multiInstallResults: MutableList<InstallResult> = mutableListOf()
    override var currentMultiInstallIndex: Int = 0
    override var moduleLog: List<String> = emptyList()

    val progressFlow = MutableStateFlow<ProgressEntity>(initialProgress)
    override val progress: Flow<ProgressEntity> = progressFlow
    override val toastEvents: Flow<String> = MutableSharedFlow()
    override val background: Flow<Boolean> = MutableStateFlow(false)
    override val closeRequested: StateFlow<Boolean> = MutableStateFlow(false)
    override val uninstallInfo: StateFlow<UninstallInfo?> = MutableStateFlow(null)
    override val confirmationDetails: StateFlow<ConfirmationDetails?> = MutableStateFlow(null)
    override val confirmationState: StateFlow<ConfirmationState> = MutableStateFlow(ConfirmationState.Idle)
    override val activePlatformSessionIds: StateFlow<Set<Int>> = MutableStateFlow(emptySet())
    override val unarchiveInfo: StateFlow<UnarchiveInfo?> = MutableStateFlow(null)
    override val unarchiveErrorInfo: StateFlow<UnarchiveErrorInfo?> = MutableStateFlow(null)

    /** Every `install(triggerAuth)` call, in order. */
    val installCalls = mutableListOf<Boolean>()

    override fun install(triggerAuth: Boolean) {
        installCalls += triggerAuth
    }

    fun emit(stage: ProgressEntity) {
        progressFlow.value = stage
    }

    override fun resolveInstall(activity: Activity) = error("unused")
    override fun analyse() = error("unused")
    override fun installMultiple(entities: List<SelectInstallEntity>, triggerAuth: Boolean) = error("unused")
    override fun resolveUninstall(activity: Activity, packageName: String) = error("unused")
    override fun uninstall(packageName: String) = error("unused")
    override fun resolveConfirmInstall(
        activity: Activity,
        sessionId: Int,
        requestType: ConfirmationRequestType,
        callerUid: Int,
    ) = error("unused")

    override fun approveConfirmation(sessionId: Int, granted: Boolean) = error("unused")
    override fun resolveUnarchive(activity: Activity, packageName: String, intentSender: IntentSender) = error("unused")
    override fun startUnarchive() = error("unused")
    override fun resolveUnarchiveError(activity: Activity, info: UnarchiveErrorInfo) = error("unused")
    override fun openUnarchiveErrorAction() = error("unused")
    override fun reboot(reason: String) = error("unused")
    override fun background(value: Boolean) = error("unused")
    override fun prepareClose() = error("unused")
    override fun cancel() = error("unused")
    override fun close() = error("unused")

    companion object {
        /** A single-APK analysis result with a synced BaseEntity and file hash. */
        fun singleApk(
            packageName: String,
            fileHash: String,
            versionCode: Long = 1L,
        ): PackageAnalysisResult {
            val base = AppEntity.BaseEntity(
                packageName = packageName,
                sharedUserId = null,
                data = DataEntity.FileEntity(path = "/data/local/tmp/$packageName.apk"),
                versionCode = versionCode,
                versionName = "1.0",
                label = packageName,
                icon = null,
                targetSdk = "34",
                minSdk = "24",
                arch = Architecture.ARM64,
                permissions = listOf("android.permission.INTERNET"),
                fileHash = fileHash,
                sourceType = DataType.APK,
            )
            return PackageAnalysisResult(
                packageName = packageName,
                sessionMode = SessionMode.Single,
                appEntities = listOf(SelectInstallEntity(app = base, selected = true)),
                displayIcon = null,
                seedColor = null,
                installedAppInfo = null,
                signatureCheckPerformed = false,
                signatureMatchStatus = SignatureMatchStatus.NOT_INSTALLED,
                identityStatus = PackageIdentityStatus.NOT_APPLICABLE,
            )
        }
    }
}
