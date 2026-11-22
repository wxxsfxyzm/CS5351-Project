// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.core.device.model.Architecture
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.engine.approval.FakeInstallerSession
import com.rosan.installer.domain.engine.model.packageinfo.AppEntity
import com.rosan.installer.domain.engine.model.packageinfo.InstalledAppInfo
import com.rosan.installer.domain.engine.model.packageinfo.PackageAnalysisResult
import com.rosan.installer.domain.engine.model.state.InstallActionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-FR-002 (evidence carries the analysed facts), REQ-IX-INV-006
 * (unknown stays unknown).
 */
class AutoDialogFactsDeriverTest {
    private val device = DeviceFacts(sdkInt = 35, supportedArchitectures = listOf(Architecture.ARM64, Architecture.ARM), currentUserId = 0)

    private fun analysis(installedVersion: Long? = null, installedName: String = "1.0"): PackageAnalysisResult {
        val base = FakeInstallerSession.singleApk("com.example.a", "a".repeat(64), versionCode = 5L)
        val installed = installedVersion?.let {
            InstalledAppInfo(
                packageName = "com.example.a",
                icon = null,
                label = "a",
                versionCode = it,
                versionName = installedName,
                applicationInfo = null,
                minSdk = 24,
                targetSdk = 34,
            )
        }
        return base.copy(installedAppInfo = installed)
    }

    private fun apk(a: PackageAnalysisResult) = a.appEntities.first().app as AppEntity.BaseEntity

    private fun derive(a: PackageAnalysisResult, apk: AppEntity.BaseEntity = apk(a), d: DeviceFacts = device, user: Int? = 0) = AutoDialogFactsDeriver.derive(a, apk, d, user, null)

    @Test
    fun versionRelation() {
        assertEquals(InstallActionType.UPGRADE, derive(analysis(installedVersion = 4)).versionAction)
        assertEquals(InstallActionType.DOWNGRADE_INSTALL_ANYWAY, derive(analysis(installedVersion = 6)).versionAction)
        assertEquals(InstallActionType.REINSTALL, derive(analysis(installedVersion = 5)).versionAction)
        // Same code, different name: not a known relation.
        assertNull(derive(analysis(installedVersion = 5, installedName = "other")).versionAction)
        assertNull(derive(analysis()).versionAction)
    }

    @Test
    fun platformCompatibility() {
        val a = analysis()
        assertEquals(LocalCompatibility.COMPATIBLE, derive(a).platformCompatibility)
        assertEquals(LocalCompatibility.INCOMPATIBLE, derive(a, apk(a).copy(minSdk = "36")).platformCompatibility)
        assertEquals(LocalCompatibility.UNKNOWN, derive(a, apk(a).copy(minSdk = null)).platformCompatibility)
        assertEquals(LocalCompatibility.UNKNOWN, derive(a, d = DeviceFacts.UNKNOWN).platformCompatibility)
    }

    @Test
    fun abiCompatibility() {
        val a = analysis()
        assertEquals(LocalCompatibility.COMPATIBLE, derive(a).abiCompatibility)
        assertEquals(LocalCompatibility.COMPATIBLE, derive(a, apk(a).copy(arch = Architecture.NONE)).abiCompatibility)
        assertEquals(LocalCompatibility.INCOMPATIBLE, derive(a, apk(a).copy(arch = Architecture.X86_64)).abiCompatibility)
        assertEquals(LocalCompatibility.UNKNOWN, derive(a, apk(a).copy(arch = Architecture.UNKNOWN)).abiCompatibility)
        assertEquals(LocalCompatibility.UNKNOWN, derive(a, d = DeviceFacts.UNKNOWN).abiCompatibility)
    }

    @Test
    fun permissionsAddedByAnUpdate() {
        // REQ-IX-FR-002: the delta is declared minus the installed version's requested permissions.
        val a = analysis(installedVersion = 4)
        val installed = a.installedAppInfo!!.copy(requestedPermissions = listOf("android.permission.INTERNET"))
        val withInstalled = a.copy(installedAppInfo = installed)
        val apk = apk(a).copy(permissions = listOf("android.permission.INTERNET", "android.permission.READ_SMS"))
        assertEquals(listOf("android.permission.READ_SMS"), derive(withInstalled, apk).permissionsAddedSinceInstalled)
        // Nothing added: an empty list, which is a claim, not unknown.
        val same = apk(a).copy(permissions = listOf("android.permission.INTERNET"))
        assertEquals(emptyList(), derive(withInstalled, same).permissionsAddedSinceInstalled)
    }

    @Test
    fun permissionsAddedIsUnknownUnlessBothListsWereRead() {
        // INV-006: a new install, or an installed version whose permissions were not read, stays null.
        assertNull(derive(analysis()).permissionsAddedSinceInstalled)
        assertNull(derive(analysis(installedVersion = 4)).permissionsAddedSinceInstalled)
    }

    @Test
    fun systemAppFlagFollowsTheInstalledPackage() {
        val a = analysis(installedVersion = 4)
        assertEquals(false, derive(a).installedIsSystemApp)
        assertEquals(true, derive(a.copy(installedAppInfo = a.installedAppInfo!!.copy(isSystemApp = true))).installedIsSystemApp)
        assertNull(derive(analysis()).installedIsSystemApp)
    }

    @Test
    fun absenceOnlyForTheLookedUpUser() {
        assertTrue(derive(analysis()).confirmedNotInstalledForTargetUser)
        // Installed, another target user, or unknown process user: not confirmed.
        assertFalse(derive(analysis(installedVersion = 4)).confirmedNotInstalledForTargetUser)
        assertFalse(derive(analysis(), user = 10).confirmedNotInstalledForTargetUser)
        assertFalse(derive(analysis(), d = DeviceFacts.UNKNOWN).confirmedNotInstalledForTargetUser)
    }
}
