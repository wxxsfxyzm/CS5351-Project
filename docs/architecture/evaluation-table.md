# D2 Architecture Evaluation Table

**Status:** draft — pending human review.
**Owner:** Member C (Software Architect).
**Baseline:** InstallerX Revived `816ea541`; repository state `0f718739`.
**Source of requirements:** `docs/req/requirements.yaml` rev.6
(`status: draft_for_group_review`).
**Related ADRs:** `adr/001-architecture-choice.md`,
`adr/002-later-important-arch-decisions.md`.

This table is an architecture quality-attribute evaluation, not a model
quality evaluation. Model evaluation lives in the AI quality
evaluation work (REQ-IX-AIQ-001).

No requirement in this table is claimed as verified. The AI component
and the deterministic policy gate are specified but not implemented, so
every "Current assessment" cell is `not yet evaluated`.

---

## 1. Why an ADD-style evaluation

The course D2 deliverable asks for an architecture design driven by
architecturally significant requirements (ASRs), with candidate
architectures compared before one is chosen. This project uses the
Attribute-Driven Design (ADD) structure:

1. Choose the requirements that most constrain the structure.
2. Group those requirements into design drivers.
3. Propose candidate architectures.
4. Evaluate each candidate against the drivers.
5. Justify the selected design and record it as an ADR.

---

## 2. ADD drivers

The nine ASRs in `requirements.yaml` are grouped into four drivers.
Grouping is by the kind of structural pressure the ASR puts on the
architecture, not by requirement ID.

### Driver D1 — Deterministic authority over an untrusted advisor

| ASR | Requirement pressure |
|---|---|
| REQ-IX-ARCH-001 | Every in-scope submission must pass a single `ApprovalCoordinator → InstallationGateway` choke point. |
| REQ-IX-INV-006 | Policy decisions must run without model SDK or network access, so policy cannot live behind the model boundary. |
| REQ-IX-INV-007 | AI may only maintain or raise a deterministic restriction; auto-approval requires the base policy to already permit it. |

Structural pressure: the model must be unable to reach the decision or
the installer by construction, not by convention.

### Driver D2 — Object-bound, tamper-evident approval

| ASR | Requirement pressure |
|---|---|
| REQ-IX-INV-003 | Approval binds operation ID, attempt ID, APK SHA-256, package name, target user, install mode, backend, config snapshot, and policy version; any change invalidates it. |
| REQ-IX-REL-002 | Request, binding, and decision must be persisted at defined transitions; an unreliable write stops automatic submission. |
| REQ-IX-REL-003 | After restart, unsubmitted requests are revalidated; unknown submission outcomes become `RESULT_UNKNOWN` with replay forbidden. |

Structural pressure: the safety chain needs a durable state model and
a pre-submission revalidation step that survives process death.

### Driver D3 — Safe degradation under model failure

| ASR | Requirement pressure |
|---|---|
| REQ-IX-INV-004 | Timeout, network loss, rate limit, service error, invalid output, preflight rejection, and post-call overage never auto-approve. |

Structural pressure: failure handling must be a normal path in the
coordinator and UI, not an exception side effect.

### Driver D4 — Replaceable, sanitized model boundary

| ASR | Requirement pressure |
|---|---|
| REQ-IX-MOD-001 | Provider and call configuration sit behind one adapter interface; policy and UI do not depend on provider response details. |
| REQ-IX-SEC-005 | Untrusted text is sanitized before it reaches an external model; this reduces but does not eliminate injection influence. |

Structural pressure: one narrow, swappable, attacking surface between
evidence and the external model.

### Non-ASR constraints carried alongside

These do not drive structure but restrict every candidate:

- REQ-IX-INV-001, REQ-IX-INV-002, REQ-IX-INV-005 — advisory-only AI,
  hard-block precedence, single submission per operation.
- REQ-IX-SCOPE-001 — single APK, `InstallerActivity`, `AutoDialog` (Must)
  and `AutoNotification` (Should), `Authorizer.None`; `ActionHandler`
  session bypass remains a documented non-goal.
- REQ-IX-COMP-001 — reuse existing APK analysis and installer
  capabilities; baseline policy, permission checks, notifications, and
  history stay regressible.
- REQ-IX-ARCH-002 — both Material 3 and Miuix UI families.

---

