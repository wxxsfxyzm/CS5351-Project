// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-INV-001, REQ-IX-FR-007, REQ-IX-ARCH-001
 *
 * Static check (ADR-001 section 6): the new approval and gateway domain
 * packages must not reach a model SDK, a network client, the installer
 * backend, a privileged provider, a session handler, or `ActionHandler`.
 * Only the approval/install domain contracts and shared models may be
 * imported; the actual backend call lives behind the InstallationGateway
 * interface, implemented later in the data layer.
 */
class ApprovalIsolationTest {

    private companion object {
        val FORBIDDEN_PREFIXES = listOf(
            "com.rosan.installer.domain.engine.repository.AppInstallerRepository",
            "com.rosan.installer.data.engine.repository",
            "com.rosan.installer.data.engine.executor.appinstaller",
            "com.rosan.installer.data.engine.policy",
            "com.rosan.installer.domain.privileged",
            "com.rosan.installer.framework.privileged",
            "com.rosan.installer.domain.session",
            "com.rosan.installer.data.session",
            "com.rosan.installer.data.ai",
            "okhttp3.",
            "java.net.",
            "kotlinx.serialization.",
            "retrofit2.",
            "com.squareup.",
            "io.ktor.",
        )

        val PACKAGE_PATHS = listOf(
            "com/rosan/installer/domain/engine/approval",
            "com/rosan/installer/domain/engine/install",
        )
    }

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

    private fun importsOf(file: File): List<String> = file.readLines()
        .asSequence()
        .map { it.trim() }
        .filter { it.startsWith("import ") }
        .map { it.removePrefix("import ").removeSuffix(";").trim() }
        .toList()

    /** Imports plus whole-file text so fully-qualified uses are caught too. */
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

    /** Verifies: REQ-IX-INV-001, REQ-IX-ARCH-001 (no forbidden import). */
    @Test
    fun approvalPackagesImportNothingForbidden() {
        val violations = mutableListOf<String>()
        for (path in PACKAGE_PATHS) {
            for (file in sources(path)) {
                for (reference in referencesOf(file)) {
                    if (reference.contains("ActionHandler")) {
                        violations += "${file.name}: $reference"
                        continue
                    }
                    val hit = FORBIDDEN_PREFIXES.firstOrNull { reference.contains(it) }
                    if (hit != null) violations += "${file.name}: $reference"
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "approval/install domain packages reference forbidden types: $violations",
        )
    }

    /** Sanity check: the scan actually sees the approval sources. */
    @Test
    fun approvalPackageIsScanned() {
        val files = PACKAGE_PATHS.flatMap { sources(it) }
        assertTrue(files.size >= 5, "expected the approval sources to be scanned, saw ${files.size}")
        assertTrue(files.any { it.name == "ApprovalCoordinator.kt" })
        assertTrue(files.any { it.name == "InstallationGateway.kt" })
    }
}
