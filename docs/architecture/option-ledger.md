# Option Ledger

**Status:** draft — pending human review.
**Owner:** Member C (Software Architect).
**Baseline:** InstallerX Revived `816ea541`; repository state `0f718739`.
**Requirements:** `docs/req/requirements.yaml` rev.6
(`status: draft_for_group_review`).
**Related:** `adr/001-architecture-choice.md`,
`adr/002-later-important-arch-decisions.md`, `evaluation-table.md`.

Course marker `[Optional]` — optional artifact, populated for review
value.

This ledger records architectural options that were evaluated and then
rejected or deferred, with the reason. It exists so a later reviewer can
see what was considered, not only what was chosen. An option recorded
here is not thereby forbidden; it is currently not selected.

---

## 1. Decision candidates

### OL-01 — Autonomous multi-agent decides the install

- **Status:** rejected.
- **Description:** An AI planner owns the decision and holds the session
  and installer backend; sub-agents analyse, call the model, and install.
- **Rejected because:** fails REQ-IX-INV-001 (AI must hold no backend
  capability), REQ-IX-INV-006 (policy must be model-independent), and
  REQ-IX-INV-007 (AI may not lower a restriction). No repair is possible
  without removing the planner's authority, which makes it the selected
  design.
- **Reversibility:** not reversible without re-opening the requirements.
- **Recorded in:** `evaluation-table.md` §3–§4.

### OL-02 — Policy as an advisory module to an AI planner

- **Status:** rejected.
- **Description:** Keep the guarded shape but let the planner override
  the policy result when confidence is high.
- **Rejected because:** direct contradiction of REQ-IX-INV-002 and
  REQ-IX-INV-007. A policy that can be overridden is not a gate.
- **Reversibility:** not reversible without re-opening the requirements.
- **Recorded in:** `adr/002-later-important-arch-decisions.md` ADR-002.6.

### OL-03 — In-tree packages in the existing `app` module (ADR-001 Option B)

- **Status:** **accepted** (2026-11-17, team lead review; ADR-001).
- **Description:** Place the AI components and the policy gate in new
  packages inside `app`, following the existing `domain`, `data`, `di`,
  and `ui` layering, rather than in a separate Gradle module.
- **Why selected:** limited team experience with Android build
  infrastructure, a tight schedule, and a priority on a working, testable
  integration over new infrastructure. Option B has the lowest wiring
  cost, the shortest build, and the highest reversibility.
- **Accepted cost:** REQ-IX-INV-001 and REQ-IX-INV-006 are enforced by a
  static import check, code review, focused negative tests, and KDoc
  tags, not by the compiler (`adr/001-architecture-choice.md` §6).
- **Reversibility:** high — the packages can be extracted into a module
  later if check-based enforcement proves insufficient.
- **Recorded in:** `adr/001-architecture-choice.md` §3, §5, §6.

### OL-03a — New standalone Gradle module for AI component and policy (ADR-001 Option A)

- **Status:** **rejected** (was the architect's recommendation; rejected by
  team lead review on 2026-11-17).
- **Description:** Put the advisory components and the deterministic gate
  in a new Gradle module depended on by `app`.
- **Why not selected:** it is the strongest enforcement mechanism (a
  forbidden import fails compilation), but it adds module setup, build
  wiring, registry change, cross-boundary Koin registration, and a
  pure/Android split. Under the team's stated constraints, that cost is
  not justified.
- **When it becomes worth revisiting:** if the check-based enforcement of
  INV-001 or INV-006 proves insufficient in practice, or if the advisory
  code needs a genuinely different dependency graph.
- **Reversibility:** moderate — a module can be folded back into `app`.
- **Recorded in:** `adr/001-architecture-choice.md` §2, §3.1, §4.

### OL-04 — Separate process for the AI component

- **Status:** rejected.
- **Description:** Run the AI adapter in its own process with IPC to the
  app.
- **Rejected because:** a module boundary (Option A) would have isolated
  the dependency direction more cheaply, and the selected in-tree layout
  (Option B) isolates by contract and check; a separate process adds IPC
  complexity, lifecycle handling, and a new failure mode without changing
  the authority relationship — directly against the "no extra
  infrastructure" priority.
