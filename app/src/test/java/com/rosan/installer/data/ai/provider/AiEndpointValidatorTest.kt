// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-SEC-004, REQ-IX-FR-009 (endpoint validation).
 *
 * Offline and deterministic. The endpoint validator is the guard that keeps
 * the transport from reaching an arbitrary host: a rejected endpoint disables
 * the live call, and the deterministic policy still decides the install.
 */
class AiEndpointValidatorTest {

    @Test
    fun httpsOnlyWellFormed() {
        // Accepted: the course URL and an HTTPS custom port, trailing slash
        // insensitive.
        val course = AiEndpointValidator.normalizeOrNull("https://llm.cs.cityu.edu.hk/v1")
        assertEquals("https://llm.cs.cityu.edu.hk/v1", course)
        assertEquals(
            "https://llm.cs.cityu.edu.hk/v1",
            AiEndpointValidator.normalizeOrNull(" https://llm.cs.cityu.edu.hk/v1/ "),
        )
        assertTrue(AiEndpointValidator.isValid("https://localhost:8443/v1"))
    }

    @Test
    fun rejectsInsecureOrMalformedEndpoints() {
        val rejected = listOf(
            null,
            "",
            "   ",
            "http://llm.cs.cityu.edu.hk/v1", // not HTTPS
            "ftp://llm.cs.cityu.edu.hk/v1",
            "llm.cs.cityu.edu.hk/v1", // relative
            "/v1/chat", // relative
            "https:///v1", // blank host
            "https://user:pass@llm.cs.cityu.edu.hk/v1", // embedded credentials
            "https://llm.cs.cityu.edu.hk/v1#frag", // fragment
            "https://llm.cs.cityu.edu.hk/v1\nX", // control char
            "https://llm .cs.cityu.edu.hk/v1", // whitespace
        )
        for (raw in rejected) {
            assertNull(AiEndpointValidator.normalizeOrNull(raw), "must reject: $raw")
            assertFalse(AiEndpointValidator.isValid(raw), "must reject: $raw")
        }
    }

    /** The default endpoint must itself pass the validator. */
    @Test
    fun defaultEndpointIsValid() {
        assertTrue(AiEndpointValidator.isValid(AiEndpointValidator.DEFAULT_ENDPOINT))
    }
}
