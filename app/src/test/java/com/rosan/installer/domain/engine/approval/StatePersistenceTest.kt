// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.engine.policy.PolicyDecision
import com.rosan.installer.domain.engine.policy.PolicyDisposition
import com.rosan.installer.domain.engine.policy.PolicyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies: REQ-IX-REL-002
 *
 * A write failure must be observable (so the coordinator can stop automatic
 * submission), and after a simulated restart the last durable state is
 * readable. The in-memory store is a test double; durable persistence is
 * ADR-002.3.
 */
class StatePersistenceTest {
    private val decision = PolicyResult(
        PolicyDecision.REQUIRE_REVIEW,
        PolicyDisposition.AWAIT_HUMAN,
        "DR-07",
        "ADVISORY_OR_COMPATIBILITY_REQUIRES_REVIEW",
    )

    private fun snapshot(operationId: String = "op-1") = ApprovalSnapshot(
        operationId = operationId,
        attemptId = "attempt-1",
        binding = null,
        state = ApprovalState.AWAIT_HUMAN,
        decision = decision,
    )

    @Test
    fun writeFailureReturnsFalseAndDoesNotMutateState() = runTest {
        val store = InMemoryApprovalStateStore()
        store.failWrites = true
        assertFalse(store.persist(snapshot()))
        assertNull(store.load("op-1"))
    }

    @Test
    fun successfulWriteIsReadableAfterRestart() = runTest {
        val store = InMemoryApprovalStateStore()
        assertTrue(store.persist(snapshot()))
        // Simulate reading the last durable state on a later "process".
        val reloaded = store.load("op-1")
        assertEquals(snapshot(), reloaded)
        assertEquals(ApprovalState.AWAIT_HUMAN, reloaded?.state)
    }

    @Test
    fun laterWriteReplacesEarlierSnapshot() = runTest {
        val store = InMemoryApprovalStateStore()
        store.persist(snapshot())
        val submitted = snapshot().copy(state = ApprovalState.SUBMITTED)
        assertTrue(store.persist(submitted))
        assertEquals(ApprovalState.SUBMITTED, store.load("op-1")?.state)
    }
}
