// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.ui.page.main.settings.preferred.network

import com.rosan.installer.domain.settings.model.config.NetworkSourceMode
import com.rosan.installer.domain.settings.model.preferences.GithubUpdateChannel
import com.rosan.installer.domain.settings.model.preferences.HttpProfile

data class NetworkSettingsState(
    val allowInternetAccess: Boolean = true,
    val networkSourceMode: NetworkSourceMode = NetworkSourceMode.Cache,
    val networkSourceModeWarningAcknowledged: Boolean = false,
    val httpProfile: HttpProfile = HttpProfile.ALLOW_SECURE,
    val githubUpdateChannel: GithubUpdateChannel = GithubUpdateChannel.OFFICIAL,
    val customGithubProxyUrl: String = "",
    // AI install-risk advisor
    val aiAdvisorEnabled: Boolean = false,
    val aiConsentGranted: Boolean = false,
    /** Derived: a validated endpoint and a key are both stored. */
    val aiEndpointConfigured: Boolean = false,
    /** The stored endpoint (non-secret), for prefilling the config dialog. */
    val aiEndpoint: String = "",
    /** Host only, for display; never the key or full path secrets. */
    val aiEndpointHostSummary: String = "",
    /** `••••` + last 4, or empty. Never the raw key. */
    val aiKeyMasked: String = "",
    /** Read-only model + prompt summary, e.g. "CS5351/DeepSeek-V4-Flash · v1.1". */
    val aiModelPromptSummary: String = "",
)

/**
 * One-shot UI events emitted by [NetworkSettingsViewModel]. Delivered through
 * a channel so recomposition does not replay them.
 */
sealed interface NetworkSettingsEvent {
    /** The advisor switch was turned on without a complete configuration. */
    data object OpenAiConfiguration : NetworkSettingsEvent

    /** The consent switch was turned on; show the enumerated allowlist first. */
    data object ShowAiConsentAllowlist : NetworkSettingsEvent

    /** An endpoint or key save was rejected; show the error inline. */
    data class AiConfigurationError(val message: AiConfigurationErrorKind) : NetworkSettingsEvent
}

enum class AiConfigurationErrorKind {
    InvalidEndpoint,
    MissingKey,
}
