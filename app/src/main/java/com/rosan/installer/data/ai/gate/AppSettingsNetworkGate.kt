// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.gate

import com.rosan.installer.domain.ai.repository.AiNetworkGate
import com.rosan.installer.domain.settings.repository.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Implements: ADR-002.5
 * Enforces: REQ-IX-SEC-004
 *
 * Gates model egress on the existing in-app network switch, exactly as
 * `OkHttpNetworkResolver.resolve` and `SourceResolver` do
 * (`AppPreferences.allowInternetAccess`). No new setting is introduced and
 * the baseline network boundary is not widened (`AGENTS.md` critical
 * constraints).
 *
 * **Fails closed.** If the stored setting cannot be read, egress is denied.
 * The caller (`DefaultAiRiskAdvisor`) also treats a thrown gate error as
 * "network disabled", but that is a second line of defence, not the rule:
 * an AI call must never be the one thing that decides it is allowed to
 * leave the device when the user's preference is unknown.
 */
class AppSettingsNetworkGate(
    private val appSettingsRepository: AppSettingsRepository,
) : AiNetworkGate {
    override suspend fun isEgressAllowed(): Boolean = try {
        appSettingsRepository.preferencesFlow.first().allowInternetAccess
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        // Unknown preference is not consent: deny.
        false
    }
}
