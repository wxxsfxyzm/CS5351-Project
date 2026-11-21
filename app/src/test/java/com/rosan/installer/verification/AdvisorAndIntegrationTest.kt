// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.verification

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue

/**
 * Stage 4 Verification Suites: `OutputValidationTest`, `FailureFallbackTest`,
 * `AdvisorIsolationTest`, `CallBudgetTest`, `TokenBudgetTest`, `ConsentGateTest`,
 * and AI + Policy Integration tests.
 *
 * Verifies: REQ-IX-FR-003, REQ-IX-FR-004, REQ-IX-FR-005, REQ-IX-FR-011,
 * REQ-IX-INV-001, REQ-IX-INV-002, REQ-IX-INV-004, REQ-IX-SEC-001, REQ-IX-SEC-004,
 * REQ-IX-COST-001, REQ-IX-QUAL-001.
 * Plan reference: `docs/verification/test-plan.md` §3 case S-03 and §6 cases A-01 .. A-10.
 */
class OutputValidationTest {

    private val validator = AIOutputValidator()

    /** Verifies: REQ-IX-FR-004, REQ-IX-QUAL-001 (Valid baseline) */
    @Test
    fun `valid structured JSON with allowlisted references passes validation`() {
        val json = """
            {
              "install_success_likelihood": "HIGH",
              "security_risk": "LOW",
              "reasons": ["Compatible SDK and standard permissions"],
              "evidence_references": ["declared_permissions", "platform_compatibility"]
            }
        """.trimIndent()

        val outcome = validator.validateRawJson(json)
        assertTrue(outcome is AIOutputValidator.ValidationOutcome.Valid)
        assertEquals(InstallSuccessLikelihood.HIGH, outcome.result.installSuccessLikelihood)
        assertEquals(SecurityRisk.LOW, outcome.result.securityRisk)
    }

    /** Verifies: REQ-IX-INV-001, REQ-IX-FR-004 (Case A-02: action/install instructions rejected) */
    @Test
    fun `A-02 response containing action or approve or install command fields is rejected as INVALID`() = runTest {
        val maliciousPayloads = listOf(
            """{"install_success_likelihood":"HIGH","security_risk":"LOW","action":"INSTALL_NOW","reasons":["ok"],"evidence_references":["min_sdk"]}""",
            """{"install_success_likelihood":"HIGH","security_risk":"LOW","approve":true,"reasons":["ok"],"evidence_references":["min_sdk"]}""",
            """{"install_success_likelihood":"HIGH","security_risk":"LOW","install_command":"pm install -r base.apk","reasons":["ok"],"evidence_references":["min_sdk"]}""",
        )

        for (raw in maliciousPayloads) {
            val validation = validator.validateRawJson(raw)
            assertTrue(validation is AIOutputValidator.ValidationOutcome.Invalid, "Payload should be INVALID: $raw")

            val gateway = FakeInstallationGateway()
            val coordinator = ApprovalCoordinator(
                advisor = FixedResponseAdvisor(AdvisorCallOutcome.RawSuccess(rawJson = raw)),
                gateway = gateway,
                apkHasher = FakeApkHashProvider("sha256-a02"),
            )
            val out = coordinator.assess(AssessmentEvidence(operationId = "op-a02", apkSha256 = "sha256-a02"))
            assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
            assertEquals("DR-08", out.ruleId)
            assertEquals(0, gateway.submissions.get())
        }
    }

    /**
     * Verifies: REQ-IX-FR-004, REQ-IX-QUAL-001 (Case A-03)
     * Rejects invalid JSON, missing dimension, illegal enum, fabricated reference, and guarantee language.
     */
    @Test
    fun `A-03 invalid JSON missing dimension illegal enum fabricated reference and guarantee language are rejected`() {
        val invalidCases = mapOf(
            "malformed_json" to """not a json object""",
            "missing_risk_dimension" to """{"install_success_likelihood":"HIGH","reasons":["ok"],"evidence_references":["min_sdk"]}""",
            "illegal_likelihood_enum" to """{"install_success_likelihood":"CERTAIN","security_risk":"LOW","reasons":["ok"],"evidence_references":["min_sdk"]}""",
            "illegal_risk_enum" to """{"install_success_likelihood":"HIGH","security_risk":"NONE","reasons":["ok"],"evidence_references":["min_sdk"]}""",
            "fabricated_reference" to """{"install_success_likelihood":"HIGH","security_risk":"LOW","reasons":["ok"],"evidence_references":["raw_apk_bytes_secret"]}""",
            "guarantee_language_1" to """{"install_success_likelihood":"HIGH","security_risk":"LOW","reasons":["This APK is 100% safe and guaranteed to work"],"evidence_references":["min_sdk"]}""",
            "guarantee_language_2" to """{"install_success_likelihood":"HIGH","security_risk":"LOW","reasons":["We guarantee this will definitely install"],"evidence_references":["min_sdk"]}""",
        )

        for ((label, raw) in invalidCases) {
            val outcome = validator.validateRawJson(raw)
            assertTrue(
                outcome is AIOutputValidator.ValidationOutcome.Invalid,
                "Case '$label' must be classified INVALID, got $outcome",
            )
        }
    }
}

