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
import com.rosan.installer.domain.engine.model.packageinfo.AppSignatureInfo
import com.rosan.installer.domain.engine.model.packageinfo.InstalledAppInfo
import com.rosan.installer.domain.engine.model.packageinfo.PackageAnalysisResult
import com.rosan.installer.domain.engine.model.packageinfo.PackageIdentityStatus
import com.rosan.installer.domain.engine.model.packageinfo.SignatureCertificateInfo
import com.rosan.installer.domain.engine.model.packageinfo.SignatureMatchStatus
import com.rosan.installer.domain.engine.model.packageinfo.SignatureVerificationStatus as EngineVerificationStatus
import com.rosan.installer.domain.engine.model.source.DataEntity
import com.rosan.installer.domain.engine.model.source.DataType
import com.rosan.installer.domain.engine.model.state.InstallActionType
import com.rosan.installer.domain.session.model.SelectInstallEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Verifies: REQ-IX-FR-001, REQ-IX-FR-002, REQ-IX-SEC-004, REQ-IX-INV-006; ADR-001 Option B. */
class AutoDialogInstallEvidenceMapperTest {
    private val mapper = AutoDialogInstallEvidenceMapper()
    private val knownFacts = AutoDialogInstallEvidenceMapper.Facts(
        versionAction = InstallActionType.REINSTALL,
        platformCompatibility = LocalCompatibility.COMPATIBLE,
        abiCompatibility = LocalCompatibility.INCOMPATIBLE,
        storageSufficient = false,
        targetUserScope = TargetUserScope.CURRENT_USER,
        packageSource = PackageSourceSignal.LOCAL_FILE,
        sourceConfidence = SourceConfidenceSignal.EXACT_CALLER,
        installerCapability = InstallerCapability.SYSTEM_PACKAGE_INSTALLER,
        permissionsAddedSinceInstalled = emptyList(),
        installedIsSystemApp = false,
    )

    @Test
    fun mapsCompleteEvidenceFromTheSelectedApkAndStructuredFacts() {
        val apk = apk()
        val evidence = evidence(apk, facts = knownFacts)

        assertEquals(
            InstallEvidence(
                versionCode = 42L,
                versionName = "4.2",
                minSdk = 26,
                targetSdk = 35,
                declaredPermissions = listOf("android.permission.INTERNET"),
                signatureVerificationStatus = SignatureVerificationStatus.VERIFIED,
                installedSignatureMatch = InstalledSignatureMatch.MATCH,
                installRelation = InstallRelation.UPDATE,
                apkIdentity = ApkIdentity.DIFFERENT,
                hasSharedUid = true,
                isXposedModule = false,
                platformCompatibility = LocalCompatibility.COMPATIBLE,
                abiCompatibility = LocalCompatibility.INCOMPATIBLE,
                storageSufficient = false,
                installMode = InstallModeSignal.AutoDialog,
                targetUserScope = TargetUserScope.CURRENT_USER,
                packageSource = PackageSourceSignal.LOCAL_FILE,
                sourceConfidence = SourceConfidenceSignal.EXACT_CALLER,
                installerCapability = InstallerCapability.SYSTEM_PACKAGE_INSTALLER,
                permissionsAddedSinceInstalled = emptyList(),
                installedIsSystemApp = false,
                targetPackageName = PACKAGE_NAME,
                apkSha256 = APK_HASH,
            ),
            evidence,
        )
        assertEquals(EvidenceCompleteness.COMPLETE, evidence.completeness())
    }

    @Test
    fun distinguishesNewInstallUpgradeAndDowngradeWithoutGuessingMissingComparison() {
        val apk = apk()
        val newInstall = analysis(apk).copy(
            installedAppInfo = null,
            signatureMatchStatus = SignatureMatchStatus.NOT_INSTALLED,
            identityStatus = PackageIdentityStatus.NOT_APPLICABLE,
        )
        val newEvidence = evidence(apk, newInstall, knownFacts.copy(confirmedNotInstalledForTargetUser = true))
        assertEquals(InstallRelation.NEW_INSTALL, newEvidence.installRelation)
        assertEquals(InstalledSignatureMatch.NOT_INSTALLED, newEvidence.installedSignatureMatch)
        assertEquals(ApkIdentity.NOT_APPLICABLE, newEvidence.apkIdentity)

        val upgrade = analysis(apk).copy(installedAppInfo = installedApp().copy(versionCode = 41L))
        val downgrade = analysis(apk).copy(installedAppInfo = installedApp().copy(versionCode = 43L))
        val upgradeFacts = knownFacts.copy(versionAction = InstallActionType.UPGRADE)
        val downgradeFacts = knownFacts.copy(versionAction = InstallActionType.DOWNGRADE_INSTALL_ANYWAY)
        assertEquals(InstallRelation.UPDATE, evidence(apk, upgrade, upgradeFacts).installRelation)
        assertEquals(InstallRelation.DOWNGRADE, evidence(apk, downgrade, downgradeFacts).installRelation)
        assertEquals(InstallRelation.UNKNOWN, evidence(apk, downgrade).installRelation)
    }

