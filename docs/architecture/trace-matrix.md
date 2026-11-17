# Requirement Traceability Matrix

**Status:** draft — pending human review.
**Owner:** Member C (Software Architect).
**Baseline:** InstallerX Revived `816ea541`; repository state `0f718739`.
**Requirements:** `docs/req/requirements.yaml` rev.6
(`status: draft_for_group_review`, 41 requirements).
**Related:** `architecture-blueprint.md`, `invariants.md`,
`evaluation-table.md`, `runtime-policy-catalog.md`,
`adr/001-architecture-choice.md`,
`adr/002-later-important-arch-decisions.md`.

Course marker `[M]` — mandatory artifact.

This matrix maps every requirement to the component that enforces it,
the planned test, the verification tier, and the governing ADR. It is
generated from `requirements.yaml` and checked by a read-only script
(see §5). Every `status` value is the value in the registry.

Regenerate this file whenever `requirements.yaml` changes; do not edit
rows by hand.

An ADR value of `—` means no architecture decision governs that problem
shape: it is a process, governance, or measurement requirement
(COMP-001, AIQ-001, QUAL-001, VER-001, GOV-001, GOV-002, SCOPE-001).
QUAL-001 is validated by `AIOutputValidator` but its acceptance is a
quality metric, not an ADR decision. Every requirement that constrains
structure carries an ADR link.

---

## 1. Full requirement matrix (41 of 41)