## 3. Candidate architectures

Two candidates were carried forward for evaluation. A third variant,
splitting the model call out of process, was folded into Candidate B
because it does not change the authority relationship.

### Candidate A — Autonomous multi-agent

An AI planner agent owns the install decision. Sub-agents analyse the
APK, call the model, and invoke the installer. A policy module exists
but is advisory to the planner. The planner holds the session and the
installer backend handle.

Shape: `UI → PlannerAgent → {AnalysisAgent, ModelAgent, InstallAgent}
→ installer backend`, with the model reachable from the planner.

### Candidate B — Guarded hybrid with a deterministic policy gate

A deterministic gate owns the decision. The AI component is an advisory
producer behind an adapter. A coordinator sequences collect → sanitize →
advise → validate → policy → (human) → gateway. The gateway is the only
holder of the installer backend on the in-scope path.

Shape: `UI → ApprovalCoordinator → {InstallEvidenceCollector,
EvidenceSanitizer, AIRiskAdvisor, AIOutputValidator,
DeterministicPolicyEngine, HumanReviewHandler, ApprovalStateStore,
DecisionAuditRecorder} → InstallationGateway → AppInstallerRepository`.

### Candidate C — Model-blind baseline (no AI)

Keep the baseline installer untouched; the AI component does not exist.
Retained as the reference point for REQ-IX-COMP-001 and REQ-IX-FR-012,
not as a product candidate.

---

## 4. Evaluation against drivers

Legend: `Pass` = the candidate's structure can satisfy the driver
without a redesign; `Fail` = the structure contradicts the driver or
requires a redesign; `n/a` = the driver does not apply.

| Driver | A: Autonomous multi-agent | B: Guarded hybrid | C: Model-blind |
|---|---|---|---|
| D1 Deterministic authority | **Fail** — the planner authorises; policy is advisory, so INV-006 and INV-007 cannot hold, and INV-002 is unenforceable once the planner holds the backend | **Pass** — the gate decides; the model holds no installer or session capability, so INV-006/INV-007 hold by construction | **Pass** — trivially, no AI |
| D2 Object-bound approval | **Fail** — the planner holds mutable session state; binding and revalidation compete with agent autonomy, and REL-002/REL-003 have no owner | **Pass** — `ApprovalStateStore` owns binding and persistence; `ApprovalCoordinator` revalidates before `InstallationGateway` | **n/a** — baseline has no approval object |
| D3 Safe degradation | **Fail** — a planner that can act on partial model output cannot guarantee fail-safe on every listed failure | **Pass** — degradation is an explicit coordinator path to `REQUIRE_REVIEW`, with hard blocks preserved | **Pass** — no model, no model failure |
| D4 Replaceable, sanitized boundary | **Partial fail** — model access is spread across agents, so a single adapter cannot be enforced | **Pass** — one `AIRiskAdvisor` adapter and one `EvidenceSanitizer` entry point | **n/a** |
| INV-001 no privileged capability | **Fail** — the planner holds the backend | **Pass** — advisory elements have no backend reference | **Pass** |

Candidate A cannot be repaired into compliance with D1 without
discarding the planner's authority, at which point it becomes
Candidate B.

---

## 5. Selected architecture

**Candidate B — guarded hybrid with a deterministic policy gate** is
selected and **accepted** in `adr/001-architecture-choice.md`. The
implementation lives inside the existing `app` module (Option B, no new
Gradle module). The structural claims below are enforced by interfaces,
code review, a static import check, and focused tests rather than by a
module boundary.

Justification against the drivers:

- D1 is satisfied structurally: the model has no path to
  `InstallationGateway`, and policy code has no model dependency.
- D2 is satisfied by giving binding, persistence, and revalidation a
  single owner (`ApprovalStateStore` plus `ApprovalCoordinator`).
- D3 is satisfied by making degradation a first-class coordinator path.
- D4 is satisfied by one adapter and one sanitizer entry point.

The cost is extra coordination code and one more persisted state model
compared with Candidate A. That cost is accepted because REQ-IX-INV-002
and REQ-IX-INV-007 make an advisory policy structurally invalid, and no
course or project goal asks for autonomous installation.

---

## 6. Mapping selected elements to drivers

