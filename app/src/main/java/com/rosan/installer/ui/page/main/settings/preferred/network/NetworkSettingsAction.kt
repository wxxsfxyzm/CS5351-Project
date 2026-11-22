// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.ui.page.main.settings.preferred.network

import com.rosan.installer.domain.settings.model.config.NetworkSourceMode
import com.rosan.installer.domain.settings.model.preferences.GithubUpdateChannel
import com.rosan.installer.domain.settings.model.preferences.HttpProfile

sealed interface NetworkSettingsAction {
    data class ChangeInternetAccess(val enabled: Boolean) : NetworkSettingsAction
    data class ChangeNetworkSourceMode(val mode: NetworkSourceMode) : NetworkSettingsAction
    data class ConfirmNetworkSourceMode(val mode: NetworkSourceMode) : NetworkSettingsAction
    data class ChangeHttpProfile(val profile: HttpProfile) : NetworkSettingsAction
    data class ChangeGithubUpdateChannel(val channel: GithubUpdateChannel) : NetworkSettingsAction
    data class ChangeCustomGithubProxyUrl(val url: String) : NetworkSettingsAction

    // AI install-risk advisor
    data class SetAiAdvisorEnabled(val enabled: Boolean) : NetworkSettingsAction
    data class SetAiConsent(val granted: Boolean) : NetworkSettingsAction
    data class SaveAiEndpoint(val endpoint: String) : NetworkSettingsAction
    data class SaveAiApiKey(val apiKey: String) : NetworkSettingsAction
    data object ClearAiConfiguration : NetworkSettingsAction
}
