# ADR-002: Later Important Architecture Decisions

**Status:** draft — pending human review.
**Date:** 2026-11-17.
**Owner:** Member C (Software Architect).
**Baseline:** extends InstallerX Revived `816ea541`.
**Depends on:** `adr/001-architecture-choice.md` (accepted — Option B).

This file is an index of decisions that ADR-001 defers. Entries 2.1–2.5
were planned in
`docs/plans/2026-11-17-d2-architecture-population.md` §5.3. Entries
2.6 and 2.7 were added by Gate-A approval to record authority
separation and hash/integrity verification explicitly.

Each entry states the problem, the decision status, and the requirement
IDs it must satisfy. An entry with status `decided (specified)` has its
policy specified in the D2 artifact named; an entry with status
`not started` has no decision yet.

---

## ADR-002.1 — Placement of the deterministic policy module

- **Status:** decided — implemented in PR #16.
- **Decision as implemented:** the policy core lives in
  `domain/engine/policy/` in the existing `app` module, as two files:
  - `DeterministicPolicyEngine.kt` — a plain Kotlin class with
    `fun evaluate(input: PolicyInput): PolicyResult`. It evaluates
    DR-01 … DR-10 in first-match order over the supplied facts and holds
    no model, network, or Android dependency (REQ-IX-INV-006).
  - `PolicyDecisionModels.kt` — the contract types `EvaluatedPolicy`,
    `HumanApprovalState`, `PolicyAdvice`, `PolicyInput`,
    `PolicyDecision` (enum), `PolicyDisposition` (enum), and
    `PolicyResult` (data class carrying `decision`, `disposition`,
    `ruleId`, `reasonCode`).
- **Naming note:** `PolicyDecision` is an **enum**
  (`ALLOW_AUTO`, `REQUIRE_REVIEW`, `BLOCK`), matching the DI requirement
  that only an active deterministic prohibition may produce `BLOCK`.
  The full decision *outcome* is the `PolicyResult` data class, not the
  enum. Earlier D2 drafts sketched a `PolicyDecision` data class with
  separate enums; the merged implementation is authoritative.
- **Constraints honoured:** pure JVM-testable; no Android framework type
  in the decision core; no Koin registration needed because the engine
  is stateless (`PolicyDecisionTableTest` constructs it directly).
- **Requirements:** REQ-IX-FR-005, REQ-IX-INV-002, REQ-IX-INV-006,
  REQ-IX-INV-007, REQ-IX-ARCH-001.
- **Evidence:** `domain/engine/policy/DeterministicPolicyEngine.kt`,
  `domain/engine/policy/PolicyDecisionModels.kt`,
  `domain/engine/policy/PolicyDecisionTableTest.kt`.

## ADR-002.2 — AI adapter interface and provider selection

- **Status:** decided — implemented in PR #13, merged to `main` in PR #16.
- **Decision as implemented:**
  - `domain/ai/repository/AiAdvisorContracts.kt` defines the boundary
    interfaces: `AIRiskAdvisor.assessRequest(request, knownEvidencePaths)`
    returning `AdvisorOutcome` (a report, never a decision:
    REQ-IX-INV-001), `AiModelClient` (the provider seam,
    REQ-IX-MOD-001), and `AiOutputValidator.parse(...)`.
  - The concrete advisory path lives in `data/ai/advisory/DefaultAiRiskAdvisor.kt`
    (ADR-001 Option B: interface in `domain`, provider in `data`).
  - The v1 provider is Groq, an OpenAI-compatible endpoint, through
    `data/ai/provider/GroqAiModelClient.kt` and
    `data/ai/provider/OkHttpAiTransport.kt`, reusing the app's existing
    `OkHttpClient` via `newBuilder()` with a 7 s per-call read budget
    (no second HTTP stack, no automatic retry: REQ-IX-COST-001).
  - The response is parsed into a sealed
    `AssessmentResult = Valid | Invalid | Missing` with no default arm
    (REQ-IX-INV-004).
- **Review items resolved before merge:**
  - E-1: `device_api_level` is no longer transmitted; it is a
    `local_only` allowlist entry and is absent from `InstallEvidence`,
    the builder, and `ALLOWLISTED_LEAF_PATHS`, with a regression test
    (REQ-IX-SEC-004, NFC-OUT-006).
  - E-3: `GROQ_API_KEY` is **not** compiled into `BuildConfig`; the key
    is read at runtime from the process environment only and appears
    solely in the `Authorization` header (REQ-IX-FR-009).
  - E-4: the five D1 context fields (`install_mode`,
    `target_user_scope`, `package_source`, `source_confidence`,
    `installer_capability`) are implemented with rev.6 enum spellings
    and documented unknown encodings.