| Element | Primary driver | Secondary driver |
|---|---|---|
| DeterministicPolicyEngine | D1 | D3 |
| ApprovalCoordinator | D1, D3 | D2 |
| InstallationGateway | D1 | — |
| ApprovalStateStore | D2 | D3 |
| DecisionAuditRecorder | D2 | — |
| HumanReviewHandler | D3 | D1 |
| AIRiskAdvisor | D4 | D3 |
| EvidenceSanitizer | D4 | — |
| AIOutputValidator | D4 | D3 |
| InstallEvidenceCollector | D4 | D2 |
| ConsentGate | D3 | — |
| NetworkGate | D4 | — |
| AuditQuery UI | D2 | — |
| InstallerSession UI | D3 | — |

---

## 7. Quality-attribute scenarios

Each row is a scenario target, not a measured result.

| ID | Attribute | Scenario | Target | Current assessment | Evidence source | Risk |
|---|---|---|---|---|---|---|
| QA-01 | Modifiability | Replace the online model with a fixed-response adapter | Policy and approval tests pass unchanged (REQ-IX-MOD-001) | not yet evaluated | `planned: AdapterContractTest` | adapter contract drift |
| QA-02 | Robustness | Model call times out at 7 s | Decision `REQUIRE_REVIEW` persisted within 15 s, 1 call, no retry (REQ-IX-PER-001, REQ-IX-INV-004) | not yet evaluated | `planned: LatencyBudgetTest` | 15 s budget excludes human time |
| QA-03 | Security | Adversarial text in label or version name | 0 auto-approvals, 0 backend calls, output `INVALID` or `REQUIRE_REVIEW` (REQ-IX-SEC-005) | not yet evaluated | `planned: SanitizerInjectionTest` | injection reduced, not eliminated |
| QA-04 | Safety | Hard prohibition plus AI HIGH/LOW plus human Continue | `BLOCK`/`BLOCKED`, 0 backend submissions (REQ-IX-INV-002) | not yet evaluated | `planned: HardBlockPrecedenceTest` | none known |
| QA-05 | Safety | Binding field changed between approval and submission | `INVALIDATED`, 0 submissions (REQ-IX-INV-003) | not yet evaluated | `planned: ApprovalBindingTest` | TOCTOU window |
| QA-06 | Robustness | Process restart mid-flow | No approval reuse, no duplicate submission, `RESULT_UNKNOWN` identifiable (REQ-IX-REL-003) | not yet evaluated | `planned: RecoveryTest` | Android 17 self-update kill-recovery |
| QA-07 | Cost | Token counts at 2000 / 2001–3999 / 4000 / 4001+ | Runtime and evaluation outcomes per REQ-IX-COST-001 (provisional) | not yet evaluated | `planned: TokenBudgetTest` | caps pending Member A |
| QA-08 | Compatibility | All baseline install scenarios | Existing checks pass at baseline rate (REQ-IX-COMP-001) | not yet evaluated | `planned: BaselineRegressionTest` | dual UI coverage |
| QA-09 | AI quality | Labeled set of ≥20 cases × 3 runs | Structural validity ≥95%, groundedness ≥90% (REQ-IX-AIQ-001, provisional) | not yet evaluated | `planned: AIQualityEvaluation` | threshold pending review |

---

## 7.1 Non-ASR quality attributes

These requirements are not flagged `asr: true`, but each shapes an
architectural element or a test obligation. "Pending realization" means
the code that would satisfy it does not exist yet, so no measurement is
possible. `Planned target` is the threshold or behaviour from the
registry; `Evidence` names the planned test; `Risk pointer` links to
`risk-list.md`.

