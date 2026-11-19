// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-INV-001, REQ-IX-INV-006, REQ-IX-SEC-001, REQ-IX-FR-009
 *
 * Static import check required by ADR-001 section 6 and
 * `permission-model.md` sections 9.2-9.3 (Option B has no module boundary, so
 * a test carries the check).
 *
 * Two packages are scanned:
 *
 * - `domain/ai` — the advisory contracts and pure logic. It must not reference
 *   an installer backend, a privileged provider, a session handler, or a
 *   network client.
 * - `data/ai` — the concrete advisor, the settings-backed gate, and the model
 *   transport (ADR-001 Option B). It may talk to the model, but through the
 *   same backend-free boundary, and only the `provider` sub-package may depend on a network
 *   or JSON library.
 *
 * This test reads files instead of resolving types so it works offline and
 * fails for the same reason the compiler would if the boundary were a module.
 */
class AdvisorIsolationTest {

    private companion object {
        /**
         * Package prefixes the advisory `domain` code must never import.
         * Keep in sync with `permission-model.md` section 9.2.
         */
        val FORBIDDEN_PREFIXES = listOf(
            "com.rosan.installer.domain.engine.repository.AppInstallerRepository",
            "com.rosan.installer.data.engine.repository",
            "com.rosan.installer.data.engine.executor.appinstaller",
            "com.rosan.installer.domain.privileged",
            "com.rosan.installer.framework.privileged",
            "com.rosan.installer.domain.session",
            "com.rosan.installer.data.session.handler",
            "com.rosan.installer.data.session",
            "com.rosan.installer.data.engine.parser",
            "okhttp3.",
            // The policy layer must not depend on the model side either; the
            // advisor may not reach back into the data layer's transport.
            "com.rosan.installer.data.ai.provider.OkHttpAiTransport",
            "com.rosan.installer.data.ai.gate",
        )

        /**
         * Backend / privilege references forbidden anywhere under `data/ai`.
         * Matched against imports **and** fully-qualified usages, so a
         * same-package reference cannot slip past the import scan.
         * `permission-model.md` section 9.2; ADR-001 section 6 item 1.
         */
        val DATA_AI_FORBIDDEN_PREFIXES = listOf(
            "com.rosan.installer.domain.engine.repository",
            "com.rosan.installer.data.engine.executor",
            "com.rosan.installer.domain.privileged",
            "com.rosan.installer.framework.privileged",
            "com.rosan.installer.domain.session",
            "com.rosan.installer.data.session",
            "com.rosan.installer.data.engine.parser",
            "com.rosan.installer.data.engine.repository",
        )

        /**
         * Network and serialization libraries allowed only in the provider
         * sub-package: the transport, not the advisory policy.
         */
        val PROVIDER_ONLY_PREFIXES = listOf(
            "okhttp3.",
            "kotlinx.serialization.",
            "java.net.",
            "retrofit2.",
            "com.squareup.",
            "io.ktor.",
        )

        val PROVIDER_SUBPACKAGE = "com/rosan/installer/data/ai/provider"

        /**
         * Shape of a real Groq key prefix plus a plausible body. The prefix is
         * concatenated so this guard file is never itself a match.
         */
        val GROQ_KEY_LITERAL = Regex("gs" + "k_[A-Za-z0-9]{8,}")
    }

    private fun advisorySources(packagePath: String = "com/rosan/installer/domain/ai"): List<File> {
        val root = findSourceRoot()
        val dir = File(root, packagePath)
        assertTrue(dir.isDirectory, "advisory package not found at $dir")
        return dir.walkTopDown().filter { it.extension == "kt" }.toList()
    }

