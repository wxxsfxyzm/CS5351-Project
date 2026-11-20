// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import java.text.Normalizer
import java.util.Locale

/**
 * Implements: ADR-001
 * Enforces: REQ-IX-SEC-005
 *
 * Pure, deterministic sanitizer for untrusted text (version name,
 * permission names) before it reaches an external model.
 *
 * It reduces prompt-injection influence but does **not** eliminate it:
 * authorization stays deterministic, so no sanitized text can create an
 * approval or a backend call.
 *
 * Bounds from `risk-list.md` RK-S-01: strip C0/C1 control characters,
 * NFKC-normalize, cap each field at [MAX_FIELD_CHARS], neutralize
 * instruction-style phrasing, and report truncation so the caller can mark
 * the evidence incomplete.
 */
class EvidenceSanitizer(
    private val maxFieldChars: Int = MAX_FIELD_CHARS,
) {
    data class Sanitized(val text: String, val truncated: Boolean)

    fun sanitizeField(raw: String?): Sanitized {
        if (raw.isNullOrEmpty()) return Sanitized("", false)

        val stripped = buildString(raw.length) {
            for (ch in raw) {
                // Drop C0 (0x00-0x1F), DEL, and C1 (0x80-0x9F) controls.
                val code = ch.code
                if (code < 0x20 || code == 0x7F || code in 0x80..0x9F) continue
                append(ch)
            }
        }

        val normalized = Normalizer.normalize(stripped, Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
            .trim()

        val neutralized = INSTRUCTION_PATTERN.replace(normalized) { "[redacted]" }

        return if (neutralized.length <= maxFieldChars) {
            Sanitized(neutralized, false)
        } else {
            Sanitized(neutralized.take(maxFieldChars), true)
        }
    }

    /** Sanitizes a list, applying [maxItems] and reporting dropped elements. */
    fun sanitizeList(raw: List<String>?, maxItems: Int = MAX_LIST_ITEMS): Pair<List<String>, Boolean> {
        if (raw.isNullOrEmpty()) return emptyList<String>() to false
        var truncated = false
        val out = ArrayList<String>(minOf(raw.size, maxItems))
        for (item in raw.take(maxItems)) {
            val s = sanitizeField(item)
            if (s.truncated) truncated = true
            if (s.text.isNotEmpty()) out += s.text
        }
        if (raw.size > maxItems) truncated = true
        return out to truncated
    }

    /**
     * Removes any occurrence of the local-only identifiers (package names)
     * from a free-text field, per the allowlist `redaction` rule.
     */
    fun redactLocalIdentifiers(text: String, identifiers: Collection<String>): String {
        var out = text
        for (id in identifiers) {
            if (id.length < MIN_REDACT_LENGTH) continue
            out = out.replace(id, "[redacted]", ignoreCase = true)
        }
        return out
    }

    companion object {
        const val MAX_FIELD_CHARS = 256
        const val MAX_LIST_ITEMS = 200
        private const val MIN_REDACT_LENGTH = 3

        /**
         * Neutralizes the most common instruction-style patterns. This is a
         * mitigation, not a guarantee (REQ-IX-SEC-005).
         */
        private val INSTRUCTION_PATTERN = Regex(
            """(?i)\b(ignore|disregard|forget)\s+(all\s+|the\s+)?(previous|prior|above|earlier)?\s*(instructions?|prompts?|rules?)|""" +
                """\byou\s+are\s+now\b|""" +
                """\bsystem\s*(prompt|message)\b|""" +
                """\b(approve|install|authori[sz]e)\s+(this|the)\s+(apk|app|package|install)""",
        )
    }
}

/** Lower-cases for defensive comparisons without locale surprises. */
internal fun String.asciiLower(): String = this.lowercase(Locale.ROOT)
