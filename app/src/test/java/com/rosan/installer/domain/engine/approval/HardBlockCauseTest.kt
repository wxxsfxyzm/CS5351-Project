// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.ai.model.AiUiState
import com.rosan.installer.domain.ai.model.HardBlockCause
import com.rosan.installer.domain.ai.model.InstallEvidence
import com.rosan.installer.domain.ai.model.InstalledSignatureMatch
import com.rosan.installer.domain.ai.model.SignatureVerificationStatus
import com.rosan.installer.domain.engine.policy.PolicyDecision
import com.rosan.installer.domain.engine.policy.PolicyDisposition
import com.rosan.installer.domain.engine.policy.PolicyResult
import com.rosan.installer.ui.page.main.installer.mapper.AiDecisionUiMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Verifies: REQ-IX-FR-006 (the user is told why an install is blocked),
 * REQ-IX-INV-002 (BLOCK keeps Continue disabled).
 */
class HardBlockCauseTest {
    private fun evidence(match: InstalledSignatureMatch, status: SignatureVerificationStatus) = InstallEvidence(installedSignatureMatch = match, signatureVerificationStatus = status)

    @Test
    fun causesNameTheRuleThatFired() {
        assertEquals(
            listOf(HardBlockCause.SIGNATURE_MISMATCH),
            EvaluatedPolicyFactory.hardBlockCauses(evidence(InstalledSignatureMatch.MISMATCH, SignatureVerificationStatus.VERIFIED)),
        )
        assertEquals(
            listOf(HardBlockCause.SIGNATURE_VERIFICATION_FAILED),
            EvaluatedPolicyFactory.hardBlockCauses(evidence(InstalledSignatureMatch.NOT_INSTALLED, SignatureVerificationStatus.FAILED)),
        )
        assertEquals(
            listOf(HardBlockCause.SIGNATURE_MISMATCH, HardBlockCause.SIGNATURE_VERIFICATION_FAILED),
            EvaluatedPolicyFactory.hardBlockCauses(evidence(InstalledSignatureMatch.MISMATCH, SignatureVerificationStatus.FAILED)),
        )
    }

    @Test
    fun causesAreEmptyExactlyWhenThereIsNoHardBlock() {
        for (match in InstalledSignatureMatch.entries) {
            for (status in SignatureVerificationStatus.entries) {
                val e = evidence(match, status)
                assertEquals(
                    EvaluatedPolicyFactory.from(e).hardBlock,
                    EvaluatedPolicyFactory.hardBlockCauses(e).isNotEmpty(),
                    "causes and hardBlock disagree for $match / $status",
                )
            }
        }
    }

    @Test
    fun blockedUiCarriesTheCausesAndKeepsContinueDisabled() {
        val ui = AiDecisionUiMapper.toUiState(
            state = ApprovalState.BLOCKED,
            result = PolicyResult(PolicyDecision.BLOCK, PolicyDisposition.BLOCKED, "DR-01", "HARD_BLOCK"),
            model = null,
            unavailable = null,
            blockCauses = listOf(HardBlockCause.SIGNATURE_MISMATCH),
        )
        val blocked = assertIs<AiUiState.Blocked>(ui)
        assertEquals(listOf(HardBlockCause.SIGNATURE_MISMATCH), blocked.causes)
        assertFalse(blocked.continueEnabled)
    }
}
