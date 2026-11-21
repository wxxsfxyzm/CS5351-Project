// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.verification

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Assume.assumeTrue

/**
 * Structural check for the deterministic policy package.
 *
 * Verifies: REQ-IX-INV-006 (policy decision independent of model).
 * Related: ADR-002.1 (policy placement), ADR-002.6 (authority separation),
 * `docs/architecture/permission-model.md` §9.2–§9.3,
 * `docs/verification/test-plan.md` case S-01 / S-02.
 *
 * Status: draft by Member D, pending Member A approval of the test plan.
 *
 * Scope of this first version:
 * - Static part only. It scans every Kotlin/Java source file in the policy
 *   package and fails on a model SDK, model adapter, or network-client
 *   reference (import or fully qualified use).
 * - The behavioral part of INV-006 ("with an empty adapter injected all
 *   policy unit tests pass") is covered later by `PolicyDecisionTableTest`,
 *   once Member C freezes the policy interface.
 *
 * Policy package discovery: ADR-002.1 has not fixed the package yet, so the
 * test scans [CANDIDATE_POLICY_DIRS] plus every directory that declares
 * `DeterministicPolicyEngine`. If no policy source exists yet the scan tests
 * are reported as SKIPPED (not passed). Update [CANDIDATE_POLICY_DIRS] once
 * ADR-002.1 is decided.
 */
class PolicyWithoutModelTest {

    /** Verifies: REQ-IX-INV-006 — no model SDK, model adapter, or network client in the policy package. */
    @Test
    fun `policy package references no model SDK or network client`() {
        val files = policySourceFiles()
        assumeTrue(
            "Policy package not implemented yet (ADR-002.1 open); static check skipped, not passed.",
            files.isNotEmpty(),
        )

        val violations = files.flatMap { file ->
            findViolations(file.readText(), FORBIDDEN_MODEL_AND_NETWORK).map { "${file.relativeTo(sourceRoot())}: $it" }
        }

        if (violations.isNotEmpty()) {
            fail(
                "REQ-IX-INV-006 violated: the policy package depends on a model or network type.\n" +
                    violations.joinToString("\n"),
            )
        }
    }

    /**
     * Verifies: ADR-002.1 constraint "no Android framework dependency in the
     * decision core" (keeps the policy pure-JVM testable, supports REQ-IX-INV-006).
     */
    @Test
    fun `policy package has no Android framework dependency`() {
        val files = policySourceFiles()
        assumeTrue(
            "Policy package not implemented yet (ADR-002.1 open); static check skipped, not passed.",
            files.isNotEmpty(),
        )

        val violations = files.flatMap { file ->
            findViolations(file.readText(), FORBIDDEN_ANDROID).map { "${file.relativeTo(sourceRoot())}: $it" }
        }

        if (violations.isNotEmpty()) {
            fail("ADR-002.1 violated: the policy core depends on the Android framework.\n" + violations.joinToString("\n"))
        }
    }

    /**
     * Self-test of the detector. Runs even before the policy package exists,
     * and proves the check would fail if a forbidden dependency were added
     * ("removing the protection must make the negative test fail",
     * verification-constraints.md §1).
     */
    @Test
    fun `detector flags forbidden imports and fully qualified references`() {
        val badSource = """
            package com.rosan.installer.domain.ai.policy

            import okhttp3.OkHttpClient
            import com.rosan.installer.data.ai.GeminiAdvisorAdapter
            import java.net.HttpURLConnection

            class DeterministicPolicyEngine {
                private val client = io.ktor.client.HttpClient()
            }
        """.trimIndent()

        val found = findViolations(badSource, FORBIDDEN_MODEL_AND_NETWORK)

        assertEquals(4, found.size, "Expected 4 violations, got:\n${found.joinToString("\n")}")
    }

