# Requirement Traceability Matrix

**Status:** regenerated 2026-11-24 after PRs #27–#33 by the lead; pending Member C review.
**Owner:** Member C (Software Architect).
**Baseline:** InstallerX Revived `816ea541`; repository state `main`, 2026-11-24.
**Requirements:** `docs/req/requirements.yaml` rev.6
(`status: approved_baseline`, 41 requirements).
**Related:** `architecture-blueprint.md`, `invariants.md`,
`evaluation-table.md`, `runtime-policy-catalog.md`,
`adr/001-architecture-choice.md`,
`adr/002-later-important-arch-decisions.md`.

Course marker `[M]` — mandatory artifact.

This matrix maps every requirement to the component that enforces it,
the classes that implement it, its tests, its runtime monitor, the verification tier, and the
governing ADR. It is generated from `requirements.yaml` and checked by a
read-only script (see §5). Every `status` value is the value in the
registry.

Regenerate this file whenever `requirements.yaml` changes; do not edit
rows by hand. Priority, enforcing element and status come from the
registry. **Implemented by** and **Tests** come from the registry's
`verification.test` field and the code on `main`; an entry starting
`planned:` is a gap that has no test yet. Exception: the **Runtime
monitor** column has no source field in the registry and is maintained
by hand; keep it when regenerating. `implemented` names a runtime signal that exists in
`main`; `planned:` names a monitor that depends on unbuilt elements
(mostly `DecisionAuditRecorder`).

An ADR value of `—` means no architecture decision governs that problem
shape: it is a process, governance, or measurement requirement
(COMP-001, AIQ-001, QUAL-001, VER-001, GOV-001, GOV-002, SCOPE-001).
QUAL-001 is validated by `AIOutputValidator` but its acceptance is a
quality metric, not an ADR decision. Every requirement that constrains
structure carries an ADR link.

The **Enforcing element** column keeps the architecture element names
from ADR-001 and the registry. **Implemented by** names the classes on
`main` that realise that element; `—` means nothing is implemented yet.

---

## 1. Full requirement matrix (41 of 41)

