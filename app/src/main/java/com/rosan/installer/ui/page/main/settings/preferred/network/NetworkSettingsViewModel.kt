// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.ui.page.main.settings.preferred.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rosan.installer.data.ai.provider.AiCredentialStore
import com.rosan.installer.data.ai.provider.AiEndpointValidator
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import com.rosan.installer.domain.settings.repository.AppSettingsRepository
import com.rosan.installer.domain.settings.repository.BooleanSetting
import com.rosan.installer.domain.settings.repository.StringSetting
import com.rosan.installer.domain.settings.usecase.settings.UpdateSettingUseCase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NetworkSettingsViewModel(
    private val appSettingsRepo: AppSettingsRepository,
    private val updateSetting: UpdateSettingUseCase,
    private val credentialStore: AiCredentialStore,
) : ViewModel() {

    private val events = Channel<NetworkSettingsEvent>(Channel.BUFFERED)

    /**
     * True while the user is walking through enable -> configure -> consent.
     * Each step continues to the next missing one; the advisor is switched on
     * only after every precondition is persisted.
     */
    private var pendingEnable = false
    val eventFlow: Flow<NetworkSettingsEvent> = events.receiveAsFlow()

    /**
     * The masked key follows endpoint changes but re-reads the stored key on
     * each change; `maskApiKey` guarantees the full key never leaks into state.
     */
    private val maskedKeyFlow: Flow<String> = credentialStore.endpointFlow.map {
        credentialStore.maskedKey()
    }

    private val storedConfig: Flow<Pair<String?, String>> = combine(
        credentialStore.endpointFlow,
        maskedKeyFlow,
    ) { endpoint, masked -> endpoint to masked }

    val state: StateFlow<NetworkSettingsState> = combine(
        appSettingsRepo.preferencesFlow,
        storedConfig,
    ) { prefs, (endpoint, maskedKey) ->
        val validEndpoint = AiEndpointValidator.normalizeOrNull(endpoint)
        val keyStored = maskedKey.isNotEmpty()
        NetworkSettingsState(
            allowInternetAccess = prefs.allowInternetAccess,
            networkSourceMode = prefs.networkSourceMode,
            networkSourceModeWarningAcknowledged = prefs.networkSourceModeWarningAcknowledged,
            httpProfile = prefs.labHttpProfile,
            githubUpdateChannel = prefs.githubUpdateChannel,
            customGithubProxyUrl = prefs.customGithubProxyUrl,
            aiAdvisorEnabled = prefs.aiAdvisorEnabled,
            aiConsentGranted = prefs.aiAdvisorConsent,
            aiEndpointConfigured = validEndpoint != null && keyStored,
            aiEndpoint = validEndpoint.orEmpty(),
            aiEndpointHostSummary = hostSummary(validEndpoint),
            aiKeyMasked = maskedKey,
            aiModelPromptSummary = modelPromptSummary(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = NetworkSettingsState(),
    )

    fun dispatch(action: NetworkSettingsAction) {
        when (action) {
            is NetworkSettingsAction.ChangeInternetAccess -> viewModelScope.launch {
                updateSetting(BooleanSetting.AllowInternetAccess, action.enabled)
            }

            is NetworkSettingsAction.ChangeNetworkSourceMode -> viewModelScope.launch {
                updateSetting(StringSetting.NetworkSourceMode, action.mode.value)
            }

            is NetworkSettingsAction.ConfirmNetworkSourceMode -> viewModelScope.launch {
                updateSetting(StringSetting.NetworkSourceMode, action.mode.value)
                updateSetting(BooleanSetting.NetworkSourceModeWarningAcknowledged, true)
            }

            is NetworkSettingsAction.ChangeHttpProfile -> viewModelScope.launch {
                updateSetting(StringSetting.LabHttpProfile, action.profile.name)
            }

            is NetworkSettingsAction.ChangeGithubUpdateChannel -> viewModelScope.launch {
                updateSetting(StringSetting.GithubUpdateChannel, action.channel.name)
            }

            is NetworkSettingsAction.ChangeCustomGithubProxyUrl -> viewModelScope.launch {
                updateSetting(StringSetting.CustomGithubProxyUrl, action.url)
            }

            is NetworkSettingsAction.SetAiAdvisorEnabled -> viewModelScope.launch {
                if (!action.enabled) {
                    // Turning the advisor off also withdraws consent (REQ-IX-FR-011),
                    // so the next enable shows the allowlist again.
                    pendingEnable = false
                    updateSetting(BooleanSetting.AiInstallRiskAdvisorEnabled, false)
                    updateSetting(BooleanSetting.AiInstallRiskAdvisorConsent, false)
                    return@launch
                }
                // Consent is asked on every enable: clear any stale grant first so
                // continueEnable always reaches the allowlist dialog.
                updateSetting(BooleanSetting.AiInstallRiskAdvisorConsent, false)
                pendingEnable = true
                continueEnable()
            }

            is NetworkSettingsAction.SetAiConsent -> viewModelScope.launch {
                if (action.granted) {
                    // Do not persist until the UI confirms the enumerated allowlist.
                    events.send(NetworkSettingsEvent.ShowAiConsentAllowlist)
                } else {
                    updateSetting(BooleanSetting.AiInstallRiskAdvisorConsent, false)
                    // Turning consent off also disables the advisor.
                    updateSetting(BooleanSetting.AiInstallRiskAdvisorEnabled, false)
                }
            }

            is NetworkSettingsAction.SaveAiConfiguration -> {
                // Read synchronously: the Miuix dialog calls onDismiss
                // (CancelAiSetup) right after onSave, before this coroutine resumes.
                val continueAfterSave = pendingEnable
                viewModelScope.launch {
                    val normalized = AiEndpointValidator.normalizeOrNull(action.endpoint)
                    if (normalized == null) {
                        pendingEnable = false
                        events.send(
                            NetworkSettingsEvent.AiConfigurationError(
                                AiConfigurationErrorKind.InvalidEndpoint,
                            ),
                        )
                        return@launch
                    }
                    // Sequential writes: the key must be stored before readiness is checked.
                    credentialStore.saveEndpoint(normalized)
                    action.apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { credentialStore.saveApiKey(it) }
                    if (continueAfterSave) continueEnable()
                }
            }

            NetworkSettingsAction.CancelAiSetup -> {
                pendingEnable = false
            }

            is NetworkSettingsAction.SaveAiEndpoint -> viewModelScope.launch {
                val normalized = AiEndpointValidator.normalizeOrNull(action.endpoint)
                if (normalized == null) {
                    events.send(
                        NetworkSettingsEvent.AiConfigurationError(
                            AiConfigurationErrorKind.InvalidEndpoint,
                        ),
                    )
                } else {
                    credentialStore.saveEndpoint(normalized)
                }
            }

            is NetworkSettingsAction.SaveAiApiKey -> viewModelScope.launch {
                if (action.apiKey.isBlank()) {
                    events.send(
                        NetworkSettingsEvent.AiConfigurationError(
                            AiConfigurationErrorKind.MissingKey,
                        ),
                    )
                } else {
                    credentialStore.saveApiKey(action.apiKey.trim())
                }
            }

            NetworkSettingsAction.ClearAiConfiguration -> viewModelScope.launch {
                credentialStore.clear()
                updateSetting(BooleanSetting.AiInstallRiskAdvisorEnabled, false)
                updateSetting(BooleanSetting.AiInstallRiskAdvisorConsent, false)
            }
        }
    }

    /** Called after the user confirms the enumerated allowlist dialog. */
    fun confirmAiConsent() {
        // Read synchronously for the same reason as SaveAiConfiguration.
        val continueAfterConsent = pendingEnable
        viewModelScope.launch {
            updateSetting(BooleanSetting.AiInstallRiskAdvisorConsent, true)
            if (continueAfterConsent) continueEnable()
        }
    }

    /**
     * Next step of a pending enable. Reads the persisted values directly
     * instead of [state], which may not have caught up with a write made a
     * moment ago.
     */
    private suspend fun continueEnable() {
        val prefs = appSettingsRepo.preferencesFlow.first()
        val configured = AiEndpointValidator.normalizeOrNull(credentialStore.endpointFlow.first()) != null &&
            !credentialStore.currentApiKey().isNullOrBlank()
        when {
            !prefs.allowInternetAccess -> pendingEnable = false

            !configured -> {
                pendingEnable = true
                events.send(NetworkSettingsEvent.OpenAiConfiguration)
            }

            !prefs.aiAdvisorConsent -> {
                pendingEnable = true
                events.send(NetworkSettingsEvent.ShowAiConsentAllowlist)
            }

            else -> {
                pendingEnable = false
                updateSetting(BooleanSetting.AiInstallRiskAdvisorEnabled, true)
            }
        }
    }

    private companion object {
        fun hostSummary(validEndpoint: String?): String {
            if (validEndpoint == null) return ""
            return runCatching { java.net.URI(validEndpoint).host ?: "" }.getOrDefault("")
        }

        fun modelPromptSummary(): String {
            val fileVersion = AiRequestBuilder.PROMPT_VERSION.removePrefix("ai-risk-prompt-v")
            return "${AiRequestBuilder.DEFAULT_MODEL_ID} · v$fileVersion"
        }
    }
}
