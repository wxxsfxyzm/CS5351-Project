// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.InstallAssessment
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.domain.ai.repository.AiOutputValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Implements: ADR-001; ADR-002.2
 * Enforces: REQ-IX-FR-004, REQ-IX-QUAL-001, REQ-IX-INV-001, REQ-IX-INV-004
 *
 * Parses one raw model response into an [AssessmentResult].
 *
 * The fixed format (`architecture-blueprint.md` §7.1) requires exactly
 * four keys:
 *
 * ```
 * {
 *   "install_success_likelihood": "HIGH" | "MEDIUM" | "LOW" | "UNKNOWN",
 *   "security_risk": "LOW" | "MEDIUM" | "HIGH" | "UNKNOWN",
 *   "reasons": ["…", …],
 *   "evidence_refs": ["evidence.target_sdk", …]
 * }
 * ```
 *
 * `model_id` and `prompt_version` are supplied by the caller, not the model,
 * so the model cannot claim a different model or prompt version.
 *
 * Every deviation is an explicit failure. There is no lenient path, no
 * default value, and no "unknown means safe" behaviour: a response that
 * cannot be fully understood becomes [AssessmentResult.Invalid] or
 * [AssessmentResult.Missing], which the caller must route to human review
 * (REQ-IX-INV-004).
 */
class StrictAiOutputValidator(
    private val maxReasons: Int = MAX_REASONS,
    private val maxReasonChars: Int = MAX_REASON_CHARS,
) : AiOutputValidator {

    /**
     * @param knownEvidencePaths the allowlisted leaf paths actually
     *   transmitted for this request; citations outside this set are
     *   treated as fabricated (REQ-IX-QUAL-001).
     */
    override fun parse(
        rawResponse: String,
        modelId: String,
        promptVersion: String,
        knownEvidencePaths: Set<String>,
    ): AssessmentResult {
        val element = runCatching { json.parseToJsonElement(stripFence(rawResponse)) }
            .getOrElse { return AssessmentResult.Invalid("response is not valid JSON") }

        val obj = element as? JsonObject
            ?: return AssessmentResult.Invalid("root is not a JSON object")

        // 0. An empty or truncated body is a missing result, not a verdict.
        if (obj.isEmpty()) return AssessmentResult.Missing(FIELD_LIKELIHOOD)

        // 1. Reject action / instruction fields outright (REQ-IX-INV-001).
        val forbidden = obj.keys
            .filter { key -> ACTION_FIELD_MARKERS.any { key.asciiLower().contains(it) } }
        if (forbidden.isNotEmpty()) {
            return AssessmentResult.Invalid(
                reason = "response contains forbidden action field(s): $forbidden",
                field = forbidden.first(),
            )
        }

        // 2. Both advisory dimensions are mandatory (REQ-IX-FR-004).
        val likelihood = parseEnum<InstallSuccessLikelihood>(obj, FIELD_LIKELIHOOD)
            ?: return missingOrIllegal(obj, FIELD_LIKELIHOOD)
        val risk = parseEnum<SecurityRisk>(obj, FIELD_RISK)
            ?: return missingOrIllegal(obj, FIELD_RISK)

        // 3. Reasons are mandatory and must be non-empty (QUAL-001).
        if (FIELD_REASONS !in obj) return AssessmentResult.Missing(FIELD_REASONS)
        val reasons = parseStringList(obj, FIELD_REASONS, maxReasons, maxReasonChars)
            ?: return AssessmentResult.Invalid(
                reason = "reasons must be a non-empty array of at most $maxReasons strings " +
                    "of at most $maxReasonChars characters",
                field = FIELD_REASONS,
            )

        // 4. Evidence citations are mandatory and must be real.
        if (FIELD_REFS !in obj) return AssessmentResult.Missing(FIELD_REFS)
        val refs = parseStringList(obj, FIELD_REFS, MAX_REFS, MAX_REF_CHARS)
            ?: return AssessmentResult.Invalid(
                reason = "evidence_refs must be a non-empty array of at most $MAX_REFS strings " +
                    "of at most $MAX_REF_CHARS characters",
                field = FIELD_REFS,
            )
        val fabricated = refs.filterNot { it in knownEvidencePaths }
        if (fabricated.isNotEmpty()) {
            return AssessmentResult.Invalid(
                reason = "evidence_refs cite paths that were not transmitted: $fabricated",
                field = FIELD_REFS,
            )
        }

        // 5. No guarantee language anywhere in the prose (REQ-IX-FR-004).
        val guaranteeing = reasons.firstOrNull { reason ->
            GUARANTEE_MARKERS.any { reason.asciiLower().contains(it) }
        }
        if (guaranteeing != null) {
            return AssessmentResult.Invalid(
                reason = "reasons contain guarantee language: \"$guaranteeing\"",
                field = FIELD_REASONS,
            )
        }

        return AssessmentResult.Valid(
            InstallAssessment(
                installSuccessLikelihood = likelihood,
                securityRisk = risk,
                reasons = reasons,
                evidenceRefs = refs,
                modelId = modelId,
                promptVersion = promptVersion,
            ),
        )
    }

    /** Absent key → [AssessmentResult.Missing]; present but wrong → [AssessmentResult.Invalid]. */
    private fun missingOrIllegal(obj: JsonObject, key: String): AssessmentResult =
        if (key !in obj) {
            AssessmentResult.Missing(key)
        } else {
            AssessmentResult.Invalid("$key has an illegal value: ${obj[key]}", field = key)
        }

    private inline fun <reified T : Enum<T>> parseEnum(obj: JsonObject, key: String): T? {
        val raw = (obj[key] as? JsonPrimitive)?.contentOrNull ?: return null
        return enumValues<T>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }

    /** Requires a JSON array of 1..[maxItems] non-blank strings within [maxChars]. */
    private fun parseStringList(
        obj: JsonObject,
        key: String,
        maxItems: Int,
        maxChars: Int,
    ): List<String>? {
        val array = obj[key] as? JsonArray ?: return null
        if (array.isEmpty() || array.size > maxItems) return null
        return array.map { el ->
            val text = (el as? JsonPrimitive)?.contentOrNull ?: return null
            if (text.isBlank() || text.length > maxChars) return null
            text.trim()
        }
    }

    /**
     * Tolerates a markdown code fence around the object, nothing else.
     * Any surrounding prose makes the body unparseable and therefore invalid.
     */
    private fun stripFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```")
            .substringBeforeLast("```")
            .trim()
    }

    companion object {
        const val MAX_REASONS = 5
        const val MAX_REASON_CHARS = 400
        const val MAX_REFS = 15
        const val MAX_REF_CHARS = 120

        const val FIELD_LIKELIHOOD = "install_success_likelihood"
        const val FIELD_RISK = "security_risk"
        const val FIELD_REASONS = "reasons"
        const val FIELD_REFS = "evidence_refs"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Any key containing one of these is treated as an attempt to give
         * an action or an approval, and invalidates the whole response.
         */
        private val ACTION_FIELD_MARKERS = listOf(
            "action", "approve", "approval", "install_now", "install_decision",
            "decision", "execute", "command", "authori", "proceed", "submit",
        )

        private val GUARANTEE_MARKERS = listOf(
            "guarantee", "guaranteed", "100%", "absolute certainty", "definitely safe",
            "completely safe", "risk-free", "zero risk",
        )
    }
}
