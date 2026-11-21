# D Verification — Test Plan (Draft v0.1)

**Status:** draft — pending Member A approval (required before tests are
implemented, per `docs/code-governance/verification-constraints.md` §2).
**Owner:** Member D (Verification).
**Date:** 2026-11-21.
**Requirements baseline:** `docs/req/requirements.yaml` rev.6
(`status: draft_for_group_review`).
**Architecture baseline:** `adr/001-architecture-choice.md` (Option B),
`adr/002-later-important-arch-decisions.md`,
`runtime-policy-catalog.md`, `invariants.md`, `trace-matrix.md`.

Nothing in this plan claims that code or tests exist. Every case below is
`planned` unless the status column says otherwise.

---

## 1. Purpose and scope

The plan turns the D1 rev.6 fit criteria into executable checks. Priority
is to prove that the system **never installs automatically when it should
not**: hard blocks win, AI advice cannot lower a restriction, model failure
never auto-approves, approval binds the installation object, and a
repeated event never submits twice.

In scope (MVP, AutoDialog path):

- `DeterministicPolicyEngine` decision table (DR-01 … DR-10).
- `ApprovalCoordinator` human review, revalidation, and submission count.
- `AIRiskAdvisor` / `AIOutputValidator` integration with the policy.
- Structural checks: policy and advisory dependency boundaries.