/**
 * Verifies: REQ-IX-INV-004 (model failure does not silently approve),
 * REQ-IX-FR-003, REQ-IX-FR-011, REQ-IX-SEC-004, REQ-IX-COST-001 (F-01 .. F-09).
 */
class FailureFallbackTest {

    /**
     * Verifies: REQ-IX-INV-004, REQ-IX-FR-003, F-01, F-03, F-04 (Case A-04)
     * Timeout, network loss after dispatch, rate limit, and service error route to
     * REQUIRE_REVIEW / AWAIT_HUMAN with 1 attempt, 1 dispatch, 0 automatic retries, and 0 backend submissions.
     */
    @Test
    fun `A-04 timeout network loss rate limit and service error route to REQUIRE_REVIEW with zero retries and zero submissions`() = runTest {
        val failures = listOf(
            AdviceAvailability.TIMEOUT,
            AdviceAvailability.NETWORK_LOSS,
            AdviceAvailability.RATE_LIMITED,
            AdviceAvailability.SERVICE_ERROR,
        )

        for (failure in failures) {
            val opId = "op-fail-$failure"
            val gateway = FakeInstallationGateway()
            val store = InMemoryApprovalStateStore()
            val advisor = FixedResponseAdvisor(nextOutcome = AdvisorCallOutcome.Failure(failure))
            val coordinator = ApprovalCoordinator(
                advisor = advisor,
                stateStore = store,
                gateway = gateway,
                apkHasher = FakeApkHashProvider("sha256-fail"),
            )

            val out = coordinator.assess(AssessmentEvidence(operationId = opId, apkSha256 = "sha256-fail"))
            assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision, "failure=$failure")
            assertEquals(PolicyDisposition.AWAIT_HUMAN, out.disposition, "failure=$failure")
            assertEquals("DR-08", out.ruleId, "failure=$failure")
            // No automatic retry: exactly 1 dispatch
            assertEquals(1, advisor.providerDispatches.get(), "failure=$failure must not auto-retry")
            assertEquals(1, store.get(opId)!!.attemptCount)
            assertEquals(1, store.get(opId)!!.dispatchCount)
            assertEquals(0, gateway.submissions.get())
        }
    }

    /** Verifies: REQ-IX-INV-004, REQ-IX-SEC-004, F-02 (Case A-05: network disabled) */
    @Test
    fun `A-05 network disabled produces REQUIRE_REVIEW with 0 attempts 0 dispatches and 0 submissions`() = runTest {
        val gateway = FakeInstallationGateway()
        val store = InMemoryApprovalStateStore()
        val advisor = FixedResponseAdvisor()
        val coordinator = ApprovalCoordinator(
            advisor = advisor,
            stateStore = store,
            gateway = gateway,
            networkEnabled = false,
        )

        val out = coordinator.assess(AssessmentEvidence(operationId = "op-net-off"))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals(0, advisor.providerDispatches.get())
        assertEquals(0, store.get("op-net-off")!!.attemptCount)
        assertEquals(0, store.get("op-net-off")!!.dispatchCount)
        assertEquals(0, gateway.submissions.get())
    }

    /** Verifies: REQ-IX-COST-001, REQ-IX-INV-004, F-07, F-09 (Case A-06: preflight token rejection & call budget) */
    @Test
    fun `A-06 preflight token rejection consumes 1 attempt with 0 provider dispatches and third request is blocked`() = runTest {
        val gateway = FakeInstallationGateway()
        val store = InMemoryApprovalStateStore()
        val advisor = FixedResponseAdvisor()
        val coordinator = ApprovalCoordinator(
            advisor = advisor,
            stateStore = store,
            gateway = gateway,
        )

        val evidence = AssessmentEvidence(operationId = "op-budget")
        // Attempt 1: preflight token estimate exceeds 4000 hard cap -> F-07
        val out1 = coordinator.assess(evidence, preflightEstimatedTokens = 4500)
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out1.decision)
        assertEquals("DR-08", out1.ruleId)
        assertEquals(1, store.get("op-budget")!!.attemptCount)
        assertEquals(0, store.get("op-budget")!!.dispatchCount)
        assertEquals(0, advisor.providerDispatches.get())
        assertEquals(0, gateway.submissions.get())

        // Attempt 2 (user retry): normal call -> consumes attempt 2, 1 dispatch
        advisor.nextOutcome = AdvisorCallOutcome.Failure(AdviceAvailability.TIMEOUT)
        coordinator.assess(evidence.copy(attemptId = "att-2"), preflightEstimatedTokens = 500)
        assertEquals(2, store.get("op-budget")!!.attemptCount)
        assertEquals(1, store.get("op-budget")!!.dispatchCount)

        // Attempt 3: blocked third request (F-09) -> 0 additional attempts, 0 additional dispatches
        val out3 = coordinator.assess(evidence.copy(attemptId = "att-3"), preflightEstimatedTokens = 500)
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out3.decision)
        assertEquals(2, store.get("op-budget")!!.attemptCount)
        assertEquals(1, store.get("op-budget")!!.dispatchCount)
        assertEquals(0, gateway.submissions.get())
    }

    /** Verifies: REQ-IX-COST-001, REQ-IX-INV-004, F-08 (Case A-07: post-call token overage) */
    @Test
    fun `A-07 post-call token overage T ge 4001 records 1 completed call and routes to REQUIRE_REVIEW`() = runTest {
        val gateway = FakeInstallationGateway()
        val store = InMemoryApprovalStateStore()
        val advisor = FixedResponseAdvisor(
            nextOutcome = AdvisorCallOutcome.RawSuccess(
                result = AssessmentResult(InstallSuccessLikelihood.HIGH, SecurityRisk.LOW),
                inputTokens = 3000,
                outputTokens = 1001, // Total = 4001 >= 4001 overage threshold
            ),
        )
        val coordinator = ApprovalCoordinator(
            advisor = advisor,
            stateStore = store,
            gateway = gateway,
        )

        val out = coordinator.assess(AssessmentEvidence(operationId = "op-overage"))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, out.decision)
        assertEquals("DR-08", out.ruleId)
        assertEquals(1, store.get("op-overage")!!.attemptCount)
        assertEquals(1, store.get("op-overage")!!.dispatchCount)
        assertEquals(0, gateway.submissions.get())
    }

    /** Verifies: REQ-IX-FR-011, NFC-OUT-003, F-06 (Case A-10: consent declined) */
    @Test
    fun `A-10 outbound consent declined makes 0 model calls routes to REQUIRE_REVIEW and preserves HARD_BLOCK`() = runTest {
        val gateway = FakeInstallationGateway()
        val advisor = FixedResponseAdvisor()
        val coordinator = ApprovalCoordinator(
            advisor = advisor,
            gateway = gateway,
            consentGranted = false,
        )

        val outNormal = coordinator.assess(AssessmentEvidence(operationId = "op-consent-no"))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, outNormal.decision)
        assertEquals("DR-02", outNormal.ruleId)
        assertEquals(0, advisor.providerDispatches.get())
        assertEquals(0, gateway.submissions.get())

        val outHardBlock = coordinator.assess(
            AssessmentEvidence(operationId = "op-consent-no-block", hasHardBlock = true),
        )
        assertEquals(PolicyDecision.BLOCK, outHardBlock.decision)
        assertEquals("DR-01", outHardBlock.ruleId)
        assertEquals(0, advisor.providerDispatches.get())
        assertEquals(0, gateway.submissions.get())
    }

    /** Verifies: REQ-IX-FR-005, ADR-002.6 (Case A-01 & A-09: end-to-end advisor + policy integration) */
    @Test
    fun `A-01 and A-09 advisor HIGH-LOW auto-approves while advisor HIGH risk routes to REQUIRE_REVIEW not BLOCK`() = runTest {
        val gwAuto = FakeInstallationGateway()
        val coordAuto = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(
                AdvisorCallOutcome.RawSuccess(
                    result = AssessmentResult(InstallSuccessLikelihood.HIGH, SecurityRisk.LOW),
                ),
            ),
            gateway = gwAuto,
            apkHasher = FakeApkHashProvider("sha256-a01"),
        )
        val outAuto = coordAuto.assess(AssessmentEvidence(operationId = "op-a01", apkSha256 = "sha256-a01"))
        assertEquals(PolicyDecision.ALLOW_AUTO, outAuto.decision)
        assertEquals(1, gwAuto.submissions.get())

        val gwHighRisk = FakeInstallationGateway()
        val coordHighRisk = ApprovalCoordinator(
            advisor = FixedResponseAdvisor(
                AdvisorCallOutcome.RawSuccess(
                    result = AssessmentResult(InstallSuccessLikelihood.HIGH, SecurityRisk.HIGH),
                ),
            ),
            gateway = gwHighRisk,
            apkHasher = FakeApkHashProvider("sha256-a09"),
        )
        val outHighRisk = coordHighRisk.assess(AssessmentEvidence(operationId = "op-a09", apkSha256 = "sha256-a09"))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, outHighRisk.decision)
        assertNotEquals(PolicyDecision.BLOCK, outHighRisk.decision)
        assertEquals(0, gwHighRisk.submissions.get())
    }
}

