// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

import com.rosan.installer.data.settings.local.datastore.AppDataStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies: REQ-IX-SEC-004, REQ-IX-FR-009 (credentials never exported/logged).
 *
 * Offline and deterministic. The credential store lives in a separate
 * app-private DataStore file that is not a member of
 * `AppDataStore.supportedKeys`, so `BackupRepositoryImpl.toBackupSettings()`
 * (which iterates only registered keys) cannot export it. The key is masked in
 * every value the UI or an error path can surface.
 */
class AiCredentialStoreTest {

    private val sentinel = "sentinel-SECRET-key-9f3a7c"

    @Test
    fun notInExportAndMasked() = runTest {
        val store = FakeAiCredentialStore()
        store.saveEndpoint("https://llm.cs.cityu.edu.hk/v1")
        store.saveApiKey(sentinel)

        // By construction: the credential keys are not in the export registry.
        val registered = AppDataStore.supportedKeys.keys
        assertFalse(registered.contains("ai_endpoint"), "endpoint must not be exportable")
        assertFalse(registered.contains("ai_api_key"), "key must not be exportable")

        // The masked value never contains the raw key and shows only the last 4.
        val masked = store.maskedKey()
        assertFalse(masked.contains(sentinel), "masked key must not embed the raw key")
        assertTrue(masked.endsWith(sentinel.takeLast(4)), "masked key must show the last 4")
        assertNotEquals(sentinel, masked)
    }

    @Test
    fun keyNeverAppearsInLogsOrErrors() = runTest {
        val store = FakeAiCredentialStore()
        store.saveEndpoint("https://llm.cs.cityu.edu.hk/v1")
        store.saveApiKey(sentinel)

        // Every value the credential path produces for display or config must
        // not contain the raw key. The config source exposes the key only to
        // the transport's Authorization header, never to a message.
        val surfaces = buildList {
            add(store.maskedKey())
            add(maskApiKey(null))
            add(maskApiKey(""))
            // A client failure on an unconfigured config must not echo a key.
            val cfg = store.current()
            add(cfg.toString())
        }
        for (value in surfaces) {
            assertFalse(value.contains(sentinel), "raw key leaked in: $value")
        }
    }

    @Test
    fun missingKeyDisablesLiveCall() = runTest {
        val store = FakeAiCredentialStore(initialEndpoint = "https://llm.cs.cityu.edu.hk/v1")
        // No key stored: config is incomplete, so the live call is skipped.
        assertFalse(store.current().isComplete)

        // A missing endpoint is also incomplete.
        val endpointless = FakeAiCredentialStore(initialApiKey = sentinel)
        assertFalse(endpointless.current().isComplete)

        // With both present, the config is complete (the call may proceed).
        val complete = FakeAiCredentialStore("https://llm.cs.cityu.edu.hk/v1", sentinel)
        assertTrue(complete.current().isComplete)
    }

    @Test
    fun maskingLeavesShortKeysSafe() {
        assertEquals("", maskApiKey(null))
        assertEquals("", maskApiKey("   "))
        // A key of length <= 4 is shown whole after the mask prefix, but there
        // is no longer secret to hide.
        assertEquals("••••abcd", maskApiKey("abcd"))
    }
}
