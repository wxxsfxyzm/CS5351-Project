// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.engine.approval

import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.InstallAssessment
import com.rosan.installer.domain.ai.model.InstallEvidence
import com.rosan.installer.domain.ai.model.InstallModeSignal
import com.rosan.installer.domain.ai.model.InstallRelation
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.InstalledSignatureMatch
import com.rosan.installer.domain.ai.model.LocalCompatibility
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.ai.model.SignatureVerificationStatus
import com.rosan.installer.domain.engine.policy.DeterministicPolicyEngine
import com.rosan.installer.domain.engine.policy.HumanApprovalState
import com.rosan.installer.domain.engine.policy.PolicyAdvice
import com.rosan.installer.domain.engine.policy.PolicyDecision
import com.rosan.installer.domain.engine.policy.PolicyDisposition
import com.rosan.installer.domain.engine.policy.PolicyInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-FR-005, REQ-IX-INV-002 (policy-v1 facts derivation).
 *
 * Offline. `EvaluatedPolicyFactory` derives the engine facts from the local
 * evidence; the deterministic engine then reaches BLOCK / DR-05 / DR-06 as the
 * rules dictate. The advisory opinion never enters these facts.
 */
class EvaluatedPolicyFactoryTest {

    private val base = InstallEvidence(
        versionCode = 42L,
        versionName = "1.4.2",
        minSdk = 26,
        targetSdk = 35,
        declaredPermissions = listOf("android.permission.INTERNET"),
        signatureVerificationStatus = SignatureVerificationStatus.VERIFIED,
        installedSignatureMatch = InstalledSignatureMatch.NOT_INSTALLED,
        installRelation = InstallRelation.NEW_INSTALL,
        hasSharedUid = false,
        isXposedModule = false,
        platformCompatibility = LocalCompatibility.COMPATIBLE,
        abiCompatibility = LocalCompatibility.COMPATIBLE,
        installMode = InstallModeSignal.AutoDialog,
        targetPackageName = "com.example.a",
        apkSha256 = "a".repeat(64),
    )

    private fun highLowAdvice() = PolicyAdvice.Valid(
        AssessmentResult.Valid(
            InstallAssessment(
                InstallSuccessLikelihood.HIGH,
                SecurityRisk.LOW,
                listOf("evidence.target_sdk"),
                listOf("evidence.target_sdk"),
                "CS5351/DeepSeek-V4-Flash",
                "ai-risk-prompt-v1.1",
            ),
        ),
    )

    private fun input(evidence: InstallEvidence, advice: PolicyAdvice) = PolicyInput(
        policy = EvaluatedPolicyFactory.from(evidence),
        evidence = evidence.completeness(),
        compatibility = LocalCompatibility.COMPATIBLE,
        advice = advice,
        human = HumanApprovalState.NOT_OBTAINED,
    )

    private val engine = DeterministicPolicyEngine()

    @Test
    fun mismatchIsHardBlock() {
        // installedSignatureMatch == MISMATCH => hardBlock => BLOCK (DR-01).
        val evidence = base.copy(installedSignatureMatch = InstalledSignatureMatch.MISMATCH)
        assertTrue(EvaluatedPolicyFactory.from(evidence).hardBlock)
        val result = engine.evaluate(input(evidence, highLowAdvice()))
        assertEquals(PolicyDecision.BLOCK, result.decision)
        assertEquals("DR-01", result.ruleId)
    }

    @Test
    fun failedSignatureIsHardBlock() {
        val evidence = base.copy(signatureVerificationStatus = SignatureVerificationStatus.FAILED)
        assertTrue(EvaluatedPolicyFactory.from(evidence).hardBlock)
        assertEquals(PolicyDecision.BLOCK, engine.evaluate(input(evidence, highLowAdvice())).decision)
    }

    @Test
    fun dr05ReachableWhenAllRulesMet() {
        // Verified + NEW_INSTALL + NOT_INSTALLED + no shared UID + not Xposed,
        // plus HIGH/LOW advice and baselineAllowsAuto => ALLOW_AUTO / DR-05.
        val policy = EvaluatedPolicyFactory.from(base)
        assertFalse(policy.hardBlock)
        assertTrue(policy.baselineAllowsAuto)
        assertTrue(policy.autoRulesSatisfied)
        val result = engine.evaluate(input(base, highLowAdvice()))
        assertEquals(PolicyDecision.ALLOW_AUTO, result.decision)
        assertEquals(PolicyDisposition.EXECUTE_ONCE, result.disposition)
        assertEquals("DR-05", result.ruleId)
    }

    @Test
    fun dr06WhenRulesNotMet() {
        // Shared UID set => autoRulesSatisfied false => DR-06 (review, not auto).
        val evidence = base.copy(hasSharedUid = true)
        val policy = EvaluatedPolicyFactory.from(evidence)
        assertFalse(policy.autoRulesSatisfied)
        val result = engine.evaluate(input(evidence, highLowAdvice()))
        assertEquals(PolicyDecision.REQUIRE_REVIEW, result.decision)
        assertEquals("DR-06", result.ruleId)
    }

    @Test
    fun xposedOrUnrecognisedRelationBlocksAutoRules() {
        assertFalse(EvaluatedPolicyFactory.from(base.copy(isXposedModule = true)).autoRulesSatisfied)
        assertFalse(
            EvaluatedPolicyFactory.from(base.copy(installRelation = InstallRelation.UNKNOWN)).autoRulesSatisfied,
        )
        assertFalse(
            EvaluatedPolicyFactory.from(base.copy(signatureVerificationStatus = SignatureVerificationStatus.UNKNOWN))
                .autoRulesSatisfied,
        )
    }
}
