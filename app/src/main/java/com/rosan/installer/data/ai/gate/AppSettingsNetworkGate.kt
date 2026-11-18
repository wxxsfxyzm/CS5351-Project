// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.gate

import com.rosan.installer.domain.ai.repository.AiNetworkGate
import com.rosan.installer.domain.settings.repository.AppSettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Implements: ADR-002.5
 * Enforces: REQ-IX-SEC-004
 *
 * Gates model egress on the existing in-app network switch, exactly as
 * `OkHttpNetworkResolver.resolve` does (`AppPreferences.allowInternetAccess`).
 * No new setting is introduced and the baseline network boundary is not
 * widened (`AGENTS.md` critical constraints).
 */
class AppSettingsNetworkGate(
    private val appSettingsRepository: AppSettingsRepository,
) : AiNetworkGate {
    override suspend fun isEgressAllowed(): Boolean =
        appSettingsRepository.preferencesFlow.first().allowInternetAccess
}
