// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-INV-001, REQ-IX-INV-006, REQ-IX-SEC-001
 *
 * Static import check required by ADR-001 section 6 and
 * `permission-model.md` sections 9.2-9.3 (Option B has no module boundary, so
 * a test carries the check).
 *
 * The advisory package (`domain/ai`) must not reference an installer backend,
 * a privileged provider, a session handler, or a network client. The concrete
 * model request and JSON transport live in `data/ai` (ADR-001 Option B), so
 * the domain package stays free of `okhttp3.*` by construction.
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
    }

    private fun advisorySources(): List<File> {
        val root = findSourceRoot()
        val dir = File(root, "com/rosan/installer/domain/ai")
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
}
