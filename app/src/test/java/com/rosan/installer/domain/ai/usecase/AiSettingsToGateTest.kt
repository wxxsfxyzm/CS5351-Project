// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-FR-011, REQ-IX-FR-012, REQ-IX-SEC-004.
 *
 * Offline matrix of the four switches that gate an advisory call. Each
 * withheld reason maps to REQUIRE_REVIEW; only a fully-permitted configuration
 * allows a dispatch, and no configuration auto-approves (the engine decides).
 */
class AiSettingsToGateTest {

    private val ready = GateInput(
        advisorEnabled = true,
        networkAllowed = true,
        endpointConfigured = true,
        consentGranted = true,
    )

    @Test
    fun consentAndSwitchMatrix() {
        // Advisor off is baseline: no dispatch, regardless of the rest.
        assertEquals(
            GateUnavailable.AdvisorOff,
            AiAdvisorSettings.unavailableReason(ready.copy(advisorEnabled = false)),
        )
        // Network off: no dispatch even if advisor, consent, and config are set.
        assertEquals(
            GateUnavailable.NetworkOff,
            AiAdvisorSettings.unavailableReason(ready.copy(networkAllowed = false)),
        )
        // Unconfigured: no endpoint/key.
        assertEquals(
            GateUnavailable.Unconfigured,
            AiAdvisorSettings.unavailableReason(ready.copy(endpointConfigured = false)),
        )
        // Consent off: no dispatch.
        assertEquals(
            GateUnavailable.ConsentOff,
            AiAdvisorSettings.unavailableReason(ready.copy(consentGranted = false)),
        )
        // All satisfied: dispatch permitted.
        assertNull(AiAdvisorSettings.unavailableReason(ready))
        assertTrue(AiAdvisorSettings.allowsDispatch(ready))
    }

    /** No configuration permits a dispatch unless all four conditions hold. */
    @Test
    fun onlyFullyPermittedConfigurationDispatches() {
        val combinations = sequence {
            for (a in listOf(false, true)) {
                for (n in listOf(false, true)) {
                    for (c in listOf(false, true)) {
                        for (g in listOf(false, true)) {
                            yield(GateInput(a, n, c, g))
                        }
                    }
                }
            }
        }
        for (input in combinations) {
            val permitted = AiAdvisorSettings.allowsDispatch(input)
            val allTrue = input.advisorEnabled && input.networkAllowed &&
                input.endpointConfigured && input.consentGranted
            assertEquals(allTrue, permitted, "dispatch permission wrong for $input")
        }
    }
}
