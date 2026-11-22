// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.InstallAssessment
import com.rosan.installer.domain.ai.model.InstallModeSignal
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.engine.policy.EvaluatedPolicy
import com.rosan.installer.domain.engine.policy.HumanApprovalState
import com.rosan.installer.domain.engine.policy.PolicyAdvice
import com.rosan.installer.domain.engine.policy.PolicyInput
import com.rosan.installer.domain.settings.model.config.Authorizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies: REQ-IX-FR-007, REQ-IX-ARCH-001
 *
 * Only the coordinator reaches the gateway, and only after a decision and
 * revalidation. The gateway spy records exactly what was submitted.
 */
class ChokePointTest {
    private val facts = BindingFacts(
        apkSha256 = "a".repeat(64),
        targetPackageName = "com.example.target",
        targetUserId = 0,
        installMode = InstallModeSignal.AutoDialog,
        installationBackend = Authorizer.None,
        configSnapshotHash = "cfg-1",
        policyVersion = "policy-v1",
    )

    private fun reviewInput() = PolicyInput(
        policy = EvaluatedPolicy("policy-v1", hardBlock = false, baselineAllowsAuto = false, autoRulesSatisfied = false),
        evidence = EvidenceCompleteness.COMPLETE,
        compatibility = LocalCompatibility.COMPATIBLE,
        advice = PolicyAdvice.Valid(
            AssessmentResult.Valid(
                InstallAssessment(
                    InstallSuccessLikelihood.HIGH,
                    SecurityRisk.LOW,
                    emptyList(),
                    listOf("evidence.target_sdk"),
                    "stub",
                    "v1",
                ),
            ),
        ),
        human = HumanApprovalState.NOT_OBTAINED,
    )

    @Test
    fun onlyGatewaySubmitsAfterRevalidation() = runTest {
        // Verifies: REQ-IX-ARCH-001 — decide alone submits nothing; Continue submits once via gateway.
        val store = InMemoryApprovalStateStore()
        val ledger = SubmissionLedger()
        val gateway = FakeInstallationGateway()
        val coordinator = ApprovalCoordinator(store, ledger, gateway, FakeBindingFactsProvider(facts, facts))

        coordinator.decide("op-1", "attempt-1", reviewInput())
        assertEquals(0, gateway.submissions.size)

        val outcome = coordinator.onContinue("op-1", "attempt-1", reviewInput())
        assertIs<ApprovalCoordinator.Outcome.Submitted>(outcome)
        assertEquals(1, gateway.submissions.size)
        // The submission carries the bound object, not a fresh/unbound one.
        assertEquals("op-1", gateway.submissions.single().operationId)
        assertEquals("com.example.target", gateway.submissions.single().targetPackageName)
    }

    @Test
    fun noDecisionNoSubmission() = runTest {
        // Verifies: REQ-IX-ARCH-001 — nothing reaches the gateway without a decision.
        val gateway = FakeInstallationGateway()
        val coordinator = ApprovalCoordinator(InMemoryApprovalStateStore(), SubmissionLedger(), gateway, FakeBindingFactsProvider(facts))
        coordinator.onContinue("op-1", "attempt-1", reviewInput())
        assertEquals(0, gateway.submissions.size)
    }

