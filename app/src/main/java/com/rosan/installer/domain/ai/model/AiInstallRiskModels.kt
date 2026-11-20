// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.model

/**
 * Implements: ADR-001; ADR-002.2
 * Enforces: REQ-IX-SEC-001, REQ-IX-FR-003
 *
 * The outbound assessment request. It contains **only** the allowlisted
 * derived signals and the already-sanitized system + user prompt text.
 *
 * This type deliberately has no field for the target/source package name,
 * APK hash, signer hash, label, path, or APK bytes: those cannot leak by
 * accident because there is nowhere to put them.
 */
data class AiInstallRiskRequest(
    val modelId: String,
    val promptVersion: String,
    val systemPrompt: String,
    val userPrompt: String,
    /** The allowlisted evidence leaves, keyed by `outbound_metadata_allowlist` paths. */
    val evidencePayloadJson: String,
    val maxOutputTokens: Int,
)

/**
 * Failure classification used by the caller to route to
 * `REQUIRE_REVIEW / AWAIT_HUMAN` (DR-02/DR-08) and to fill the audit fields.
 * It is a [Throwable] so transports can throw it out of a failing call
 * while the advisor still receives it as data, never as advice.
 * Implements: ADR-002.2
 * Requirements: REQ-IX-INV-004, REQ-IX-REL-001
 */
sealed class AiAdvisorFailure(message: String) : Throwable(message.substringBefore('\n')) {
    /** ADR-001 Option B, REQ-IX-FR-009: rejection known to precede HTTP dispatch. */
    sealed class BeforeDispatch(message: String) : AiAdvisorFailure(message)

    class NetworkDisabled(cause: String) : BeforeDispatch(cause)
    class ConsentUnavailable : BeforeDispatch("model request consent is unavailable")
    class ApiKeyUnavailable : BeforeDispatch("model API key is unavailable")
    class NetworkError(cause: String) : AiAdvisorFailure(cause)
    class Timeout(cause: String) : AiAdvisorFailure(cause)
    class RateLimited(val retryAfterSeconds: Long?) : AiAdvisorFailure("rate limited")
    class ServiceError(val statusCode: Int, cause: String) : AiAdvisorFailure(cause)
    class OutputOversize(val bytes: Int, val capBytes: Int) : AiAdvisorFailure("response exceeds $capBytes bytes")

    /**
     * The provider accepted the request but produced no usable text:
     * output truncated by the token cap, a content filter, or an empty
     * completion. F-08 (`runtime-policy-catalog.md`).
     */
    class TokenLimitExceeded(cause: String) : AiAdvisorFailure(cause)

    /** Structural/semantic validation failure. Never produces auto-approval. */
    class InvalidOutput(val reason: String) : AiAdvisorFailure(reason)

    override fun fillInStackTrace(): Throwable = this
}

/**
 * Coarse failure classification for the deterministic side.
 *
 * The advisor does **not** decide what to do with a failure: the policy
 * layer maps [code] to `REQUIRE_REVIEW` / `RECOLLECT` / `BLOCK` per
 * `runtime-policy-catalog.md` section 3. The advisor only reports which
 * kind of failure occurred (`REQ-IX-INV-004`).
 */
enum class FailureCode {
    /** F-02: egress blocked by the in-app network setting. 0 dispatches. */
    NETWORK_DISABLED,

    /** Local consent unavailable (DR-02). 0 dispatches. */
    CONSENT_UNAVAILABLE,

    /** F-01: the 7 s model-call budget expired. */
    TIMEOUT,

    /** F-03: connection lost, DNS failure, or no API key. */
    NETWORK_ERROR,

    /** F-04: HTTP 429. */
    RATE_LIMITED,

    /** F-04: any other non-2xx status, or an unparseable provider envelope. */
    SERVICE_ERROR,

    /** F-08: token cap hit. Truncated, filtered, or empty completion. */
    TOKEN_LIMIT_EXCEEDED,

    /** The response body exceeded the local byte cap. */
    OUTPUT_OVERSIZE,

    /** F-05: the body arrived but did not match the fixed format. */
    INVALID_OUTPUT,
}

/**
 * Result of one advisory attempt at the advisor boundary.
 *
 * This type is a **report**, not a command. It says what happened to the AI
 * call and nothing about installation: there is no approve, no install, and
 * no decision field (REQ-IX-INV-001). The deterministic policy layer is the
 * only consumer and the only place a decision is made.
 *
 * - [Ok]: a body arrived. [AssessmentResult.Valid] is a usable advisory
 *   result; `Invalid` / `Missing` are failures (F-05) for the policy layer.
 * - [Failure]: no usable body (F-01 ... F-04, F-08), with an
 *   [AiAdvisorFailure] and the `providerDispatches` consumed.
 *
 * Every non-valid outcome is a failure status only. Nothing here converts a
 * failure into a favorable assessment (REQ-IX-INV-004).
 */
sealed interface AdvisorOutcome {
    data class Ok(val result: AssessmentResult) : AdvisorOutcome
    data class Failure(val failure: AiAdvisorFailure) : AdvisorOutcome
}

/**
 * Implements: ADR-001; ADR-002.2
 * Enforces: REQ-IX-FR-002, REQ-IX-SEC-004
 *
 * A request ready to send, plus **what it discloses**. Produced by
 * `AiRequestBuilder`; carries the transmitted leaf paths so the advisor never
 * has to re-parse the payload to know what the model was given.
 */
data class PreparedAiRequest(
    val request: AiInstallRiskRequest,
    val completeness: EvidenceCompleteness,
    /** Dotted `evidence.*` leaf paths present in the outbound payload. */
    val transmittedPaths: Set<String>,
)

/** `runtime-policy-catalog.md` section 3 code for this failure. */
val AiAdvisorFailure.code: FailureCode
    get() = when (this) {
        is AiAdvisorFailure.NetworkDisabled -> FailureCode.NETWORK_DISABLED
        is AiAdvisorFailure.Timeout -> FailureCode.TIMEOUT
        is AiAdvisorFailure.ConsentUnavailable -> FailureCode.CONSENT_UNAVAILABLE
        is AiAdvisorFailure.ApiKeyUnavailable, is AiAdvisorFailure.NetworkError -> FailureCode.NETWORK_ERROR
        is AiAdvisorFailure.RateLimited -> FailureCode.RATE_LIMITED
        is AiAdvisorFailure.ServiceError -> FailureCode.SERVICE_ERROR
        is AiAdvisorFailure.TokenLimitExceeded -> FailureCode.TOKEN_LIMIT_EXCEEDED
        is AiAdvisorFailure.OutputOversize -> FailureCode.OUTPUT_OVERSIZE
        is AiAdvisorFailure.InvalidOutput -> FailureCode.INVALID_OUTPUT
    }

/** `runtime-policy-catalog.md` section 3 code for this parse outcome, or null when valid. */
val AssessmentResult.code: FailureCode?
    get() = when (this) {
        is AssessmentResult.Valid -> null
        is AssessmentResult.Invalid, is AssessmentResult.Missing -> FailureCode.INVALID_OUTPUT
    }
