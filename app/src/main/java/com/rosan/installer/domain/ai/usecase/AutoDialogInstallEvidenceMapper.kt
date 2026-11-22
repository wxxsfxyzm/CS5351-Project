// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.domain.ai.model.ApkIdentity
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
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
import com.rosan.installer.domain.engine.model.install.SessionMode
import com.rosan.installer.domain.engine.model.packageinfo.AppEntity
import com.rosan.installer.domain.engine.model.packageinfo.PackageAnalysisResult
import com.rosan.installer.domain.engine.model.packageinfo.PackageIdentityStatus
import com.rosan.installer.domain.engine.model.packageinfo.SignatureMatchStatus
import com.rosan.installer.domain.engine.model.packageinfo.SignatureVerificationStatus as EngineVerificationStatus
import com.rosan.installer.domain.engine.model.source.DataType
import com.rosan.installer.domain.engine.model.state.InstallActionType

/**
 * Implements: ADR-001 Option B.
 * Requirements: REQ-IX-FR-001, REQ-IX-FR-002, REQ-IX-SEC-004, REQ-IX-INV-006.
 * Converts existing in-memory facts for a plain single APK in AutoDialog.
 */
class AutoDialogInstallEvidenceMapper {
    /**
     * Caller-supplied facts for this candidate and device (ADR-001 Option B, REQ-IX-FR-002).
     * [versionAction] is the existing structured install action when it conveys a known version comparison;
     * leave it absent if another notice replaced the action or the comparison is unavailable.
     * [verifiedApkSha256] must be verified by the caller over the APK bytes of the same selected candidate.
     * The mapper checks its hex representation only; it does not read or verify the APK.
     * [confirmedNotInstalledForTargetUser] requires affirmative absence for this package in the actual target user;
     * a null lookup result without that guarantee is insufficient.
     */
    data class Facts(
        val versionAction: InstallActionType? = null,
        val platformCompatibility: LocalCompatibility = LocalCompatibility.UNKNOWN,
        val abiCompatibility: LocalCompatibility = LocalCompatibility.UNKNOWN,
        val storageSufficient: Boolean? = null,
        val targetUserScope: TargetUserScope = TargetUserScope.UNKNOWN,
        val packageSource: PackageSourceSignal = PackageSourceSignal.UNSPECIFIED,
        val sourceConfidence: SourceConfidenceSignal = SourceConfidenceSignal.UNKNOWN,
        val installerCapability: InstallerCapability = InstallerCapability.UNKNOWN,
        val verifiedApkSha256: String? = null,
        val confirmedNotInstalledForTargetUser: Boolean = false,
        val permissionsAddedSinceInstalled: List<String>? = null,
        val installedIsSystemApp: Boolean? = null,
    )

    /** Local mapping outcomes (ADR-001 Option B, REQ-IX-FR-002, REQ-IX-SEC-004). */
    sealed interface Result {
        data object OutOfScope : Result

        // Unrepresentable or contradictory evidence must not expose a buildable payload.
        data class Incomplete(val completeness: EvidenceCompleteness) : Result

        // Mapping succeeded; the evidence can still be incomplete, and egress gates still apply.
        data class Mapped(val evidence: InstallEvidence) : Result
    }

    /** Maps an explicitly selected candidate from this analysis (ADR-001 Option B, REQ-IX-FR-001/002). */
    fun map(
        analysis: PackageAnalysisResult,
        selectedApk: AppEntity.BaseEntity?,
        facts: Facts = Facts(),
    ): Result {
        if (selectedApk == null ||
            analysis.sessionMode != SessionMode.Single ||
            selectedApk.sourceType != DataType.APK ||
            selectedApk.packageName != analysis.packageName
        ) {
            return Result.OutOfScope
        }

        val candidate = analysis.appEntities.singleOrNull()
        if (candidate == null || !candidate.selected || candidate.app !== selectedApk) return Result.OutOfScope

        val confirmedAbsent = facts.confirmedNotInstalledForTargetUser
        if (confirmedAbsent && analysis.installedAppInfo != null) {
            return Result.Incomplete(EvidenceCompleteness.CONTRADICTORY)
        }

        val candidateHash = selectedApk.fileHash?.takeIf { SHA256_HEX.matches(it) }
        val callerHash = facts.verifiedApkSha256?.takeIf { SHA256_HEX.matches(it) }
        if (candidateHash != null && callerHash != null && !candidateHash.equals(callerHash, ignoreCase = true)) {
            return Result.Incomplete(EvidenceCompleteness.CONTRADICTORY)
        }

        val apkSha256 = candidateHash ?: callerHash
        val permissions = selectedApk.permissions?.toList() ?: return Result.Incomplete(
            if (apkSha256 == null) EvidenceCompleteness.UNREADABLE else EvidenceCompleteness.KEY_UNKNOWN,
        )

        return Result.Mapped(
            InstallEvidence(
                versionCode = selectedApk.versionCode,
                versionName = selectedApk.versionName,
                minSdk = selectedApk.minSdk?.toIntOrNull(),
                targetSdk = selectedApk.targetSdk?.toIntOrNull(),
                declaredPermissions = permissions,
                signatureVerificationStatus = verificationStatus(analysis, selectedApk),
                installedSignatureMatch = installedSignatureMatch(analysis, confirmedAbsent),
                installRelation = installRelation(analysis, facts.versionAction, confirmedAbsent),
                apkIdentity = apkIdentity(analysis, confirmedAbsent),
                hasSharedUid = !selectedApk.sharedUserId.isNullOrEmpty(),
                isXposedModule = selectedApk.isXposedModule,
                platformCompatibility = facts.platformCompatibility,
                abiCompatibility = facts.abiCompatibility,
                storageSufficient = facts.storageSufficient,
                installMode = InstallModeSignal.AutoDialog,
                targetUserScope = facts.targetUserScope,
                packageSource = facts.packageSource,
                sourceConfidence = facts.sourceConfidence,
                installerCapability = facts.installerCapability,
                permissionsAddedSinceInstalled = facts.permissionsAddedSinceInstalled,
                installedIsSystemApp = facts.installedIsSystemApp,
                targetPackageName = selectedApk.packageName,
                apkSha256 = apkSha256,
            ),
        )
    }

