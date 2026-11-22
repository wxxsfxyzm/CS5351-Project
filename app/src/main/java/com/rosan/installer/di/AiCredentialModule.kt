// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.rosan.installer.data.ai.provider.AiCredentialStore
import com.rosan.installer.data.ai.provider.AiEndpointConfigSource
import com.rosan.installer.data.ai.provider.DataStoreAiCredentialStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Implements: ADR-002.2; ADR-002.5
 * Enforces: REQ-IX-SEC-004, REQ-IX-FR-009
 *
 * Registers the app-private credential storage for the AI advisor: a
 * **separate** Preferences DataStore file (`ai_credentials`) that is not part
 * of `AppDataStore.supportedKeys`, so the settings backup/export never sees the
 * endpoint or key (`AGENTS.md`: never commit or export credentials; privacy by
 * construction, not by filtering).
 *
 * The store is a single instance; the config source resolves the endpoint and
 * key per call, so a change takes effect without a restart. The key is never
 * logged or exported (REQ-IX-FR-009).
 */
private val AI_CREDENTIAL_DATASTORE = named("AiCredentialDataStore")

val aiCredentialModule = module {
    single<DataStore<Preferences>>(AI_CREDENTIAL_DATASTORE) {
        PreferenceDataStoreFactory.create {
            androidContext().preferencesDataStoreFile("ai_credentials")
        }
    }

    single { DataStoreAiCredentialStore(dataStore = get(AI_CREDENTIAL_DATASTORE)) }

    single<AiCredentialStore> { get<DataStoreAiCredentialStore>() }

    single<AiEndpointConfigSource> { get<DataStoreAiCredentialStore>() }
}
