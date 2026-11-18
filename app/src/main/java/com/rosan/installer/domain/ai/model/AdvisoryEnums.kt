// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.model

/**
 * Implements: ADR-001
 * Requirements: REQ-IX-FR-003
 *
 * Advisory axis `install_success_likelihood` from `requirements.yaml`
 * (`advisory_axes`). This is an estimate, never a guarantee, and it never
 * authorises an install.
 */
enum class InstallSuccessLikelihood { HIGH, MEDIUM, LOW, UNKNOWN; }

/**
 * Implements: ADR-001
 * Requirements: REQ-IX-FR-003
 *
 * Advisory axis `security_risk` from `requirements.yaml`
 * (`advisory_axes`). This is an estimate, never a guarantee.
 */
enum class SecurityRisk { LOW, MEDIUM, HIGH, UNKNOWN; }

/**
 * Implements: ADR-001; ADR-002.1
 * Requirements: REQ-IX-FR-002
 *
 * Completeness of the local evidence at assessment time. Mirrors
 * `outbound_metadata_allowlist.completeness` in `requirements.yaml`.
 * Anything other than [COMPLETE] means the deterministic policy gate must
 * route to `REQUIRE_REVIEW` / `RECOLLECT` (DR-03), never to auto-approval.
 */
enum class EvidenceCompleteness { COMPLETE, KEY_UNKNOWN, TRUNCATED, CONTRADICTORY, UNREADABLE }

/**
 * Implements: ADR-002.1
 * Requirements: REQ-IX-FR-005
 *
 * Local platform/ABI compatibility of the candidate APK with the device.
 * Derived deterministically; no model input is used (REQ-IX-INV-006).
 */
enum class LocalCompatibility { COMPATIBLE, INCOMPATIBLE, UNKNOWN }