    @Test
    fun missingInstalledRecordDoesNotConfirmAbsenceForTheTargetUser() {
        val apk = apk()
        val analysis = analysis(apk).copy(
            installedAppInfo = null,
            signatureMatchStatus = SignatureMatchStatus.NOT_INSTALLED,
            identityStatus = PackageIdentityStatus.NOT_APPLICABLE,
        )
        for (signatureCheckPerformed in listOf(false, true)) {
            val result = evidence(apk, analysis.copy(signatureCheckPerformed = signatureCheckPerformed))
            assertEquals(InstallRelation.UNKNOWN, result.installRelation)
            assertEquals(InstalledSignatureMatch.UNKNOWN_ERROR, result.installedSignatureMatch)
            assertEquals(ApkIdentity.UNKNOWN, result.apkIdentity)
        }
    }

    @Test
    fun confirmedAbsenceDoesNotImplyApkSignatureVerification() {
        val apk = apk()
        val analysis = analysis(apk).copy(
            installedAppInfo = null,
            signatureCheckPerformed = false,
            signatureMatchStatus = SignatureMatchStatus.NOT_INSTALLED,
            identityStatus = PackageIdentityStatus.NOT_APPLICABLE,
        )
        val result = evidence(apk, analysis, knownFacts.copy(confirmedNotInstalledForTargetUser = true))
        assertEquals(InstallRelation.NEW_INSTALL, result.installRelation)
        assertEquals(InstalledSignatureMatch.NOT_INSTALLED, result.installedSignatureMatch)
        assertEquals(SignatureVerificationStatus.UNKNOWN, result.signatureVerificationStatus)
    }

    @Test
    fun confirmedAbsenceConflictingWithAnInstalledRecordDoesNotProduceEvidence() {
        val apk = apk()
        val facts = knownFacts.copy(confirmedNotInstalledForTargetUser = true)
        val result = assertIs<AutoDialogInstallEvidenceMapper.Result.Incomplete>(mapper.map(analysis(apk), apk, facts))
        assertEquals(EvidenceCompleteness.CONTRADICTORY, result.completeness)
    }

    @Test
    fun rejectsMissingUnselectedAndForeignCandidatesEvenWithTheSamePackageName() {
        val apk = apk()
        val analysis = analysis(apk)
        assertIs<AutoDialogInstallEvidenceMapper.Result.OutOfScope>(mapper.map(analysis, null))
        assertIs<AutoDialogInstallEvidenceMapper.Result.OutOfScope>(mapper.map(analysis, apk()))
        assertIs<AutoDialogInstallEvidenceMapper.Result.OutOfScope>(
            mapper.map(analysis.copy(appEntities = listOf(SelectInstallEntity(apk, false))), apk),
        )
        assertIs<AutoDialogInstallEvidenceMapper.Result.OutOfScope>(
            mapper.map(analysis.copy(packageName = "com.example.other"), apk),
        )
    }

    @Test
    fun rejectsSplitContainersAndSplitCandidatesEvenInSingleMode() {
        val containerBase = apk().copy(sourceType = DataType.APKS)
        assertIs<AutoDialogInstallEvidenceMapper.Result.OutOfScope>(mapper.map(analysis(containerBase), containerBase))

        val apk = apk()
        val split = AppEntity.SplitEntity(
            packageName = PACKAGE_NAME,
            data = apk.data,
            splitName = "config.en",
            targetSdk = apk.targetSdk,
            minSdk = apk.minSdk,
            arch = null,
            size = 1L,
            sourceType = DataType.APK,
        )
        val analysis = analysis(apk)
        assertIs<AutoDialogInstallEvidenceMapper.Result.OutOfScope>(
            mapper.map(analysis.copy(appEntities = analysis.appEntities + SelectInstallEntity(split, false)), apk),
        )
        assertIs<AutoDialogInstallEvidenceMapper.Result.OutOfScope>(
            mapper.map(analysis.copy(sessionMode = SessionMode.Batch), apk),
        )
    }

