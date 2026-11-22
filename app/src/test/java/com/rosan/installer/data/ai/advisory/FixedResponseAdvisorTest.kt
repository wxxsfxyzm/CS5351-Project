// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.advisory

import com.rosan.installer.domain.ai.fixture.EvidenceFixtures
import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import com.rosan.installer.domain.ai.model.AiInstallRiskRequest
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.InstallAssessment
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.ai.usecase.AiRequestBuilder
import com.rosan.installer.domain.ai.usecase.StrictAiOutputValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Verifies: REQ-IX-FR-003, REQ-IX-FR-004, REQ-IX-INV-001, REQ-IX-INV-004,
 *   REQ-IX-MOD-001
 * Offline and deterministic: [FixedResponseAdvisor] never calls a model.
 *
 * The fixture set proves A/D can obtain every advisory shape they need
 * (HIGH success + LOW risk, low success, high risk, UNKNOWN, INVALID) without
 * a network dependency, and that every failure shape stays an explicit
 * failure.
 */
class FixedResponseAdvisorTest {

    private val request = AiRequestBuilder().build(EvidenceFixtures.normalUpdate).request

    private val knownPaths = AiRequestBuilder.ALLOWLISTED_LEAF_PATHS

    private fun validOf(outcome: AdvisorOutcome): AssessmentResult.Valid = assertIs(assertIs<AdvisorOutcome.Ok>(outcome).result)

    /** REQ-IX-FR-003: the favorable fixed case is exactly HIGH + LOW. */
    @Test
    fun highSuccessLowRisk_returnsHighLikelihoodAndLowRisk() = runTest {
        val advisor = FixedResponseAdvisor.highSuccessLowRisk()
        val assessment = validOf(advisor.assessRequest(request, knownPaths)).assessment

        assertEquals(InstallSuccessLikelihood.HIGH, assessment.installSuccessLikelihood)
        assertEquals(SecurityRisk.LOW, assessment.securityRisk)
        assertEquals(FixedResponseAdvisor.MODEL_ID, assessment.modelId)
        assertEquals(FixedResponseAdvisor.PROMPT_VERSION, assessment.promptVersion)
        assertEquals(listOf(FixedResponseAdvisor.EVIDENCE_REF), assessment.evidenceRefs)
        assertEquals(1, advisor.callCount)
    }

    @Test
    fun lowSuccess_returnsLowLikelihood() = runTest {
        val assessment = validOf(
            FixedResponseAdvisor.lowSuccess().assessRequest(request, knownPaths),
        ).assessment
        assertEquals(InstallSuccessLikelihood.LOW, assessment.installSuccessLikelihood)
    }

    @Test
    fun highRisk_returnsHighRisk() = runTest {
        val assessment = validOf(
            FixedResponseAdvisor.highRisk().assessRequest(request, knownPaths),
        ).assessment
        assertEquals(SecurityRisk.HIGH, assessment.securityRisk)
    }

    /**
     * REQ-IX-INV-004: UNKNOWN is a usable assessment, not a failure and not a
     * quiet "safe" value.
     */
    @Test
    fun unassessable_returnsUnknownOnBothAxes() = runTest {
        val assessment = validOf(
            FixedResponseAdvisor.unassessable().assessRequest(request, knownPaths),
        ).assessment
        assertEquals(InstallSuccessLikelihood.UNKNOWN, assessment.installSuccessLikelihood)
        assertEquals(SecurityRisk.UNKNOWN, assessment.securityRisk)
    }

    /** REQ-IX-INV-004: INVALID is an explicit parse failure, never a verdict. */
    @Test
    fun invalid_returnsExplicitParseFailure() = runTest {
        val result = assertIs<AdvisorOutcome.Ok>(
            FixedResponseAdvisor.invalid().assessRequest(request, knownPaths),
        ).result
        val invalid = assertIs<AssessmentResult.Invalid>(result)
        assertEquals("security_risk", invalid.field)
    }

    @Test
    fun missing_returnsMissingField() = runTest {
        val result = assertIs<AdvisorOutcome.Ok>(
            FixedResponseAdvisor.missing("security_risk").assessRequest(request, knownPaths),
        ).result
        assertEquals("security_risk", assertIs<AssessmentResult.Missing>(result).field)
    }

