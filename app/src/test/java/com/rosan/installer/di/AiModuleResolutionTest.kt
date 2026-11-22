// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.di

import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.repository.AiModelClient
import com.rosan.installer.domain.ai.repository.AiNetworkGate
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import com.rosan.installer.domain.ai.usecase.RequestInstallRiskAssessmentUseCase
import com.rosan.installer.domain.engine.approval.PrepareApprovalUseCase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Verifies: REQ-IX-ARCH-001 (the AI-evaluated install path can be constructed)
 *
 * Resolves the real [aiModule] graph that the installer screen needs. Before
 * this test, `singleOf(::PrepareApprovalUseCase)` and `singleOf(::AiRequestBuilder)`
 * asked Koin for their defaulted parameters (AutoDialogInstallEvidenceMapper,
 * Json), which have no definition, so the app crashed when an install started.
 *
 * Only the two network-facing definitions are replaced, because the real ones
 * need OkHttp and the settings DataStore, which cannot start in a JVM test.
 */
class AiModuleResolutionTest {
    private val app = koinApplication {
        allowOverride(true)
        modules(
            aiModule,
            module {
                single<AiNetworkGate> {
                    object : AiNetworkGate {
                        override suspend fun isEgressAllowed() = false
                    }
                }
                single<AiModelClient> {
                    object : AiModelClient {
                        override val modelId = "test"
                        override suspend fun complete(request: AiInstallRiskRequest): String = error("not called")
                    }
                }
            },
        )
    }

    @AfterTest
    fun tearDown() = app.close()

    @Test
    fun aiRequestBuilderResolves() {
        assertNotNull(app.koin.get<AiRequestBuilder>())
    }

    @Test
    fun assessmentUseCaseResolves() {
        assertNotNull(app.koin.get<RequestInstallRiskAssessmentUseCase>())
    }

    @Test
    fun prepareApprovalUseCaseResolves() {
        assertNotNull(app.koin.get<PrepareApprovalUseCase>())
    }
}