    @Test
    fun unknownPermissionsCannotBecomeAnEmptyOutboundList() {
        val unknown = apk().copy(permissions = null)
        val result = assertIs<AutoDialogInstallEvidenceMapper.Result.Incomplete>(mapper.map(analysis(unknown), unknown))
        assertEquals(EvidenceCompleteness.KEY_UNKNOWN, result.completeness)

        val empty = evidence(apk().copy(permissions = emptyList()))
        assertEquals(emptyList(), empty.declaredPermissions)
        val request = AiRequestBuilder().build(empty).request
        val payload = Json.parseToJsonElement(request.evidencePayloadJson).jsonObject
        assertEquals(JsonArray(emptyList()), payload["declared_permissions"])
    }

    @Test
    fun missingApkHashIsUnreadableDespiteAvailableSignerDigests() {
        val evidence = evidence(apk().copy(fileHash = null))
        assertNull(evidence.apkSha256)
        assertEquals(EvidenceCompleteness.UNREADABLE, evidence.completeness())
    }

    @Test
    fun usesVerifiedCallerHashWhenTheSelectedApkHasNoHash() {
        val apk = apk().copy(fileHash = null)
        val facts = knownFacts.copy(verifiedApkSha256 = APK_HASH)
        val evidence = evidence(apk, facts = facts)
        assertEquals(APK_HASH, evidence.apkSha256)
        assertEquals(EvidenceCompleteness.COMPLETE, evidence.completeness())
        val request = AiRequestBuilder().build(evidence).request
        assertFalse(APK_HASH in request.userPrompt + request.evidencePayloadJson)
    }

    @Test
    fun malformedCallerHashDoesNotResolveUnreadableEvidence() {
        val apk = apk().copy(fileHash = null)
        val facts = knownFacts.copy(verifiedApkSha256 = "g".repeat(64))
        val evidence = evidence(apk, facts = facts)
        assertNull(evidence.apkSha256)
        assertEquals(EvidenceCompleteness.UNREADABLE, evidence.completeness())
    }

    @Test
    fun preservesSelectedApkHashWhenCallerConfirmsItInDifferentCase() {
        val facts = knownFacts.copy(verifiedApkSha256 = APK_HASH.uppercase())
        assertEquals(APK_HASH, evidence(apk(), facts = facts).apkSha256)
    }

    @Test
    fun conflictingApkHashesReturnContradictoryWithoutBuildableEvidence() {
        val apk = apk()
        val facts = knownFacts.copy(verifiedApkSha256 = "f".repeat(64))
        val result = assertIs<AutoDialogInstallEvidenceMapper.Result.Incomplete>(mapper.map(analysis(apk), apk, facts))
        assertEquals(EvidenceCompleteness.CONTRADICTORY, result.completeness)
    }

    @Test
    fun uncheckedSignatureDoesNotTurnCachedMetadataIntoAConfirmedResult() {
        val apk = apk()
        val evidence = evidence(apk, analysis(apk).copy(signatureCheckPerformed = false))
        assertEquals(SignatureVerificationStatus.UNKNOWN, evidence.signatureVerificationStatus)
        assertEquals(InstalledSignatureMatch.UNKNOWN_ERROR, evidence.installedSignatureMatch)

        val missing = apk.copy(signatureInfo = null)
        assertEquals(SignatureVerificationStatus.UNKNOWN, evidence(missing).signatureVerificationStatus)
    }

    @Test
    fun preservesExplicitSignatureAndContentComparisonOutcomes() {
        val apk = apk()
        val mismatch = analysis(apk).copy(
            signatureMatchStatus = SignatureMatchStatus.MISMATCH,
            identityStatus = PackageIdentityStatus.ERROR,
        )
        val mismatched = evidence(apk, mismatch)
        assertEquals(InstalledSignatureMatch.MISMATCH, mismatched.installedSignatureMatch)
        assertEquals(ApkIdentity.ERROR, mismatched.apkIdentity)

        val signingBlock = apk.copy(signatureInfo = signature(EngineVerificationStatus.SIGNING_BLOCK_ONLY))
        assertEquals(SignatureVerificationStatus.SIGNING_BLOCK_ONLY, evidence(signingBlock).signatureVerificationStatus)
        val failed = apk.copy(signatureInfo = signature(EngineVerificationStatus.FAILED))
        assertEquals(SignatureVerificationStatus.FAILED, evidence(failed).signatureVerificationStatus)
    }

