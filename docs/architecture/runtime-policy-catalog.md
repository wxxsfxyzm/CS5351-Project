# Runtime Policy Catalog

**Status:** draft — pending human review.
**Owner:** Member C (Software Architect).
**Baseline:** InstallerX Revived `816ea541`; repository state `0f718739`.
**Requirements:** `docs/req/requirements.yaml` rev.6
(`status: draft_for_group_review`).
**Related:** `adr/002-later-important-arch-decisions.md` (ADR-002.6,
ADR-002.7), `permission-model.md` §9.

This is the runtime exception and degradation catalog (course marker
`[M]`). It states what the system does when advice is missing, late,
malformed, or unfavorable, and how each outcome maps to the decision
recorded in `requirements.yaml`.

Nothing here is implemented. Tests named are planned.

---

## 1. Decision rules (first match wins)

Order is significant. A rule listed earlier takes precedence over every
rule after it. `DR-01` dominates every other rule, so no advisory result
and no human action can override an active hard prohibition
(REQ-IX-INV-002, REQ-IX-SEC-002).

| ID | Evidence | Advice | Policy | Human | Decision | Disposition | Reason code | Compatibility |
|---|---|---|---|---|---|---|---|---|
| DR-01 | ANY | ANY | HARD_BLOCK | ANY | BLOCK | BLOCKED | HARD_BLOCK | ANY |
| DR-10 | ANY | ANY | NOT_HARD_BLOCK | REVALIDATION_FAILED_AFTER_APPROVAL | REQUIRE_REVIEW | INVALIDATED | APPROVAL_INVALIDATED | ANY |
| DR-03 | UNREADABLE_OR_KEY_UNKNOWN_OR_TRUNCATED | ANY | NOT_HARD_BLOCK | ANY | REQUIRE_REVIEW | RECOLLECT | EVIDENCE_INCOMPLETE | ANY |
| DR-02 | ANY | UNAVAILABLE_CONSENT_DECLINED | NOT_HARD_BLOCK | NOT_OBTAINED | REQUIRE_REVIEW | AWAIT_HUMAN | CONSENT_DECLINED | ANY |
| DR-04 | CONTRADICTORY | ANY | NOT_HARD_BLOCK | NOT_OBTAINED | REQUIRE_REVIEW | AWAIT_HUMAN | EVIDENCE_CONTRADICTORY | ANY |
| DR-05 | COMPLETE | VALID_HIGH_SUCCESS_LOW_RISK | AUTO_RULES_MET | NOT_OBTAINED | ALLOW_AUTO | EXECUTE_ONCE | AUTO_APPROVED | COMPATIBLE |
| DR-06 | COMPLETE | VALID_HIGH_SUCCESS_LOW_RISK | AUTO_RULES_NOT_MET | NOT_OBTAINED | REQUIRE_REVIEW | AWAIT_HUMAN | AUTO_RULES_NOT_MET | COMPATIBLE |
| DR-07 | COMPLETE | VALID_ANY | NOT_HARD_BLOCK | NOT_OBTAINED | REQUIRE_REVIEW | AWAIT_HUMAN | ADVISORY_OR_COMPATIBILITY_REQUIRES_REVIEW | ANY |
| DR-08 | ANY | INVALID_OR_UNAVAILABLE_OR_BUDGET_REJECTED_OR_OVERAGE_OR_BLOCKED_THIRD | NOT_HARD_BLOCK | NOT_OBTAINED | REQUIRE_REVIEW | AWAIT_HUMAN | ADVICE_UNUSABLE | ANY |
| DR-09 | ANY | ANY | NOT_HARD_BLOCK | VALID_APPROVAL_AND_REVALIDATION_PASSED | REQUIRE_REVIEW | EXECUTE_ONCE | APPROVED_AFTER_REVIEW | ANY |

Source: `requirements.yaml` `decision_rules`. The rule bodies are not
paraphrased; only the columns are reordered for reading.

Provisional note: the `decision_rules` array is part of the rev.6 draft
awaiting group approval. This table must be regenerated if those rules
change.

---

## 2. Enumerations used by the catalog

| Enum | Values | Source |
|---|---|---|
| Evidence completeness | COMPLETE, KEY_UNKNOWN, TRUNCATED, CONTRADICTORY, UNREADABLE | `outbound_metadata_allowlist.completeness` |
| `install_success_likelihood` | HIGH, MEDIUM, LOW, UNKNOWN | `advisory_axes` |
| `security_risk` | LOW, MEDIUM, HIGH, UNKNOWN | `advisory_axes` |
| Decision | ALLOW_AUTO, REQUIRE_REVIEW, BLOCK | `req-schema.json` |
| Disposition | AWAIT_HUMAN, BLOCKED, EXECUTE_ONCE, INVALIDATED, RECOLLECT | `req-schema.json` |
| Compatibility | COMPATIBLE, INCOMPATIBLE, UNKNOWN | `evidence.platform_compatibility`, `evidence.abi_compatibility` |

