// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.policy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verifies: ADR-001 Option B; REQ-IX-FR-007, REQ-IX-INV-001/006. */
class PolicyDependencyBoundaryTest {
    @Test
    fun policySourcesOnlyImportPureAdvisoryData() {
        val cwd = File("").absoluteFile
        val relativePath = "src/main/java/com/rosan/installer/domain/engine/policy"
        val sources = assertNotNull(
            listOf(cwd.resolve("app/$relativePath"), cwd.resolve(relativePath)).firstOrNull { it.isDirectory },
            "Cannot locate policy sources from $cwd",
        )
        val files = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "No Kotlin sources found in $sources")

        val modelPackage = "com.rosan.installer.domain.ai.model"
        val allowed = setOf("AssessmentResult", "EvidenceCompleteness", "FailureCode", "InstallSuccessLikelihood", "LocalCompatibility", "SecurityRisk")
            .map { "$modelPackage.$it" }.toSet()
        val imports = Regex("(?m)^\\s*import\\s+([\\w.*]+)")
        val qualifiedReference = Regex(
            "\\b(?:(?:com\\.rosan\\.installer|androidx?|okhttp3|retrofit2|io\\.ktor|java|javax|kotlinx|kotlin\\.time)\\.[\\w.]+)",
        )

        for (file in files) {
            val body = file.readText().lineSequence().filterNot { it.startsWith("package ") }.joinToString("\n")
            val blockedImports = imports.findAll(body).map { it.groupValues[1] }.filterNot { it in allowed }.toList()
            val blockedReferences = qualifiedReference.findAll(body).map { it.value }.filterNot { reference ->
                allowed.any { reference == it || reference.startsWith("$it.") }
            }.toList()
            assertTrue(blockedImports.isEmpty() && blockedReferences.isEmpty(), "${file.name}: forbidden dependencies: ${blockedImports + blockedReferences}")
        }
    }
}
