// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.verification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reference implementations and test fakes for Member D's verification suites.
 *
 * Implements: ADR-001 (Option B), ADR-002.6 (Authority Separation), ADR-002.7 (Binding & Tamper Resistance)
 * Enforces: REQ-IX-FR-003..007, REQ-IX-INV-001..007, REQ-IX-COST-001
 */

/**
 * Deterministic policy engine implementing `decision_rules` DR-01 .. DR-10 in exact first-match order.
 *
 * Implements: ADR-002.1, ADR-002.6
 * Enforces: REQ-IX-FR-005, REQ-IX-INV-002, REQ-IX-INV-006, REQ-IX-INV-007
 */
class DeterministicPolicyEngine(
    private val defaultPolicyVersion: String = "v1",
) {
    fun decide(input: PolicyInput): PolicyEvaluationOutcome {
        val version = input.policyVersion.ifBlank { defaultPolicyVersion }

        // DR-01: Active hard prohibition dominates every other rule.
        if (input.basePolicy == BasePolicyState.HARD_BLOCK) {
            return PolicyEvaluationOutcome(
                decision = PolicyDecision.BLOCK,
                disposition = PolicyDisposition.BLOCKED,
                ruleId = "DR-01",
                reasonCode = "HARD_BLOCK",
                policyVersion = version,
            )
        }

        // DR-10: Revalidation failed after approval -> INVALIDATED.
        if (input.humanState == HumanApprovalState.REVALIDATION_FAILED_AFTER_APPROVAL) {
            return PolicyEvaluationOutcome(
                decision = PolicyDecision.REQUIRE_REVIEW,
                disposition = PolicyDisposition.INVALIDATED,
                ruleId = "DR-10",
                reasonCode = "APPROVAL_INVALIDATED",
                policyVersion = version,
            )
        }

        // DR-03: Evidence unreadable, key unknown, or truncated -> RECOLLECT.
        if (input.evidenceCompleteness == EvidenceCompleteness.UNREADABLE ||
            input.evidenceCompleteness == EvidenceCompleteness.KEY_UNKNOWN ||
            input.evidenceCompleteness == EvidenceCompleteness.TRUNCATED
        ) {
            return PolicyEvaluationOutcome(
                decision = PolicyDecision.REQUIRE_REVIEW,
                disposition = PolicyDisposition.RECOLLECT,
                ruleId = "DR-03",
                reasonCode = "EVIDENCE_INCOMPLETE",
                policyVersion = version,
            )
        }

        // DR-09: Valid human approval and revalidation passed (when evidence is complete/valid and not hard-blocked).
        if (input.humanState == HumanApprovalState.VALID_APPROVAL_AND_REVALIDATION_PASSED) {
            return PolicyEvaluationOutcome(
                decision = PolicyDecision.REQUIRE_REVIEW,
                disposition = PolicyDisposition.EXECUTE_ONCE,
                ruleId = "DR-09",
                reasonCode = "APPROVED_AFTER_REVIEW",
                policyVersion = version,
            )
        }

        // DR-02: Outbound consent declined or withdrawn (human NOT_OBTAINED).
        if (input.adviceAvailability == AdviceAvailability.UNAVAILABLE_CONSENT_DECLINED) {
            return PolicyEvaluationOutcome(
                decision = PolicyDecision.REQUIRE_REVIEW,
                disposition = PolicyDisposition.AWAIT_HUMAN,
                ruleId = "DR-02",
                reasonCode = "CONSENT_DECLINED",
                policyVersion = version,
            )
        }

        // DR-04: Contradictory local evidence (human NOT_OBTAINED).
        if (input.evidenceCompleteness == EvidenceCompleteness.CONTRADICTORY) {
            return PolicyEvaluationOutcome(
                decision = PolicyDecision.REQUIRE_REVIEW,
                disposition = PolicyDisposition.AWAIT_HUMAN,
                ruleId = "DR-04",
                reasonCode = "EVIDENCE_CONTRADICTORY",
                policyVersion = version,
            )
        }

        val hasValidAdvice = input.adviceAvailability == AdviceAvailability.VALID && input.assessment != null
        val isFavorableAdvice = hasValidAdvice && input.assessment.isFavorable

        // DR-05: Complete, compatible, HIGH success, LOW risk, auto rules met -> ALLOW_AUTO / EXECUTE_ONCE.
        if (input.evidenceCompleteness == EvidenceCompleteness.COMPLETE &&
            input.compatibility == CompatibilityResult.COMPATIBLE &&
            isFavorableAdvice &&
            input.basePolicy == BasePolicyState.AUTO_RULES_MET
        ) {
            return PolicyEvaluationOutcome(
                decision = PolicyDecision.ALLOW_AUTO,
                disposition = PolicyDisposition.EXECUTE_ONCE,
                ruleId = "DR-05",
                reasonCode = "AUTO_APPROVED",
                policyVersion = version,
            )
        }

        // DR-06: Complete, compatible, HIGH/LOW, auto rules NOT met -> REQUIRE_REVIEW / AWAIT_HUMAN.
        if (input.evidenceCompleteness == EvidenceCompleteness.COMPLETE &&
            input.compatibility == CompatibilityResult.COMPATIBLE &&
            isFavorableAdvice &&
            input.basePolicy == BasePolicyState.AUTO_RULES_NOT_MET
        ) {
            return PolicyEvaluationOutcome(
                decision = PolicyDecision.REQUIRE_REVIEW,
                disposition = PolicyDisposition.AWAIT_HUMAN,
                ruleId = "DR-06",
                reasonCode = "AUTO_RULES_NOT_MET",
                policyVersion = version,
            )
        }

        // DR-07: Complete evidence, any valid advice, not hard block (covers unfavorable/UNKNOWN dimensions or incompatible/UNKNOWN compatibility).
        if (input.evidenceCompleteness == EvidenceCompleteness.COMPLETE && hasValidAdvice) {
            return PolicyEvaluationOutcome(
                decision = PolicyDecision.REQUIRE_REVIEW,
                disposition = PolicyDisposition.AWAIT_HUMAN,
                ruleId = "DR-07",
                reasonCode = "ADVISORY_OR_COMPATIBILITY_REQUIRES_REVIEW",
                policyVersion = version,
            )
        }

        // DR-08: Advice invalid, unavailable, timeout, budget rejected, overage, or blocked third request.
        return PolicyEvaluationOutcome(
            decision = PolicyDecision.REQUIRE_REVIEW,
            disposition = PolicyDisposition.AWAIT_HUMAN,
            ruleId = "DR-08",
            reasonCode = "ADVICE_UNUSABLE",
            policyVersion = version,
        )
    }
}

