// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

/**
 * Implements: ADR-002.2; ADR-002.5
 * Enforces: REQ-IX-SEC-004, REQ-IX-FR-009, REQ-IX-MOD-001
 *
 * The endpoint, credential, and model selection for one model call, resolved
 * from app-private storage for **each** call so a change or a missing key
 * disables the live call without a restart and never weakens a deterministic
 * check.
 *
 * The `apiKey` is only ever placed in the `Authorization` header by the
 * transport; it is never logged, exported, shown in the UI, or echoed in a
 * failure message (REQ-IX-FR-009).
 */
data class AiEndpointConfig(
    val endpoint: String?,
    val apiKey: String?,
    val modelId: String,
    val promptVersion: String,
) {
    val isComplete: Boolean
        get() = !endpoint.isNullOrBlank() && !apiKey.isNullOrBlank()

    /**
     * Never render the raw key. A `data class` default `toString` would print
     * the key, and any accidental logging or error message would leak it
     * (REQ-IX-FR-009). The mask shows only emptiness, never content.
     */
    override fun toString(): String = "AiEndpointConfig(endpoint=$endpoint, apiKey=${if (apiKey.isNullOrEmpty()) "absent" else "present"}, " +
        "modelId=$modelId, promptVersion=$promptVersion)"
}

/**
 * Implements: ADR-002.2; ADR-002.5
 * Enforces: REQ-IX-SEC-004, REQ-IX-FR-009
 *
 * Reads the current [AiEndpointConfig]. Implementations read storage on each
 * call; they must never cache a key beyond the duration of one assessment.
 */
fun interface AiEndpointConfigSource {
    suspend fun current(): AiEndpointConfig
}
