// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.model

/**
 * Implements: ADR-001
 * Enforces: REQ-IX-FR-002, REQ-IX-SEC-001, REQ-IX-INV-003
 *
 * The **local-only** installation evidence gathered before an advisory
 * assessment. Every field is optional so that completeness can be
 * reported honestly instead of inferred: a `null` field is *not known*,
 * never a default.
 *
 * Privacy rule (`outbound_metadata_allowlist`): only the fields listed in
 * [AiRequestBuilder]'s allowlist are ever transmitted. The identifier and
 * hash fields below are kept locally for the approval binding and the
 * audit trail and are explicitly excluded from the outbound request.
 */
data class InstallEvidence(
    // --- allowlisted: derived, non-identifying signals ---------------------
    val versionCode: Long? = null,
    val versionName: String? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val declaredPermissions: List<String> = emptyList(),
    val signatureVerificationStatus: SignatureVerificationStatus? = null,
    val installedSignatureMatch: InstalledSignatureMatch? = null,
    val installRelation: InstallRelation? = null,
    val apkIdentity: ApkIdentity? = null,
    val hasSharedUid: Boolean? = null,
    val isXposedModule: Boolean? = null,
    val platformCompatibility: LocalCompatibility? = null,
    val abiCompatibility: LocalCompatibility? = null,
    val storageSufficient: Boolean? = null,
    val deviceApiLevel: Int? = null,

    // --- local-only: never transmitted (PMA-OUT-001) -----------------------
    /** Bound by the approval record; stays local. */
    val targetPackageName: String? = null,
    /** Bound by the approval record; stays local. */
    val apkSha256: String? = null,
) {
    /**
     * Completeness state per `outbound_metadata_allowlist.completeness`.
     *
     * - [EvidenceCompleteness.UNREADABLE] when the APK could not be parsed.
     * - [EvidenceCompleteness.KEY_UNKNOWN] when a required allowlisted key
     *   has no value.
     * - [EvidenceCompleteness.TRUNCATED] when a list hit a configured cap
     *   and elements were dropped.
     * - [EvidenceCompleteness.COMPLETE] otherwise.
     *
     * Implements: ADR-002.1
     * Requirements: REQ-IX-FR-002
     */
    fun completeness(truncated: Boolean = false): EvidenceCompleteness {
        if (apkSha256 == null) return EvidenceCompleteness.UNREADABLE
        if (truncated) return EvidenceCompleteness.TRUNCATED
        val requiredKnown = versionCode != null &&
            minSdk != null &&
            targetSdk != null &&
            signatureVerificationStatus != null &&
            installedSignatureMatch != null &&
            installRelation != null
        return if (requiredKnown) EvidenceCompleteness.COMPLETE else EvidenceCompleteness.KEY_UNKNOWN
    }
}

/**
 * Implements: ADR-001
 * Requirements: REQ-IX-FR-002
 *
 * `signature_verification_status` allowlist enum.
 */
enum class SignatureVerificationStatus { VERIFIED, FAILED, SIGNING_BLOCK_ONLY, UNKNOWN }

/**
 * Implements: ADR-001
 * Requirements: REQ-IX-FR-002
 *
 * `installed_signature_match` allowlist enum.
 */
enum class InstalledSignatureMatch {
    NOT_INSTALLED,
    MATCH,
    MISMATCH,
    ROTATION_COMPATIBLE,
    CANDIDATE_ROTATION_UNCONFIRMED,
    UNKNOWN_ERROR,
}

/**
 * Implements: ADR-001
 * Requirements: REQ-IX-FR-002
 *
 * `install_relation` allowlist enum, derived locally from the target and
 * installed versions.
 */
enum class InstallRelation { NEW_INSTALL, UPDATE, DOWNGRADE, UNKNOWN }

/**
 * Implements: ADR-001
 * Requirements: REQ-IX-FR-002
 *
 * `apk_identity` allowlist enum, derived locally from deterministic
 * content comparison. The underlying hashes are never transmitted.
 */
enum class ApkIdentity { IDENTICAL, DIFFERENT, NOT_APPLICABLE, ERROR, UNKNOWN }