| ID | Priority | Enforcing element | Implemented by | Tests | Runtime monitor | Verification tier | ADR | Status |
|---|---|---|---|---|---|---|---|---|
| `REQ-IX-FR-001` | Must | `ApprovalCoordinator` | PrepareApprovalUseCase, ApprovalCoordinator | PrepareApprovalUseCaseTest; ChokePointTest; planned: operation-ID query (ApprovalRequestTest) | planned: audit record of operation/attempt IDs and decision | behavioral | ADR-001, ADR-002.6 | conceptual |
| `REQ-IX-FR-002` | Must | `InstallEvidenceCollector` | AutoDialogInstallEvidenceMapper, AutoDialogFactsDeriver, ApkContentHasher | AutoDialogInstallEvidenceMapperTest; AutoDialogFactsDeriverTest; ApkHashEvidenceTest | planned: audit record of evidence completeness state | behavioral | ADR-001 | implemented |
| `REQ-IX-FR-003` | Must | `AIRiskAdvisor` | DefaultAiRiskAdvisor, AiRequestBuilder, OpenAiCompatibleModelClient | AiRequestBuilderTest; ProviderRequestShapeTest; DefaultAiRiskAdvisorTest; CourseServiceLiveAdvisorTest (live) | `AssessmentResult` status and dispatch count (implemented) | behavioral | ADR-002.2 | implemented |
| `REQ-IX-FR-004` | Must | `AIOutputValidator` | StrictAiOutputValidator | StrictAiOutputValidatorTest; ProviderResponseHandlingTest; ReadCappedBodyTest | `AssessmentResult` INVALID count (implemented) | behavioral | ADR-002.2 | implemented |
| `REQ-IX-FR-005` | Must | `DeterministicPolicyEngine` | DeterministicPolicyEngine, EvaluatedPolicyFactory | PolicyDecisionTableTest; EvaluatedPolicyFactoryTest; HardBlockCauseTest | planned: audit record of decision, disposition, and DR rule ID | behavioral | ADR-002.6 | implemented |
| `REQ-IX-FR-006` | Must | `HumanReviewHandler` | ApprovalCoordinator, AI review card (Material 3 and Miuix) | ApprovalCoordinatorTest; PrepareApprovalUseCaseTest; AdvisorDecisionTest; AiDecisionUiMapperTest | planned: audit record of Continue/Cancel actions | behavioral | ADR-002.4 | implemented |
| `REQ-IX-FR-007` | Must | `InstallationGateway` | ApprovalCoordinator, SessionInstallationGateway | ChokePointTest; GatewaySessionIsolationTest; SessionInstallationGatewayTest | planned: gateway submission counter per operation | behavioral | ADR-001, ADR-002.6 | implemented |
| `REQ-IX-FR-008` | Must | `InstallerSession UI` | InstallerViewModel, OkHttpAiTransport (cancel) | AttemptBudgetTest; planned: notification path and RESULT_UNKNOWN (ProgressCancelTest) | planned: audit record of install result, including RESULT_UNKNOWN | behavioral | ADR-002.4 | conceptual |
| `REQ-IX-FR-009` | Must | `DecisionAuditRecorder` | — | planned: AuditRecordTest | planned: audit append failures | behavioral | ADR-002.3 | conceptual |
| `REQ-IX-FR-010` | Should | `AuditQuery UI` | — | planned: AuditQueryTest | planned: audit export events | behavioral | ADR-002.3 | future |
| `REQ-IX-FR-011` | Must | `ConsentGate` | ModelRequestGate, consent dialog | ModelRequestGateTest; AiConsentDialogTest; PrepareApprovalUseCaseTest; planned: consent audit record (FR-009) | `ModelRequestGate` denial on missing consent (implemented) | behavioral | ADR-002.4 | conceptual |
| `REQ-IX-FR-012` | Must | `Settings + ConsentGate` | AppSettingsNetworkGate, PrepareApprovalUseCase (advisor off) | AiSettingsToGateTest; PrepareApprovalUseCaseTest; planned: AI_DISABLED audit record (FR-009) | `AppSettingsNetworkGate` denial when the network switch is off (implemented) | behavioral | ADR-002.4 | conceptual |
| `REQ-IX-INV-001` | Must | `ApprovalCoordinator` | package boundary of domain/ai and data/ai | AdvisorIsolationTest; AiIsolationTest; ApprovalIsolationTest | planned: gateway calls attributed to a non-coordinator caller (must be 0) | structural + behavioral | ADR-001, ADR-002.6 | implemented |
| `REQ-IX-INV-002` | Must | `DeterministicPolicyEngine` | EvaluatedPolicyFactory, DeterministicPolicyEngine (DR-01) | HardBlockCauseTest; EvaluatedPolicyFactoryTest; PolicyDecisionTableTest | planned: audit record: BLOCK followed by submission (must be 0) | behavioral | ADR-002.6 | implemented |
| `REQ-IX-INV-003` | Must | `ApprovalStateStore` | ApprovalCoordinator, SessionBindingFactsProvider, ApkContentHasher | ApprovalBindingTest; ApprovalCoordinatorTest; ApkHashEvidenceTest | planned: audit record: binding mismatches and INVALIDATED count | behavioral | ADR-002.7 | implemented |
| `REQ-IX-INV-004` | Must | `ApprovalCoordinator` | StrictAiOutputValidator, PrepareApprovalUseCase, DeterministicPolicyEngine (DR-08) | FailureEscalationTest; AttemptBudgetTest; AdvisorFailureReportingTest | `AssessmentResult` failure codes (implemented); planned: auto-approvals after failure (must be 0) | behavioral | ADR-002.6 | implemented |
| `REQ-IX-INV-005` | Must | `ApprovalStateStore` | SubmissionLedger, InMemoryApprovalStateStore | IdempotencyTest; ApprovalCoordinatorTest; planned: deduplication across restarts (DEBT-003) | planned: submissions per operation ID (must be ≤ 1) | behavioral | ADR-002.3 | conceptual |
| `REQ-IX-INV-006` | Must | `DeterministicPolicyEngine` | DeterministicPolicyEngine | PolicyWithoutModelTest; PolicyDependencyBoundaryTest | planned: policy decisions recorded with network off | structural + behavioral | ADR-001 | implemented |
| `REQ-IX-INV-007` | Must | `DeterministicPolicyEngine` | DeterministicPolicyEngine (DR-05) | PolicyDecisionTableTest; AiPolicyIntegrationTest; ApkHashEvidenceTest | planned: audit record: decisions less restrictive than baseline policy (must be 0) | behavioral | ADR-002.6 | implemented |
| `REQ-IX-SEC-001` | Must | `AIRiskAdvisor` | DefaultAiRiskAdvisor (no APK byte access) | AdvisorIsolationTest; CI permission gate; planned: attack-instruction samples (AdapterPermissionTest) | planned: advisory-element backend references (static, must be 0) | structural + behavioral | ADR-001, ADR-002.6 | conceptual |
| `REQ-IX-SEC-002` | Must | `DeterministicPolicyEngine` | EvaluatedPolicyFactory, DeterministicPolicyEngine (DR-01) | HardBlockCauseTest; EvaluatedPolicyFactoryTest; PolicyDecisionTableTest | planned: audit record of hard-rule hits | behavioral | ADR-002.6 | implemented |
| `REQ-IX-SEC-003` | Must | `ApprovalStateStore` | ApprovalCoordinator (revalidation) | ApprovalBindingTest; ApprovalCoordinatorTest; planned: tamper-evident audit (ApprovalTamperTest) | planned: audit record: tamper detections (hash mismatch) | behavioral | ADR-002.7 | conceptual |
| `REQ-IX-SEC-004` | Must | `NetworkGate` | AiRequestBuilder (ALLOWLISTED_LEAF_PATHS), AppSettingsNetworkGate, ModelRequestGate | AiRequestBuilderTest; AutoDialogInstallEvidenceMapperTest; AdvisorIsolationTest | `AiRequestBuilder.transmittedPaths` per call (implemented) | behavioral | ADR-002.5 | implemented |
| `REQ-IX-SEC-005` | Must | `EvidenceSanitizer` | EvidenceSanitizer | EvidenceSanitizerTest; AiRequestBuilderTest; planned: 20-sample injection suite (SanitizerInjectionTest) | `EvidenceSanitizer` truncation flag → completeness `TRUNCATED` (implemented) | behavioral | ADR-001 | conceptual |
| `REQ-IX-REL-001` | Must | `ApprovalCoordinator` | PrepareApprovalUseCase, DefaultAiRiskAdvisor | FailureEscalationTest; AttemptBudgetTest; planned: preflight rejection and third request (FailureModeTest) | `AssessmentResult` failure code per failure type (implemented) | behavioral | ADR-002.6 | conceptual |
| `REQ-IX-REL-002` | Must | `ApprovalStateStore` | ApprovalCoordinator, InMemoryApprovalStateStore | StatePersistenceTest (in memory); planned: durable store (DEBT-003) | planned: state-store write failures | behavioral | ADR-002.3 | conceptual |
| `REQ-IX-REL-003` | Must | `ApprovalStateStore` | ApprovalCoordinator.onRecovered | RecoveryTest (in memory); planned: recovery after process restart (DEBT-003) | planned: recovered operations by state at restart | behavioral | ADR-002.3 | conceptual |
| `REQ-IX-REL-004` | Must | `ApprovalStateStore` | SubmissionLedger | IdempotencyTest; planned: durable deduplication (DedupTest) | planned: duplicate-event suppressions | behavioral | ADR-002.3 | conceptual |
| `REQ-IX-PER-001` | Should | `AIRiskAdvisor` | OkHttpAiTransport (callTimeout 7 s), PrepareApprovalUseCase (12 s budget) | AttemptBudgetTest | planned: model call latency per attempt | behavioral | ADR-002.2 | implemented |
| `REQ-IX-COST-001` | Should | `AIRiskAdvisor` | PrepareApprovalUseCase (one call, no retry) | PrepareApprovalUseCaseTest; planned: TokenBudgetTest; planned: CostDistributionEvaluation | dispatch count (implemented); planned: token totals per call and cohort | behavioral | ADR-002.2 | conceptual |
| `REQ-IX-MOD-001` | Should | `AIRiskAdvisor` | AiModelClient interface, FixedResponseAdvisor | AdapterContractTest; FixedResponseAdvisorIntegrationTest | planned: provider ID and model ID per call | structural + behavioral | ADR-001, ADR-002.2 | implemented |
| `REQ-IX-COMP-001` | Must | `Baseline regression suite` | advisor-off baseline path | PrepareApprovalUseCaseTest (advisor off); planned: BaselineRegressionTest | CI regression gate (`ci.yml`) | structural + behavioral | — | conceptual |
| `REQ-IX-AIQ-001` | Should | `Evaluation harness` | live evaluation suite | CourseServiceLiveAdvisorTest (13 scenarios); planned: 20-case human evaluation (AIQualityEvaluation) | CI semantic evaluation gate (`ci.yml`) | behavioral | — | conceptual |
| `REQ-IX-QUAL-001` | Should | `AIOutputValidator` | StrictAiOutputValidator (evidence references) | StrictAiOutputValidatorTest; CourseServiceLiveAdvisorTest (live) | `AssessmentResult` INVALID count for missing evidence refs (implemented) | behavioral | — | implemented |
| `REQ-IX-VER-001` | Must | `Test suite` | test suites, this matrix | CI regression gate; this matrix (§5) | CI regression gate; trace check in review | structural + behavioral | — | conceptual |
| `REQ-IX-GOV-001` | Must | `requirements registry` | requirements.yaml, req-schema.json | course_gates.py schema | CI schema gate (`ci.yml`) | structural + behavioral | — | implemented |
| `REQ-IX-GOV-002` | Must | `Process records` | process records | course_gates.py ai-use-log; docs/review-memo.md | CI AI-use log gate; `docs/review-memo.md` | structural + behavioral | — | implemented |
| `REQ-IX-SCOPE-001` | Must | `Scope inventory` | PrepareApprovalUseCase (AutoDialog only) | PrepareApprovalUseCaseTest; planned: entry-point inventory | planned: entry-point audit per release | structural + behavioral | — | conceptual |
| `REQ-IX-ARCH-001` | Must | `InstallationGateway` | ApprovalCoordinator -> InstallationGateway | ChokePointTest; AdvisorDecisionTest | planned: gateway submission counter by caller | structural + behavioral | ADR-001, ADR-002.6 | implemented |
| `REQ-IX-ARCH-002` | Should | `UI layer` | review card, consent and configuration dialogs (Material 3 and Miuix) | both UI families compile; planned: DualUiSmokeTest | planned: review-screen render events per UI family | structural + behavioral | ADR-002.4 | implemented |
| `REQ-IX-DATA-001` | Must | `DecisionAuditRecorder` | — | planned: AuditIntegrityTest | planned: audit record hash-chain verification failures | behavioral | ADR-002.7 | conceptual |

