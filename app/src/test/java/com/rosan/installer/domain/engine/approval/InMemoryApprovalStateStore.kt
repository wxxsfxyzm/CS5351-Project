// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

/**
 * Verifies: REQ-IX-REL-002 (test double)
 *
 * In-memory [ApprovalStateStore] used only by tests in this slice. It is not
 * a durable implementation: [failWrites] simulates a write-failure injection,
 * and [persist] returns `false` in that case so the caller can prove that no
 * automatic submission happens.
 */
class InMemoryApprovalStateStore : ApprovalStateStore {
    private val store = LinkedHashMap<String, ApprovalSnapshot>()

    /** When true, [persist] fails without changing stored state. */
    var failWrites: Boolean = false

    override suspend fun persist(snapshot: ApprovalSnapshot): Boolean {
        if (failWrites) return false
        store[snapshot.operationId] = snapshot
        return true
    }

    override suspend fun load(operationId: String): ApprovalSnapshot? = store[operationId]
}
