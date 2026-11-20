// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.data.ai.advisory.DefaultAiRiskAdvisor
import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.FailureCode
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
import com.rosan.installer.domain.ai.repository.AiModelClient
import com.rosan.installer.domain.ai.repository.AiNetworkGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies: REQ-IX-INV-004, REQ-IX-REL-001
 * Offline and deterministic: scripted clients, no network.
 *
 * Each test is one row of the `runtime-policy-catalog.md` section 3 failure
 * catalog. The assertion is always the same shape: the advisory step reports
 * a failure status with the right code, consumes the right number of
 * dispatches, and **never** produces a usable assessment. What the
 * deterministic policy does with that status is out of scope here.
 */
class AdvisorFailureReportingTest {

    private val evidence = InstallEvidence(
        versionCode = 42L,
        versionName = "1.4.2",
        minSdk = 26,
        targetSdk = 35,
        declaredPermissions = listOf("android.permission.INTERNET"),
        signatureVerificationStatus = SignatureVerificationStatus.VERIFIED,
        installedSignatureMatch = InstalledSignatureMatch.MATCH,
        installRelation = InstallRelation.UPDATE,
        hasSharedUid = false,
        isXposedModule = false,
        platformCompatibility = LocalCompatibility.COMPATIBLE,
        abiCompatibility = LocalCompatibility.COMPATIBLE,
        storageSufficient = true,
        installMode = InstallModeSignal.AutoNotification,
        targetUserScope = TargetUserScope.CURRENT_USER,
        packageSource = PackageSourceSignal.DOWNLOADED_FILE,
        sourceConfidence = SourceConfidenceSignal.REFERRER_HEURISTIC,
        installerCapability = InstallerCapability.SYSTEM_PACKAGE_INSTALLER,
        targetPackageName = "com.example.app",
        apkSha256 = "abc123",
    )

    private class Client(private val behaviour: suspend () -> String) : AiModelClient {
        override val modelId = "stub"
        override suspend fun complete(request: AiInstallRiskRequest): String = behaviour()
    }

    private class Gate(private val allowed: Boolean) : AiNetworkGate {
        override suspend fun isEgressAllowed() = allowed
    }

    private fun useCase(client: AiModelClient, allowed: Boolean = true) = RequestInstallRiskAssessmentUseCase(
        builder = AiRequestBuilder(),
        advisor = DefaultAiRiskAdvisor(client, Gate(allowed), StrictAiOutputValidator()),
    )

    private val validReply = """
        {"install_success_likelihood":"HIGH","security_risk":"LOW",
         "reasons":["target_sdk 35 is current"],"evidence_refs":["evidence.target_sdk"]}
    """.trimIndent()

    /** Verifies: REQ-IX-INV-004 (happy path: one dispatch, usable status). */
    @Test
    fun validReply_reportsUsableStatus() = runTest {
        val status = useCase(Client { validReply }).invoke(evidence)
        assertTrue(status.isUsable)
        assertNull(status.failure)
        assertEquals(1, status.providerDispatches)
    }

    /** F-02: network off. Blocked before sending, so 0 dispatches. */
    @Test
    fun networkDisabled_reportsNetworkDisabledWithZeroDispatches() = runTest {
        val status = useCase(Client { validReply }, allowed = false).invoke(evidence)
        assertTrue(!status.isUsable)
        assertNull(status.assessment)
        assertEquals(FailureCode.NETWORK_DISABLED, status.failure?.code)
        assertEquals(0, status.providerDispatches, "blocked before transmission")
    }

    /** F-01: timeout. */
    @Test
    fun timeout_reportsTimeout() = runTest {
        val status = useCase(Client { throw AiAdvisorFailure.Timeout("7s") }).invoke(evidence)
        assertNotUsable(status)
        assertEquals(FailureCode.TIMEOUT, status.failure?.code)
    }

    /** F-03: network error mid-flight. */
    @Test
    fun networkError_reportsNetworkError() = runTest {
        val status = useCase(Client { throw AiAdvisorFailure.NetworkError("connection reset") })
            .invoke(evidence)
        assertNotUsable(status)
        assertEquals(FailureCode.NETWORK_ERROR, status.failure?.code)
    }

    /** F-04: rate limited. */
    @Test
    fun rateLimited_reportsRateLimited() = runTest {
        val status = useCase(Client { throw AiAdvisorFailure.RateLimited(30) }).invoke(evidence)
        assertNotUsable(status)
        assertEquals(FailureCode.RATE_LIMITED, status.failure?.code)
    }