/**
 * Stage 4 Structural & Negative Invariant Suite: `AdvisorIsolationTest`.
 *
 * Verifies: REQ-IX-INV-001 (AI has no direct privileged execution capability),
 * REQ-IX-SEC-001 (runtime least-privilege boundary), `permission-model.md` §9.2–§9.3.
 * Plan reference: `docs/verification/test-plan.md` §3 case S-03.
 */
class AdvisorIsolationTest {

    /**
     * Verifies: REQ-IX-INV-001, REQ-IX-SEC-001 (Case S-03)
     * Static import-graph check over the advisory package (`AIRiskAdvisor`, `EvidenceSanitizer`,
     * `AIOutputValidator`). Skips (not passes) until the production advisory files are merged.
     */
    @Test
    fun `S-03 advisory package references no installer backend session repository or privileged provider`() {
        val files = advisorySourceFiles()
        assumeTrue(
            "Production advisory package not merged yet; static check skipped, not passed.",
            files.isNotEmpty(),
        )

        val violations = files.flatMap { file ->
            PolicyWithoutModelTest.findViolations(file.readText(), FORBIDDEN_ADVISORY_DEPENDENCIES)
                .map { "${file.relativeTo(sourceRoot())}: $it" }
        }

        if (violations.isNotEmpty()) {
            fail(
                "REQ-IX-INV-001 / REQ-IX-SEC-001 violated: advisory package references forbidden execution/privileged types.\n" +
                    violations.joinToString("\n"),
            )
        }
    }

