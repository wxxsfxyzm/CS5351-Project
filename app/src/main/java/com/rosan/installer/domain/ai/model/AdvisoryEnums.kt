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

/**
 * Implements: ADR-001
 * Requirements: REQ-IX-FR-002, REQ-IX-FR-003
 *
 * `install_mode` allowlist enum (`outbound_metadata_allowlist.fields`).
 * Advisory copies of the installation-context signals; the setting types
 * under `domain/settings` and `domain/session` stay out of this package so
 * the advisory model keeps its backend-free boundary (REQ-IX-INV-001).
 *
 * rev.6 defines no unknown encoding for `install_mode`; an unknown context is
 * left absent rather than invented.
 */
enum class InstallModeSignal { AutoDialog, AutoNotification }

/**
 * Implements: ADR-001
 * Requirements: REQ-IX-FR-002
 *
 * `target_user_scope` allowlist enum. The numeric target user ID stays local.
 */
enum class TargetUserScope { CURRENT_USER, OTHER_USER, ALL_USERS, UNKNOWN }

/**
 * Implements: ADR-001
 * Requirements: REQ-IX-FR-002
 *
 * `package_source` allowlist enum. The install-source package name stays local.
 */
enum class PackageSourceSignal { UNSPECIFIED, OTHER, STORE, LOCAL_FILE, DOWNLOADED_FILE }

/**
 * Implements: ADR-001
 * Requirements: REQ-IX-FR-002
 *
 * `source_confidence` allowlist enum. The source UID and package identifiers
 * stay local.
 */
enum class SourceConfidenceSignal {
    EXACT_CALLER,
    LAUNCHED_FROM_UID,
    TRUSTED_PROXY_ORIGINATING_UID,
    PROVIDER_OWNER,
    REFERRER_HEURISTIC,
    UNKNOWN,
}

/**
 * Implements: ADR-001
 * Requirements: REQ-IX-FR-002
 *
 * `installer_capability` allowlist enum. The authorizer's package identifier
 * and raw capability stay local.
 */
enum class InstallerCapability { SYSTEM_PACKAGE_INSTALLER, UNKNOWN }