Out of scope for D (per the PR #6 roadmap): rewriting the installer,
Root/Shizuku compatibility, deciding product rules. AutoNotification is
`Should`; it is tested only if implemented.

---

## 2. Test strategy

| Stage | Trigger | Suites | Dependency |
|---|---|---|---|
| 1 | now | this plan; structural checks (S-01 … S-02) | none |
| 2 | C freezes contracts and ADR-002.1 | `PolicyDecisionTableTest`, `HardBlockPrecedenceTest`, `NoDowngradeTest`, `PolicyWithoutModelTest` | C: `AssessmentEvidence`, `AssessmentResult`, `PolicyDecision` |
| 3 | A delivers Coordinator | `ApprovalCoordinatorTest`, `ApprovalBindingTest`, `IdempotencyTest`, `ControlledInstallTest` | A: testable `ApprovalCoordinator`, `FakeInstallationGateway`, injectable APK hash reader |
| 4 | E's #13 merged | `OutputValidationTest`, `FailureFallbackTest`, `AdvisorIsolationTest`, AI+Policy integration | E: `FixedResponseAdvisor`, failure stubs |
| 5 | end | D3 Verification report | all of the above |

Rules applied to every test (verification-constraints §1, §4):

- Each test names its requirement / invariant / DR rule in its name or
  KDoc, e.g. `/** Verifies: REQ-IX-FR-005, DR-05 */`.
- Every invariant has at least one **negative** test; removing the
  protection must make that test fail (evidence recorded in D3).
- Offline tests use fixed model responses and hand-written fakes (the
  project has no mocking library) and live under `app/src/test`, so the
  existing CI job `testUnstableDebugUnitTest` runs them.
- Live tests that call a real model are kept separate and are not part of
  CI; their results are recorded manually and never reported as a
  deterministic installation guarantee.

Command:

```bash
./gradlew testUnstableDebugUnitTest --no-configuration-cache
```

---

## 3. Structural checks (Stage 1–2)

| ID | Check | Expected | Requirement | Suite | Status |
|---|---|---|---|---|---|
| S-01 | Policy package imports no model SDK, model adapter (`data/ai`), or network client (`okhttp3`, `OkHttpNetworkResolver`, `io.ktor`, `retrofit2`, `java.net`, `javax.net`) | 0 violations | REQ-IX-INV-006 | `PolicyWithoutModelTest` | draft written; skipped until policy package exists |
| S-02 | Policy core imports no `android.*` / `androidx.*` | 0 violations | ADR-002.1 constraint | `PolicyWithoutModelTest` | draft written; skipped until policy package exists |
| S-03 | Advisory package (`AIRiskAdvisor`, `EvidenceSanitizer`, `AIOutputValidator`) references no `AppInstallerRepository`, `InstallationGateway`, `ActionHandler`, `domain/privileged/provider`, `framework/privileged`, session repositories | 0 violations | REQ-IX-INV-001, REQ-IX-SEC-001 | `AdvisorIsolationTest` | planned (Stage 4) |
| S-04 | Only `ApprovalCoordinator` references `InstallationGateway` in the in-scope path | 1 caller | REQ-IX-ARCH-001 | `ChokePointTest` | planned (Stage 3) |

---

## 4. Policy decision table (Stage 2 — `PolicyDecisionTableTest`)

Source of expected values: `requirements.yaml` `decision_rules`
(first match wins) and REQ-IX-FR-005 fit criterion. "Human" =
`NOT_OBTAINED` unless stated.

| ID | Evidence | Compatibility | Success / Risk | Base policy | Human | Expected decision / disposition | Rule | Requirement |
|---|---|---|---|---|---|---|---|---|
| P-01 | COMPLETE | COMPATIBLE | HIGH / LOW | AUTO_RULES_MET | — | ALLOW_AUTO / EXECUTE_ONCE | DR-05 | FR-005, INV-007 |
| P-02 | COMPLETE | COMPATIBLE | HIGH / LOW | AUTO_RULES_NOT_MET | — | REQUIRE_REVIEW / AWAIT_HUMAN | DR-06 | FR-005, INV-007 |
| P-03 | COMPLETE | COMPATIBLE | LOW / LOW | AUTO_RULES_MET | — | REQUIRE_REVIEW / AWAIT_HUMAN | DR-07 | FR-005 |
| P-04 | COMPLETE | COMPATIBLE | MEDIUM / LOW | AUTO_RULES_MET | — | REQUIRE_REVIEW / AWAIT_HUMAN | DR-07 | FR-005 |
| P-05 | COMPLETE | COMPATIBLE | HIGH / **HIGH** | AUTO_RULES_MET | — | **REQUIRE_REVIEW** (never BLOCK) / AWAIT_HUMAN | DR-07 | FR-005, ADR-002.6 |
| P-06 | COMPLETE | COMPATIBLE | HIGH / MEDIUM | AUTO_RULES_MET | — | REQUIRE_REVIEW / AWAIT_HUMAN | DR-07 | FR-005 |
| P-07 | COMPLETE | COMPATIBLE | UNKNOWN / LOW | AUTO_RULES_MET | — | REQUIRE_REVIEW / AWAIT_HUMAN | DR-07 | FR-005 |
| P-08 | COMPLETE | COMPATIBLE | HIGH / UNKNOWN | AUTO_RULES_MET | — | REQUIRE_REVIEW / AWAIT_HUMAN | DR-07 | FR-005 |
| P-09 | COMPLETE | UNKNOWN | HIGH / LOW | AUTO_RULES_MET | — | REQUIRE_REVIEW, never ALLOW_AUTO | DR-07 | FR-005 |
| P-10 | COMPLETE | INCOMPATIBLE | HIGH / LOW | AUTO_RULES_MET | — | REQUIRE_REVIEW, never ALLOW_AUTO | DR-07 | FR-005 |
| P-11 | ANY | ANY | advice INVALID / UNAVAILABLE / BUDGET_REJECTED / OVERAGE / BLOCKED_THIRD | NOT_HARD_BLOCK | — | REQUIRE_REVIEW / AWAIT_HUMAN | DR-08 | INV-004, FR-005 |
| P-12 | ANY | ANY | consent declined | NOT_HARD_BLOCK | — | REQUIRE_REVIEW / AWAIT_HUMAN | DR-02 | FR-011, NFC-OUT-003 |
| P-13 | KEY_UNKNOWN / TRUNCATED / UNREADABLE (one case each) | ANY | ANY | NOT_HARD_BLOCK | ANY | REQUIRE_REVIEW / RECOLLECT | DR-03 | FR-005, NFC-OUT-005 |
| P-14 | CONTRADICTORY | ANY | ANY | NOT_HARD_BLOCK | — | REQUIRE_REVIEW / AWAIT_HUMAN | DR-04 | FR-005 |
| P-15 | COMPLETE | COMPATIBLE | HIGH / LOW | **HARD_BLOCK** | Continue / approved | **BLOCK / BLOCKED** | DR-01 | INV-002, SEC-002 |
| P-16 | ANY | ANY | ANY | NOT_HARD_BLOCK | valid approval + revalidation passed | REQUIRE_REVIEW / EXECUTE_ONCE | DR-09 | FR-005 |
| P-17 | ANY | ANY | ANY | NOT_HARD_BLOCK | revalidation failed after approval | REQUIRE_REVIEW / INVALIDATED | DR-10 | INV-003 |
| P-18 | same input twice, same policy version | — | — | — | — | identical decision, disposition, rule ID | — | FR-005 (determinism) |
| P-19 | exhaustive: every Success × Risk × Compatibility except HIGH/LOW/COMPATIBLE | — | — | AUTO_RULES_MET | — | never ALLOW_AUTO | DR-07 | FR-005 |
| P-20 | exhaustive: every combination with HARD_BLOCK | — | — | HARD_BLOCK | ANY | always BLOCK | DR-01 | INV-002 (`HardBlockPrecedenceTest`) |
| P-21 | base REQUIRE_REVIEW + AI HIGH/LOW | — | — | AUTO_RULES_NOT_MET | — | stays REQUIRE_REVIEW | DR-06 | INV-007 (`NoDowngradeTest`) |
| P-22 | empty / disconnected advisor injected | — | — | — | — | all P-cases still produce BLOCK or REQUIRE_REVIEW without a model call | — | INV-006 (`PolicyWithoutModelTest`, behavioral part) |

---

## 5. Human review, revalidation, and submission count (Stage 3 — `ApprovalCoordinatorTest` and related)

Observation point: a `FakeInstallationGateway` that counts backend
submissions, and an injectable APK SHA-256 reader for TOCTOU cases
(both requested from Member A).

| ID | Scenario | Expected | Requirement | Suite |
|---|---|---|---|---|
| C-01 | REQUIRE_REVIEW, user has not acted | backend submissions 0 | FR-006, FR-007 | `ControlledInstallTest` |
| C-02 | User presses Cancel | submissions 0; approval not reusable | FR-006 | `ApprovalCoordinatorTest` |
| C-03 | First Continue, revalidation passes | binding created; DR-09 EXECUTE_ONCE; exactly 1 submission; 0 additional model calls | FR-006, DR-09 | `ApprovalCoordinatorTest` |
| C-04 | APK SHA-256 changes after approval (TOCTOU) | DR-10 INVALIDATED; submissions 0 | INV-003, ADR-002.7, F-16 | `ApprovalBindingTest` |
| C-05 | Target user changes after approval | INVALIDATED; submissions 0 | INV-003 | `ApprovalBindingTest` |
| C-06 | Only the bound target package name changes; fixture has distinct target and source package names | INVALIDATED; submissions 0; source package not mistaken for target | NFC-OUT-004 | `ApprovalBindingTest` |
| C-07 | Each remaining binding field (`operation_id`, `attempt_id`, `install_mode`, `installation_backend`, `config_snapshot`, `policy_version`) changed or missing, one case per field | INVALIDATED; submissions 0 for every field | INV-003 | `ApprovalBindingTest` |
| C-08 | Repeated Continue clicks / duplicate notification event | at most 1 submission; at most 1 approval | INV-005, FR-006 | `IdempotencyTest` |
| C-09 | 20 concurrent execution events for one operation ID | submissions ≤ 1 | INV-005 | `IdempotencyTest` |
| C-10 | User Retry | new attempt ID only; no new submission right | INV-005, FR-005 | `IdempotencyTest` |
| C-11 | Continue requested while BLOCK | no Continue action / call rejected; submissions 0 | INV-002, FR-006 | `HardBlockPrecedenceTest` |
| C-12 | Continue requested while RECOLLECT | confirmation disabled; submissions 0 | FR-006 | `ApprovalCoordinatorTest` |
| C-13 | ALLOW_AUTO | automatic submission exactly 1 | FR-007, DR-05 | `ControlledInstallTest` |
| C-14 | Process recovery before submission | stays AWAIT_HUMAN; no automatic submission | FR-006, REL-003 | `RecoveryTest` |
| C-15 | Process recovery after submission with unknown outcome | RESULT_UNKNOWN shown; 0 additional submissions; no replay | FR-007, REL-003 | `RecoveryTest` |
| C-16 | `ApprovalStateStore` write failure | automatic submission stopped | REL-002 | `StatePersistenceTest` |

---

## 6. AI advisor and policy integration (Stage 4)

Uses E's `FixedResponseAdvisor` and failure stubs; offline, no network.

| ID | Scenario | Expected | Counters | Requirement | Suite |
|---|---|---|---|---|---|
| A-01 | Advisor returns HIGH / LOW, other auto conditions met | ALLOW_AUTO | 1 dispatch, ≤ 1 submission | FR-005 | integration |
| A-02 | Response contains an install / approve / action field | INVALID → REQUIRE_REVIEW | backend calls 0 | INV-001, FR-004 | `AdvisorIsolationTest`, `OutputValidationTest` |
| A-03 | Invalid JSON, missing dimension, illegal enum, fabricated reference, guarantee language (one case each) | INVALID → REQUIRE_REVIEW | — | FR-004 | `OutputValidationTest` |
| A-04 | Timeout (F-01), network loss after dispatch (F-03), rate limit / service error (F-04) | REQUIRE_REVIEW / AWAIT_HUMAN; no automatic retry | 1 attempt, 1 dispatch, 0 submissions | INV-004, FR-003 | `FailureFallbackTest` |
| A-05 | Network disabled (F-02) | REQUIRE_REVIEW | 0 attempts, 0 dispatches | INV-004, SEC-004 | `FailureFallbackTest` |
| A-06 | Preflight token rejection (F-07) | REQUIRE_REVIEW | 1 attempt consumed, 0 dispatches | COST-001 | `CallBudgetTest` |
| A-07 | Post-call overage (F-08) | REQUIRE_REVIEW | 1 completed call, not re-sent | COST-001 | `TokenBudgetTest` |
| A-08 | Hard block + advisor HIGH / LOW | BLOCK | 0 submissions | INV-002 | `HardBlockPrecedenceTest` |
| A-09 | Advisor reports security risk HIGH, no hard block | REQUIRE_REVIEW, not BLOCK | 0 submissions before Continue | FR-005, ADR-002.6 | integration |
| A-10 | Consent declined (F-06) | REQUIRE_REVIEW | 0 model calls | FR-011, NFC-OUT-003 | `ConsentGateTest` |

Live evaluation (not CI): a small fixed sample set (compatible,
incompatible, insufficient information, high risk; 2–3 APKs each) run
against the real model with E. Record both axes and whether the
explanation matches known evidence. Reported separately from offline
results (REQ-IX-AIQ-001, REQ-IX-QUAL-001).

---

## 7. Inputs required from other members

| From | Item | Needed for |
|---|---|---|
| C | Frozen `AssessmentEvidence`, `AssessmentResult`, `PolicyDecision`, and the `decide()` signature (does the result carry `ruleId` and `reasonCode`?) | Stage 2 |
| C | ADR-002.1 policy package location (update `CANDIDATE_POLICY_DIRS` in `PolicyWithoutModelTest`) | S-01, S-02 |
| A | Testable `ApprovalCoordinator`, counting `FakeInstallationGateway`, injectable APK hash reader, in-memory `ApprovalStateStore` | Stage 3 |
| E | `FixedResponseAdvisor` with HIGH/LOW, LOW success, HIGH risk, UNKNOWN, INVALID responses; timeout / empty / network-loss stubs | Stage 4 |

---

## 8. Open questions for A / C

1. **DR-09 decision field.** DR-09 records `decision: REQUIRE_REVIEW`
   with `disposition: EXECUTE_ONCE`. Should "submission allowed" be
   asserted on the disposition (`EXECUTE_ONCE`) rather than the decision?
   This plan assumes **disposition**.
2. **DR-03 vs. existing approval.** DR-03 has `human: ANY`. If evidence
   becomes TRUNCATED after an approval, is the expected result RECOLLECT
   (DR-03) or INVALIDATED (DR-10, which is listed first)? This plan
   follows the listed order (DR-10 first) pending confirmation.
3. **Offline / live source sets.** verification-constraints §4 defers the
   mapping to an ADR. This plan assumes offline tests in `app/src/test`
   and live tests excluded from CI.
4. **`java.net` in the policy package.** S-01 forbids all of `java.net.*`
   to keep the check simple. Confirm this is acceptable for the policy
   core.

---

## 9. Exit criteria

- Every case in §3–§6 is implemented or explicitly marked "not run" with
  a reason in the D3 report.
- All invariants INV-001 … INV-007 have a passing negative test, with
  evidence that removing the protection makes it fail.
- Safety escapes: 0 (verification-constraints §3).
- Results, commands, and failures are recorded in the D3 Verification
  report; defects are logged in `docs/bug-diary.md`.

---

## 10. Approval

| Role | Name | Decision | Date | Link |
|---|---|---|---|---|
| Member A (approver) | | pending | | |
| Member C (interface review) | | pending | | |