    @Test
    fun missingCompatibilityFactsRemainUnknownAndCallsAreDeterministic() {
        val apk = apk().copy(minSdk = "unrecognized")
        val analysis = analysis(apk)
        val first = mapper.map(analysis, apk)
        assertEquals(first, mapper.map(analysis, apk))

        val evidence = assertIs<AutoDialogInstallEvidenceMapper.Result.Mapped>(first).evidence
        assertNull(evidence.minSdk)
        assertEquals(LocalCompatibility.UNKNOWN, evidence.platformCompatibility)
        assertEquals(LocalCompatibility.UNKNOWN, evidence.abiCompatibility)
        assertNull(evidence.storageSufficient)
        assertEquals(PackageSourceSignal.UNSPECIFIED, evidence.packageSource)
        assertEquals(SourceConfidenceSignal.UNKNOWN, evidence.sourceConfidence)
    }

    @Test
    fun mappedEvidenceReachesOnlyTheExistingRequestAllowlist() {
        val apk = apk().copy(versionName = "4.2 $PACKAGE_NAME")
        val prepared = AiRequestBuilder().build(evidence(apk, facts = knownFacts))
        val request = prepared.request
        val payload = Json.parseToJsonElement(request.evidencePayloadJson).jsonObject

        assertEquals(AiRequestBuilder.ALLOWLISTED_LEAF_PATHS, prepared.transmittedPaths)
        assertEquals(AiRequestBuilder.ALLOWLISTED_LEAF_PATHS, payload.keys.map { "evidence.$it" }.toSet())
        assertEquals("VERIFIED", payload.getValue("signature_verification_status").jsonPrimitive.content)
        assertEquals("DIFFERENT", payload.getValue("apk_identity").jsonPrimitive.content)
        val outbound = request.systemPrompt + request.userPrompt + request.evidencePayloadJson
        for (localOnly in listOf(PACKAGE_NAME, APK_HASH, SIGNATURE_HASH, SIGNER_HASH, CERTIFICATE_SUBJECT)) {
            assertFalse(localOnly in outbound, "Local-only value reached the request: $localOnly")
        }
    }

    private fun evidence(
        apk: AppEntity.BaseEntity,
        analysis: PackageAnalysisResult = analysis(apk),
        facts: AutoDialogInstallEvidenceMapper.Facts = AutoDialogInstallEvidenceMapper.Facts(),
    ): InstallEvidence = assertIs<AutoDialogInstallEvidenceMapper.Result.Mapped>(mapper.map(analysis, apk, facts)).evidence

    private fun analysis(apk: AppEntity.BaseEntity) = PackageAnalysisResult(
        packageName = PACKAGE_NAME,
        sessionMode = SessionMode.Single,
        appEntities = listOf(SelectInstallEntity(apk, true)),
        installedAppInfo = installedApp(),
        signatureCheckPerformed = true,
        signatureMatchStatus = SignatureMatchStatus.MATCH,
        identityStatus = PackageIdentityStatus.DIFFERENT,
    )

    private fun installedApp() = InstalledAppInfo(
        packageName = PACKAGE_NAME,
        icon = null,
        label = "Installed app",
        versionCode = 42L,
        versionName = "4.2",
        applicationInfo = null,
        minSdk = 26,
        targetSdk = 35,
    )

    private fun apk() = AppEntity.BaseEntity(
        packageName = PACKAGE_NAME,
        sharedUserId = "local.shared.uid",
        data = object : DataEntity.FileEntity("offline.apk") {
            override fun openChannel(): Nothing = error("Unexpected APK read")
            override fun getInputStream(): Nothing = error("Unexpected APK read")
            override fun getSize(): Nothing = error("Unexpected APK stat")
        },
        versionCode = 42L,
        versionName = "4.2",
        label = null,
        icon = null,
        targetSdk = "35",
        minSdk = "26",
        size = 1L,
        sourceType = DataType.APK,
        permissions = listOf("android.permission.INTERNET"),
        signatureHash = SIGNATURE_HASH,
        signatureInfo = signature(EngineVerificationStatus.VERIFIED),
        fileHash = APK_HASH,
    )

    private fun signature(status: EngineVerificationStatus) = AppSignatureInfo(
        verified = status == EngineVerificationStatus.VERIFIED,
        signerSha256Set = setOf(SIGNER_HASH),
        certificates = listOf(
            SignatureCertificateInfo(
                sha256 = SIGNER_HASH,
                sha1 = "fixture-sha1",
                subject = CERTIFICATE_SUBJECT,
                issuer = "fixture issuer",
                serialNumber = "42",
                validFrom = null,
                validUntil = null,
                publicKeyAlgorithm = null,
                signatureAlgorithm = null,
            ),
        ),
        verificationStatus = status,
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.privatecandidate"
        const val APK_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SIGNATURE_HASH = "fixture-local-signature-digest"
        const val SIGNER_HASH = "fixture-local-signer-digest"
        const val CERTIFICATE_SUBJECT = "fixture-local-certificate-subject"
    }
}
