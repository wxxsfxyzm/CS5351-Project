// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.session.handler

import com.rosan.installer.domain.engine.approval.FakeInstallerSession
import com.rosan.installer.domain.session.model.ProgressEntity
import com.rosan.installer.domain.settings.model.config.InstallMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Verifies: REQ-IX-FR-012, REQ-IX-SCOPE-001 (advisor-on AutoDialog is not
 * auto-installed by the progress handler; AutoNotification keeps baseline).
 *
 * Offline. The handler reads the advisor switch through the injected provider.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressHandlerTest {

    private fun sessionWith(installMode: InstallMode) = FakeInstallerSession(
        analysisResults = listOf(FakeInstallerSession.singleApk("com.example.a", "a".repeat(64))),
        initialProgress = ProgressEntity.InstallAnalysing,
    ).also {
        it.config = it.config.copy(installMode = installMode)
    }

    private suspend fun drive(handler: ProgressHandler, session: FakeInstallerSession) {
        handler.onStart()
        session.emit(ProgressEntity.InstallAnalysedSuccess)
        // Let the collector run.
        kotlinx.coroutines.yield()
    }

    @Test
    fun autoDialogSkipsAutoInstallWhenAdvisorEnabled() = runTest {
        val session = sessionWith(InstallMode.AutoDialog)
        val handler = ProgressHandler(TestScope(testScheduler), session) { true }
        drive(handler, session)
        advanceUntilIdle()
        assertEquals(0, session.installCalls.size, "advisor-on AutoDialog must not auto-install")
        handler.onFinish()
    }

    @Test
    fun autoDialogInstallsWhenAdvisorDisabled() = runTest {
        val session = sessionWith(InstallMode.AutoDialog)
        val handler = ProgressHandler(TestScope(testScheduler), session) { false }
        drive(handler, session)
        advanceUntilIdle()
        assertEquals(1, session.installCalls.size, "advisor-off keeps the baseline auto-install")
        handler.onFinish()
    }

    @Test
    fun autoNotificationKeepsBaselineWhenAdvisorEnabled() = runTest {
        // AutoNotification is not AutoDialog: even with the advisor on, the
        // baseline path stays (0 AI calls, not advisor-protected).
        val session = sessionWith(InstallMode.AutoNotification)
        val handler = ProgressHandler(TestScope(testScheduler), session) { true }
        drive(handler, session)
        advanceUntilIdle()
        assertEquals(1, session.installCalls.size, "AutoNotification keeps the baseline auto-install")
        handler.onFinish()
    }
}
