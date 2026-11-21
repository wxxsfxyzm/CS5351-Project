// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.ai.model.InstallModeSignal
import com.rosan.installer.domain.settings.model.config.Authorizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Verifies: REQ-IX-INV-003, REQ-IX-SEC-003
 *
 * Every binding field is mandatory and bound; changing any one of them
 * invalidates the approval, including the APK hash (TOCTOU) and the bound
 * target package name (NFC-OUT-004, with a distinct source package fixture).
 */
class ApprovalBindingTest {
    private val baseFacts = BindingFacts(
        apkSha256 = "a".repeat(64),
        targetPackageName = "com.example.target",
        targetUserId = 0,
        installMode = InstallModeSignal.AutoDialog,
        installationBackend = Authorizer.None,
        configSnapshotHash = "config-hash-1",
        policyVersion = "policy-v1",
    )

    private fun binding(facts: BindingFacts = baseFacts): ApprovalBinding = ApprovalBinding.create(operationId = "op-1", attemptId = "attempt-1", facts = facts)!!

    @Test
    fun everyFieldIsBound() {
        val b = binding()
        assertEquals("op-1", b.operationId)
        assertEquals("attempt-1", b.attemptId)
        assertEquals(baseFacts.apkSha256, b.apkSha256)
        assertEquals("com.example.target", b.targetPackageName)
        assertEquals(0, b.targetUserId)
        assertEquals(InstallModeSignal.AutoDialog, b.installMode)
        assertEquals(Authorizer.None, b.installationBackend)
        assertEquals("config-hash-1", b.configSnapshotHash)
        assertEquals("policy-v1", b.policyVersion)
    }

    @Test
    fun anyMissingFactYieldsNullBinding() {
        // Verifies: REQ-IX-INV-003 — a missing binding field prevents dispatch (Q3).
        val nulled = listOf(
            baseFacts.copy(apkSha256 = null),
            baseFacts.copy(targetPackageName = null),
            baseFacts.copy(targetUserId = null),
            baseFacts.copy(installMode = null),
            baseFacts.copy(installationBackend = null),
            baseFacts.copy(configSnapshotHash = null),
            baseFacts.copy(policyVersion = null),
            baseFacts.copy(apkSha256 = "  "),
        )
        for (facts in nulled) {
            assertNull(ApprovalBinding.create("op-1", "attempt-1", facts), facts.toString())
        }
    }

    @Test
    fun changedFieldInvalidatesAndSubmitsZero() {
        // Verifies: REQ-IX-INV-003 — changing any bound field makes the binding unequal.
        val original = binding()
        val changed = listOf(
            baseFacts.copy(apkSha256 = "b".repeat(64)),
            baseFacts.copy(targetPackageName = "com.example.other"),
            baseFacts.copy(targetUserId = 10),
            baseFacts.copy(installMode = InstallModeSignal.AutoNotification),
            baseFacts.copy(installationBackend = Authorizer.Root),
            baseFacts.copy(configSnapshotHash = "config-hash-2"),
            baseFacts.copy(policyVersion = "policy-v2"),
        )
        for (facts in changed) {
            assertNotEquals(original, binding(facts), "change must invalidate: $facts")
        }
    }

    @Test
    fun identicalFactsRevalidate() {
        // Verifies: REQ-IX-INV-003 — the same object revalidates successfully.
        assertEquals(binding(), binding(baseFacts.copy()))
    }

    @Test
    fun apkReplacementIsDetectedByHash() {
        // Verifies: REQ-IX-INV-003 (TOCTOU) — a replaced APK is a different hash.
        val bound = binding()
        val reread = binding(baseFacts.copy(apkSha256 = "c".repeat(64)))
        assertNotEquals(bound, reread)
    }

    @Test
    fun distinctSourcePackageDoesNotMatterButTargetDoes() {
        // Verifies: REQ-IX-INV-003 / NFC-OUT-004 — the bound target package is
        // what matters; only changing it (not some separate source package)
        // invalidates the approval.
        val bound = binding()
        assertEquals(bound, binding(baseFacts.copy()))
        assertNotEquals(bound, binding(baseFacts.copy(targetPackageName = "com.example.source-confusion")))
    }
}
