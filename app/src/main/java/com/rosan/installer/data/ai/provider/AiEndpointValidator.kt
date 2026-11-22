// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

/**
 * Implements: ADR-002.2; ADR-002.5
 * Enforces: REQ-IX-SEC-004, REQ-IX-FR-009
 *
 * Pure validation of the endpoint the advisor is allowed to reach.
 *
 * The model transport builds its request URL only from a value that passed
 * this check, so a caller cannot route the advisor to an arbitrary host:
 *
 * - `https` scheme only (never `http`, `file`, `ftp`, ...);
 * - absolute, well-formed URL with a non-empty host;
 * - no embedded userinfo (`user:pass@`), no fragment;
 * - no whitespace or control characters;
 * - a custom port is allowed as long as the scheme stays HTTPS.
 *
 * A rejected endpoint disables the live call; the deterministic policy still
 * decides the install. This is belt-and-braces on top of `AiNetworkGate`.
 */
object AiEndpointValidator {

    /** Default endpoint for the CS5351 course service (ADR-002.2). */
    const val DEFAULT_ENDPOINT = "https://llm.cs.cityu.edu.hk/v1"

    /**
     * @return the normalized endpoint (trailing slash removed) when [raw] is
     *   acceptable, or `null` when it must be rejected.
     */
    fun normalizeOrNull(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        // Reject whitespace and control characters anywhere in the value.
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null

        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        if (!uri.isAbsolute) return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null) return null
        if (uri.fragment != null) return null
        // A URI with opaque parts (e.g. "https:foo") has no authority/host.
        if (uri.isOpaque) return null

        return trimmed.trimEnd('/')
    }

    fun isValid(raw: String?): Boolean = normalizeOrNull(raw) != null
}
