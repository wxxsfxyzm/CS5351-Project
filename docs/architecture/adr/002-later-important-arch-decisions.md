# ADR-002: Later Important Architecture Decisions

**Status:** draft — pending human review.
**Date:** 2026-11-17.
**Owner:** Member C (Software Architect).
**Baseline:** extends InstallerX Revived `816ea541`.
**Depends on:** `adr/001-architecture-choice.md` (proposal).

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

- **Status:** not started — blocked on ADR-001 acceptance.
- **Problem:** `DeterministicPolicyEngine` must run without a model SDK
  or network (REQ-IX-INV-006) and must own every decision
  (REQ-IX-FR-005). Its final module and source set are unresolved.
- **Constraints:** pure JVM-testable; no Android framework dependency in
  the decision core; Koin registration in an existing `di/` module.
- **Requirements:** REQ-IX-FR-005, REQ-IX-INV-002, REQ-IX-INV-006,
  REQ-IX-INV-007, REQ-IX-ARCH-001.

## ADR-002.2 — AI adapter interface and provider selection

- **Status:** not started.
- **Problem:** REQ-IX-MOD-001 requires one adapter interface so the
  provider can be replaced without touching policy or UI. The interface
  shape, timeout, and provider configuration are unresolved.
- **Constraints:** at most one provider dispatch per attempt, no
  automatic retry (REQ-IX-FR-003, REQ-IX-COST-001). Model tokens and
  endpoints are never logged (REQ-IX-FR-009). Provider-side output cap
  required before spend (REQ-IX-COST-001, provisional).
- **Requirements:** REQ-IX-FR-003, REQ-IX-FR-004, REQ-IX-MOD-001,
  REQ-IX-COST-001, REQ-IX-PER-001.

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

- **Status:** not started.
- **Problem:** REQ-IX-SEC-004 requires outbound egress to obey consent
  and the in-app network switch. The existing
  `BooleanSetting.AllowInternetAccess` setting is a configuration flag,
  not a dedicated `NetworkGate` element. Whether to add an element or
  reuse the setting plus a guard is unresolved.
- **Constraints:** network requests stay disabled when the in-app
  setting is off (`AGENTS.md`); outbound payload matches the allowlist
  exactly (REQ-IX-SEC-004).
- **Requirements:** REQ-IX-FR-003, REQ-IX-FR-011, REQ-IX-SEC-004,
  REQ-IX-INV-004.

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
| 002.1 | Policy module placement | not started (blocked on ADR-001) |
| 002.2 | AI adapter interface and provider | not started |
| 002.3 | Approval and audit persistence | not started |
| 002.4 | Dual-UI review and consent | not started |
| 002.5 | Network gate placement | not started |
| 002.6 | Authority separation | decided (specified) |
| 002.7 | Hash and integrity verification | decided (specified) |

A `decided (specified)` entry states the required behavior from the
requirements registry; it does not claim that code exists. Tests named
here are planned, per `verification-constraints.md` §2.