- **Reversibility:** moderate.
- **Recorded in:** `adr/001-architecture-choice.md` §7.

### OL-05 — File-based approval store instead of Room

- **Status:** deferred.
- **Description:** Persist approval binding and audit entries as files
  (JSON/NDJSON) rather than in the Room database.
- **Why deferred:** the baseline already ships a migrated, schema-exported
  Room database (`InstallerRoom.kt`, version 18) with a working history
  table and DAO. Adding a second persistence stack increases the number
  of places a crash can lose state.
- **Open concern:** an append-only hash-chained audit is a good fit for
  append-only files; this concern is why the option stays on the ledger
  for ADR-002.3.
- **Reversibility:** high.
- **Recorded in:** `memory-design.md` §3.3, ADR-002.3.

### OL-06 — New `NetworkGate` element vs reuse of `AllowInternetAccess`

- **Status:** deferred.
- **Description:** Keep the existing `BooleanSetting.AllowInternetAccess`
  setting plus an inline guard instead of a named `NetworkGate` element.
- **Why deferred:** a named element owned by REQ-IX-SEC-004 is easier to
  test and to trace than an inline guard spread across call sites. Reuse
  would reduce new types but weaken traceability.
- **Reversibility:** high.
- **Recorded in:** ADR-002.5, `architecture-blueprint.md` §3 #13.

### OL-07 — Model call inside `AppInstallerRepositoryImpl`

- **Status:** rejected.
- **Description:** Call the model from the repository implementation, on
  the authoritative path.
- **Rejected because:** it puts model access inside the installer
  backend layer and violates REQ-IX-INV-001 by construction.
- **Reversibility:** not reversible without re-opening the requirements.
- **Recorded in:** `adr/001-architecture-choice.md` §6.

### OL-08 — Policy inside the UI layer

- **Status:** rejected.
- **Description:** Implement the decision in `InstallerViewModel` or the
  Miuix page.
- **Rejected because:** REQ-IX-ARCH-001 forbids UI-direct backend calls,
  and a UI-hosted policy cannot be tested without Android. It also breaks
  the one-element-one-module rule.
- **Reversibility:** moderate.
- **Recorded in:** `adr/001-architecture-choice.md` §6.

### OL-09 — Change `OperationHistoryEntity` in place for the audit

- **Status:** deferred.
- **Description:** Extend the existing history entity with all FR-009
  fields and hash-chain columns.
- **Why deferred:** `OperationHistoryRepositoryImpl.insert` replaces the
  previous row for the same session key, which contradicts the
  append-only audit requirement. Mixing the two semantics in one table is
  risky.
- **Alternatives:** a separate `decision_audit` table in the same
  database (assumed in `memory-design.md` §3.3).
- **Reversibility:** moderate (schema migration).
- **Recorded in:** `memory-design.md` §3.2–§3.3, ADR-002.3.

### OL-10 — Flag INV-005 as an ASR

- **Status:** deferred (registry decision, not an architecture decision).
- **Description:** Ask Member B to mark REQ-IX-INV-005 idempotency as
  architecturally significant.
- **Why deferred:** the registry currently sets `asr: false`. Changing it
  is outside D2's authority; it is recorded as plan Q3.
- **Reversibility:** high.
- **Recorded in:** `invariants.md` §5, plan §8 Q3.

---

## 2. Summary

| ID | Option | Status | Primary driver |
|---|---|---|---|
| OL-01 | Autonomous multi-agent | rejected | D1, INV-001 |
| OL-02 | Advisory policy | rejected | D1, INV-002/007 |
| OL-03 | In-tree packages | **accepted** | D1 enforcement |
| OL-03a | New Gradle module | rejected | D1 enforcement |
| OL-04 | Separate process | rejected | D4 |
| OL-05 | File-based store | deferred | D2 |
| OL-06 | Reuse network setting | deferred | D4 |
| OL-07 | Model in repository | rejected | D1, INV-001 |
| OL-08 | Policy in UI | rejected | D1, ARCH-001 |
| OL-09 | Extend history entity | deferred | D2, DATA-001 |
| OL-10 | INV-005 as ASR | deferred | process |