Status counts from the registry: 21 implemented,
19 conceptual, 1 future. 20 rows still
carry at least one `planned:` test; most depend on the deferred
`DecisionAuditRecorder` and durable state store (DEBT-003).

---

## 2. ASR detail sub-matrix

The nine architecturally significant requirements, with the ADR that
records their structural decision. The ADD driver for each ASR is in
`evaluation-table.md` §2.

| ASR | Priority | Enforcing element | Tests | Tier | ADR | Status |
|---|---|---|---|---|---|---|
| `REQ-IX-INV-003` | Must | `ApprovalStateStore` | ApprovalBindingTest; ApprovalCoordinatorTest; ApkHashEvidenceTest | behavioral | ADR-002.7 | implemented |
| `REQ-IX-INV-004` | Must | `ApprovalCoordinator` | FailureEscalationTest; AttemptBudgetTest; AdvisorFailureReportingTest | behavioral | ADR-002.6 | implemented |
| `REQ-IX-INV-006` | Must | `DeterministicPolicyEngine` | PolicyWithoutModelTest; PolicyDependencyBoundaryTest | structural + behavioral | ADR-001 | implemented |
| `REQ-IX-INV-007` | Must | `DeterministicPolicyEngine` | PolicyDecisionTableTest; AiPolicyIntegrationTest; ApkHashEvidenceTest | behavioral | ADR-002.6 | implemented |
| `REQ-IX-SEC-005` | Must | `EvidenceSanitizer` | EvidenceSanitizerTest; AiRequestBuilderTest; planned: 20-sample injection suite (SanitizerInjectionTest) | behavioral | ADR-001 | conceptual |
| `REQ-IX-REL-002` | Must | `ApprovalStateStore` | StatePersistenceTest (in memory); planned: durable store (DEBT-003) | behavioral | ADR-002.3 | conceptual |
| `REQ-IX-REL-003` | Must | `ApprovalStateStore` | RecoveryTest (in memory); planned: recovery after process restart (DEBT-003) | behavioral | ADR-002.3 | conceptual |
| `REQ-IX-MOD-001` | Should | `AIRiskAdvisor` | AdapterContractTest; FixedResponseAdvisorIntegrationTest | structural + behavioral | ADR-001, ADR-002.2 | implemented |
| `REQ-IX-ARCH-001` | Must | `InstallationGateway` | ChokePointTest; AdvisorDecisionTest | structural + behavioral | ADR-001, ADR-002.6 | implemented |

