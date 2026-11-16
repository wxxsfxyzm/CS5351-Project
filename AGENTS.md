# AGENTS.md


## CS5351 Project Governance

This repository extends InstallerX Revived for the
CS5351 Software Engineering project.

The existing InstallerX engineering constraints remain
applicable. The following rules govern AI-assisted work.

### Workflow

1. Read the relevant requirements and approved constraints.
2. Inspect existing behavior before proposing changes.
3. Present a plan, affected artifacts, and verification strategy.
4. Obtain explicit human approval before implementation.
5. Execute only the approved scope.
6. Verify results and report failures honestly.

Implementation plans and relevant tests must be approved
before coding. Silence is not approval.

### Authority

- Approved requirements, invariants, and ADRs constrain work.
- Draft documents are proposals, not approved baselines.
- AI advice cannot authorize installation or bypass
  deterministic security policies.
- Changes to approved constraints require human review.
- Agents must not self-approve or merge their own changes.

### Traceability and Evidence

- Link substantive changes to requirements and ADRs.
- Preserve complete, authentic agent session transcripts.
- Record significant human interventions and decisions.
- Update the AI-use log for relevant agent-assisted work.
- Never fabricate approvals, verification, or evidence.

### Repository Operations

- Use task branches and Pull Requests; do not push to main.
- Do not commit, push, or create PRs without authorization.
- Do not bypass CI, branch protection, or permission controls.
- Stop and request approval when scope or safety constraints
  would be violated.

## Build Target

InstallerX Revived (Android, Kotlin) extended with an AI
component that gives advisory install-risk recommendations.
Deterministic code makes every install decision; AI output
never authorizes an install.

- Smoke build: `./gradlew assembleUnstableDebug`.
- PR-equivalent build: `./gradlew assemblePreviewDebug`.
- Toolchain and variant rules:
  `docs/code-governance/installerx-baseline.md`.

## Code Baseline

- Baseline: upstream InstallerX Revived at commit `816ea541`,
  the last upstream commit before CS5351 work began.
- New work extends the baseline and must not replace it.
- Active Gradle modules: `:app`, `:app-process`, `:hidden-api`,
  `:baselineprofile` (see `settings.gradle.kts`).

## Reading Order

1. `AGENTS.md` (this file).
2. `Prompt.md` — session workflow and approval gates.
3. `architecture-blueprint.md` — master constraint document.
4. `docs/req/requirements.yaml` — requirements and fit criteria.
5. `docs/architecture/` — ADRs, `invariants.md`,
   `permission-model.md`, then other artifacts as relevant.
6. `docs/code-governance/structural-constraints.md` and
   `verification-constraints.md`.
7. `docs/code-governance/installerx-baseline.md` — before any
   Android implementation, build, dependency, commit, or PR work.

Read steps 4–7 to the depth the current task needs. Do not
infer the current project phase or approval status from the
existence of a file.

## Agent Tooling and Roles

- Coding-agent sessions use OpenCode v1 (team agreement). Start
  every session with
  `OPENCODE_CONFIG=.opencode/permissions.jsonc opencode`; without
  it the project permissions are not loaded.
- Roles: `plan` (plans and governance drafts), `build` (source and
  tests), `verify` (checks and QA records). Tools per role:
  `docs/code-governance/mcp-tool-manifest.md`.
- Session workflow and approval gates: `Prompt.md`. OpenCode
  injects `Prompt.md`, `.opencode/rules.md`, and `.agent/rules.md`
  through `instructions`; the first message must still reference
  `@Prompt.md` (format in `Prompt.md`, step 0).

## Access Scope

`docs/architecture/permission-model.md` defines per-role access and
mirrors `.opencode/permissions.jsonc`. Until it is approved, these
defaults apply:

- **Readable:** the read whitelist in the permission model; never
  credentials, keystores, or local Gradle properties.
- **Modifiable:** only the paths inside the approved task scope.
- **Human approval required:** `AGENTS.md`, `Prompt.md`,
  `architecture-blueprint.md`, `docs/req/`, `docs/architecture/`,
  `docs/code-governance/`, `.opencode/permissions.jsonc`,
  `.agent/rules.md`, `.github/workflows/`.
- **Frozen (agents must not edit):** `.opencode/rules.md`
  (human-only), `.github/CODEOWNERS`, signing configuration,
  and release workflows such as `manual-stable-release.yml`.

## Module Organization

- InstallerX package layering (`core`, `data`, `di`, `domain`,
  `framework`, `ui`) is described in the baseline document.
- Each architectural element maps to exactly one module; see
  `docs/code-governance/structural-constraints.md`.
- Placement of the AI component and policy engine, and the
  mapping to the course `src/` and `test/` layout, are decided
  in `docs/architecture/adr/`. Do not create modules before that
  decision is approved.

## Integration Contracts

- The AI component returns advisory data only. It must not hold
  or call installer, Root, Shizuku, Dhizuku, or other privileged
  backend capabilities.
- Only the deterministic policy path may decide an install or
  invoke an installer backend.
- Policy code must not depend on model SDKs or network access.
- Approved interface definitions live in the ADRs and
  `docs/architecture/trace-matrix.md`. Where they are not yet
  approved, stop and ask rather than invent a contract.

## Verification Requirements

- Follow `docs/code-governance/verification-constraints.md`:
  structural, regression, and behavioral tiers.
- Every fit criterion has at least one test; every invariant has
  at least one negative test that fails when it is violated.
- Every test references the requirement or invariant it verifies.
- Each round re-runs all prior test suites with zero
  regressions.
- Tests for a change are approved before implementation begins.
- Report the commands run and their results; never claim
  unverified results.

## Critical Constraints

These InstallerX safety boundaries apply to every task:

- Network requests must stay disabled when the in-app network
  setting is off.
- Prefer existing native API paths; do not add shell-command
  shortcuts unless a maintainer explicitly requests them.
- Dialog, notification, and automatic install flows are
  distinct; do not assume a capability carries across flows.
- Do not widen privileged behavior between Root, Shizuku,
  Dhizuku, or `app_process` backends without explicit evidence.
- Never commit credentials or weaken credential handling.
- Do not alter signing, versioning, or release workflows during
  unrelated work.