    @Test
    fun unavailable_returnsFailureWithoutResult() = runTest {
        val outcome = FixedResponseAdvisor.unavailable().assessRequest(request, knownPaths)
        assertIs<AiAdvisorFailure.NetworkDisabled>(assertIs<AdvisorOutcome.Failure>(outcome).failure)
    }

    @Test
    fun recordsRequestEvidencePathsAndCallCount() = runTest {
        val advisor = FixedResponseAdvisor.highSuccessLowRisk()
        repeat(2) { advisor.assessRequest(request, knownPaths) }

        assertEquals(2, advisor.callCount)
        assertEquals(request, assertNotNull(advisor.lastRequest))
        assertEquals(knownPaths, advisor.lastKnownEvidencePaths)
    }

    @Test
    fun scripted_advancesAndRepeatsLast() = runTest {
        val advisor = FixedResponseAdvisor.scripted(
            AdvisorOutcome.Ok(AssessmentResult.Invalid("not json")),
            AdvisorOutcome.Ok(
                AssessmentResult.Valid(
                    InstallAssessment(
                        installSuccessLikelihood = InstallSuccessLikelihood.HIGH,
                        securityRisk = SecurityRisk.LOW,
                        reasons = listOf("recovered"),
                        evidenceRefs = listOf(FixedResponseAdvisor.EVIDENCE_REF),
                        modelId = FixedResponseAdvisor.MODEL_ID,
                        promptVersion = FixedResponseAdvisor.PROMPT_VERSION,
                    ),
                ),
            ),
        )

        assertIs<AssessmentResult.Invalid>(
            assertIs<AdvisorOutcome.Ok>(advisor.assessRequest(request, knownPaths)).result,
        )
        assertIs<AssessmentResult.Valid>(
            assertIs<AdvisorOutcome.Ok>(advisor.assessRequest(request, knownPaths)).result,
        )
        assertIs<AssessmentResult.Valid>(
            assertIs<AdvisorOutcome.Ok>(advisor.assessRequest(request, knownPaths)).result,
        )
        assertEquals(3, advisor.callCount)
    }

    /** An empty script must still be total: it reports a failure, never Valid. */
    @Test
    fun emptyScript_reportsFailureNeverValid() = runTest {
        val outcome = FixedResponseAdvisor.scripted().assessRequest(request, knownPaths)
        val failure = assertIs<AdvisorOutcome.Failure>(outcome)
        assertIs<AiAdvisorFailure.InvalidOutput>(failure.failure)
    }

    /**
     * REQ-IX-FR-004: every fixed valid fixture survives the real strict
     * validator, so A/D see the same shape the production path produces.
     */
    @Test
    fun everyValidFixture_passesTheStrictValidator() = runTest {
        val validator = StrictAiOutputValidator()
        val fixtures = listOf(
            FixedResponseAdvisor.highSuccessLowRisk(),
            FixedResponseAdvisor.lowSuccess(),
            FixedResponseAdvisor.highRisk(),
            FixedResponseAdvisor.unassessable(),
        )
        for (advisor in fixtures) {
            val valid = validOf(advisor.assessRequest(request, knownPaths))
            val reparsed = validator.parse(
                rawResponse = """{"install_success_likelihood":"${valid.assessment.installSuccessLikelihood.name}",""" +
                    """"security_risk":"${valid.assessment.securityRisk.name}",""" +
                    """"reasons":${valid.assessment.reasons.joinToString(",", "[", "]") { "\"$it\"" }},""" +
                    """"evidence_refs":["${FixedResponseAdvisor.EVIDENCE_REF}"]}""",
                modelId = valid.assessment.modelId,
                promptVersion = valid.assessment.promptVersion,
                knownEvidencePaths = knownPaths,
            )
            assertIs<AssessmentResult.Valid>(reparsed)
        }
    }

    /**
     * REQ-IX-INV-001: a fixed assessment carries no action, approval, or
     * instruction field - the fixed type cannot express a decision.
     */
    @Test
    fun fixedAssessment_hasNoDecisionField() {
        val componentNames = InstallAssessment::class.java.declaredMethods.map { it.name.lowercase() }
        for (forbidden in listOf("action", "approve", "decision", "installnow", "execute")) {
            assertNull(
                componentNames.firstOrNull { it.contains(forbidden) },
                "InstallAssessment must not carry a '$forbidden' component",
            )
        }
    }
}
