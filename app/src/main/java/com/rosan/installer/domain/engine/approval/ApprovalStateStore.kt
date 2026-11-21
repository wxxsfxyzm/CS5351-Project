// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.engine.policy.PolicyResult

/**
 * Implements: ADR-002.3 (contract only; durable implementation deferred)
 * Enforces: REQ-IX-REL-002
 *
 * The durable state written at the transitions named by REQ-IX-REL-002
 * (`memory-design.md` section 4). This is the in-memory shape for the first
 * slice; the Room table and DAO remain ADR-002.3.
 */
data class ApprovalSnapshot(
    val operationId: String,
    val attemptId: String,
    val binding: ApprovalBinding?,
    val state: ApprovalState,
    val decision: PolicyResult?,
)

/**
 * Implements: ADR-002.3
 * Enforces: REQ-IX-REL-002
 *
 * Persistence contract for approval state. [persist] returns `true` only when
 * the write is durable; a `false` result **must** stop automatic submission
 * (REQ-IX-REL-002). This slice ships only a test double; the production Room
 * implementation is deferred to ADR-002.3.
 */
interface ApprovalStateStore {
    /** @return true iff the write is durable. A false MUST stop automatic submission. */
    suspend fun persist(snapshot: ApprovalSnapshot): Boolean

    suspend fun load(operationId: String): ApprovalSnapshot?
}