The disposition `RESULT_UNKNOWN` used by REQ-IX-FR-008 and
REQ-IX-REL-003 is a post-submission installation result, not a policy
disposition. It is not a value of the `disposition` enum and must not be
added to it.

---

## 3. Failure catalog

Each row names the failure, the routed decision, and the counters. A
hard prohibition present at the same time keeps the outcome `BLOCK`
(DR-01).

| ID | Failure | Routed decision | Attempts | Provider dispatches | Backend submissions | Test |
|---|---|---|---|---|---|---|
| F-01 | Model timeout (7 s) | REQUIRE_REVIEW / AWAIT_HUMAN | 1 consumed | 1 (sent) | 0 | `planned: LatencyBudgetTest` |
| F-02 | Network disabled | REQUIRE_REVIEW / AWAIT_HUMAN | 0 | 0 | 0 | `planned: FailureFallbackTest` |
| F-03 | Network loss after dispatch | REQUIRE_REVIEW / AWAIT_HUMAN | 1 consumed | 1 | 0 | `planned: FailureFallbackTest` |
| F-04 | Rate limiting or service error | REQUIRE_REVIEW / AWAIT_HUMAN | 1 consumed | 1 | 0 | `planned: FailureFallbackTest` |
| F-05 | Invalid or malformed output | REQUIRE_REVIEW / AWAIT_HUMAN | 1 consumed | 1 | 0 | `planned: OutputValidationTest` |
| F-06 | Consent declined or withdrawn | REQUIRE_REVIEW / AWAIT_HUMAN | 0 | 0 | 0 | `planned: ConsentGateTest` |
| F-07 | Preflight token rejection | REQUIRE_REVIEW / AWAIT_HUMAN | 1 consumed | 0 | 0 | `planned: CallBudgetTest` |
| F-08 | Post-call overage (T ≥ 4001) | REQUIRE_REVIEW / AWAIT_HUMAN | 1 consumed | 1 completed call | 0 | `planned: TokenBudgetTest` |
| F-09 | Blocked third request (budget exhausted) | REQUIRE_REVIEW / AWAIT_HUMAN | 0 additional | 0 additional | 0 | `planned: CallBudgetTest` |
| F-10 | AI toggle off | baseline path, no AI decision | 0 | 0 | baseline | `planned: ToggleSemanticsTest` |
| F-11 | Out-of-scope path | baseline path, no AI decision | 0 | 0 | baseline | `planned: ChokePointTest` |
| F-12 | Evidence KEY_UNKNOWN before any attempt | REQUIRE_REVIEW / RECOLLECT | 0 | 0 | 0 | `planned: EvidenceCollectorTest` |
| F-13 | Evidence TRUNCATED at a configured cap | REQUIRE_REVIEW / RECOLLECT | 0 | 0 | 0 | `planned: EvidenceCollectorTest` |
| F-14 | Contradictory local evidence | REQUIRE_REVIEW / AWAIT_HUMAN | 1 | 1 | 0 | `planned: PolicyDecisionTableTest` |
| F-15 | Unreadable APK | REQUIRE_REVIEW / RECOLLECT | 0 | 0 | 0 | `planned: EvidenceCollectorTest` |
| F-16 | Binding revalidation fails after approval | REQUIRE_REVIEW / INVALIDATED | 0 additional | 0 additional | 0 | `planned: ApprovalBindingTest` |

Provisional note: the token caps (2000 aggregate target, 4000 per-call
hard cap) and the attempt and dispatch counts are provisional, pending
Member A review (PMA-COST-001 … PMA-COST-004). This catalog must not be
read as a settled runtime budget.

---

## 4. Per-element runtime failure contracts

Each element is expected to produce one of the outcomes above and never
to swallow a failure silently.