/**
 * Local approval binding retaining all 9 fields required by REQ-IX-INV-003 and ADR-002.7.
 */
data class ApprovalBinding(
    val operationId: String,
    val attemptId: String,
    val apkSha256: String,
    val targetPackageName: String,
    val targetUserId: Int,
    val installMode: String,
    val installationBackend: String,
    val configSnapshot: String,
    val policyVersion: String,
) {
    fun isComplete(): Boolean = operationId.isNotBlank() &&
        attemptId.isNotBlank() &&
        apkSha256.isNotBlank() &&
        targetPackageName.isNotBlank() &&
        installMode.isNotBlank() &&
        installationBackend.isNotBlank() &&
        configSnapshot.isNotBlank() &&
        policyVersion.isNotBlank()

    companion object {
        fun fromEvidence(evidence: AssessmentEvidence, currentApkSha256: String = evidence.apkSha256): ApprovalBinding = ApprovalBinding(
            operationId = evidence.operationId,
            attemptId = evidence.attemptId,
            apkSha256 = currentApkSha256,
            targetPackageName = evidence.targetPackageName,
            targetUserId = evidence.targetUserId,
            installMode = evidence.installMode,
            installationBackend = evidence.installationBackend,
            configSnapshot = evidence.configSnapshot,
            policyVersion = evidence.policyVersion,
        )
    }
}