### 2.1 ASR structural obligations

| ASR | Structural obligation | Enforcement on `main` |
|---|---|---|
| REQ-IX-INV-003 | approval binds the installation object; revalidation before submit | implemented: `ApprovalBindingTest`, `ApprovalCoordinatorTest` (TOCTOU), `ApkHashEvidenceTest` |
| REQ-IX-INV-004 | model failure never auto-approves | implemented: `FailureEscalationTest`, `AttemptBudgetTest` (fault injection, virtual time) |
| REQ-IX-INV-006 | policy runs without model SDK or network | implemented: `PolicyWithoutModelTest` (static) + `PolicyDependencyBoundaryTest` |
| REQ-IX-INV-007 | AI may not lower a deterministic restriction | implemented: `PolicyDecisionTableTest`, `AiPolicyIntegrationTest` (decision-table negatives) |
| REQ-IX-SEC-005 | sanitization before external model | partial: `EvidenceSanitizerTest`; planned: adversarial corpus |
| REQ-IX-REL-002 | durable state at defined transitions | partial: `StatePersistenceTest` on the in-memory store; durable store deferred (DEBT-003) |
| REQ-IX-REL-003 | crash-recovery protocol with reconciliation | partial: `RecoveryTest` on the in-memory store; restart not covered (DEBT-003) |
| REQ-IX-MOD-001 | provider behind one adapter interface | implemented: `AdapterContractTest`, `FixedResponseAdvisorIntegrationTest` (adapter swap) |
| REQ-IX-ARCH-001 | every AI-evaluated submission passes `ApprovalCoordinator → InstallationGateway` (narrowed for v1, PR #31) | implemented: `ChokePointTest` (exact call sites) + `AdvisorDecisionTest` |

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

| Invariant | Negative test on `main` | Present in matrix |
|---|---|---|
| REQ-IX-INV-001 | AdvisorIsolationTest, AiIsolationTest | yes |
| REQ-IX-INV-002 | HardBlockCauseTest, EvaluatedPolicyFactoryTest | yes |
| REQ-IX-INV-003 | ApprovalBindingTest | yes |
| REQ-IX-INV-004 | FailureEscalationTest | yes |
| REQ-IX-INV-005 | IdempotencyTest (in-process only) | yes |
| REQ-IX-INV-006 | PolicyWithoutModelTest | yes |
| REQ-IX-INV-007 | PolicyDecisionTableTest, AiPolicyIntegrationTest | yes |

---

## 5. Coverage check

The completeness claim is produced by a read-only check, not asserted
by hand. The check reads `requirements.yaml`, this file, and the test
tree under `app/src/test`.

Checks performed:

1. Every requirement ID in the registry appears as a matrix row.
2. No matrix row references an ID absent from the registry (no dangling
   IDs).
3. Every row has the same priority, enforcing element, and status as the
   registry.
4. Every test named in the Tests column that is not marked `planned:`
   exists as a test class on `main`.
5. Every invariant appears in §4 with a negative test.
6. All 9 ASRs appear in §2.

Result on 2026-11-24: **41/41 requirements mapped, 0 dangling IDs,
0 status mismatches, 0 missing test classes.**

---

## 6. Traceability

Source of truth: `docs/req/requirements.yaml` rev.6.
Decisions: `adr/001-architecture-choice.md`,
`adr/002-later-important-arch-decisions.md`.
Invariant semantics: `invariants.md`.
Decision rules: `runtime-policy-catalog.md`.
Persistence and audit: `memory-design.md`.
