# ADR-001: Architecture Choice — Guarded Hybrid with a Deterministic Policy Gate

**Status:** accepted — 2026-11-17.
**Date:** 2026-11-17.
**Decided by:** team lead review (Member A), recorded by Member C
(Software Architect).
**Owner:** Member C (Software Architect).
**Baseline:** extends InstallerX Revived `816ea541`; supersedes none.
**Requirements:** `docs/req/requirements.yaml` rev.6
(`status: draft_for_group_review`).

This ADR is accepted. It selects **Option B**: the AI component and the
deterministic policy gate are implemented inside the existing `app`
module, in packages that follow the established `domain`, `data`, `di`,
and `ui` layering. **No new Gradle module is created.** This keeps the
project within the active module set from `settings.gradle.kts`
(`:app`, `:app-process`, `:hidden-api`, `:baselineprofile`).

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

The open structural question was where the AI component and the policy
gate live, and how their separation is enforced. Team constraints on the
answer:

- limited team experience with Android infrastructure;
- a tight project schedule;
- a priority on a working, testable integration over new build
  infrastructure.

---

## 2. Options

### Option A — New standalone Gradle module for AI component and policy

The advisory components and the deterministic gate live in a new Gradle
module; the app module depends on it.

- Boundary enforcement: module dependency direction is machine-checkable;
  a forbidden import fails compilation.
- REQ-IX-INV-006: enforceable by module dependency.
- Cost: new module, build wiring, registry entry, Koin registration
  across a boundary, mapping to the course `src/` and `test/` layout.
- Risk: the module must not pull Android framework classes if policy is
  to stay pure JVM-testable, requiring a deliberate pure/Android split.
- **Not selected.** See §3.

### Option B — In-tree packages in the existing `app` module (selected)

The same components live in new packages inside `app`, placed in the
established layers, with a documented mapping to the course `src/` and
`test/` directories in the report.

- Boundary enforcement: interfaces, code review, and focused tests; a
  forbidden import compiles, so a static check or test rule is required.
- REQ-IX-INV-006: enforceable by a lint or test rule plus review.
- Cost: lowest; no build wiring; Koin registration in an existing `di/`
  module, unchanged in kind.
- Risk: the separation can erode silently because nothing fails the build
  when a dependency direction breaks. Mitigated by §6.

### Option C — Autonomous agent owns the decision (rejected)

Evaluated in `evaluation-table.md` §4 and rejected there. It fails
REQ-IX-INV-001, REQ-IX-INV-006, and REQ-IX-INV-007 because the planner
holds the backend and the policy module is advisory only.

---

## 3. Decision

**Selected: Option B.**

The architecture remains the **guarded hybrid with a deterministic policy
gate**: a coordinator sequences evidence collection, sanitization,
advisory production, output validation, deterministic policy, human
review, and submission through a gateway; the model holds no installer
or session capability.

The implementation lives inside the existing `app` module, reusing the
baseline layering:

| Element | Owning layer (baseline layering) |
|---|---|
| `InstallEvidenceCollector` | `domain` (business rule) over `data` providers |
| `EvidenceSanitizer`, `AIOutputValidator` | `domain` (pure logic) |
| `AIRiskAdvisor` interface | `domain`; implementation in `data` behind the interface |
| `DeterministicPolicyEngine` | `domain`; pure Kotlin, no model SDK, no network |
| `ApprovalCoordinator`, `HumanReviewHandler` | `domain` |
| `InstallationGateway` interface | `domain`; implementation in `data` wrapping `AppInstallerRepository` |
| `ApprovalStateStore`, `DecisionAuditRecorder` | `domain` contracts; `data` implementations on the existing Room/DataStore stack |
| `ConsentGate`, `NetworkGate` | `domain` |
| `AuditQuery UI`, review/consent screens, `InstallerSession UI` | `ui` (both `ui/page/main` and `ui/page/miuix`) |
| Koin registration | existing modules under `di/` |

Exactly one package (or sub-package) per element, per
`docs/code-governance/structural-constraints.md` §1. Element names stay
identical to the names in `requirements.yaml` and the blueprint.

### 3.1 Trade-off against Option A

Option B is selected over Option A **despite** Option A's stronger
enforcement. The trade-off is explicit:

| Criterion | Option A | Option B (selected) | Why it does not block selection |
|---|---|---|---|
| Boundary enforceability | Build-time failure | Check-time only | Interfaces plus a static test rule provide a check; see §6 |
| REQ-IX-INV-006 static checkability | Module dependency | Custom lint or test | A test asserts the policy package imports no model SDK or network client |
| Team familiarity | New module and build wiring | Existing `app` structure | Team has limited Android build experience |
| Schedule | Extra module setup and mapping | No new infrastructure | Tight schedule; deliver a testable integration first |
| Koin wiring | Across module boundary | Inside existing `di/` modules | Lower wiring risk |
| Build time | Higher | Lowest | Faster iteration |
| app-process isolation | Module graph | Discipline + review | Advisory code lives in `domain` and has no `app-process` dependency by contract |
| Reversibility | Moderate | High; packages can be extracted into a module later | Option A remains available as a future refactor |