| Element | Failure it can produce | Required runtime behavior | Requirement |
|---|---|---|---|
| `InstallEvidenceCollector` | unknown, truncated, contradictory, unreadable | record completeness state, never infer an undetermined value | REQ-IX-FR-002 |
| `ConsentGate` | declined or withdrawn | 0 model calls, no auto-approval, REQUIRE_REVIEW | REQ-IX-FR-011 |
| `NetworkGate` | network disabled or unavailable | 0 dispatches, REQUIRE_REVIEW | REQ-IX-SEC-004 |
| `EvidenceSanitizer` | oversized text | truncate and mark incomplete, do not crash | REQ-IX-SEC-005 |
| `AIRiskAdvisor` | timeout, rate limit, service error | at most one dispatch per attempt, no automatic retry, route to review | REQ-IX-FR-003, REQ-IX-COST-001 |
| `AIOutputValidator` | invalid JSON, missing dimension, illegal enum, action field, guarantee language, fabricated reference | classify INVALID, no auto-approval | REQ-IX-FR-004 |
| `DeterministicPolicyEngine` | none; it is total | always return a decision and a rule ID, or `BLOCK` if any input is invalid | REQ-IX-FR-005, REQ-IX-INV-006 |
| `HumanReviewHandler` | duplicate event, repeated Continue | at most one approval and one submission | REQ-IX-FR-006, REQ-IX-INV-005 |
| `ApprovalCoordinator` | binding revalidation failure, crash | forbid submission, persist state, no automatic replay | REQ-IX-INV-003, REQ-IX-REL-003 |
| `InstallationGateway` | backend error | report platform status; `RESULT_UNKNOWN` when unknown | REQ-IX-FR-007, REQ-IX-FR-008 |
| `ApprovalStateStore` | write failure | stop automatic submission | REQ-IX-REL-002 |
| `DecisionAuditRecorder` | none; best-effort append must not block a decision | append-only, hash-chained | REQ-IX-FR-009, REQ-IX-DATA-001 |

---

## 5. Degradation matrix

| Configuration | AI decision produced | Model calls | Decision | Baseline checks |
|---|---|---|---|---|
| AI enabled, consent granted, network on | yes | ≤ 2 attempts | per DR table | unchanged |
| AI enabled, consent declined or withdrawn | no | 0 | REQUIRE_REVIEW | unchanged |
| AI enabled, network off | no | 0 | REQUIRE_REVIEW | unchanged |
| AI disabled (REQ-IX-FR-012) | no | 0 | baseline path only, audited `AI_DISABLED` | unchanged |
| Out of scope (REQ-IX-SCOPE-001) | no | 0 | baseline path only | unchanged |
| Hard prohibition active, any configuration | per DR-01 | any | BLOCK | unchanged |

In every row the existing signature, blacklist, permission, and platform
policy checks run exactly as at baseline (REQ-IX-COMP-001). Turning AI
off, declining consent, or taking an out-of-scope path cannot weaken
those checks.

---

## 6. Counter semantics

- **Attempt:** one model-analysis attempt for an operation. At most two
  per operation; attempt 1 is system-initiated, attempt 2 is
  user-initiated only (REQ-IX-COST-001, provisional). A preflight
  rejection still consumes an attempt.
- **Provider dispatch:** 0 when rejected before transmission, exactly 1
  when a request is sent. A post-call overage is 1 completed call.
- **Backend submission:** at most 1 cumulative per operation ID
  (REQ-IX-INV-005, REQ-IX-REL-004). A retry adds only an attempt ID.
- **Additional vs cumulative:** "additional" counters are caused by the
  named action; "cumulative" counters are totals for the same operation
  ID.

---

## 7. Existing baseline mechanisms this catalog depends on

These are the current deterministic checks the catalog preserves, not
new rules:

- `ProcessInstallationUseCase.checkBlockedByProfile()`
  (`app/src/main/java/com/rosan/installer/domain/engine/usecase/ProcessInstallationUseCase.kt:200-249`)
  — throws `BLOCKED_BY_PROFILE_SIGNATURE_MISMATCH` and
  `BLOCKED_BY_PROFILE_SIGNATURE_UNKNOWN`.
- `data/engine/policy/PlatformInstallPolicyChecker.kt` — platform
  sideload policy.
- Blacklist and shared-user-ID lists passed to
  `AppInstallerRepository.doInstallWork`
  (`domain/engine/repository/AppInstallerRepository.kt:34`).

REQ-IX-INV-002 and REQ-IX-SEC-002 map onto these mechanisms. This
catalog does not propose a parallel rule engine.

---

## 8. Traceability

Covers: REQ-IX-FR-002, REQ-IX-FR-003, REQ-IX-FR-004, REQ-IX-FR-005,
REQ-IX-FR-006, REQ-IX-FR-007, REQ-IX-FR-008, REQ-IX-FR-011,
REQ-IX-FR-012, REQ-IX-INV-002, REQ-IX-INV-003, REQ-IX-INV-004,
REQ-IX-INV-005, REQ-IX-INV-006, REQ-IX-SEC-002, REQ-IX-SEC-004,
REQ-IX-SEC-005, REQ-IX-REL-001, REQ-IX-REL-002, REQ-IX-REL-003,
REQ-IX-REL-004, REQ-IX-COST-001, REQ-IX-PER-001, REQ-IX-COMP-001,
REQ-IX-SCOPE-001, REQ-IX-ARCH-001.