| ID | Priority | Enforcing component | Planned test | Verification tier | ADR | Status |
|---|---|---|---|---|---|---|
| `REQ-IX-FR-001` | Must | `ApprovalCoordinator` | ApprovalRequestTest | behavioral | ADR-001, ADR-002.6 | conceptual |
| `REQ-IX-FR-002` | Must | `InstallEvidenceCollector` | EvidenceCollectorTest | behavioral | ADR-001 | conceptual |
| `REQ-IX-FR-003` | Must | `AIRiskAdvisor` | AdvisorIntegrationTest | behavioral | ADR-002.2 | conceptual |
| `REQ-IX-FR-004` | Must | `AIOutputValidator` | OutputValidationTest | behavioral | ADR-002.2 | conceptual |
| `REQ-IX-FR-005` | Must | `DeterministicPolicyEngine` | PolicyDecisionTableTest | behavioral | ADR-002.6 | conceptual |
| `REQ-IX-FR-006` | Must | `HumanReviewHandler` | ReviewUiTest | behavioral | ADR-002.4 | conceptual |
| `REQ-IX-FR-007` | Must | `InstallationGateway` | ControlledInstallTest | behavioral | ADR-001, ADR-002.6 | conceptual |
| `REQ-IX-FR-008` | Must | `InstallerSession UI` | ProgressCancelTest | behavioral | ADR-002.4 | conceptual |
| `REQ-IX-FR-009` | Must | `DecisionAuditRecorder` | AuditRecordTest | behavioral | ADR-002.3 | conceptual |
| `REQ-IX-FR-010` | Should | `AuditQuery UI` | AuditQueryTest | behavioral | ADR-002.3 | conceptual |
| `REQ-IX-FR-011` | Must | `ConsentGate` | ConsentGateTest | behavioral | ADR-002.4 | conceptual |
| `REQ-IX-FR-012` | Must | `Settings + ConsentGate` | ToggleSemanticsTest | behavioral | ADR-002.4 | conceptual |
| `REQ-IX-INV-001` | Must | `ApprovalCoordinator` | AdvisorIsolationTest | structural + behavioral | ADR-001, ADR-002.6 | conceptual |
| `REQ-IX-INV-002` | Must | `DeterministicPolicyEngine` | HardBlockPrecedenceTest | behavioral | ADR-002.6 | conceptual |
| `REQ-IX-INV-003` | Must | `ApprovalStateStore` | ApprovalBindingTest | behavioral | ADR-002.7 | conceptual |
| `REQ-IX-INV-004` | Must | `ApprovalCoordinator` | FailureFallbackTest | behavioral | ADR-002.6 | conceptual |
| `REQ-IX-INV-005` | Must | `ApprovalStateStore` | IdempotencyTest | behavioral | ADR-002.3 | conceptual |
| `REQ-IX-INV-006` | Must | `DeterministicPolicyEngine` | PolicyWithoutModelTest | structural + behavioral | ADR-001 | conceptual |
| `REQ-IX-INV-007` | Must | `DeterministicPolicyEngine` | NoDowngradeTest | behavioral | ADR-002.6 | conceptual |
| `REQ-IX-SEC-001` | Must | `AIRiskAdvisor` | AdapterPermissionTest | structural + behavioral | ADR-001, ADR-002.6 | conceptual |
| `REQ-IX-SEC-002` | Must | `DeterministicPolicyEngine` | HardRuleRegressionTest | behavioral | ADR-002.6 | conceptual |
| `REQ-IX-SEC-003` | Must | `ApprovalStateStore` | ApprovalTamperTest | behavioral | ADR-002.7 | conceptual |
| `REQ-IX-SEC-004` | Must | `NetworkGate` | OutboundWhitelistTest | behavioral | ADR-002.5 | conceptual |
| `REQ-IX-SEC-005` | Must | `EvidenceSanitizer` | SanitizerInjectionTest | behavioral | ADR-001 | conceptual |
| `REQ-IX-REL-001` | Must | `ApprovalCoordinator` | FailureModeTest | behavioral | ADR-002.6 | conceptual |
| `REQ-IX-REL-002` | Must | `ApprovalStateStore` | StatePersistenceTest | behavioral | ADR-002.3 | conceptual |
| `REQ-IX-REL-003` | Must | `ApprovalStateStore` | RecoveryTest | behavioral | ADR-002.3 | conceptual |
| `REQ-IX-REL-004` | Must | `ApprovalStateStore` | DedupTest | behavioral | ADR-002.3 | conceptual |
| `REQ-IX-PER-001` | Should | `AIRiskAdvisor` | LatencyBudgetTest | behavioral | ADR-002.2 | conceptual |
| `REQ-IX-COST-001` | Should | `AIRiskAdvisor` | CallBudgetTest; TokenBudgetTest; CostDistributionEvaluation | behavioral | ADR-002.2 | conceptual |
| `REQ-IX-MOD-001` | Should | `AIRiskAdvisor` | AdapterContractTest | structural + behavioral | ADR-001, ADR-002.2 | conceptual |
| `REQ-IX-COMP-001` | Must | `Baseline regression suite` | BaselineRegressionTest | structural + behavioral | — | conceptual |
| `REQ-IX-AIQ-001` | Should | `Evaluation harness` | AIQualityEvaluation | behavioral | — | conceptual |
| `REQ-IX-QUAL-001` | Should | `AIOutputValidator` | ExplanationGroundingTest | behavioral | — | conceptual |
| `REQ-IX-VER-001` | Must | `Test suite` | requirements trace check | structural + behavioral | — | conceptual |
| `REQ-IX-GOV-001` | Must | `requirements registry` | validate_requirements.py | structural + behavioral | — | conceptual |
| `REQ-IX-GOV-002` | Must | `Process records` | review log | structural + behavioral | — | conceptual |
| `REQ-IX-SCOPE-001` | Must | `Scope inventory` | entry-point audit | structural + behavioral | — | conceptual |
| `REQ-IX-ARCH-001` | Must | `InstallationGateway` | ChokePointTest | structural + behavioral | ADR-001, ADR-002.6 | conceptual |
| `REQ-IX-ARCH-002` | Should | `UI layer` | DualUiSmokeTest | structural + behavioral | ADR-002.4 | conceptual |
| `REQ-IX-DATA-001` | Must | `DecisionAuditRecorder` | AuditIntegrityTest | behavioral | ADR-002.7 | conceptual |

---

## 2. ASR detail sub-matrix

The nine architecturally significant requirements, with the ADR that
records their structural decision. The ADD driver for each ASR is in
`evaluation-table.md` §2.

| ASR | Priority | Enforcing component | Planned test | Tier | ADR | Structural pressure |
|---|---|---|---|---|---|---|
| `REQ-IX-INV-003` | Must | `ApprovalStateStore` | ApprovalBindingTest | behavioral | ADR-002.7 | see `evaluation-table.md` §2 |
| `REQ-IX-INV-004` | Must | `ApprovalCoordinator` | FailureFallbackTest | behavioral | ADR-002.6 | see `evaluation-table.md` §2 |
| `REQ-IX-INV-006` | Must | `DeterministicPolicyEngine` | PolicyWithoutModelTest | structural + behavioral | ADR-001 | see `evaluation-table.md` §2 |
| `REQ-IX-INV-007` | Must | `DeterministicPolicyEngine` | NoDowngradeTest | behavioral | ADR-002.6 | see `evaluation-table.md` §2 |
| `REQ-IX-SEC-005` | Must | `EvidenceSanitizer` | SanitizerInjectionTest | behavioral | ADR-001 | see `evaluation-table.md` §2 |
| `REQ-IX-REL-002` | Must | `ApprovalStateStore` | StatePersistenceTest | behavioral | ADR-002.3 | see `evaluation-table.md` §2 |
| `REQ-IX-REL-003` | Must | `ApprovalStateStore` | RecoveryTest | behavioral | ADR-002.3 | see `evaluation-table.md` §2 |
| `REQ-IX-MOD-001` | Should | `AIRiskAdvisor` | AdapterContractTest | structural + behavioral | ADR-001, ADR-002.2 | see `evaluation-table.md` §2 |
| `REQ-IX-ARCH-001` | Must | `InstallationGateway` | ChokePointTest | structural + behavioral | ADR-001, ADR-002.6 | see `evaluation-table.md` §2 |

