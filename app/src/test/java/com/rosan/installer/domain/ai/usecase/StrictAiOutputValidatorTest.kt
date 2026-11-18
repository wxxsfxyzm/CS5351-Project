// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.SecurityRisk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-FR-004, REQ-IX-QUAL-001, REQ-IX-INV-001, REQ-IX-INV-004
 * Offline and deterministic: the parser consumes fixed model responses.
 */
class StrictAiOutputValidatorTest {
    private val validator = StrictAiOutputValidator()
    private val paths = AiRequestBuilder.ALLOWLISTED_LEAF_PATHS

    private fun parse(json: String) = validator.parse(json, "test-model", "v1", paths)

    private fun invalidOf(json: String): AssessmentResult.Invalid =
        assertIs<AssessmentResult.Invalid>(parse(json))

    private val validJson = """
        {
          "install_success_likelihood": "HIGH",
          "security_risk": "LOW",
          "reasons": ["target_sdk 35 is current"],
          "evidence_refs": ["evidence.target_sdk"]
        }
    """.trimIndent()

    private fun withLikelihood(value: String) =
        validJson.replace("\"HIGH\"", "\"$value\"")

    /** Verifies: REQ-IX-FR-004 (happy path maps every fixed field). */
    @Test
    fun validResponse_parsesEveryField() {
        val assessment = assertIs<AssessmentResult.Valid>(parse(validJson)).assessment
        assertEquals(InstallSuccessLikelihood.HIGH, assessment.installSuccessLikelihood)
        assertEquals(SecurityRisk.LOW, assessment.securityRisk)
        assertEquals(listOf("target_sdk 35 is current"), assessment.reasons)
        assertEquals(listOf("evidence.target_sdk"), assessment.evidenceRefs)
        assertEquals("test-model", assessment.modelId)
        assertEquals("v1", assessment.promptVersion)
    }

    /** Verifies: REQ-IX-FR-004 (enums are case-insensitive but must be legal). */
    @Test
    fun lowercaseEnums_areAccepted() {
        assertIs<AssessmentResult.Valid>(
            parse(withLikelihood("high").replace("\"LOW\"", "\"low\"")),
        )
    }

    // --- Missing required fields -------------------------------------------
    // Each absent key must be a distinct Missing outcome, never a default.

    /** Verifies: REQ-IX-FR-004 (negative: no install_success_likelihood). */
    @Test
    fun missingLikelihood_isMissingNotSafe() {
        val json = """{"security_risk":"LOW","reasons":["r"],"evidence_refs":["evidence.target_sdk"]}"""
        val missing = assertIs<AssessmentResult.Missing>(parse(json))
        assertEquals(StrictAiOutputValidator.FIELD_LIKELIHOOD, missing.field)
    }

    /** Verifies: REQ-IX-FR-004 (negative: no security_risk). */
    @Test
    fun missingSecurityRisk_isMissingNotSafe() {
        val json = """{"install_success_likelihood":"HIGH","reasons":["r"],"evidence_refs":["evidence.target_sdk"]}"""
        val missing = assertIs<AssessmentResult.Missing>(parse(json))
        assertEquals(StrictAiOutputValidator.FIELD_RISK, missing.field)
    }

    /** Verifies: REQ-IX-FR-004 (negative: no reasons). */
    @Test
    fun missingReasons_isMissing() {
        val json = """{"install_success_likelihood":"HIGH","security_risk":"LOW","evidence_refs":["evidence.target_sdk"]}"""
        assertIs<AssessmentResult.Missing>(parse(json))
    }

    /** Verifies: REQ-IX-FR-004 (negative: no evidence_refs). */
    @Test
    fun missingEvidenceRefs_isMissing() {
        val json = """{"install_success_likelihood":"HIGH","security_risk":"LOW","reasons":["r"]}"""
        assertIs<AssessmentResult.Missing>(parse(json))
    }

    /** Verifies: REQ-IX-INV-004 (empty object is never read as favorable). */
    @Test
    fun emptyObject_isMissing() {
        assertIs<AssessmentResult.Missing>(parse("{}"))
    }

    /** Verifies: REQ-IX-INV-004 (empty response is never read as favorable). */
    @Test
    fun emptyResponse_isInvalid() {
        assertIs<AssessmentResult.Invalid>(parse("   "))
    }

    // --- Invalid content ---------------------------------------------------

    /** Verifies: REQ-IX-FR-004 (negative: illegal enum). */
    @Test
    fun illegalEnum_isInvalid() {
        val invalid = invalidOf(withLikelihood("VERY_SAFE"))
        assertEquals(StrictAiOutputValidator.FIELD_LIKELIHOOD, invalid.field)
    }

