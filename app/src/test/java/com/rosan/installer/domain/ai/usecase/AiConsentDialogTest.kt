// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-FR-011 (consent dialog lists the whole allowlist).
 *
 * Offline. The consent dialog is generated from
 * `AiRequestBuilder.ALLOWLISTED_LEAF_PATHS`, so it cannot drift from what is
 * actually transmitted. Cancel leaves consent off; only an explicit confirm
 * turns it on (enforced in the ViewModel; the pure list is asserted here).
 */
class AiConsentDialogTest {

    @Test
    fun listsWholeAllowlist() {
        val dialogEntries = AiAdvisorSettings.consentAllowlist()
        val allowlist = AiRequestBuilder.ALLOWLISTED_LEAF_PATHS.toList()

        assertEquals(allowlist, dialogEntries, "dialog entries must equal the allowlist")
        // The list must be non-trivial and cover the installation-context fields.
        assertTrue(dialogEntries.isNotEmpty())
        assertTrue(dialogEntries.contains("evidence.target_sdk"))
        assertTrue(dialogEntries.contains("evidence.declared_permissions"))
        // A local-only value must never be offered for consent.
        assertTrue(dialogEntries.none { it.contains("device_api_level") })
    }
}