    /**
     * Verifies: REQ-IX-ARCH-001 (advisor-on), REQ-IX-FR-007.
     *
     * The production `SessionInstallationGateway` is the only implementation of
     * [com.rosan.installer.domain.engine.install.InstallationGateway] in the
     * app, and the `ApprovalCoordinator` is the only class that calls
     * `gateway.submit`. The `InstallWaitingUnknownSource` continuation
     * (`InstallerActivity` re-issues `session.install(false)`) is the one
     * recognised, explicit exception to "only the gateway reaches
     * `session.install`" — it continues a submission the gateway already
     * dispatched, it is not a new permit.
     */
    @Test
    fun productionGatewayIsOnlySubmitPath() {
        val sourceRoot = findMainSourceRoot()
        val kt = sourceRoot.walkTopDown().filter { it.extension == "kt" }.toList()

        // Only one production InstallationGateway implementation.
        val implementations = kt.filter { file ->
            Regex(""":\s*InstallationGateway\s*\{""").containsMatchIn(file.readText()) &&
                !file.name.endsWith("Test.kt")
        }
        assertTrue(
            implementations.map { it.name } == listOf("SessionInstallationGateway.kt"),
            "expected exactly the production gateway, saw ${implementations.map { it.name }}",
        )

        // Only the coordinator calls gateway.submit.
        val submitCallers = kt.filter { file ->
            Regex("""gateway\.submit\(""").containsMatchIn(file.readText())
        }
        assertTrue(
            submitCallers.map { it.name } == listOf("ApprovalCoordinator.kt"),
            "only the coordinator may call gateway.submit, saw ${submitCallers.map { it.name }}",
        )

        // Enumerate every actual (non-comment) `session.install(...)` call site by
        // file and line. Whole files are NOT allowlisted: the exact set of files
        // and the per-file count are asserted, so a new direct install call fails.
        val installPattern = Regex("""\bsession\.install\(""")
        val perFile = linkedMapOf<String, MutableList<Int>>()
        for (file in kt) {
            file.readLines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                if (installPattern.containsMatchIn(line)) {
                    perFile.getOrPut(file.name) { mutableListOf() }.add(index + 1)
                }
            }
        }
        // Allowed files and their exact number of call sites.
        val allowed = mapOf(
            "SessionInstallationGateway.kt" to 1, // the advisor-on submission seam
            "BroadcastHandler.kt" to 1, // notification/action baseline (not advisor-protected)
            "ProgressHandler.kt" to 1, // AutoNotification/baseline; skips AutoDialog when advisor on
            "InstallerViewModel.kt" to 1, // the single baseline funnel (baselineInstall)
        )
        assertTrue(
            perFile.keys == allowed.keys,
            "unexpected session.install files: ${perFile.keys - allowed.keys}; missing: ${allowed.keys - perFile.keys}",
        )
        for ((name, count) in allowed) {
            assertEquals(
                count,
                perFile[name]?.size ?: 0,
                "$name must have exactly $count session.install call site(s), saw ${perFile[name]}",
            )
        }

        // The InstallWaitingUnknownSource continuation in InstallerActivity is the
        // one recognised extra re-entry; it uses `currentSession?.install(false)`
        // (not `session.install`) and is exactly one call site.
        val activity = kt.first { it.name == "InstallerActivity.kt" }
        val continuationSites = activity.readLines().withIndex().filter { (_, line) ->
            val t = line.trim()
            !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) &&
                Regex("""currentSession\?\.install\(\s*false\s*\)""").containsMatchIn(t)
        }
        assertEquals(
            1,
            continuationSites.size,
            "InstallerActivity must have exactly one currentSession?.install(false) continuation",
        )

        // The single ViewModel call must sit inside the `baselineInstall()` helper,
        // which is the only funnel for advisor-off / out-of-scope installs.
        val vmFile = kt.first { it.name == "InstallerViewModel.kt" }
        val vmLines = vmFile.readLines()
        val vmCallLine = perFile.getValue("InstallerViewModel.kt").single() - 1
        val inBaselineHelper = (maxOf(0, vmCallLine - 4)..vmCallLine)
            .any { vmLines[it].contains("private fun baselineInstall(") }
        assertTrue(
            inBaselineHelper,
            "the sole ViewModel session.install must be inside baselineInstall(); near line ${vmCallLine + 1}",
        )
        // baselineInstall() must be called (it is the baseline funnel).
        assertTrue(
            vmLines.count { it.contains("baselineInstall()") } >= 2,
            "baselineInstall() must be invoked from the baseline paths",
        )

        // PrepareApprovalUseCase must never call session.install directly.
        assertTrue(
            perFile.keys.none { it == "PrepareApprovalUseCase.kt" },
            "the use case must not call session.install directly",
        )
    }

    private fun findMainSourceRoot(): java.io.File {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("could not locate app/src/main/java")
    }
}