    /** Verifies: REQ-IX-FR-004 (negative: enum is not a string). */
    @Test
    fun nonStringEnum_isInvalid() {
        assertIs<AssessmentResult.Invalid>(parse(validJson.replace("\"HIGH\"", "5")))
    }

    /** Verifies: REQ-IX-QUAL-001 (negative: reasons must not be empty). */
    @Test
    fun emptyReasons_isInvalid() {
        invalidOf(validJson.replace("[\"target_sdk 35 is current\"]", "[]"))
    }

    /** Verifies: REQ-IX-QUAL-001 (negative: blank reason). */
    @Test
    fun blankReason_isInvalid() {
        invalidOf(validJson.replace("[\"target_sdk 35 is current\"]", "[\"   \"]"))
    }

    /** Verifies: REQ-IX-QUAL-001 (negative: fabricated evidence reference). */
    @Test
    fun fabricatedEvidenceRef_isInvalid() {
        val invalid = invalidOf(validJson.replace("evidence.target_sdk", "evidence.apk_sha256"))
        assertEquals(StrictAiOutputValidator.FIELD_REFS, invalid.field)
    }

    /** Verifies: REQ-IX-QUAL-001 (negative: empty evidence_refs). */
    @Test
    fun emptyEvidenceRefs_isInvalid() {
        invalidOf(validJson.replace("[\"evidence.target_sdk\"]", "[]"))
    }

    /** Verifies: REQ-IX-INV-001 (negative: action field). */
    @Test
    fun actionField_isInvalid() {
        val json = validJson.replaceFirst("{", """{"action":"INSTALL_NOW",""")
        val invalid = invalidOf(json)
        assertTrue(invalid.reason.contains("action"))
    }

    /** Verifies: REQ-IX-INV-001 (negative: approve instruction). */
    @Test
    fun approvalInstruction_isInvalid() {
        invalidOf(validJson.replaceFirst("{", """{"approval":"granted","""))
    }

    /** Verifies: REQ-IX-FR-004 (negative: guarantee language). */
    @Test
    fun guaranteeLanguage_isInvalid() {
        invalidOf(validJson.replace("target_sdk 35 is current", "this APK is guaranteed safe"))
    }

    /** Verifies: REQ-IX-QUAL-001 (negative: too many reasons). */
    @Test
    fun tooManyReasons_isInvalid() {
        val reasons = (1..StrictAiOutputValidator.MAX_REASONS + 1).joinToString(",") { "\"r$it\"" }
        invalidOf(validJson.replace("[\"target_sdk 35 is current\"]", "[$reasons]"))
    }

    /** Verifies: REQ-IX-QUAL-001 (negative: oversized reason). */
    @Test
    fun oversizedReason_isInvalid() {
        invalidOf(
            validJson.replace(
                "target_sdk 35 is current",
                "x".repeat(StrictAiOutputValidator.MAX_REASON_CHARS + 1),
            ),
        )
    }

    /** Verifies: REQ-IX-FR-004 (negative: non-JSON prose). */
    @Test
    fun nonJson_isInvalid() {
        invalidOf("Install it now, it is safe.")
    }

    /** Verifies: REQ-IX-FR-004 (negative: JSON array root). */
    @Test
    fun arrayRoot_isInvalid() {
        invalidOf("""[{"install_success_likelihood":"HIGH","security_risk":"LOW"}]""")
    }

    /** Verifies: REQ-IX-FR-004 (a fenced object is still accepted). */
    @Test
    fun fencedJson_isAccepted() {
        assertIs<AssessmentResult.Valid>(parse("```json\n$validJson\n```"))
    }

    /** Verifies: REQ-IX-INV-004: no output shape ever yields Valid without all four keys. */
    @Test
    fun everyOmittedKey_isNeverValid() {
        val perKey = mapOf(
            "install_success_likelihood" to
                """{"security_risk":"LOW","reasons":["r"],"evidence_refs":["evidence.target_sdk"]}""",
            "security_risk" to
                """{"install_success_likelihood":"HIGH","reasons":["r"],"evidence_refs":["evidence.target_sdk"]}""",
            "reasons" to
                """{"install_success_likelihood":"HIGH","security_risk":"LOW","evidence_refs":["evidence.target_sdk"]}""",
            "evidence_refs" to
                """{"install_success_likelihood":"HIGH","security_risk":"LOW","reasons":["r"]}""",
        )
        perKey.forEach { (field, json) ->
            val result = parse(json)
            val missing = assertIs<AssessmentResult.Missing>(
                result,
                "omitting $field must be Missing, not $result",
            )
            assertEquals(field, missing.field)
        }
    }
}
