// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.core.device.model.Architecture
import com.rosan.installer.data.engine.approval.SessionBindingFactsProvider
import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.InstallAssessment
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.ai.repository.AIRiskAdvisor
import com.rosan.installer.domain.ai.repository.ModelRequestGate
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import com.rosan.installer.domain.ai.usecase.ApkContentHasher
import com.rosan.installer.domain.ai.usecase.DeviceFacts
import com.rosan.installer.domain.ai.usecase.GateInput
import com.rosan.installer.domain.ai.usecase.RequestInstallRiskAssessmentUseCase
import com.rosan.installer.domain.engine.model.packageinfo.AppEntity
import com.rosan.installer.domain.engine.model.packageinfo.AppSignatureInfo
import com.rosan.installer.domain.engine.model.packageinfo.PackageAnalysisResult
import com.rosan.installer.domain.engine.model.packageinfo.SignatureMatchStatus
import com.rosan.installer.domain.engine.model.source.DataEntity
import com.rosan.installer.domain.engine.policy.PolicyDisposition
import java.io.File
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Verifies: REQ-IX-FR-002 (evidence completeness), REQ-IX-INV-003 (binding
 * detects a replaced APK), ADR-002.7.
 *
 * The parser never sets `BaseEntity.fileHash`. These tests use an entity whose
 * `fileHash` is null and whose data is a real file, as on a device. Before the
 * fix the evidence was UNREADABLE (DR-03 RECOLLECT) for every APK and the
 * binding had no APK hash.
 */
class ApkHashEvidenceTest {
    private val apk: File = File.createTempFile("evidence", ".apk").apply { writeBytes(ByteArray(4096) { it.toByte() }) }

