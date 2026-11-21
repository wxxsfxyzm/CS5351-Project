// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import java.util.concurrent.ConcurrentHashMap

/**
 * Implements: ADR-002.3 (contract), ADR-002.6
 * Enforces: REQ-IX-INV-005, REQ-IX-REL-004
 *
 * Single-consume submission permit per operation ID. [tryAcquire] returns
 * `true` at most once per ID, so repeated events, concurrent callbacks,
 * notification replays, recovery replays, and user retries for the same
 * operation produce at most one submission. A retry may add an attempt ID but
 * never gets a second permit.
 *
 * Concurrency: the check-and-set is atomic via [ConcurrentHashMap.putIfAbsent],
 * so two racing callers cannot both win.
 */
class SubmissionLedger {
    private val acquired = ConcurrentHashMap.newKeySet<String>()

    /** @return true the first time it is called for [operationId]; false afterwards. */
    fun tryAcquire(operationId: String): Boolean = acquired.add(operationId)

    /** @return true if a permit was already consumed for [operationId]. */
    fun isConsumed(operationId: String): Boolean = operationId in acquired
}