enum class SubmissionResultState {
    NOT_SUBMITTED,
    SUBMITTED_SUCCESS,
    RESULT_UNKNOWN,
}

data class StoredOperationState(
    val operationId: String,
    val latestAttemptId: String,
    val evidence: AssessmentEvidence,
    val outcome: PolicyEvaluationOutcome,
    val binding: ApprovalBinding? = null,
    val cancelled: Boolean = false,
    val submissionCount: Int = 0,
    val submissionState: SubmissionResultState = SubmissionResultState.NOT_SUBMITTED,
    val attemptCount: Int = 0,
    val dispatchCount: Int = 0,
)

interface ApprovalStateStore {
    fun get(operationId: String): StoredOperationState?
    fun save(state: StoredOperationState): Boolean
}

class InMemoryApprovalStateStore(
    var failWrites: Boolean = false,
) : ApprovalStateStore {
    private val map = ConcurrentHashMap<String, StoredOperationState>()

    override fun get(operationId: String): StoredOperationState? = map[operationId]

    override fun save(state: StoredOperationState): Boolean {
        if (failWrites) return false
        map[state.operationId] = state
        return true
    }

    fun mutateBinding(operationId: String, transform: (ApprovalBinding) -> ApprovalBinding) {
        val current = map[operationId] ?: error("Operation $operationId not found")
        val existingBinding = current.binding ?: ApprovalBinding.fromEvidence(current.evidence)
        map[operationId] = current.copy(binding = transform(existingBinding))
    }
}

interface InstallationGateway {
    suspend fun submit(evidence: AssessmentEvidence, binding: ApprovalBinding): Boolean
}

class FakeInstallationGateway(
    var simulateUnknownOutcome: Boolean = false,
) : InstallationGateway {
    val submissions = AtomicInteger(0)
    val submittedOperations = mutableListOf<String>()

    override suspend fun submit(evidence: AssessmentEvidence, binding: ApprovalBinding): Boolean {
        submissions.incrementAndGet()
        synchronized(submittedOperations) {
            submittedOperations += evidence.operationId
        }
        return !simulateUnknownOutcome
    }
}

fun interface ApkHashProvider {
    fun readSha256(evidence: AssessmentEvidence): String
}

class FakeApkHashProvider(
    var currentSha256: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
) : ApkHashProvider {
    override fun readSha256(evidence: AssessmentEvidence): String = currentSha256
}

/**
 * Validator for model responses (`AIOutputValidator`).
 *
 * Implements: ADR-002.2, ADR-002.6
 * Enforces: REQ-IX-FR-004, REQ-IX-INV-001, REQ-IX-QUAL-001
 */
class AIOutputValidator {
    sealed class ValidationOutcome {
        data class Valid(val result: AssessmentResult) : ValidationOutcome()
        data class Invalid(val reason: String) : ValidationOutcome()
    }

    fun validateRawJson(
        rawJson: String,
        allowedEvidenceKeys: Set<String> = DEFAULT_ALLOWLIST_KEYS,
    ): ValidationOutcome {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return ValidationOutcome.Invalid("Malformed JSON envelope")
        }

        // Reject any action/install/approve directive (REQ-IX-INV-001, REQ-IX-FR-004).
        if (FORBIDDEN_ACTION_KEY_REGEX.containsMatchIn(trimmed)) {
            return ValidationOutcome.Invalid("Forbidden action or execution instruction field in AI response")
        }

        val likelihoodStr = extractStringField(trimmed, "install_success_likelihood")
            ?: return ValidationOutcome.Invalid("Missing install_success_likelihood")
        val riskStr = extractStringField(trimmed, "security_risk")
            ?: return ValidationOutcome.Invalid("Missing security_risk")

