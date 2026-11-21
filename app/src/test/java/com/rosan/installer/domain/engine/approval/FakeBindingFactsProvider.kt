// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

/**
 * Verifies: REQ-IX-INV-003 (test double)
 *
 * Scriptable [BindingFactsProvider] for tests. Each call returns the next
 * scripted fact list, so a test can prove the coordinator re-reads facts
 * before submission: the first call builds the binding, the second (re-read)
 * returns changed facts and must invalidate the approval (DR-10).
 */
class FakeBindingFactsProvider(
    private vararg val responses: BindingFacts?,
) : BindingFactsProvider {
    private var index = 0
    val calls: Int get() = index

    override suspend fun currentFacts(operationId: String): BindingFacts? {
        val result = if (responses.isEmpty()) null else responses[minOf(index, responses.lastIndex)]
        index++
        return result
    }
}