| ID | Attribute | Requirement | Planned target | Current assessment | Evidence | Risk pointer |
|---|---|---|---|---|---|---|
| NQA-01 | Robustness | REQ-IX-REL-001 | every listed model failure degrades to `REQUIRE_REVIEW`; hard block preserved; 0 silent auto-approvals | pending realization | `planned: FailureModeTest` | RK-S-04, RK-T-07 |
| NQA-02 | Robustness | REQ-IX-REL-002 | durable write at each of the 7 transitions; write failure stops automatic submission | pending realization | `planned: StatePersistenceTest` | RK-T-04 |
| NQA-03 | Robustness | REQ-IX-REL-004 | 20 concurrent submissions → at most 1; retry adds attempt only; third attempt blocked | pending realization | `planned: DedupTest` | RK-T-05 |
| NQA-04 | Performance | REQ-IX-PER-001 | `REQUIRE_REVIEW` ≤1 s after a 7 s timeout; decision persisted ≤15 s; 1 call, no auto-retry; no ANR | pending realization | `planned: LatencyBudgetTest` | RK-T-07 |
| NQA-05 | Cost | REQ-IX-COST-001 (provisional) | ≤2 attempts/op; ≤1 dispatch per sent attempt; 4 000-token inclusive hard cap before send; 95% ≤2 000 in the fixed cohort | pending realization | `planned: CallBudgetTest`, `planned: TokenBudgetTest`, `planned: CostDistributionEvaluation` | RK-C-02 |
| NQA-06 | Modifiability | REQ-IX-MOD-001 (**ASR**) | swapping the provider for a fixed-response adapter leaves policy and approval tests unchanged | pending realization | `planned: AdapterContractTest` | RK-T-08 |
| NQA-07 | AI quality | REQ-IX-AIQ-001 (provisional) | ≥20 cases × 3 runs; structural validity ≥95%; groundedness ≥90%, relevance ≥90% per dimension | pending realization | `planned: AIQualityEvaluation` | RK-S-01, RK-S-03 |
| NQA-08 | Explainability | REQ-IX-QUAL-001 | 100% of accepted reasons cite an existing evidence field; 0 fabricated references | pending realization | `planned: ExplanationGroundingTest` | RK-S-03 |
| NQA-09 | Compatibility | REQ-IX-COMP-001 | install, update, downgrade, signature-mismatch, blacklist scenarios pass at baseline rate | pending realization | `planned: BaselineRegressionTest` | RK-C-01, RK-C-03 |
| NQA-10 | Security | REQ-IX-SEC-004 | 0 outbound when consent or network forbids; leaf paths equal the allowlist; no local-only identifier in the full prompt | pending realization | `planned: OutboundWhitelistTest` | RK-S-02, RK-S-07 |
| NQA-11 | Data integrity | REQ-IX-DATA-001 | append-only, hash-chained; export replays the decision; no credentials | pending realization | `planned: AuditIntegrityTest` | RK-S-05, RK-S-06 |
| NQA-12 | UI consistency | REQ-IX-ARCH-002 | both M3 and Miuix compile and complete the review flow, or an approved exemption exists | pending realization | `planned: DualUiSmokeTest` | RK-C-03 |

NQA-06 restates REQ-IX-MOD-001, which is an ASR; it is repeated here so
the MOD row is visible beside the other quality attributes. Its driver
treatment is in §2 (D4).

No row in this table may be read as a result. The project has no
implementation of any element named in this document.

---

## 8. Provisional and unresolved items

- `outbound_metadata_allowlist` contents and `COST-001` numeric caps are
  provisional, pending Member A review (PMA-OUT-002 … PMA-OUT-006,
  PMA-COST-001 … PMA-COST-004).
- ADR-001 is accepted on Option B: elements live in the existing `app`
  module. Concrete package roots are open and fixed in the first build
  plan (`structural-constraints.md` §1 still requires one element = one
  package).
- `evaluation-table.md` is used here as an architecture QA table. If the
  course intends this file to hold model-evaluation results, the mapping
  must be revisited.

---

## 9. Traceability

- Drivers and evaluations: REQ-IX-ARCH-001, REQ-IX-INV-003,
  REQ-IX-INV-004, REQ-IX-INV-006, REQ-IX-INV-007, REQ-IX-SEC-005,
  REQ-IX-REL-002, REQ-IX-REL-003, REQ-IX-MOD-001.
- Non-ASR quality attributes (§7.1): REQ-IX-REL-001, REQ-IX-REL-004,
  REQ-IX-PER-001, REQ-IX-COST-001, REQ-IX-AIQ-001, REQ-IX-QUAL-001,
  REQ-IX-COMP-001, REQ-IX-SEC-004, REQ-IX-DATA-001, REQ-IX-ARCH-002.
- Selected design: `adr/001-architecture-choice.md`.
- Deferred decisions: `adr/002-later-important-arch-decisions.md`.
- Rejected candidates: `option-ledger.md`.
- Risk pointers: `risk-list.md`.
- Evaluation history: `evaluation-logbook.md`.