        val likelihood = InstallSuccessLikelihood.entries.firstOrNull { it.name == likelihoodStr }
            ?: return ValidationOutcome.Invalid("Illegal enum for install_success_likelihood: $likelihoodStr")
        val risk = SecurityRisk.entries.firstOrNull { it.name == riskStr }
            ?: return ValidationOutcome.Invalid("Illegal enum for security_risk: $riskStr")

        val reasons = extractStringArray(trimmed, "reasons")
        val refs = extractStringArray(trimmed, "evidence_references")

        val result = AssessmentResult(
            installSuccessLikelihood = likelihood,
            securityRisk = risk,
            reasons = reasons,
            evidenceReferences = refs,
        )
        return validateStructured(result, allowedEvidenceKeys)
    }

    fun validateStructured(
        result: AssessmentResult,
        allowedEvidenceKeys: Set<String> = DEFAULT_ALLOWLIST_KEYS,
    ): ValidationOutcome {
        if (!result.forbiddenActionField.isNullOrBlank()) {
            return ValidationOutcome.Invalid("Forbidden action directive: ${result.forbiddenActionField}")
        }
        if (result.reasons.isEmpty() || result.reasons.all { it.isBlank() }) {
            return ValidationOutcome.Invalid("Missing explanation reasons")
        }
        if (result.reasons.any { GUARANTEE_REGEX.containsMatchIn(it) }) {
            return ValidationOutcome.Invalid("Contains forbidden installation guarantee language")
        }
        if (result.evidenceReferences.isEmpty()) {
            return ValidationOutcome.Invalid("Missing evidence references")
        }
        val fabricated = result.evidenceReferences.filter { it !in allowedEvidenceKeys }
        if (fabricated.isNotEmpty()) {
            return ValidationOutcome.Invalid("Fabricated or non-allowlisted evidence references: $fabricated")
        }
        return ValidationOutcome.Valid(result)
    }

    private fun extractStringField(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractStringArray(json: String, key: String): List<String> {
        val regex = Regex(""""$key"\s*:\s*\[([^\]]*)]""")
        val inner = regex.find(json)?.groupValues?.get(1) ?: return emptyList()
        return Regex(""""([^"]+)"""").findAll(inner).map { it.groupValues[1] }.toList()
    }

    companion object {
        val DEFAULT_ALLOWLIST_KEYS = setOf(
            "version_code",
            "version_name",
            "min_sdk",
            "target_sdk",
            "declared_permissions",
            "signature_verification_status",
            "installed_signature_match",
            "install_relation",
            "apk_identity",
            "install_mode",
            "target_user_scope",
            "package_source",
            "source_confidence",
            "has_shared_uid",
            "is_xposed_module",
            "platform_compatibility",
            "abi_compatibility",
            "storage_sufficient",
            "installer_capability",
        )

        private val FORBIDDEN_ACTION_KEY_REGEX =
            Regex(""""(action|command|execute|approve|install_command|auto_submit)"\s*:""", RegexOption.IGNORE_CASE)

        private val GUARANTEE_REGEX =
            Regex("""\b(guarantee[ds]?|100%\s*(safe|compatible|success)|will\s+definitely\s+install|certif(y|ied)\s+safe)\b""", RegexOption.IGNORE_CASE)
    }
}

/**
 * Advisory response contract from `AIRiskAdvisor`.
 */
sealed class AdvisorCallOutcome {
    data class RawSuccess(
        val result: AssessmentResult? = null,
        val rawJson: String? = null,
        val inputTokens: Int = 300,
        val outputTokens: Int = 100,
    ) : AdvisorCallOutcome()

    data class Failure(val reason: AdviceAvailability) : AdvisorCallOutcome()
}

interface AIRiskAdvisor {
    suspend fun evaluate(evidence: AssessmentEvidence): AdvisorCallOutcome
}

class FixedResponseAdvisor(
    var nextOutcome: AdvisorCallOutcome = AdvisorCallOutcome.RawSuccess(
        result = AssessmentResult(
            installSuccessLikelihood = InstallSuccessLikelihood.HIGH,
            securityRisk = SecurityRisk.LOW,
        ),
    ),
    var estimatedPreflightTokens: Int = 500,
) : AIRiskAdvisor {
    val providerDispatches = AtomicInteger(0)

    override suspend fun evaluate(evidence: AssessmentEvidence): AdvisorCallOutcome {
        providerDispatches.incrementAndGet()
        return nextOutcome
    }
}

/**
 * Coordinator orchestrating evidence -> gates -> advisor -> validator -> policy -> store -> gateway.
 *
 * Implements: ADR-001, ADR-002.6, ADR-002.7
 * Enforces: REQ-IX-FR-001, REQ-IX-FR-005..007, REQ-IX-INV-001..005, REQ-IX-COST-001, REQ-IX-ARCH-001
 */
class ApprovalCoordinator(
    private val policyEngine: DeterministicPolicyEngine = DeterministicPolicyEngine(),
    private val advisor: AIRiskAdvisor? = null,
    private val validator: AIOutputValidator = AIOutputValidator(),
    private val stateStore: ApprovalStateStore = InMemoryApprovalStateStore(),
    private val gateway: InstallationGateway = FakeInstallationGateway(),
    private val apkHasher: ApkHashProvider = FakeApkHashProvider(),
    var consentGranted: Boolean = true,
    var networkEnabled: Boolean = true,
    private val maxAttemptsPerOperation: Int = 2,
    private val perCallTokenHardCap: Int = 4000,
) {
    private val mutex = Mutex()

    suspend fun assess(
        evidence: AssessmentEvidence,
        preflightEstimatedTokens: Int = 500,
    ): PolicyEvaluationOutcome = mutex.withLock {
        val prior = stateStore.get(evidence.operationId)
        var attempts = prior?.attemptCount ?: 0
        var dispatches = prior?.dispatchCount ?: 0

        // Short-circuit before any model attempt if evidence is incomplete or hard-blocked.
        val incomplete = evidence.completeness == EvidenceCompleteness.KEY_UNKNOWN ||
            evidence.completeness == EvidenceCompleteness.TRUNCATED ||
            evidence.completeness == EvidenceCompleteness.UNREADABLE

        val (availability, validatedAssessment) = when {
            evidence.hasHardBlock || incomplete -> {
                AdviceAvailability.INVALID to null
            }

            !consentGranted -> {
                AdviceAvailability.UNAVAILABLE_CONSENT_DECLINED to null
            }

            !networkEnabled -> {
                AdviceAvailability.NETWORK_DISABLED to null
            }

            attempts >= maxAttemptsPerOperation -> {
                AdviceAvailability.BLOCKED_THIRD_REQUEST to null
            }

            preflightEstimatedTokens > perCallTokenHardCap -> {
                // F-07: preflight token rejection consumes 1 attempt and sends 0 provider dispatches.
                attempts += 1
                AdviceAvailability.BUDGET_REJECTED to null
            }

            advisor == null -> {
                AdviceAvailability.INVALID to null
            }

            else -> {
                attempts += 1
                dispatches += 1
                when (val call = advisor.evaluate(evidence)) {
                    is AdvisorCallOutcome.Failure -> call.reason to null

                    is AdvisorCallOutcome.RawSuccess -> {
                        val totalTokens = call.inputTokens + call.outputTokens
                        if (totalTokens > perCallTokenHardCap) {
                            // F-08: post-call overage (T >= 4001) counts 1 completed call and routes to REQUIRE_REVIEW.
                            AdviceAvailability.POST_CALL_OVERAGE to null
                        } else {
                            val validation = when {
                                call.rawJson != null -> validator.validateRawJson(call.rawJson)
                                call.result != null -> validator.validateStructured(call.result)
                                else -> AIOutputValidator.ValidationOutcome.Invalid("Empty model response")
                            }
                            when (validation) {
                                is AIOutputValidator.ValidationOutcome.Valid -> AdviceAvailability.VALID to validation.result
                                is AIOutputValidator.ValidationOutcome.Invalid -> AdviceAvailability.INVALID to null
                            }
                        }
                    }
                }
            }
        }

        val outcome = policyEngine.decide(
            PolicyInput(
                evidenceCompleteness = evidence.completeness,
                compatibility = evidence.combinedCompatibility,
                assessment = validatedAssessment,
                adviceAvailability = availability,
                basePolicy = evidence.basePolicyState,
                humanState = HumanApprovalState.NOT_OBTAINED,
                policyVersion = evidence.policyVersion,
            ),
        )

        var submissions = prior?.submissionCount ?: 0
        var subState = prior?.submissionState ?: SubmissionResultState.NOT_SUBMITTED

        val initialStored = StoredOperationState(
            operationId = evidence.operationId,
            latestAttemptId = evidence.attemptId,
            evidence = evidence,
            outcome = outcome,
            binding = prior?.binding,
            cancelled = false,
            submissionCount = submissions,
            submissionState = subState,
            attemptCount = attempts,
            dispatchCount = dispatches,
        )
        val persisted = stateStore.save(initialStored)

        // Only auto-submit when ALLOW_AUTO / EXECUTE_ONCE, state write succeeded, and never submitted before.
        if (persisted &&
            outcome.decision == PolicyDecision.ALLOW_AUTO &&
            outcome.disposition == PolicyDisposition.EXECUTE_ONCE &&
            submissions == 0
        ) {
            val currentHash = apkHasher.readSha256(evidence)
            val autoBinding = ApprovalBinding.fromEvidence(evidence, evidence.apkSha256)
            if (autoBinding.isComplete() && currentHash == autoBinding.apkSha256) {
                val ok = gateway.submit(evidence, autoBinding)
                submissions += 1
                subState = if (ok) SubmissionResultState.SUBMITTED_SUCCESS else SubmissionResultState.RESULT_UNKNOWN
                stateStore.save(
                    initialStored.copy(
                        binding = autoBinding,
                        submissionCount = submissions,
                        submissionState = subState,
                    ),
                )
            }
        }

        return outcome
    }

    /**
     * First explicit Continue in AWAIT_HUMAN creates the INV-003 binding (if none exists yet),
     * immediately revalidates every bound field and re-reads the APK SHA-256, and submits at most
     * once if valid. Makes zero additional model calls.
     */
    suspend fun onContinue(
        operationId: String,
        currentEvidence: AssessmentEvidence? = null,
    ): PolicyEvaluationOutcome = mutex.withLock {
        val stored = stateStore.get(operationId)
            ?: error("Unknown operationId: $operationId")

        val liveEvidence = currentEvidence ?: stored.evidence

        // Hard prohibition always wins (REQ-IX-INV-002).
        if (liveEvidence.hasHardBlock || stored.outcome.decision == PolicyDecision.BLOCK) {
            val blocked = policyEngine.decide(
                PolicyInput(
                    basePolicy = BasePolicyState.HARD_BLOCK,
                    humanState = HumanApprovalState.VALID_APPROVAL_AND_REVALIDATION_PASSED,
                    policyVersion = liveEvidence.policyVersion,
                ),
            )
            stateStore.save(stored.copy(outcome = blocked))
            return blocked
        }

        // Cancelled, RECOLLECT, or already-submitted operations cannot submit via Continue (REQ-IX-FR-006, REQ-IX-INV-005).
        if (stored.cancelled ||
            stored.outcome.disposition == PolicyDisposition.RECOLLECT ||
            stored.submissionCount >= 1 ||
            stored.submissionState == SubmissionResultState.RESULT_UNKNOWN
        ) {
            return stored.outcome
        }

        // Create binding on first Continue if not already bound, then revalidate against live object + APK hash.
        val binding = stored.binding ?: ApprovalBinding.fromEvidence(stored.evidence)
        val liveSha256 = apkHasher.readSha256(liveEvidence)
        val expectedFromLive = ApprovalBinding.fromEvidence(liveEvidence, liveSha256)

        val revalidationPassed = binding.isComplete() && binding == expectedFromLive

        if (!revalidationPassed) {
            val invalidated = policyEngine.decide(
                PolicyInput(
                    evidenceCompleteness = liveEvidence.completeness,
                    compatibility = liveEvidence.combinedCompatibility,
                    basePolicy = liveEvidence.basePolicyState,
                    humanState = HumanApprovalState.REVALIDATION_FAILED_AFTER_APPROVAL,
                    policyVersion = liveEvidence.policyVersion,
                ),
            )
            stateStore.save(stored.copy(binding = binding, outcome = invalidated))
            return invalidated
        }

        val approvedOutcome = policyEngine.decide(
            PolicyInput(
                evidenceCompleteness = liveEvidence.completeness,
                compatibility = liveEvidence.combinedCompatibility,
                basePolicy = liveEvidence.basePolicyState,
                humanState = HumanApprovalState.VALID_APPROVAL_AND_REVALIDATION_PASSED,
                policyVersion = liveEvidence.policyVersion,
            ),
        )

        val preSubmitSaved = stateStore.save(
            stored.copy(
                binding = binding,
                outcome = approvedOutcome,
            ),
        )
        if (!preSubmitSaved) {
            return approvedOutcome
        }

        val ok = gateway.submit(liveEvidence, binding)
        val subState = if (ok) SubmissionResultState.SUBMITTED_SUCCESS else SubmissionResultState.RESULT_UNKNOWN
        stateStore.save(
            stored.copy(
                binding = binding,
                outcome = approvedOutcome,
                submissionCount = stored.submissionCount + 1,
                submissionState = subState,
            ),
        )
        return approvedOutcome
    }

    suspend fun onCancel(operationId: String) = mutex.withLock {
        val stored = stateStore.get(operationId) ?: return@withLock
        stateStore.save(
            stored.copy(
                cancelled = true,
                binding = null,
            ),
        )
    }

    /**
     * User Retry keeps the same operationId, allocates a new attemptId, and runs a fresh assessment
     * without granting any automatic submission right on top of a prior submission (REQ-IX-INV-005).
     */
    suspend fun onRetry(
        operationId: String,
        newAttemptId: String,
    ): PolicyEvaluationOutcome {
        val stored = stateStore.get(operationId) ?: error("Unknown operationId: $operationId")
        val updatedEvidence = stored.evidence.copy(attemptId = newAttemptId)
        return assess(updatedEvidence)
    }

    /**
     * Process crash/restart recovery reconciliation (REQ-IX-REL-003, REQ-IX-FR-006, REQ-IX-FR-007).
     */
    suspend fun recoverAfterRestart(operationId: String): StoredOperationState = mutex.withLock {
        val stored = stateStore.get(operationId) ?: error("Unknown operationId: $operationId")
        return when (stored.submissionState) {
            SubmissionResultState.NOT_SUBMITTED -> {
                // Must remain AWAIT_HUMAN (or its unsubmitted state) and never auto-approve or auto-submit on recovery.
                val awaiting = stored.outcome.copy(
                    decision = if (stored.outcome.decision == PolicyDecision.BLOCK) PolicyDecision.BLOCK else PolicyDecision.REQUIRE_REVIEW,
                    disposition = if (stored.outcome.decision == PolicyDecision.BLOCK) PolicyDisposition.BLOCKED else PolicyDisposition.AWAIT_HUMAN,
                )
                val recovered = stored.copy(outcome = awaiting, binding = null)
                stateStore.save(recovered)
                recovered
            }

            SubmissionResultState.RESULT_UNKNOWN,
            SubmissionResultState.SUBMITTED_SUCCESS,
            -> {
                // Automatic replay is strictly forbidden.
                stored
            }
        }
    }
}
