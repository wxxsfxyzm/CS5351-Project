# ADR-001: Architecture Choice — Guarded Hybrid with a Deterministic Policy Gate

**Status:** proposed — requires team decision.
**Date:** 2026-11-17.
**Owner:** Member C (Software Architect).
**Baseline:** extends InstallerX Revived `816ea541`; supersedes none.
**Requirements:** `docs/req/requirements.yaml` rev.6
(`status: draft_for_group_review`).

This ADR records a proposal. It is not an accepted baseline. Per
`docs/code-governance/structural-constraints.md` §1, no new Gradle
module may be created until this ADR is accepted.

---

## 1. Context

The project adds an advisory install-risk component to InstallerX
Revived. Requirements rev.6 constrains its shape in ways that determine
the architecture before any code exists:

- **REQ-IX-ARCH-001** requires a single
  `ApprovalCoordinator → InstallationGateway` choke point for every
  in-scope submission. AI, UI, and policy must not call the installer
  backend directly.
- **REQ-IX-INV-006** requires policy decisions to work with no model
  SDK and no network, so policy cannot sit behind the model boundary.
- **REQ-IX-INV-007** allows auto-approval only when the base policy
  already permits it and the advisory result is favorable.
- **REQ-IX-INV-001** and **REQ-IX-SEC-001** forbid the AI component from
  holding or calling installer, Root, Shizuku, Dhizuku, or
  `app_process` capability.
- **REQ-IX-MOD-001** requires the model provider behind one adapter
  interface.

The existing code already has a layered structure. The install path runs
from `InstallerViewModel` and `ProgressHandler` through
`ActionHandler`, `ProcessInstallationUseCase`, and
`AppInstallerRepository`, with hard-prohibition checks in
`ProcessInstallationUseCase.checkBlockedByProfile()` and
`PlatformInstallPolicyChecker`. See
`docs/plans/2026-11-17-d2-architecture-population.md` §3.3 for the
verified call graph.

The open structural question is where the AI component and the policy
gate live, and how their separation is enforced.

---

## 2. Options

### Option A — New standalone Gradle module for AI component and policy

The advisory components (`AIRiskAdvisor`, `EvidenceSanitizer`,
`AIOutputValidator`) and the deterministic gate
(`DeterministicPolicyEngine`) live in a new Gradle module. The module
declares only shared-model dependencies. The app module depends on it.

- Boundary enforcement: module dependency direction is machine-checkable;
  a forbidden import fails compilation.
- REQ-IX-INV-006: enforceable by module dependency, not by convention.
- Cost: new module, build wiring, Koin registration from the app module,
  mapping to the course `src/` and `test/` layout.
- Risk: the module must not pull Android framework classes if policy is
  to stay pure JVM-testable; that requires a deliberate split between
  pure models and Android-facing adapters.

### Option B — In-tree packages under `app/src/main/java/.../ai/`

The same components live in new packages inside the app module, with a
documented path mapping to `src/` and `test/` in the report.

- Boundary enforcement: by static check and code review; a forbidden
  import compiles.
- REQ-IX-INV-006: enforceable by a lint or test rule, weaker than a
  module boundary.
- Cost: lowest; no build wiring; Koin registration unchanged in kind.
- Risk: the separation can erode silently because nothing fails the
  build when a dependency direction breaks.

### Option C — Autonomous agent owns the decision (rejected)

Evaluated in `evaluation-table.md` §4 and rejected there. It fails
REQ-IX-INV-001, REQ-IX-INV-006, and REQ-IX-INV-007 because the planner
holds the backend and the policy module is advisory only.

---

## 3. Decision

**Proposal: Option A**, subject to team acceptance.

The selected architecture is the guarded hybrid with a deterministic
policy gate: a coordinator sequences evidence collection, sanitization,
advisory production, output validation, deterministic policy, human
review, and submission through a gateway; the model holds no installer
or session capability.

Option A is proposed over Option B because REQ-IX-INV-006 is a
structural constraint, and a module boundary is the only mechanism in
this build system that fails automatically when the constraint breaks.

The blueprint and later ADRs name architectural elements only. Package
names and the final module list are not fixed by this ADR; they follow
once the team accepts Option A.

---

## 4. Evaluation criteria

| Criterion | Option A | Option B |
|---|---|---|
| Boundary enforceability | Build-time failure on a forbidden import | Check-time only |
| REQ-IX-INV-006 static checkability | Module dependency | Custom lint or test |
| Koin wiring cost | Moderate — one new module registration | Low |
| app-process isolation | Advisory code cannot reach `app-process` by module graph | Requires discipline |
| Build time | Higher — extra module | Lowest |
| Course `src/`/`test/` mapping | Direct, module to directory | Indirect, documented mapping |
| Reversibility | Moderate — module can be folded back | High |

No criterion is decisive against Option A. The build-time property is
decisive for it.

---

## 5. Consequences

If accepted:

- **Positive.** REQ-IX-INV-001, REQ-IX-INV-006, and REQ-IX-ARCH-001 can
  be checked structurally. Policy tests run without Android.
  REQ-IX-MOD-001 is testable by swapping the adapter in the module's
  test source set.
- **Negative.** A new Gradle module, registry entry, and Koin
  registration are added. The pure/Android split inside the module must
  be maintained deliberately.
- **Neutral.** UI work is unchanged: review and consent screens still
  live in both UI families under `ui/`.

If rejected and Option B is chosen instead:

- The same element boundaries apply; only enforcement weakens.
- A lint or test rule must replace the module boundary before
  REQ-IX-INV-006 can be claimed as enforced.
- This ADR must be superseded, not silently reinterpreted.

---

## 6. Alternatives not carried forward

- **Model call inside `AppInstallerRepositoryImpl`.** Rejected: it puts
  model access on the authoritative path and violates
  REQ-IX-INV-001 by construction.
- **Separate process for the AI component.** Folded into Option A,
  which already isolates by module; a separate process adds IPC cost
  without changing the authority relationship.
- **Policy inside the UI layer.** Rejected: `InstallerViewModel` and the
  Miuix page are presentation; REQ-IX-ARCH-001 forbids UI-direct backend
  calls, and a UI-hosted policy cannot be tested without Android.

Cross-listed in `option-ledger.md` (OL-03, OL-04, OL-07, OL-08).

---

## 7. Open questions

1. Final Gradle module name and package root, if Option A is accepted.
2. Whether the pure policy/model part must be a separate source set
   inside the module or a separate module of its own.
3. The mapping of the course `src/` and `test/offline`, `test/live`
   directories to Gradle source sets
   (`docs/code-governance/verification-constraints.md` §4 defers this
   here).

---

## 8. Traceability

Implements the structural basis for: REQ-IX-ARCH-001,
REQ-IX-INV-001, REQ-IX-INV-006, REQ-IX-INV-007, REQ-IX-MOD-001,
REQ-IX-SEC-001.

Related: `evaluation-table.md` §2–§5,
`adr/002-later-important-arch-decisions.md`.