    private fun verificationStatus(analysis: PackageAnalysisResult, apk: AppEntity.BaseEntity): SignatureVerificationStatus {
        if (!analysis.signatureCheckPerformed) return SignatureVerificationStatus.UNKNOWN

        return when (apk.signatureInfo?.verificationStatus) {
            EngineVerificationStatus.VERIFIED -> if (apk.name in analysis.signatureAnalysis.verificationFailedFiles) {
                SignatureVerificationStatus.UNKNOWN
            } else {
                SignatureVerificationStatus.VERIFIED
            }

            EngineVerificationStatus.FAILED -> SignatureVerificationStatus.FAILED

            EngineVerificationStatus.SIGNING_BLOCK_ONLY -> SignatureVerificationStatus.SIGNING_BLOCK_ONLY

            null -> SignatureVerificationStatus.UNKNOWN
        }
    }

    private fun installedSignatureMatch(analysis: PackageAnalysisResult, confirmedAbsent: Boolean): InstalledSignatureMatch {
        if (analysis.installedAppInfo == null) {
            return if (confirmedAbsent) InstalledSignatureMatch.NOT_INSTALLED else InstalledSignatureMatch.UNKNOWN_ERROR
        }
        if (!analysis.signatureCheckPerformed) return InstalledSignatureMatch.UNKNOWN_ERROR

        return when (analysis.signatureMatchStatus) {
            SignatureMatchStatus.NOT_INSTALLED -> InstalledSignatureMatch.NOT_INSTALLED
            SignatureMatchStatus.MATCH -> InstalledSignatureMatch.MATCH
            SignatureMatchStatus.MISMATCH -> InstalledSignatureMatch.MISMATCH
            SignatureMatchStatus.ROTATION_COMPATIBLE -> InstalledSignatureMatch.ROTATION_COMPATIBLE
            SignatureMatchStatus.CANDIDATE_ROTATION_UNCONFIRMED -> InstalledSignatureMatch.CANDIDATE_ROTATION_UNCONFIRMED
            SignatureMatchStatus.UNKNOWN_ERROR -> InstalledSignatureMatch.UNKNOWN_ERROR
        }
    }

    private fun installRelation(
        analysis: PackageAnalysisResult,
        versionAction: InstallActionType?,
        confirmedAbsent: Boolean,
    ): InstallRelation {
        val installed = analysis.installedAppInfo
            ?: return if (confirmedAbsent) InstallRelation.NEW_INSTALL else InstallRelation.UNKNOWN
        if (installed.isArchived || installed.isUninstalled || installed.packageName != analysis.packageName) {
            return InstallRelation.UNKNOWN
        }

        return when (versionAction) {
            InstallActionType.UPGRADE, InstallActionType.REINSTALL -> InstallRelation.UPDATE
            InstallActionType.DOWNGRADE_INSTALL_ANYWAY -> InstallRelation.DOWNGRADE
            else -> InstallRelation.UNKNOWN
        }
    }

    private fun apkIdentity(analysis: PackageAnalysisResult, confirmedAbsent: Boolean): ApkIdentity {
        if (analysis.identityStatus == PackageIdentityStatus.ERROR) return ApkIdentity.ERROR
        if (analysis.installedAppInfo == null && !confirmedAbsent) return ApkIdentity.UNKNOWN

        return when (analysis.identityStatus) {
            PackageIdentityStatus.IDENTICAL -> ApkIdentity.IDENTICAL
            PackageIdentityStatus.DIFFERENT -> ApkIdentity.DIFFERENT
            PackageIdentityStatus.NOT_APPLICABLE -> ApkIdentity.NOT_APPLICABLE
            PackageIdentityStatus.ERROR -> ApkIdentity.ERROR
        }
    }

    private companion object {
        val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
    }
}
