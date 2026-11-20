// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.repository

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Implements: ADR-001 Option B, DR-02
 * Enforces: REQ-IX-FR-011, REQ-IX-FR-012, REQ-IX-SEC-004, REQ-IX-INV-004
 *
 * The caller installs this element with `withContext` around one assessment.
 * [readConsent] must read the current authorization for that assessment on
 * each check. Only `true` authorizes egress; missing, declined, withdrawn,
 * and unreadable consent all deny it. Never supply a cached grant.
 */
class ModelRequestGate(
    private val readConsent: suspend () -> Boolean?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ModelRequestGate> {
        /** Reads the current assessment's consent without exposing read failures (REQ-IX-FR-009). */
        internal suspend fun hasCurrentConsent(): Boolean {
            val context = currentCoroutineContext()
            context.ensureActive()
            val granted = try {
                context[Key]?.readConsent() == true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            context.ensureActive()
            return granted
        }
    }
}