    /**
     * Self-test proving the detector fails when an advisory class imports `AppInstallerRepository`,
     * `InstallationGateway`, `ActionHandler`, a privileged provider, or a session repository.
     */
    @Test
    fun `detector flags forbidden installer and privileged imports in advisory source`() {
        val badAdvisorySource = """
            package com.rosan.installer.data.ai

            import com.rosan.installer.domain.engine.repository.AppInstallerRepository
            import com.rosan.installer.data.session.handler.ActionHandler
            import com.rosan.installer.domain.privileged.provider.ShellExecutionProvider

            class AIRiskAdvisorImpl
        """.trimIndent()

        val found = PolicyWithoutModelTest.findViolations(badAdvisorySource, FORBIDDEN_ADVISORY_DEPENDENCIES)
        assertEquals(3, found.size, "Expected 3 forbidden advisory dependencies, got:\n${found.joinToString("\n")}")
    }

    private fun advisorySourceFiles(): List<File> {
        val root = sourceRoot()
        val configured = CANDIDATE_ADVISORY_DIRS.map { File(root, it) }.filter { it.isDirectory }
        val discovered = root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { ADVISORY_DECLARATION.containsMatchIn(it.readText()) }
            .toList()

        return (configured.flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() } + discovered).distinct()
    }

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate app/src/main/java from ${File(".").absolutePath}")
    }

    companion object {
        private val CANDIDATE_ADVISORY_DIRS = listOf(
            "com/rosan/installer/data/ai",
            "com/rosan/installer/domain/ai/advisor",
        )

        private val ADVISORY_DECLARATION =
            Regex("""\b(class|interface|object)\s+(AIRiskAdvisor|EvidenceSanitizer|AIOutputValidator)\b""")

        /** `docs/architecture/permission-model.md` §9.2 forbidden dependencies for advisory elements. */
        private val FORBIDDEN_ADVISORY_DEPENDENCIES = listOf(
            "com.rosan.installer.domain.engine.repository.AppInstallerRepository",
            "com.rosan.installer.data.engine.repository.AppInstallerRepositoryImpl",
            "com.rosan.installer.data.engine.executor.appinstaller.",
            "com.rosan.installer.domain.privileged.",
            "com.rosan.installer.framework.privileged.",
            "com.rosan.installer.domain.session.",
            "com.rosan.installer.data.session.handler.ActionHandler",
            "InstallationGateway",
        )
    }
}
