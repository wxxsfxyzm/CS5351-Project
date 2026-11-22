// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

import kotlinx.coroutines.flow.Flow

/**
 * Implements: ADR-002.2; ADR-002.5
 * Enforces: REQ-IX-SEC-004, REQ-IX-FR-009
 *
 * App-private storage for the model endpoint and API key.
 *
 * It is deliberately **not** part of `AppDataStore.supportedKeys`, so the
 * settings backup/export registry never sees it
 * (`BackupRepositoryImpl.toBackupSettings()` only exports registered keys), and
 * the file is excluded from Android backup by `allowBackup="false"` plus the
 * data-extraction rules. The key is only ever read here at call time and placed
 * in the `Authorization` header; it is never logged, exported, or shown
 * unmasked in the UI.
 */
interface AiCredentialStore {
    /** Current endpoint, or null when none is stored. */
    val endpointFlow: Flow<String?>

    /**
     * The stored API key, or null. Read per call by the transport; never
     * cached beyond the duration of one request.
     */
    suspend fun currentApiKey(): String?

    /** Stores a validated HTTPS endpoint. */
    suspend fun saveEndpoint(endpoint: String)

    /** Stores the API key. It is not validated here (kept opaque). */
    suspend fun saveApiKey(apiKey: String)

    /** Removes both the endpoint and the key. */
    suspend fun clear()

    /**
     * A display-only mask of the stored key (`••••` + last 4), or an empty
     * string when no key is stored. Never returns the full key
     * (REQ-IX-FR-009).
     */
    suspend fun maskedKey(): String
}

/**
 * The mask used in the UI: a fixed prefix plus at most the last four
 * characters. Returns an empty string for a blank or absent key so callers do
 * not have to special-case it.
 */
internal fun maskApiKey(apiKey: String?): String {
    val key = apiKey?.trim().orEmpty()
    if (key.isEmpty()) return ""
    val tail = if (key.length <= 4) key else key.takeLast(4)
    return "••••$tail"
}