- **Known deviation:** `AiRequestBuilder` builds wire JSON inside
  `domain/ai`, coupling `domain` to `kotlinx.serialization`. Accepted
  under ADR-001 Option B.
- **Requirements:** REQ-IX-FR-003, REQ-IX-FR-004, REQ-IX-MOD-001,
  REQ-IX-COST-001, REQ-IX-PER-001, REQ-IX-SEC-004.
- **Evidence:** `domain/ai/`, `data/ai/`, PR #13 review fixes, PR #16.

## ADR-002.3 — Persistence for approval state and decision audit

- **Status:** not started.
- **Problem:** `ApprovalStateStore` and `DecisionAuditRecorder` need a
  durable model (REQ-IX-REL-002, REQ-IX-FR-009, REQ-IX-DATA-001). The
  existing Room database `InstallerRoom` and `OperationHistoryEntity`
  are close but not equal to the audit schema.
- **Constraints:** append-only, hash-chained audit; no credentials; no
  automatic deletion while retention is unfrozen (OI-06); transaction
  boundaries at the transitions in REQ-IX-REL-002.
- **Requirements:** REQ-IX-INV-003, REQ-IX-REL-002, REQ-IX-REL-003,
  REQ-IX-SEC-003, REQ-IX-FR-009, REQ-IX-DATA-001.

## ADR-002.4 — Dual-UI presentation of review and consent

- **Status:** not started.
- **Problem:** review and consent screens must exist in both Material 3
  (`ui/page/main/installer/`) and Miuix (`ui/page/miuix/installer/`)
  families (REQ-IX-ARCH-002). The shared stage model `InstallerStage`
  is the candidate insertion point, but adding new stages is not yet
  decided.
- **Constraints:** dialog, notification, and automatic flows stay
  distinct; a feature in one is not implied for others. Both families
  compile and complete the same flow, or an exemption is recorded.
- **Requirements:** REQ-IX-FR-006, REQ-IX-FR-008, REQ-IX-FR-011,
  REQ-IX-ARCH-002.

## ADR-002.5 — Placement of the network gate

- **Status:** decided — implemented in PR #13 (PR #16) and extended with
  consent scoping in PR #19.
- **Problem:** REQ-IX-SEC-004 requires outbound egress to obey consent
  and the in-app network switch; REQ-IX-FR-011 makes consent a
  per-assessment precondition.
