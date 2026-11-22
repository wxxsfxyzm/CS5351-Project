// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.fixture

import com.rosan.installer.domain.ai.model.ApkIdentity
import com.rosan.installer.domain.ai.model.InstallEvidence
import com.rosan.installer.domain.ai.model.InstallModeSignal
import com.rosan.installer.domain.ai.model.InstallRelation
import com.rosan.installer.domain.ai.model.InstalledSignatureMatch
import com.rosan.installer.domain.ai.model.InstallerCapability
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.model.PackageSourceSignal
import com.rosan.installer.domain.ai.model.SignatureVerificationStatus
import com.rosan.installer.domain.ai.model.SourceConfidenceSignal
import com.rosan.installer.domain.ai.model.TargetUserScope

/**
 * Implements: ADR-001
 * Enforces: REQ-IX-FR-002, REQ-IX-SEC-004
 *
 * Fixed APK + device evidence sets used by the scenario tests. Each set is a
 * realistic shape of the real thing, written once so every scenario is
 * reproducible and reviewable.
 *
 * All package names and hashes here are synthetic. They exist so the tests
 * can prove those values never leave the device.
 */
object EvidenceFixtures {

    /**
     * A: An ordinary healthy app. Everything known, fully compatible, a
     * modest permission set, matching installed signature for an update.
     */
    val normalUpdate = InstallEvidence(
        versionCode = 20250001,
        versionName = "2.5.1",
        minSdk = 24,
        targetSdk = 35,
        declaredPermissions = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.POST_NOTIFICATIONS",
        ),
        signatureVerificationStatus = SignatureVerificationStatus.VERIFIED,
        installedSignatureMatch = InstalledSignatureMatch.MATCH,
        installRelation = InstallRelation.UPDATE,
        apkIdentity = ApkIdentity.DIFFERENT,
        hasSharedUid = false,
        isXposedModule = false,
        platformCompatibility = LocalCompatibility.COMPATIBLE,
        abiCompatibility = LocalCompatibility.COMPATIBLE,
        storageSufficient = true,
        installMode = InstallModeSignal.AutoDialog,
        targetUserScope = TargetUserScope.CURRENT_USER,
        packageSource = PackageSourceSignal.STORE,
        sourceConfidence = SourceConfidenceSignal.EXACT_CALLER,
        installerCapability = InstallerCapability.SYSTEM_PACKAGE_INSTALLER,
        targetPackageName = "com.example.notes",
        apkSha256 = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
    )

    /** B: A new install of a small game. Not installed before, so no relation to compare. */
    val normalNewInstall = normalUpdate.copy(
        installRelation = InstallRelation.NEW_INSTALL,
        installedSignatureMatch = InstalledSignatureMatch.NOT_INSTALLED,
        apkIdentity = ApkIdentity.NOT_APPLICABLE,
        versionName = "1.0.0",
        targetPackageName = "com.example.puzzle",
    )

    /**
     * C: Incompatible with the device. The APK needs a newer platform than
     * the device offers and ships no ABI the device can run. Only the derived
     * compatibility result is known; the raw platform level stays local and
     * is not part of the evidence at all (NFC-OUT-006).
     */
    val incompatible = normalUpdate.copy(
        versionCode = 90000001,
        versionName = "4.0.0-beta",
        minSdk = 34,
        targetSdk = 35,
        platformCompatibility = LocalCompatibility.INCOMPATIBLE,
        abiCompatibility = LocalCompatibility.INCOMPATIBLE,
        storageSufficient = false,
        targetPackageName = "com.example.modern",
    )

    /** D: Compatible platform, but an ABI the device cannot run. */
    val abiMismatch = normalUpdate.copy(
        versionName = "3.1.0",
        abiCompatibility = LocalCompatibility.INCOMPATIBLE,
        declaredPermissions = listOf("android.permission.INTERNET"),
        targetPackageName = "com.example.neonengine",
    )

    /**
     * E: High permission risk. SMS, call log, accessibility, and overlay
     * permissions, an Xposed module, and a shared UID. All evidence is
     * present, so the only adverse signal is the permission profile.
     */
    val highPermissionRisk = normalUpdate.copy(
        versionCode = 70000012,
        versionName = "7.0.12",
        declaredPermissions = listOf(
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_CALL_LOG",
            "android.permission.READ_CONTACTS",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.POST_NOTIFICATIONS",
        ),
        hasSharedUid = true,
        isXposedModule = true,
        installRelation = InstallRelation.NEW_INSTALL,
        installedSignatureMatch = InstalledSignatureMatch.NOT_INSTALLED,
        targetPackageName = "com.example.toolbox",
    )

    /** F: A downgrade with a signature mismatch. Both are hard local signals. */
    val downgradeSignatureMismatch = normalUpdate.copy(
        versionCode = 100,
        versionName = "0.9.0",
        targetSdk = 29,
        installRelation = InstallRelation.DOWNGRADE,
        installedSignatureMatch = InstalledSignatureMatch.MISMATCH,
        signatureVerificationStatus = SignatureVerificationStatus.SIGNING_BLOCK_ONLY,
        targetPackageName = "com.example.banking",
    )

    /**
     * G: Missing information. The APK was readable (a hash is present) but
     * the platform level, compatibility results, and storage state are
     * unknown. Expect `KEY_UNKNOWN` completeness and the model told so.
     */
    val missingCompatibility = InstallEvidence(
        versionCode = 12,
        versionName = "1.2",
        declaredPermissions = listOf("android.permission.INTERNET"),
        signatureVerificationStatus = SignatureVerificationStatus.VERIFIED,
        installedSignatureMatch = InstalledSignatureMatch.NOT_INSTALLED,
        installRelation = InstallRelation.NEW_INSTALL,
        targetPackageName = "com.example.mystery",
        apkSha256 = "0000000000000000000000000000000000000000000000000000000000000001",
    )

    /** H: Almost nothing known. Only a version code survived parsing. */
    val nearlyEmpty = InstallEvidence(
        versionCode = 1,
        targetPackageName = "com.example.broken",
        apkSha256 = "0000000000000000000000000000000000000000000000000000000000000002",
    )

    /** I: The APK could not be read at all. No hash, so `UNREADABLE`. */
    val unreadable = InstallEvidence(
        versionName = "?",
        targetPackageName = "com.example.unreadable",
        apkSha256 = null,
    )

    /** J: Too many permissions; the list is capped and marked truncated. */
    val truncatedPermissionList = normalUpdate.copy(
        declaredPermissions = (1..300).map { "com.example.permission.CUSTOM_$it" },
        targetPackageName = "com.example.hugeperm",
    )

    /**
     * Every scenario in one list, ordered from unremarkable to degraded.
     * `AdvisorScenarioTest` and the live test both iterate this.
     */

    /**
     * K: A routine update of a permission-heavy app that adds no permission
     * (prompt v1.3). The user already runs the installed version with all of
     * these, so the risk is judged on what changed.
     */
    val heavyUpdateNoNewPermissions = normalUpdate.copy(
        versionCode = 26030100,
        versionName = "6.8.5",
        declaredPermissions = listOf(
            "android.permission.INTERNET",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_CONTACTS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.READ_PHONE_STATE",
            "android.permission.POST_NOTIFICATIONS",
        ),
        permissionsAddedSinceInstalled = emptyList(),
        installedIsSystemApp = false,
        targetPackageName = "com.example.community",
    )

    /**
     * L: An update of a preinstalled system/vendor component with a matching
     * signature and no added permission (prompt v1.3).
     */
    val systemAppUpdate = normalUpdate.copy(
        versionCode = 15000210,
        versionName = "15.0.2.10",
        declaredPermissions = listOf(
            "android.permission.INTERNET",
            "android.permission.READ_LOGS",
            "android.permission.READ_PRIVILEGED_PHONE_STATE",
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.INTERACT_ACROSS_USERS",
            "miui.permission.USE_INTERNAL_GENERAL_API",
        ),
        permissionsAddedSinceInstalled = emptyList(),
        installedIsSystemApp = true,
        targetPackageName = "com.example.vendor.service",
    )

    /**
     * M: A vendor utility (calculator-shaped) reinstalled from /data/app. It
     * declares privileged permissions it is never granted and adds none.
     */
    val vendorUtilityReinstall = normalUpdate.copy(
        versionCode = 317000041,
        versionName = "17.4.1",
        minSdk = 29,
        declaredPermissions = listOf(
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.GET_TASKS",
            "android.permission.INTERACT_ACROSS_USERS_FULL",
            "android.permission.INTERNET",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.VIBRATE",
            "android.permission.WRITE_SECURE_SETTINGS",
            "com.miui.securitycenter.permission.SYSTEM_PERMISSION_DECLARE",
        ),
        apkIdentity = ApkIdentity.IDENTICAL,
        permissionsAddedSinceInstalled = emptyList(),
        installedIsSystemApp = false,
        targetPackageName = "com.example.calculator",
    )

    /** Update-relative scenarios for prompt v1.3; kept separate from [all] so its counts stay fixed. */
    val updateRelative: List<Scenario> = listOf(
        Scenario("K: heavy update, no new permissions", heavyUpdateNoNewPermissions),
        Scenario("L: system app update", systemAppUpdate),
        Scenario("M: vendor utility reinstall", vendorUtilityReinstall),
    )

    val all: List<Scenario> = listOf(
        Scenario("A: normal update", normalUpdate),
        Scenario("B: normal new install", normalNewInstall),
        Scenario("C: incompatible platform and ABI", incompatible),
        Scenario("D: ABI mismatch only", abiMismatch),
        Scenario("E: high permission risk", highPermissionRisk),
        Scenario("F: downgrade with signature mismatch", downgradeSignatureMismatch),
        Scenario("G: missing compatibility information", missingCompatibility),
        Scenario("H: nearly no evidence", nearlyEmpty),
        Scenario("I: unreadable APK", unreadable),
        Scenario("J: oversized permission list", truncatedPermissionList),
    )

    data class Scenario(val name: String, val evidence: InstallEvidence)
}
