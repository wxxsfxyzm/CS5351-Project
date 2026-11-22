// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-INV-001, REQ-IX-INV-006 (static boundary across layers).
 *
 * Offline, deterministic static scan complementing [AdvisorIsolationTest]:
 *
 * - the deterministic policy engine (`domain/engine/policy`) must not import a
 *   model SDK, a network client, or the AI data layer (INV-006);
 * - the AI provider data layer (`data/ai/provider`) must not import the policy
 *   engine, so the advisory path cannot decide an install (INV-001/INV-006).
 */
class AiIsolationTest {

    private fun findSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("could not locate app/src/main/java from ${System.getProperty("user.dir")}")
    }

    private fun sources(packagePath: String): List<File> {
        val dir = File(findSourceRoot(), packagePath)
        assertTrue(dir.isDirectory, "package not found at $dir")
        return dir.walkTopDown().filter { it.extension == "kt" }.toList()
    }

    private fun references(file: File): List<String> {
        val text = file.readText()
        return text.lines()
            .asSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("//") }
            .filterNot { it.startsWith("*") }
            .filterNot { it.startsWith("/*") }
            .toList()
    }

    @Test
    fun policyEngineImportsNoModelOrNetwork() {
        val forbidden = listOf(
            "com.rosan.installer.data.ai",
            "okhttp3.",
            "java.net.",
            "retrofit2.",
            "com.squareup.",
            "io.ktor.",
            "kotlinx.serialization.",
        )
        val violations = mutableListOf<String>()
        for (file in sources("com/rosan/installer/domain/engine/policy")) {
            for (reference in references(file)) {
                if (reference.startsWith("import ") || reference.contains("com.rosan.installer")) {
                    val hit = forbidden.firstOrNull { reference.contains(it) }
                    if (hit != null) violations += "${file.name}: $reference"
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "the deterministic policy engine references a model/network type: $violations",
        )
    }

    @Test
    fun aiProviderDoesNotImportPolicyEngine() {
        val violations = mutableListOf<String>()
        for (file in sources("com/rosan/installer/data/ai/provider")) {
            for (reference in references(file)) {
                if (reference.contains("import ") &&
                    reference.contains("com.rosan.installer.domain.engine.policy")
                ) {
                    violations += "${file.name}: $reference"
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "the AI provider layer must not import the policy engine: $violations",
        )
    }

    @Test
    fun providerPackageIsScanned() {
        val files = sources("com/rosan/installer/data/ai/provider")
        assertTrue(files.size >= 4, "expected provider sources to be scanned, saw ${files.size}")
        assertTrue(files.any { it.name == "OpenAiCompatibleModelClient.kt" })
    }
}
