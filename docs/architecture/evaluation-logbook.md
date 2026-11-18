# Architecture Evaluation Logbook

**Status:** draft — pending human review.
**Owner:** Member C (Software Architect).
**Baseline:** InstallerX Revived `816ea541`.
**Related:** `docs/plans/2026-11-17-d2-architecture-population.md`,
`evaluation-table.md`, `option-ledger.md`, `risk-list.md`,
`docs/ai-use-log.yaml`.

Course marker `[Optional]` — optional artifact, populated for review
value.

This logbook records architecture-evaluation **events**: what was
evaluated, by whom, what was found, and how the finding was dispositioned.
It is evidence, not a changelog of edits. Each entry is appended and is
never rewritten. Where an entry states a result, the result was actually
observed; unverified claims are marked `not run`.

---

## 1. Entry format

| Field | Meaning |
|---|---|
| EV | evidence ID, monotonically increasing |
| Date | date of the activity |
| Activity | what was evaluated or reviewed |
| Inputs | files or code read |
| Finding | what was observed |
| Disposition | accepted / revised / deferred / rejected, with pointer |
| Evidence | plan section, transcript, or file state |

---

## 2. Events

### EV-001 — 2026-11-17 — D2 skeleton gap analysis

- **Activity:** Inspect governance files, requirements rev.6, the D2
  skeletons, and the baseline code to find what is empty and what governs
  population.
- **Inputs:** `AGENTS.md`, `Prompt.md`, `docs/req/requirements.yaml`,
  `docs/req/req-schema.json`, `docs/code-governance/*`,
  `docs/architecture/*`, `architecture-blueprint.md`, selected source
  under `app/src/main/java/com/rosan/installer/`.
- **Finding:** Two D2 files empty (`architecture-blueprint.md`,
  `adr/001`, `evaluation-table.md`), eight one-line placeholders, three
  `[Optional]` files with filename-form markers. Nine ASRs and seven
  invariants identified. Two install bypasses found (`ActionHandler`
  session auto-approve, `InAppInstallProviderImpl`).
- **Disposition:** accepted; produced the population plan.
- **Evidence:** `docs/plans/2026-11-17-d2-architecture-population.md`
  §2–§3.

### EV-002 — 2026-11-17 — Gate-A approval and batch rescope

- **Activity:** Human review of the plan and six open questions.
- **Finding:** Plan approved. Batch 1 redefined to include
  `evaluation-table.md`, both ADRs, `permission-model.md`, and
  `runtime-policy-catalog.md`; blueprint moved to a later batch.
- **Disposition:** accepted; plan §0 records the deviation.
- **Evidence:** plan §0 and §6.

### EV-003 — 2026-11-17 — Batch 1 evaluation of candidate architectures

- **Activity:** ADD-style driver clustering and candidate comparison.
- **Inputs:** nine ASRs; `evaluation-table.md` candidates A/B/C.
- **Finding:** Candidate A (autonomous multi-agent) fails D1 and
  INV-001/006/007 structurally. Candidate B (guarded hybrid with a
  deterministic policy gate) passes all four drivers. Candidate C
  (model-blind) is the reference, not a product candidate.
- **Disposition:** accepted; Candidate B selected in ADR-001 (initially
  recorded as a proposal, accepted as Option B on 2026-11-17 — see
  EV-008).
- **Evidence:** `evaluation-table.md` §2–§5;
  `adr/001-architecture-choice.md` §3.

### EV-004 — 2026-11-17 — Batch 1 authority-boundary specification

- **Activity:** Specify authority separation and integrity verification.
- **Finding:** Requirements rev.6 fully determine the separation:
  advisory elements produce data only; policy owns the decision; human
  confirmation cannot override `BLOCK`; the gateway is the only backend
  caller on the in-scope path. Binding and hash recheck before submission
  are specified by INV-003 and SEC-003.
- **Disposition:** accepted; recorded as ADR-002.6 and ADR-002.7, marked
  `decided (specified)` with planned tests only.
- **Evidence:** `adr/002-later-important-arch-decisions.md`.

### EV-005 — 2026-11-17 — Batch 2 invariant and failure-mode review

- **Activity:** Map all seven invariants to baseline enforcement and
  complete the runtime failure catalog.
