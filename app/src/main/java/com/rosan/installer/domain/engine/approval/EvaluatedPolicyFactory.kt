// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.ai.model.InstallEvidence
import com.rosan.installer.domain.ai.model.InstallRelation
import com.rosan.installer.domain.ai.model.InstalledSignatureMatch
import com.rosan.installer.domain.ai.model.SignatureVerificationStatus
import com.rosan.installer.domain.engine.policy.EvaluatedPolicy

/**
 * Implements: ADR-002.6 (policy-v1, lead decision 2026-11-22)
 * Enforces: REQ-IX-FR-005, REQ-IX-INV-002
 *
 * Derives the [EvaluatedPolicy] facts from the deterministic local evidence,
 * not from constants. These are the install-permission signals the engine reads;
 * the advisory opinion never enters them.
 *
 *  - `hardBlock` = the selected APK's signature does not match the installed app
 *    (`installedSignatureMatch == MISMATCH`) or the signature does not verify
 *    (`signatureVerificationStatus == FAILED`).
 *  - `baselineAllowsAuto` = `true`: AutoDialog is the user's automatic-install
 *    mode, so the baseline permission is granted in this flow.
 *  - `autoRulesSatisfied` = verified signature AND a recognised install relation
 *    (NEW_INSTALL/UPDATE) AND a compatible installed-signature state
 *    (NOT_INSTALLED/MATCH) AND no shared UID AND not an Xposed module.
 */
object EvaluatedPolicyFactory {

    const val POLICY_VERSION = "policy-v1"

    fun from(evidence: InstallEvidence): EvaluatedPolicy {
        val hardBlock = evidence.installedSignatureMatch == InstalledSignatureMatch.MISMATCH ||
            evidence.signatureVerificationStatus == SignatureVerificationStatus.FAILED

        val autoRulesSatisfied =
            evidence.signatureVerificationStatus == SignatureVerificationStatus.VERIFIED &&
                evidence.installRelation in setOf(InstallRelation.NEW_INSTALL, InstallRelation.UPDATE) &&
                evidence.installedSignatureMatch in setOf(
                    InstalledSignatureMatch.NOT_INSTALLED,
                    InstalledSignatureMatch.MATCH,
                ) &&
                evidence.hasSharedUid == false &&
                evidence.isXposedModule == false

        return EvaluatedPolicy(
            version = POLICY_VERSION,
            hardBlock = hardBlock,
            baselineAllowsAuto = true,
            autoRulesSatisfied = autoRulesSatisfied,
        )
    }
}