    /** Walks up from the working directory to `app/src/main/java`. */
    private fun findSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            val nested = File(dir, "src/main/java")
            if (nested.isDirectory && File(dir, "com/rosan/installer").isDirectory) return File(dir, ".")
            dir = dir.parentFile
        }
        error("could not locate app/src/main/java from ${System.getProperty("user.dir")}")
    }

    private fun importsOf(file: File): List<String> = file.readLines()
        .asSequence()
        .map { it.trim() }
        .filter { it.startsWith("import ") }
        .map { it.removePrefix("import ").removeSuffix(";").trim() }
        .toList()

    /** Imports plus whole-file text, so fully-qualified uses are caught too. */
    private fun referencesOf(file: File): List<String> {
        val text = file.readText()
        return importsOf(file) + text.lines()
            .asSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("import ") }
            .filterNot { it.startsWith("package ") }
            .filterNot { it.startsWith("//") }
            .filterNot { it.startsWith("*") }
            .filterNot { it.startsWith("/*") }
            .toList()
    }

    /** Verifies: REQ-IX-INV-001, REQ-IX-SEC-001 (no backend or privileged import). */
    @Test
    fun advisoryPackage_importsNothingPrivileged() {
        val violations = mutableListOf<String>()
        for (file in advisorySources()) {
            for (import in importsOf(file)) {
                val hit = FORBIDDEN_PREFIXES.firstOrNull { import.startsWith(it) }
                if (hit != null) violations += "${file.name}: $import"
            }
        }
        assertTrue(
            violations.isEmpty(),
            "advisory domain package references forbidden types: $violations",
        )
    }

    /**
     * Verifies: REQ-IX-INV-006 (policy logic stays model- and network-free).
     *
     * The domain package may not import the concrete provider client either:
     * only the `AIRiskAdvisor` / `AiModelClient` interfaces are allowed.
     */
    @Test
    fun advisoryPackage_dependsOnInterfacesNotConcreteProvider() {
        val violations = advisorySources()
            .filterNot { it.name == "AiAdvisorContracts.kt" }
            .flatMap { file ->
                importsOf(file)
                    .filter { it.startsWith("com.rosan.installer.data.") }
                    .map { "${file.name}: $it" }
            }
        assertTrue(
            violations.isEmpty(),
            "domain/ai must not import the data layer, found: $violations",
        )
    }

    /** Sanity check: the scan actually sees the advisory sources. */
    @Test
    fun advisoryPackage_isScanned() {
        val files = advisorySources()
        assertTrue(files.size >= 5, "expected the advisory sources to be scanned, saw ${files.size}")
        val importCount = files.sumOf { importsOf(it).size }
        assertTrue(importCount > 0, "expected at least one import across the advisory sources")
    }

    /**
     * Verifies: REQ-IX-INV-001, REQ-IX-SEC-001 (ADR-001 section 6 item 1).
     *
     * The real advisor, gate, and transport live under `data/ai`; none of them
     * may reach an installer backend, a privileged provider, a session
     * handler, or `ActionHandler`.
     */
    @Test
    fun dataAiPackage_importsNoBackendOrPrivilegedType() {
        val violations = mutableListOf<String>()
        for (file in advisorySources("com/rosan/installer/data/ai")) {
            for (reference in referencesOf(file)) {
                if (reference.contains("ActionHandler")) {
                    violations += "${file.name}: $reference"
                    continue
                }
                val hit = DATA_AI_FORBIDDEN_PREFIXES.firstOrNull { reference.contains(it) }
                if (hit != null) violations += "${file.name}: $reference"
            }
        }
        assertTrue(
            violations.isEmpty(),
            "data/ai references forbidden backend or privileged types: $violations",
        )
    }

    /**
     * Verifies: REQ-IX-INV-006 (ADR-001 section 6 item 1).
     *
     * Under Option B the transport lives in the same package tree as the
     * advisor, so the network/JSON allowance is narrowed to the `provider`
     * sub-package.
     * The advisor and the network gate must stay free of both.
     */
    @Test
    fun dataAiPackage_networkAndJsonLibrariesOnlyUnderProvider() {
        val violations = mutableListOf<String>()
        for (file in advisorySources("com/rosan/installer/data/ai")) {
            val inProvider = file.invariantSeparatorsPath.contains(PROVIDER_SUBPACKAGE)
            if (inProvider) continue
            for (import in importsOf(file)) {
                val hit = PROVIDER_ONLY_PREFIXES.firstOrNull { import.startsWith(it) }
                if (hit != null) violations += "${file.name}: $import"
            }
        }
        assertTrue(
            violations.isEmpty(),
            "only data/ai/provider may use network or JSON libraries, found: $violations",
        )
    }

    /** Sanity check: the scan actually sees `data/ai` and its provider tree. */
    @Test
    fun dataAiPackage_isScanned() {
        val files = advisorySources("com/rosan/installer/data/ai")
        assertTrue(files.size >= 5, "expected the data/ai sources to be scanned, saw ${files.size}")
        assertTrue(
            files.any { it.invariantSeparatorsPath.contains(PROVIDER_SUBPACKAGE) },
            "expected the provider sub-package to be part of the scan",
        )
    }

    /**
     * Verifies: REQ-IX-FR-009 (credentials are not compiled into the app).
     *
     * The build must not bake an API key into `BuildConfig`, and no source
     * file may carry a Groq key literal. This is the regression guard for
     * review item E-3.
     */
    @Test
    fun boundingSecrets_areNotCompiledIntoBuildConfig() {
        // The test task runs with `user.dir` inside `app/`, so walk up to the
        // directory that actually owns `build.gradle.kts`.
        val sourceRoot = findSourceRoot()
        var appDir: File? = sourceRoot
        while (appDir != null && !File(appDir, "build.gradle.kts").isFile) appDir = appDir.parentFile
        assertTrue(appDir != null, "could not locate app/build.gradle.kts from $sourceRoot")
        val sources = buildList {
            add(File(appDir!!, "build.gradle.kts"))
            addAll(
                sourceRoot.walkTopDown()
                    // Skip this file: it names the guard pattern itself.
                    .filterNot { it.name == "AdvisorIsolationTest.kt" }
                    .filter { it.extension == "kt" || it.extension == "kts" },
            )
        }
        val violations = sources.filter { it.isFile }.flatMap { file ->
            file.readLines().withIndex().mapNotNull { (index, line) ->
                val compiled = line.contains("buildConfigField") &&
                    line.contains("GROQ_API_KEY")
                val literal = GROQ_KEY_LITERAL.containsMatchIn(line)
                val reason = if (compiled) "buildConfigField for GROQ_API_KEY" else "hard-coded Groq key literal"
                if (compiled || literal) "${file.name}:${index + 1}: $reason" else null
            }
        }
        assertTrue(
            violations.isEmpty(),
            "the model API key must never be compiled into the build: $violations",
        )
    }
}