- **Inputs:** `ProcessInstallationUseCase.kt`,
  `PlatformInstallPolicyChecker.kt`,
  `IBinderAppInstallerRepoImpl.kt`, `InstallErrorType.kt`,
  `OperationHistoryEntity.kt`, `FileHashUtils.kt`.
- **Finding:** Six of seven invariants map onto an existing mechanism or
  a named new element. INV-001 and INV-006 have no baseline equivalent.
  The `Authorizer.None` implementation does not itself re-check the
  blacklist lists; the check runs on the `IBinder` path. Recorded as
  fact, preserved, not changed.
- **Disposition:** accepted; catalog §7 restructured into §7.1–§7.5.
- **Evidence:** `invariants.md` §3; `runtime-policy-catalog.md` §7.

### EV-006 — 2026-11-17 — Batch 3 verification of traceability

- **Activity:** Generate the 41-row trace matrix and run a read-only
  coverage check.
- **Inputs:** `requirements.yaml`, generated matrix.
- **Finding:** 41/41 requirements mapped, 0 dangling IDs, 0 priority or
  component mismatches, 7/7 invariants in the coverage section, 9/9 ASRs
  in the sub-matrix. During self-review a hardcoded tier count (28/2) was
  found to be wrong and was recomputed (30/0 plus 11 dual-tier rows).
- **Disposition:** corrected before completion; result recorded honestly.
- **Evidence:** `trace-matrix.md` §5; verification script output in the
  session report.

### EV-007 — 2026-11-17 — Batch 4 option and risk review

- **Activity:** Consolidate rejected/deferred options and risks.
- **Finding:** Seven options rejected, three deferred. Highest-priority
  technical risk is the missing SHA-256 carrier on
  `PackageAnalysisResult` (RK-T-01), which blocks INV-003 implementation.
  Highest-impact security risk is prompt injection (RK-S-01), which is
  reduced but not eliminated.
- **Disposition:** accepted; options recorded in `option-ledger.md`,
  risks in `risk-list.md`.
- **Evidence:** this file; `option-ledger.md`; `risk-list.md` §5.

---

### EV-008 — 2026-11-17 — ADR-001 accepted: Option B selected over Option A

- **Activity:** Team lead review of ADR-001's module question.
- **Inputs:** `adr/001-architecture-choice.md` §2–§4; active modules in
  `settings.gradle.kts`; `docs/code-governance/structural-constraints.md`
  §1–§2.
- **Finding:** The architect recommended Option A (new Gradle module) for
  build-time enforcement of REQ-IX-INV-001 and REQ-IX-INV-006. The team
  lead selected Option B (in-tree packages in the existing `app` module)
  instead, weighing limited Android infrastructure experience, a tight
  schedule, and a preference for a working testable integration over new
  build complexity. The authority separation itself is unchanged; only
  the enforcement mechanism changes from compile-time to
  interface + review + static check + focused tests.
- **Disposition:** accepted and propagated. ADR-001 status is now
  `accepted`; the blueprint §7.1 and §9, invariants INV-006 link,
  permission-model §9.3, risk RK-T-08, and ADR-002 dependency line were
  updated. Option A is recorded as rejected in `option-ledger.md`
  (OL-03a); Option B is recorded as accepted (OL-03).
- **Evidence:** `adr/001-architecture-choice.md` §3, §3.1, §5, §6;
  `option-ledger.md` OL-03/OL-03a.

---

## 3. Verification status at close of D2 population

| Check | Result |
|---|---|
| Requirement coverage | PASS 41/41, 0 dangling |
| Invariant negative tests named | 7/7 |
| Decision rules catalogued | DR-01…DR-10 present, order preserved |
| Unknown requirement IDs in D2 files | 0 |
| CJK characters in new English content | 0 |
| Files under `app/` modified | 0 |
| Code or tests implemented | none — documentation only |
| Gradle build run | not run; not required (no source change) |

## 4. Open items carried forward

1. ADR-001 is accepted (Option B, no new module); concrete package roots
   are still open (ADR-001 §8).
2. ADR-002.1–002.5 are not started.
3. Requirements rev.6 is still a draft; the matrix must be regenerated on
   change.
4. Retention and deletion policy (OI-06) is unfrozen.
5. The bypasses in `ActionHandler` and `InAppInstallProviderImpl` remain
   out of scope.