    /** Self-test: a clean pure-Kotlin policy file and comment-only mentions are not flagged. */
    @Test
    fun `detector accepts pure policy source and ignores comments`() {
        val cleanSource = """
            package com.rosan.installer.domain.ai.policy

            import kotlin.collections.List

            // This engine must never import okhttp3 or call a model.
            /*
             * Network types such as java.net.URL are forbidden here.
             */
            class DeterministicPolicyEngine {
                fun decide(input: String): String = "REQUIRE_REVIEW"
            }
        """.trimIndent()

        assertTrue(findViolations(cleanSource, FORBIDDEN_MODEL_AND_NETWORK).isEmpty())
        assertTrue(findViolations(cleanSource, FORBIDDEN_ANDROID).isEmpty())
    }

    private fun policySourceFiles(): List<File> {
        val root = sourceRoot()
        val configured = CANDIDATE_POLICY_DIRS.map { File(root, it) }.filter { it.isDirectory }
        val discovered = root.walkTopDown()
            .filter { it.isFile && it.isSourceFile() }
            .filter { POLICY_DECLARATION.containsMatchIn(it.readText()) }
            .mapNotNull { it.parentFile }
            .toList()

        return (configured + discovered)
            .distinct()
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.isSourceFile() }.toList() }
            .distinct()
    }

    private fun sourceRoot(): File {
        // Gradle runs Android unit tests with the module directory as working dir;
        // fall back to the repository root for IDE runs.
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate app/src/main/java from ${File(".").absolutePath}")
    }

    private fun File.isSourceFile() = extension == "kt" || extension == "java"

    companion object {
        /** Policy locations under Option B (ADR-001, ADR-002.1: `domain/engine/policy`). */
        private val CANDIDATE_POLICY_DIRS = listOf(
            "com/rosan/installer/domain/engine/policy",
            "com/rosan/installer/domain/ai/policy",
            "com/rosan/installer/domain/policy",
        )

        private val POLICY_DECLARATION =
            Regex("""\b(class|interface|object)\s+DeterministicPolicyEngine\b""")

        /** permission-model.md §9.2: no model SDK, no model adapter implementation, no network client. */
        private val FORBIDDEN_MODEL_AND_NETWORK = listOf(
            // network clients
            "okhttp3.",
            "com.rosan.installer.data.session.resolver.OkHttpNetworkResolver",
            "io.ktor.",
            "retrofit2.",
            "java.net.",
            "javax.net.",
            // model adapter implementation (Option B places it under data/ai)
            "com.rosan.installer.data.ai.",
            // model SDKs
            "com.google.ai.client.generativeai.",
            "com.google.genai.",
            "com.google.firebase.vertexai.",
            "com.google.firebase.ai.",
            "com.openai.",
            "com.aallam.openai.",
            "com.anthropic.",
            "dev.langchain4j.",
        )

        private val FORBIDDEN_ANDROID = listOf(
            "android.",
            "androidx.",
        )

        /**
         * Returns one entry per offending source line. Comment lines are ignored
         * so that KDoc may mention forbidden types.
         */
        internal fun findViolations(source: String, forbidden: List<String>): List<String> {
            var inBlockComment = false
            val result = mutableListOf<String>()
            source.lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (inBlockComment) {
                    if (line.contains("*/")) inBlockComment = false
                    return@forEachIndexed
                }
                if (line.startsWith("/*")) {
                    if (!line.contains("*/")) inBlockComment = true
                    return@forEachIndexed
                }
                if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed

                val code = line.substringBefore("//")
                val hit = forbidden.firstOrNull { prefix -> referencesPackage(code, prefix) }
                if (hit != null) result += "line ${index + 1}: '$line' references forbidden '$hit'"
            }
            return result
        }

        /** Matches the prefix only at an identifier boundary, so `myandroid.x` does not match `android.`. */
        private fun referencesPackage(code: String, prefix: String): Boolean {
            var from = 0
            while (true) {
                val i = code.indexOf(prefix, from)
                if (i < 0) return false
                val before = if (i == 0) ' ' else code[i - 1]
                if (!before.isLetterOrDigit() && before != '_' && before != '.') return true
                from = i + 1
            }
        }
    }
}
