// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.engine.approval

import com.rosan.installer.domain.engine.approval.ApprovalSnapshot
import com.rosan.installer.domain.engine.approval.ApprovalStateStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Implements: ADR-002.3 (in-memory only; durability still deferred)
 * Enforces: REQ-IX-REL-002 (the write-completion contract)
 *
 * Production in-memory [ApprovalStateStore]. This slice needs a real store so
 * the coordinator can run in the app; it is **not** durable. A process death
 * loses the state, so REQ-IX-REL-002/003/004 remain unmet (DEBT-003). Adding
 * the Room table is ADR-002.3 and is out of scope.
 *
 * Single instance (registered as a Koin `single`); [persist] always completes
 * synchronously for the in-memory case, so it returns `true`.
 */
class InMemoryApprovalStateStore : ApprovalStateStore {
    private val store = ConcurrentHashMap<String, ApprovalSnapshot>()

    override suspend fun persist(snapshot: ApprovalSnapshot): Boolean {
        store[snapshot.operationId] = snapshot
        return true
    }

    override suspend fun load(operationId: String): ApprovalSnapshot? = store[operationId]
}