    /** F-04: service error. */
    @Test
    fun serviceError_reportsServiceError() = runTest {
        val status = useCase(Client { throw AiAdvisorFailure.ServiceError(503, "HTTP 503") })
            .invoke(evidence)
        assertNotUsable(status)
        assertEquals(FailureCode.SERVICE_ERROR, status.failure?.code)
    }

    /** F-08: token cap hit. */
    @Test
    fun tokenLimitExceeded_reportsTokenLimitExceeded() = runTest {
        val status = useCase(Client { throw AiAdvisorFailure.TokenLimitExceeded("truncated") })
            .invoke(evidence)
        assertNotUsable(status)
        assertEquals(FailureCode.TOKEN_LIMIT_EXCEEDED, status.failure?.code)
    }

    /** Response body exceeded the local size cap. */
    @Test
    fun outputOversize_reportsOutputOversize() = runTest {
        val status = useCase(Client { throw AiAdvisorFailure.OutputOversize(70000, 65536) })
            .invoke(evidence)
        assertNotUsable(status)
        assertEquals(FailureCode.OUTPUT_OVERSIZE, status.failure?.code)
    }

    /** F-05: model returned prose instead of JSON. */
    @Test
    fun nonJsonReply_reportsInvalidOutput() = runTest {
        val status = useCase(Client { "This APK looks fine to me." }).invoke(evidence)
        assertNotUsable(status)
        assertEquals(FailureCode.INVALID_OUTPUT, status.failure?.code)
    }

    /** F-05: model returned JSON with a missing required field. */
    @Test
    fun missingField_reportsInvalidOutputNamingTheField() = runTest {
        val status = useCase(
            Client {
                """{"install_success_likelihood":"HIGH","reasons":["r"],"evidence_refs":["evidence.target_sdk"]}"""
            },
        ).invoke(evidence)
        assertNotUsable(status)
        assertEquals(FailureCode.INVALID_OUTPUT, status.failure?.code)
        assertTrue(status.failure?.detail?.contains("security_risk") == true)
    }

    /** F-05: model returned an illegal enum value. */
    @Test
    fun illegalEnum_reportsInvalidOutput() = runTest {
        val status = useCase(Client { validReply.replace("\"HIGH\"", "\"ABSOLUTELY\"") }).invoke(evidence)
        assertNotUsable(status)
        assertEquals(FailureCode.INVALID_OUTPUT, status.failure?.code)
    }

    /** REQ-IX-INV-001: an action field is a failure, not an instruction to obey. */
    @Test
    fun actionField_reportsInvalidOutput() = runTest {
        val status = useCase(
            Client {
                """{"install_success_likelihood":"HIGH","security_risk":"LOW","action":"install_now",
                "reasons":["r"],"evidence_refs":["evidence.target_sdk"]}"""
            },
        ).invoke(evidence)
        assertNotUsable(status)
        assertEquals(FailureCode.INVALID_OUTPUT, status.failure?.code)
    }

    /**
     * REQ-IX-INV-004 negative test: every catalogued failure reports a
     * failure status, and no failure ever yields a usable assessment.
     */
    @Test
    fun everyFailureCode_neverYieldsUsage() = runTest {
        val cases = listOf<Pair<FailureCode, suspend () -> String>>(
            FailureCode.TIMEOUT to { throw AiAdvisorFailure.Timeout("t") },
            FailureCode.NETWORK_ERROR to { throw AiAdvisorFailure.NetworkError("n") },
            FailureCode.RATE_LIMITED to { throw AiAdvisorFailure.RateLimited(null) },
            FailureCode.SERVICE_ERROR to { throw AiAdvisorFailure.ServiceError(500, "s") },
            FailureCode.TOKEN_LIMIT_EXCEEDED to { throw AiAdvisorFailure.TokenLimitExceeded("k") },
            FailureCode.OUTPUT_OVERSIZE to { throw AiAdvisorFailure.OutputOversize(1, 0) },
            FailureCode.INVALID_OUTPUT to { throw AiAdvisorFailure.InvalidOutput("i") },
        )
        for ((expected, behaviour) in cases) {
            val status = useCase(Client(behaviour)).invoke(evidence)
            val failure = assertNotNull(status.failure, "$expected must report a failure")
            assertEquals(expected, failure.code)
            assertNull(status.assessment, "$expected must not yield an assessment")
        }
    }

    private fun assertNotUsable(status: RequestInstallRiskAssessmentUseCase.Status) {
        assertTrue(!status.isUsable, "expected a failure status, got ${status.assessment}")
        assertNull(status.assessment)
        assertNotNull(status.failure)
        assertEquals(1, status.providerDispatches, "a sent request is still counted")
    }
}