Accepted cost: enforcement depends on discipline, review, and tests
rather than on the compiler. This is recorded as a known limitation in
`risk-list.md` (RK-G-06) and must be tracked, not forgotten.

The **authority separation is unchanged** by this choice. Only the
mechanism that enforces it changes:

- The AI advisor returns structured recommendations only and has no
  access to installation backends.
- The deterministic policy engine remains independent of model SDKs and
  network operations.
- The approval coordinator controls the transition from assessment to
  installation submission.

---

## 4. Evaluation criteria

| Criterion | Option A | Option B |
|---|---|---|
| Boundary enforceability | Build-time failure on a forbidden import | Check-time only |
| REQ-IX-INV-006 static checkability | Module dependency | Custom lint or test |
| Team familiarity | Low — new module and build wiring | High — existing structure |
| Schedule impact | Higher setup cost | Lowest |
| Koin wiring cost | Moderate — new module registration | Low |
| app-process isolation | Module graph | Requires discipline |
| Build time | Higher — extra module | Lowest |
| Course `src/`/`test/` mapping | Direct, module to directory | Indirect, documented mapping |
| Reversibility | Moderate — module can be folded back | High — packages can be extracted later |

Option A wins on enforceability only; Option B wins on familiarity,
schedule, wiring cost, build time, and reversibility. Under the team's
stated constraints, Option B is selected.

---

## 5. Consequences

Accepted consequences of Option B:

- **Positive.** No new Gradle module, registry entry, or cross-module
  wiring. New elements land in existing `domain`, `data`, `di`, and `ui`
  packages, following the nearest comparable feature. Package tests run
  as ordinary app-module tests alongside the existing suites under
  `app/src/test`.
- **Negative.** REQ-IX-INV-001 and REQ-IX-INV-006 are not enforced by the
  compiler. They must be enforced by a static check or test rule (§6),
  code review, and the KDoc traceability tag.
- **Neutral.** UI work is unchanged: review and consent screens still
  live in both UI families under `ui/`.
- **Follow-up available.** Extracting the packages into a module later is
  a refactor, not a redesign; this ADR can be amended to do so if the
  check-based enforcement proves insufficient.

---

## 6. Enforcement without a module boundary

Because the build no longer fails on a forbidden import, the following
checks are **mandatory** before any claim that INV-001 or INV-006 is
enforced:

1. **Static import check.** A test walks the source of the advisory and
   policy packages and fails if any file imports a forbidden type
   (`AppInstallerRepository`, `AppInstallerRepositoryImpl`, the
   `data/engine/executor/appinstaller` implementations, anything under
   `domain/privileged` or `framework/privileged`, session repositories or
   handlers, `ActionHandler`, or `okhttp3`/model SDK types in the policy
   package). See `permission-model.md` §9.2–§9.3.
2. **Code review.** Every PR touching the advisory or policy packages is
   reviewed against `permission-model.md` §9.2.
3. **Focused negative tests.** `planned: AdvisorIsolationTest`,
   `planned: PolicyWithoutModelTest`, `planned: HardBlockPrecedenceTest`,
   `planned: NoDowngradeTest`, `planned: ChokePointTest`.
4. **KDoc traceability.** Every new public class or function carries the
   `Implements: ADR-001` / `Enforces: REQ-IX-…` tags required by
   `structural-constraints.md` §3.

If any of these checks is absent, the corresponding invariant is **not**
claimed as enforced.

---

## 7. Alternatives not carried forward

- **Model call inside `AppInstallerRepositoryImpl`.** Rejected: it puts
  model access on the authoritative path and violates
  REQ-IX-INV-001 by construction.
- **Separate process for the AI component.** Rejected: adds IPC cost and
  lifecycle handling without changing the authority relationship; also
  against the "no extra infrastructure" priority.
- **Policy inside the UI layer.** Rejected: REQ-IX-ARCH-001 forbids
  UI-direct backend calls, and a UI-hosted policy cannot be tested
  without Android.
- **Autonomous multi-agent.** Rejected: fails INV-001, INV-006, INV-007.

Cross-listed in `option-ledger.md` (OL-01, OL-03, OL-04, OL-07, OL-08).

---

## 8. Open questions

1. Exact package roots inside `app` (for example a shared `ai` package
   with `domain`/`data` sub-packages). To be fixed in the first build
   plan, not here.
2. Whether the pure policy logic sits in the same package as the
   coordinator or in its own sub-package.
3. The mapping of the course `src/` and `test/offline`, `test/live`
   directories to Gradle source sets
   (`docs/code-governance/verification-constraints.md` §4 defers this
   here).

---

## 9. Traceability

Governs the structural basis for: REQ-IX-ARCH-001, REQ-IX-INV-001,
REQ-IX-INV-006, REQ-IX-INV-007, REQ-IX-MOD-001, REQ-IX-SEC-001.

Related: `architecture-blueprint.md` §9, `evaluation-table.md` §2–§5,
`adr/002-later-important-arch-decisions.md`, `permission-model.md` §9,
`option-ledger.md`, `evaluation-logbook.md` EV-008.
