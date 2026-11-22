// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.di

import com.rosan.installer.data.ai.advisory.DefaultAiRiskAdvisor
import com.rosan.installer.data.ai.gate.AppSettingsNetworkGate
import com.rosan.installer.data.ai.provider.AiEndpointConfigSource
import com.rosan.installer.data.ai.provider.OkHttpAiTransport
import com.rosan.installer.data.ai.provider.OpenAiCompatibleModelClient
import com.rosan.installer.domain.ai.repository.AIRiskAdvisor
import com.rosan.installer.domain.ai.repository.AiModelClient
import com.rosan.installer.domain.ai.repository.AiNetworkGate
import com.rosan.installer.domain.ai.repository.AiOutputValidator
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import com.rosan.installer.domain.ai.usecase.EvidenceSanitizer
import com.rosan.installer.domain.ai.usecase.RequestInstallRiskAssessmentUseCase
import com.rosan.installer.domain.ai.usecase.StrictAiOutputValidator
import com.rosan.installer.domain.engine.approval.PrepareApprovalUseCase
import com.rosan.installer.domain.settings.repository.AppSettingsRepository
import okhttp3.OkHttpClient
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Implements: ADR-001; ADR-002.2; ADR-002.5
 * Enforces: REQ-IX-ARCH-001, REQ-IX-INV-001, REQ-IX-MOD-001
 *
 * Koin registrations for the advisory elements, in an existing-style `di/`
 * module (`structural-constraints.md` section 2). All singletons so no
 * duplicate advisor or gate can be created (risk RK-T-05).
 *
 * The OkHttp client is the one already registered by [networkModule]; no
 * second HTTP stack is introduced. Only the per-call read budget differs
 * (7 s, `runtime-policy-catalog.md` F-01), applied through
 * `newBuilder()` so timeouts stay per-call rather than global.
 *
 * The endpoint and API key are resolved per call from app-private storage
 * through [AiEndpointConfigSource] and are never compiled into the APK
 * (REQ-IX-FR-009). A missing key only disables the live call and never
 * weakens a deterministic check.
 *
 * Consent is assessment-scoped, not a singleton binding. Both checkpoints
 * deny by default until the caller supplies a current-consent reader through
 * the coroutine context.
 */
val aiModule = module {
    single { EvidenceSanitizer() }
    singleOf(::AiRequestBuilder)
    single<AiOutputValidator> { StrictAiOutputValidator() }
    single<AiNetworkGate> { AppSettingsNetworkGate(appSettingsRepository = get<AppSettingsRepository>()) }

    single<AiModelClient> {
        OpenAiCompatibleModelClient(
            transport = OkHttpAiTransport(
                apiKeyProvider = { get<AiEndpointConfigSource>().current().apiKey },
                egressAllowed = { get<AiNetworkGate>().isEgressAllowed() },
                baseClient = get<OkHttpClient>(),
            ),
            configSource = get<AiEndpointConfigSource>(),
        )
    }

    singleOf(::DefaultAiRiskAdvisor)
    single<AIRiskAdvisor> { get<DefaultAiRiskAdvisor>() }
    singleOf(::RequestInstallRiskAssessmentUseCase)
    singleOf(::PrepareApprovalUseCase)
}