### 2.1 ASR structural obligations

| ASR | Structural obligation | Enforcement |
|---|---|---|
| REQ-IX-ARCH-001 | every in-scope submission passes `ApprovalCoordinator → InstallationGateway` | `planned: ChokePointTest` static + dynamic spy |
| REQ-IX-INV-003 | approval binds the installation object; revalidation before submit | `planned: ApprovalBindingTest` negative + TOCTOU |
| REQ-IX-INV-004 | model failure never auto-approves | `planned: FailureFallbackTest` fault injection |
| REQ-IX-INV-006 | policy runs without model SDK or network | `planned: PolicyWithoutModelTest` static + unit |
| REQ-IX-INV-007 | AI may not lower a deterministic restriction | `planned: NoDowngradeTest` decision-table negative |
| REQ-IX-SEC-005 | sanitization before external model | `planned: SanitizerInjectionTest` adversarial corpus |
| REQ-IX-REL-002 | durable state at defined transitions | `planned: StatePersistenceTest` restart + write-fault |
| REQ-IX-REL-003 | crash-recovery protocol with reconciliation | `planned: RecoveryTest` crash recovery |
| REQ-IX-MOD-001 | provider behind one adapter interface | `planned: AdapterContractTest` adapter swap |

---

## 3. Verification tiers

| Tier | Meaning | Rows here |
|---|---|---|
| structural + behavioral | both a static/import/module check and a behavioral test are required | 11 |
| behavioral | a behavioral test is sufficient (no separate static check named) | 30 |
| regression | a suite-level regression check (none assigned separately; baseline and trace checks are counted under structural + behavioral) | 0 |

Tier assignment follows `docs/code-governance/verification-constraints.md`
§3: structural checks cover imports, module dependencies, layer
boundaries, and architectural fitness functions; behavioral checks cover
quality thresholds and safety outcomes; regression re-runs all prior
suites.

A requirement with an ASR flag always requires a structural check, even
where its planned test is primarily behavioral (e.g. INV-003 needs the
binding schema to exist structurally).

---

## 4. Invariant coverage

Every invariant has a negative test that must fail when the invariant is
violated (`docs/code-governance/verification-constraints.md` §1).

| Invariant | Planned negative test | Present in matrix |
|---|---|---|
| REQ-IX-INV-001 | AdvisorIsolationTest | yes |
| REQ-IX-INV-002 | HardBlockPrecedenceTest | yes |
| REQ-IX-INV-003 | ApprovalBindingTest | yes |
| REQ-IX-INV-004 | FailureFallbackTest | yes |
| REQ-IX-INV-005 | IdempotencyTest | yes |
| REQ-IX-INV-006 | PolicyWithoutModelTest | yes |
| REQ-IX-INV-007 | NoDowngradeTest | yes |

---

## 5. Coverage check

The completeness claim is produced by a read-only script, not asserted
by hand. The script reads `requirements.yaml` and this file.

Checks performed:

1. Every requirement ID in the registry appears as a matrix row.
2. No matrix row references an ID absent from the registry (no dangling
   IDs).
3. Every registry requirement has the same priority and enforcing
   component in the matrix.
4. Every invariant appears in §4 with a negative test.
5. All 9 ASRs appear in §2.

Result at generation: **41/41 requirements mapped, 0 dangling IDs.** The
script and its raw output are recorded in the session report.

---

## 6. Traceability

Source of truth: `docs/req/requirements.yaml` rev.6.
Decisions: `adr/001-architecture-choice.md`,
`adr/002-later-important-arch-decisions.md`.
Invariant semantics: `invariants.md`.
Decision rules: `runtime-policy-catalog.md`.
Persistence and audit: `memory-design.md`.
