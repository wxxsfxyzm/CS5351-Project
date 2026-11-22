// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.engine.install

import com.rosan.installer.domain.ai.model.InstallModeSignal
import com.rosan.installer.domain.engine.approval.ApprovalBinding
import com.rosan.installer.domain.engine.approval.FakeInstallerSession
import com.rosan.installer.domain.engine.install.GatewayResult
import com.rosan.installer.domain.session.model.ProgressEntity
import com.rosan.installer.domain.settings.model.config.Authorizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Verifies: REQ-IX-FR-007, REQ-IX-FR-008, REQ-IX-INV-003, REQ-IX-INV-005,
 * REQ-IX-ARCH-001.
 *
 * Offline. The gateway wraps the existing `session.install(true)` seam and
 * classifies only on a terminal stage.
 */
class SessionInstallationGatewayTest {

    private fun binding(pkg: String) = ApprovalBinding(
        operationId = "op-1",
        attemptId = "attempt-1",
        apkSha256 = "a".repeat(64),
        targetPackageName = pkg,
        targetUserId = 0,
        installMode = InstallModeSignal.AutoDialog,
        installationBackend = Authorizer.None,
        configSnapshotHash = "cfg-1",
        policyVersion = "policy-v1",
    )

    @Test
    fun packageMismatchBlocks() = runTest {
        // INV-003 second belt: a package other than the session's current one
        // must fail with zero session.install calls.
        val session = FakeInstallerSession(
            analysisResults = listOf(FakeInstallerSession.singleApk("com.example.a", "a".repeat(64))),
        )
        val gateway = SessionInstallationGateway(session)
        val result = gateway.submit(binding("com.example.b"))
        assertIs<GatewayResult.Failed>(result)
        assertEquals(0, session.installCalls.size)
    }

    @Test
    fun callsSessionInstallNotProcessInstallation() = runTest {
        // A valid submission calls session.install(true) once; success => Submitted.
        val session = FakeInstallerSession(
            analysisResults = listOf(FakeInstallerSession.singleApk("com.example.a", "a".repeat(64))),
            initialProgress = ProgressEntity.Ready,
        )
        val gateway = SessionInstallationGateway(session, resultTimeoutMillis = 5_000)
        val job = launch {
            kotlinx.coroutines.yield()
            session.emit(ProgressEntity.InstallSuccess)
        }
        val result = gateway.submit(binding("com.example.a"))
        job.join()
        assertEquals(1, session.installCalls.size)
        assertEquals(true, session.installCalls.single())
        assertEquals(GatewayResult.Submitted, result)
    }

    @Test
    fun waitingUnknownSourceIsNotTerminal() = runTest {
        // INV-005/FR-007: waiting for the permission is a wait, not a failure.
        // progress: Waiting -> (granted, re-issue install(false) by Activity) -> Success
        // The gateway must yield Submitted after the wait, not Failed.
        val session = FakeInstallerSession(
            analysisResults = listOf(FakeInstallerSession.singleApk("com.example.a", "a".repeat(64))),
            initialProgress = ProgressEntity.Ready,
        )
        val gateway = SessionInstallationGateway(session, resultTimeoutMillis = 5_000)
        val job = launch {
            kotlinx.coroutines.yield()
            session.emit(ProgressEntity.InstallWaitingUnknownSource)
            kotlinx.coroutines.yield()
            session.emit(ProgressEntity.InstallSuccess)
        }
        val result = gateway.submit(binding("com.example.a"))
        job.join()
        assertEquals(GatewayResult.Submitted, result)
    }

    @Test
    fun failureIsFailed() = runTest {
        val session = FakeInstallerSession(
            analysisResults = listOf(FakeInstallerSession.singleApk("com.example.a", "a".repeat(64))),
            initialProgress = ProgressEntity.Ready,
        )
        val gateway = SessionInstallationGateway(session, resultTimeoutMillis = 5_000)
        val job = launch {
            kotlinx.coroutines.yield()
            session.emit(ProgressEntity.InstallFailed)
        }
        val result = gateway.submit(binding("com.example.a"))
        job.join()
        assertIs<GatewayResult.Failed>(result)
    }

    @Test
    fun unreconcilableIsResultUnknown() = runTest {
        // No terminal stage within the timeout => ResultUnknown (never "failed").
        val session = FakeInstallerSession(
            analysisResults = listOf(FakeInstallerSession.singleApk("com.example.a", "a".repeat(64))),
            initialProgress = ProgressEntity.Ready,
        )
        val gateway = SessionInstallationGateway(session, resultTimeoutMillis = 100)
        val result = gateway.submit(binding("com.example.a"))
        assertEquals(GatewayResult.ResultUnknown, result)
    }
}