    @AfterTest
    fun cleanup() {
        apk.delete()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Device-shaped analysis: real file, no precomputed fileHash. */
    private fun deviceAnalysis(): PackageAnalysisResult {
        val template = FakeInstallerSession.singleApk("com.example.a", "a".repeat(64))
        val base = (template.appEntities.first().app as AppEntity.BaseEntity)
            .copy(data = DataEntity.FileEntity(apk.absolutePath), fileHash = null)
        return template.copy(appEntities = listOf(template.appEntities.first().copy(app = base)))
    }

    private val unusableAdvisor = object : AIRiskAdvisor {
        override suspend fun assessRequest(request: AiInstallRiskRequest, knownEvidencePaths: Set<String>): AdvisorOutcome = AdvisorOutcome.Failure(AiAdvisorFailure.InvalidOutput("offline"))
    }

    private val gate = GateInput(advisorEnabled = true, networkAllowed = true, endpointConfigured = true, consentGranted = true)

    private suspend fun decide(
        useCase: PrepareApprovalUseCase,
        analysis: PackageAnalysisResult = deviceAnalysis(),
    ): PrepareApprovalUseCase.Outcome.Decided {
        val session = FakeInstallerSession(analysisResults = listOf(analysis))
        val coordinator = ApprovalCoordinator(
            store = InMemoryApprovalStateStore(),
            ledger = SubmissionLedger(),
            gateway = FakeInstallationGateway(),
            factsProvider = SessionBindingFactsProvider(session),
        )
        val outcome = withContext(ModelRequestGate { true }) {
            useCase.prepare(
                "op-1",
                "attempt-1",
                true,
                gate,
                analysis,
                analysis.appEntities.first().app as AppEntity.BaseEntity,
                coordinator,
                targetUserId = 0,
            )
        }
        return assertIs<PrepareApprovalUseCase.Outcome.Decided>(outcome)
    }

    @Test
    fun hasherReadsFileBytes() = runTest {
        assertEquals(sha256(apk.readBytes()), ApkContentHasher.sha256(DataEntity.FileEntity(apk.absolutePath)))
    }

    @Test
    fun hasherReturnsNullForMissingFile() = runTest {
        assertNull(ApkContentHasher.sha256(DataEntity.FileEntity(apk.absolutePath + ".missing")))
    }

    @Test
    fun deviceShapedApkIsNotUnreadable() = runTest {
        val useCase = PrepareApprovalUseCase(RequestInstallRiskAssessmentUseCase(AiRequestBuilder(), unusableAdvisor))
        val decided = decide(useCase)
        assertNotEquals("DR-03", decided.decision.ruleId, "a readable APK must not be RECOLLECT")
        assertEquals(PolicyDisposition.AWAIT_HUMAN, decided.decision.disposition)
    }

    @Test
    fun withoutHashTheEvidenceIsRecollect() = runTest {
        // Negative control: the pre-fix behaviour (no hash) lands on DR-03.
        val useCase = PrepareApprovalUseCase(
            assessment = RequestInstallRiskAssessmentUseCase(AiRequestBuilder(), unusableAdvisor),
            apkHasher = { null },
        )
        val decided = decide(useCase)
        assertEquals("DR-03", decided.decision.ruleId)
        assertEquals(PolicyDisposition.RECOLLECT, decided.decision.disposition)
    }

    @Test
    fun bindingHashFollowsFileContent() = runTest {
        // INV-003: a file replaced after approval changes the bound hash.
        val session = FakeInstallerSession(analysisResults = listOf(deviceAnalysis()))
        val provider = SessionBindingFactsProvider(session)
        val before = assertNotNull(provider.currentFacts("op-1")).apkSha256
        assertEquals(sha256(apk.readBytes()), before)

        apk.writeBytes(ByteArray(4096) { (255 - it).toByte() })
        val after = assertNotNull(provider.currentFacts("op-1")).apkSha256
        assertNotEquals(before, after, "a replaced APK must change the binding hash")
    }

    /** A verified new install of a plain APK on a compatible device, as analysed on a device. */
    private fun verifiedNewInstall(): PackageAnalysisResult {
        val a = deviceAnalysis()
        val base = (a.appEntities.first().app as AppEntity.BaseEntity).copy(
            signatureInfo = AppSignatureInfo(verified = true, signerSha256Set = setOf("b".repeat(64)), certificates = emptyList()),
        )
        return a.copy(
            appEntities = listOf(a.appEntities.first().copy(app = base)),
            signatureCheckPerformed = true,
            signatureMatchStatus = SignatureMatchStatus.NOT_INSTALLED,
        )
    }

    private val favorableAdvisor = object : AIRiskAdvisor {
        override suspend fun assessRequest(request: AiInstallRiskRequest, knownEvidencePaths: Set<String>): AdvisorOutcome = AdvisorOutcome.Ok(
            AssessmentResult.Valid(
                InstallAssessment(
                    InstallSuccessLikelihood.HIGH,
                    SecurityRisk.LOW,
                    listOf("evidence.target_sdk"),
                    listOf("evidence.target_sdk"),
                    "test-model",
                    "test-prompt",
                ),
            ),
        )
    }

    private val compatibleDevice = DeviceFacts(sdkInt = 35, supportedArchitectures = listOf(Architecture.ARM64), currentUserId = 0)

    @Test
    fun verifiedNewInstallCanReachAllowAuto() = runTest {
        // DR-05 needs complete evidence, platform+ABI COMPATIBLE, NEW_INSTALL and
        // NOT_INSTALLED: all now derived from the analysis instead of left UNKNOWN.
        val useCase = PrepareApprovalUseCase(
            assessment = RequestInstallRiskAssessmentUseCase(AiRequestBuilder(), favorableAdvisor),
            deviceFacts = { compatibleDevice },
        )
        val decided = decide(useCase, verifiedNewInstall())
        assertEquals("DR-05", decided.decision.ruleId)
        assertEquals(PolicyDisposition.EXECUTE_ONCE, decided.decision.disposition)
    }

    @Test
    fun withoutDeviceFactsTheSameInstallNeedsReview() = runTest {
        // Negative control: the pre-fix inputs (no device facts) never reach DR-05.
        val useCase = PrepareApprovalUseCase(
            assessment = RequestInstallRiskAssessmentUseCase(AiRequestBuilder(), favorableAdvisor),
        )
        val decided = decide(useCase, verifiedNewInstall())
        assertNotEquals("DR-05", decided.decision.ruleId)
        assertEquals(PolicyDisposition.AWAIT_HUMAN, decided.decision.disposition)
    }
}
