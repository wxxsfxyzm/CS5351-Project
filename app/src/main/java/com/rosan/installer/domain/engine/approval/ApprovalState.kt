// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

/**
 * Implements: ADR-002.3 (contract), ADR-002.6
 * Enforces: REQ-IX-FR-001, REQ-IX-FR-006, REQ-IX-REL-003
 *
 * Operation state model (`memory-design.md` section 1). This mirrors the
 * documented states; a terminal or gated state does not by itself grant a
 * submission right. `RESULT_UNKNOWN` is a post-submission installation
 * result, **not** a policy disposition, and is never a value of
 * `PolicyDisposition` (`runtime-policy-catalog.md` section 2).
 */
enum class ApprovalState {
    /** Request accepted; operation and attempt IDs created. */
    RECEIVED,

    /** Evidence complete; awaiting the advisory attempt. */
    AWAITING_AI,

    /** Evidence incomplete; Continue is disabled. */
    RECOLLECT,

    /** A decision requires human review. */
    AWAIT_HUMAN,

    /** Active hard prohibition; no Continue action. */
    BLOCKED,

    /** A valid approval is bound to the current object. */
    APPROVED,

    /** One permitted submission; consumed on use. */
    EXECUTE_ONCE,

    /** Approval failed revalidation; no submission. */
    INVALIDATED,

    /** Backend accepted the submission. */
    SUBMITTED,

    /** Submitted, outcome not reconcilable; replay forbidden. */
    RESULT_UNKNOWN,

    /** Platform result known. */
    COMPLETED,

    /**
     * The backend reported a failure for a consumed submission permit. This is
     * terminal: the permit is spent, so the operation cannot be submitted
     * again (REQ-IX-INV-005).
     */
    FAILED,

    /** User cancelled before submission. */
    CANCELLED,
}