- **Decision as implemented — two layers, both default-deny:**
  1. **Network switch.** `AiNetworkGate` is implemented by
     `data/ai/gate/AppSettingsNetworkGate.kt`, which reads the existing
     `AppPreferences.allowInternetAccess` and **fails closed**: an
     unreadable preference denies egress. `OkHttpAiTransport` re-checks
     the gate immediately before the request leaves the process. No new
     setting is introduced and the baseline network boundary is not
     widened (`AGENTS.md`).
  2. **Consent scoping (PR #19).** `ModelRequestGate`
     (`domain/ai/repository/ModelRequestGate.kt`) is a coroutine-context
     element the caller installs around exactly one assessment. Its
     `Key.hasCurrentConsent()` reads the *current* authorization for
     that assessment on each check; a missing, declined, withdrawn, or
     unreadable consent all deny it, and a cached grant must never be
     supplied. Both `RequestInstallRiskAssessmentUseCase` and
     `OkHttpAiTransport` consult it, so a consent change after a request
     is built still blocks transmission. A denial is reported as
     `AiAdvisorFailure.ConsentUnavailable` → `FailureCode.CONSENT_UNAVAILABLE`
     with **0 provider dispatches**, which the policy layer routes to
     DR-02 / `REQUIRE_REVIEW` (REQ-IX-INV-004).
- **Why a context element rather than a constructor parameter:** consent
  is per assessment, not per session; a Koin singleton gate cannot hold
  it safely. The context element keeps the gate stateless and lets the
  transport re-check it at the last moment.
- **Requirements:** REQ-IX-FR-003, REQ-IX-FR-011, REQ-IX-FR-012,
  REQ-IX-SEC-004, REQ-IX-INV-004.
- **Evidence:** `data/ai/gate/AppSettingsNetworkGate.kt`,
  `domain/ai/repository/ModelRequestGate.kt`,
  `data/ai/provider/OkHttpAiTransport.kt`,
  `domain/ai/usecase/RequestInstallRiskAssessmentUseCase.kt`,
  `ModelRequestGateTest.kt`, PR #19.

## ADR-002.6 — Authority separation between AI advisory and the policy gate

- **Status:** decided (specified).
- **Problem:** REQ-IX-INV-002 and REQ-IX-INV-007 require that AI advice
  and human confirmation never lower a deterministic restriction, and
  REQ-IX-INV-006 requires the decision to be model-independent. The
  architecture must make this separation structural.
- **Decision as specified:**
  - The advisory elements (`AIRiskAdvisor`, `EvidenceSanitizer`,
    `AIOutputValidator`) produce a structured recommendation only. They
    hold no reference to `InstallationGateway`, `AppInstallerRepository`,
    installer sessions, or any privileged backend
    (`REQ-IX-INV-001`, REQ-IX-SEC-001).
  - `DeterministicPolicyEngine` is the sole producer of
    `ALLOW_AUTO`, `REQUIRE_REVIEW`, or `BLOCK`. It consumes evidence
    completeness, local compatibility, both advisory dimensions, the
    effective policy version, and the human approval state
    (REQ-IX-FR-005).
  - `HumanReviewHandler` may confirm a `REQUIRE_REVIEW` request but
    cannot override `BLOCK` (REQ-IX-INV-002).
  - Only `ApprovalCoordinator` may call `InstallationGateway`, and only
    after `ALLOW_AUTO` or a valid revalidated approval
    (REQ-IX-ARCH-001, REQ-IX-FR-007).
- **Enforcement:** static check that the advisory package references no
  gateway, repository, or privileged backend type
  (`planned: AdvisorIsolationTest`); decision-table negative test that a
  favorable advisory result cannot change a hard block
  (`planned: HardBlockPrecedenceTest`, `planned: NoDowngradeTest`).
- **Requirements:** REQ-IX-INV-001, REQ-IX-INV-002, REQ-IX-INV-006,
  REQ-IX-INV-007, REQ-IX-FR-005, REQ-IX-FR-007, REQ-IX-ARCH-001,
  REQ-IX-SEC-001, REQ-IX-SEC-002.
- **Specified in:** `permission-model.md` (runtime boundary section),
  `runtime-policy-catalog.md`, `evaluation-table.md` §2 and §5.

## ADR-002.7 — Hash and integrity verification against post-approval tampering

- **Status:** decided (specified).
- **Problem:** REQ-IX-INV-003 requires approval to bind the installation
  object, and REQ-IX-SEC-003 and REQ-IX-DATA-001 require tamper
  resistance for approval and audit records. A replacement of the APK
  or a modification of a stored binding field between approval and
  submission must be detected.
- **Decision as specified:**
  - The approval binding retains, at minimum, operation ID, attempt ID,
    APK SHA-256, package name, target user, install mode, installer
    backend, config snapshot, and policy version
    (`local_approval_binding.retains` in `requirements.yaml`).
  - Immediately before submission, `ApprovalCoordinator` rechecks every
    binding field. If any is missing, changed, or invalidated, submission
    is forbidden and the disposition is `INVALIDATED` (DR-10).
  - A re-read of the APK's SHA-256 before submission must equal the bound
    value; a mismatch invalidates the approval and produces 0 backend
    submissions.
  - Audit records are append-only and hash-chained; tampering with any
    record is detectable (REQ-IX-DATA-001).
  - The target package name stays local for binding and audit and is
    never sent to the model (PMA-OUT-001, approved).
- **Enforcement:** `planned: ApprovalBindingTest` (negative + TOCTOU),
  `planned: ApprovalTamperTest`, `planned: AuditIntegrityTest`,
  `planned: ChokePointTest`.
- **Requirements:** REQ-IX-INV-003, REQ-IX-SEC-003, REQ-IX-DATA-001,
  REQ-IX-FR-007, REQ-IX-FR-009.
- **Specified in:** `memory-design.md` §2, §4, §6,
  `runtime-policy-catalog.md` (DR-10), `evaluation-table.md` QA-05.

---

## Index summary

| ADR | Topic | Status |
|---|---|---|
| 002.1 | Policy module placement | decided — implemented (PR #16) |
| 002.2 | AI adapter interface and provider | decided — implemented (PR #13/#16) |
| 002.3 | Approval and audit persistence | not started |
| 002.4 | Dual-UI review and consent | not started |
| 002.5 | Network gate placement (incl. `ModelRequestGate`) | decided — implemented (PR #16/#19) |
| 002.6 | Authority separation | decided (specified) |
| 002.7 | Hash and integrity verification | decided (specified) |

A `decided (specified)` entry states the required behavior from the
requirements registry; it does not claim that code exists. A
`decided — implemented` entry names the merged code that realizes it.
Tests named here are planned unless the entry cites an existing suite,
per `verification-constraints.md` §2.
