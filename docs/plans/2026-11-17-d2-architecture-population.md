# D2 Architecture Population — Execution Plan

**Status:** Gate-A approved 2026-11-17. Batch execution in progress.
**Role:** plan (Member C, Software Architect)
**Date:** 2026-11-17
**Artifact path:** `docs/plans/2026-11-17-d2-architecture-population.md`
**Baseline:** InstallerX Revived `816ea541`; repo state `0f718739`.
**Requirements source:** `docs/req/requirements.yaml` rev.6
(`status: draft_for_group_review`).

---

## 0. Purpose and Approval Gate

Populate the D2 architecture artifacts (12 files) from the D2 skeletons
into reviewable drafts, grounded in rev.6 requirements and the actual
Android code at the baseline commit.

This plan does not authorize code, tests, Gradle, module, dependency,
or `docs/req/` changes.

**Gate A:** human approval of this plan — granted 2026-11-17.
**Gate B:** per-batch human review before the next batch starts.
**Gate C:** per-file edit approval imposed by
`docs/architecture/permission-model.md`.

Silence, partial replies, or approval of a different plan do not
count as approval (`Prompt.md` §2).

### Batch 1 scope note (approved deviation)

Gate-A approval carried a batch re-definition: Batch 1 populates
`evaluation-table.md`, both ADRs, `permission-model.md`, and
`runtime-policy-catalog.md`. `architecture-blueprint.md` moves to a
later batch. This supersedes the original Batch 1 file list while
keeping the same content commitments.

---

## 1. Decisions Carried Into This Plan

| # | Decision | Effect on this plan |
|---|---|---|
| D1 | Rename the three `[Optional]*` files via `git mv` to bare course names; keep `[Optional]` as an in-body heading marker. | Batch 4. |
| D2 | ADR-001 recorded Option A (new standalone Gradle module) as the recommended **proposal**, Option B (in-tree packages) as the alternative. **Superseded 2026-11-17:** team lead review accepted **Option B**; ADR-001 status is now `accepted`. | Batch 1, revised after review (EV-008). |
| D3 | All new D2 content in English; retain inherited `[M]` and `[O]` markers. | All batches. |
| D4 | Mark `outbound_metadata_allowlist` and `COST-001` numeric values as **provisional, pending Member A review**. | Blueprint, catalog, option ledger. |
| D5 | Keep `permission-model.md` as agent-access governance, add top note, specify runtime AI-adapter isolation in blueprint, flag naming overlap. | Batch 1. |
| D6 | All 12 D2 files in scope, delivered in batches. | §6. |

---

## 2. Traceability

**ASRs covered (9):** REQ-IX-ARCH-001, REQ-IX-INV-003, REQ-IX-INV-004,
REQ-IX-INV-006, REQ-IX-INV-007, REQ-IX-SEC-005, REQ-IX-REL-002,
REQ-IX-REL-003, REQ-IX-MOD-001.

**Invariants covered (7):** REQ-IX-INV-001 … REQ-IX-INV-007.

**Also mapped:** REQ-IX-SCOPE-001, REQ-IX-COMP-001, REQ-IX-ARCH-002,
REQ-IX-DATA-001, REQ-IX-COST-001, REQ-IX-PER-001, REQ-IX-AIQ-001,
REQ-IX-QUAL-001, REQ-IX-VER-001, REQ-IX-GOV-001, REQ-IX-GOV-002.
All 41 requirement IDs appear in `trace-matrix.md` (Batch 3).

**Decision rules:** DR-01 … DR-10.

**Negative fit criteria:** NFC-OUT-001 … NFC-OUT-006, provisional.

---

## 3. Interface Contract

### 3.1 Architectural elements (verbatim `enforcing_component` names)

ApprovalCoordinator, InstallEvidenceCollector, AIRiskAdvisor,
AIOutputValidator, DeterministicPolicyEngine, HumanReviewHandler,
InstallationGateway, InstallerSession UI, DecisionAuditRecorder,
AuditQuery UI, ConsentGate, ApprovalStateStore, NetworkGate,
EvidenceSanitizer.

Process components (not runtime elements): Baseline regression suite,
Test suite, Evaluation harness, requirements registry, Process records,
Scope inventory.

No new component name is invented. Package/Gradle-module assignment
stays deferred to ADR-001 acceptance.

### 3.2 Authority boundary (advisory / authoritative / human)

- **Advisory:** AIRiskAdvisor, EvidenceSanitizer, AIOutputValidator
  return structured recommendation only; no installer, Root, Shizuku,
  Dhizuku, `app_process`, or session reference (`REQ-IX-INV-001`,
  `REQ-IX-SEC-001`).
- **Authoritative:** DeterministicPolicyEngine makes every decision and
  runs with no model SDK/network (`REQ-IX-INV-006`); cannot be lowered
  by AI or human (`REQ-IX-INV-002`, `REQ-IX-INV-007`).
- **Human:** HumanReviewHandler/ConsentGate confirm or refuse; cannot
  override a hard block.
- **Single choke point:** in-scope submissions pass
  ApprovalCoordinator → InstallationGateway (`REQ-IX-ARCH-001`,
  `REQ-IX-FR-007`).

### 3.3 Confirmed code integration points (baseline `0f718739`)

    UI: InstallerViewModel.install()                 ui/page/main/installer/InstallerViewModel.kt:817
        InstallerViewModel auto-install (500 ms)     .../InstallerViewModel.kt:598-604
        ProgressHandler.onAnalysedSuccess()          data/session/handler/ProgressHandler.kt:51-69
    -> InstallerSessionRepository.install(triggerAuth)
    -> ActionHandler.handleSingleInstall/performInstallLogic
                                                     data/session/handler/ActionHandler.kt:473, 573+
    -> ProcessInstallationUseCase.invoke             domain/engine/usecase/ProcessInstallationUseCase.kt:59
       -> checkBlockedByProfile()                    ProcessInstallationUseCase.kt:200-249
    -> AppInstallerRepository.doInstallWork          domain/engine/repository/AppInstallerRepository.kt:34
       -> AppInstallerRepositoryImpl.executeWithRepo data/engine/repository/AppInstallerRepositoryImpl.kt:60,176
       -> resolveRepo() -> None/Shizuku/Dhizuku/Process/System
                                                     AppInstallerRepositoryImpl.kt:233-249
       -> data/engine/executor/appinstaller/*

Interception seams:

- **AI assessment seam (FR-001/FR-002):** before `session.install(...)`,
  at `InstallerViewModel.kt:817` and `ProgressHandler.kt:51-69`.
- **InstallationGateway seam (FR-007/ARCH-001):** wrap
  `AppInstallerRepository.doInstallWork` / `executeWithRepo`
  (`AppInstallerRepositoryImpl.kt:60-101, 176`).
- **Hard-prohibition mechanisms for INV-002/SEC-002:**
  `ProcessInstallationUseCase.checkBlockedByProfile()`
  (`:200-249`), `data/engine/policy/PlatformInstallPolicyChecker.kt`,
  and the blacklist/sharedUid parameters to `doInstallWork`.
- **Out-of-scope bypasses:** `ActionHandler`
  `ResolveConfirmInstall`/`ApproveSession` (`ActionHandler.kt:666-770`);
  `InAppInstallProviderImpl.executeInstall`
  (`data/updater/provider/InAppInstallProviderImpl.kt:31`).
- **Persistence seam:** `data/settings/local/room/InstallerRoom.kt`,
  `dao/OperationHistoryDao.kt`, `entity/OperationHistoryEntity.kt`.
- **DI seam:** `di/EngineModule.kt:94,102`, `InstallerSessionModule.kt`,
  `NetworkModule.kt`, `PrivilegedModule.kt`.
- **Network boundary:** `BooleanSetting.AllowInternetAccess`
  (`AppSettingsRepository.kt:37`); `OkHttpNetworkResolver.kt`.
- **UI families (ARCH-002):** `ui/page/main/installer/**`,
  `ui/page/miuix/installer/**`, `InstallerActivityContent.kt:46`,
  `InstallerStage.kt`.

### 3.4 Evidence availability (FR-002)

Present: package/version/minSdk/targetSdk/permissions
(`AppEntity.kt:22-37`, `ApkParser.kt:394-590`); signature status
(`SignatureMatchStatus.kt`, `PackageSignatureAnalysis.kt`,
`SigningBlockCertificateStatus.kt`); installed info
(`InstalledAppInfo.kt:6-21`); source/config
(`ConfigModel.kt:62-65`, `InstallModes.kt:22-28`,
`Authorization.kt:11-18`, `ConfigResolver.kt`).
Gap: APK SHA-256 exists (`PackagePreprocessor.kt:106,175-177`) but is
not carried on `PackageAnalysisResult`; `install_relation`/`apk_identity`
derivations are absent. Recorded, not implemented.

---

## 4. Scope Boundaries

### In scope (files)

1. `architecture-blueprint.md`
2. `docs/architecture/adr/001-architecture-choice.md`
3. `docs/architecture/adr/002-later-important-arch-decisions.md`
4. `docs/architecture/invariants.md`
5. `docs/architecture/runtime-policy-catalog.md`
6. `docs/architecture/memory-design.md`
7. `docs/architecture/trace-matrix.md`
8. `docs/architecture/evaluation-table.md`
9. `docs/architecture/option-ledger.md` (renamed)
10. `docs/architecture/risk-list.md` (renamed)
11. `docs/architecture/evaluation-logbook.md` (renamed)
12. `docs/architecture/permission-model.md` (top note + runtime section)
13. This plan file.

### Out of scope (must not change)

- `docs/req/**`, `docs/code-governance/**`, `AGENTS.md`, `Prompt.md`,
  `.opencode/**`, `.agent/**`, `.github/**`.
- Any file under `app/` (source, tests, resources, Gradle).
- Any commit, push, PR, or merge.
- Creating Gradle modules.

---

## 5. Per-File Content Plan

Every file: English; `[M]`/`[O]` markers retained; top line
`Status: draft — pending human review`; no invented IDs, methods, or
package paths; unresolved items go to Open Questions.

### 5.1 `architecture-blueprint.md` (moved to Batch 2)
Header; §1 boundary; §2 non-negotiable rules; §3 element table; §4
interception points; §5 decision flow; §6 data & privacy (provisional);
§7 runtime AI-adapter least-privilege isolation; §8 persistence &
recovery; §9 module organization; §10 traceability index; §11 open
questions.

### 5.2 `adr/001-architecture-choice.md` (Batch 1)
Context; Options A/B with criteria; Decision = **Option B accepted**
(amended 2026-11-17 from the initial Option A proposal); Consequences;
Alternatives; "extends 816ea541, supersedes none".

### 5.3 `adr/002-later-important-arch-decisions.md` (Batch 1)
Index of deferred ADRs 002.1–002.5, each `not started`, linked IDs,
owner Member C. Gate-A added two: authority separation and
hash/integrity verification (recorded as `ADR-002.6`, `ADR-002.7`).

### 5.4 `invariants.md` (Batch 2)
`[M]` table of 7 invariants + baseline enforcement map + INV↔ASR
cross-ref + INV-005 open question.

### 5.5 `runtime-policy-catalog.md` (Batch 1)
Full DR-01..DR-10 first-match table; failure catalogue from
INV-004/REL-001 with dispatch/attempt counts (provisional per
COST-001); degradation matrix; per-element failure contracts;
counter semantics; each row linked to its code mechanism.

### 5.6 `memory-design.md` (Batch 3)
State machine from dispositions; binding record; Room mapping;
transaction + recovery; FR-009 audit fields; retention pending OI-06.

### 5.7 `trace-matrix.md` (Batch 3)
41-row requirement → component → test → tier → ADR → status; ASR
sub-matrix; coverage count from a read-only script.

### 5.8 `evaluation-table.md` (Batch 1)
ADD driver clustering of the 9 ASRs; candidate comparison
(autonomous multi-agent vs guarded hybrid with deterministic policy
gate); all assessment cells `not yet evaluated` where no code exists.

### 5.9 `option-ledger.md` (Batch 4) `[O]`
Options with decision/alternatives/rationale/reversibility/ADR link.

### 5.10 `risk-list.md` (Batch 4) `[O]`
Technical, security, scope, governance risks with
likelihood/impact/mitigation/owner/ID.

### 5.11 `evaluation-logbook.md` (Batch 4) `[O]`
Dated architecture-evaluation evidence log starting with this session.

### 5.12 `permission-model.md` (Batch 1)
Top note separating agent-access governance from runtime AI-adapter
isolation; new section naming the runtime privilege boundary and
forbidden dependencies; no edits to existing agent tables.

---

## 6. Steps and Batch Schedule

**Batch 1 (approved):**
1. Create this plan file.
2. `docs/architecture/evaluation-table.md` — ADD drivers + candidates.
3. `adr/001-architecture-choice.md`.
4. `adr/002-later-important-arch-decisions.md`.
5. `permission-model.md` — top note + runtime boundary section.
6. `runtime-policy-catalog.md`.
**Stop → review Batch 1.**

**Batch 2:** `architecture-blueprint.md`, `adr/001` refinements if
required, `invariants.md`. **Stop → review.**

**Batch 3:** `memory-design.md`, `trace-matrix.md`, coverage script.
**Stop → review.**

**Batch 4:** `git mv` the three `[Optional]` files; populate
`option-ledger.md`, `risk-list.md`, `evaluation-logbook.md`.
**Stop → review.**

**Close:** append `docs/ai-use-log.yaml` entry; report.

---

## 7. Verification Strategy

D2 is documentation; verification is gate-based and read-only:

| Check | Method | Pass condition |
|---|---|---|
| Requirement coverage | Python read of registry vs trace-matrix | 41/41 IDs, 0 dangling |
| Invariant negatives | Read invariants.md + YAML | 7/7 named negative tests |
| DR completeness | Read catalog vs `decision_rules` | DR-01..DR-10, order preserved |
| No invented IDs | Regex against registry | 0 unknown IDs |
| No invented contracts | Manual review | No new component names/methods/paths without ADR-001 |
| Provisional flags | Manual check | allowlist and COST numbers marked provisional |
| No source edits | `git status` | Only scoped docs changed |

Commands are read-only. No Gradle build is required because no code
changes. Report each command and result.

---

## 8. Risks and Open Questions

**Risks**
- R1: ADR-001 is now accepted on Option B; concrete package roots remain
  open and are fixed in the first build plan (`structural-constraints.md`
  §1 still requires one element = one package).
- R2: rev.6 is `draft_for_group_review`; PMA-* items pending.
- R3: `[Optional]` rename could break external links; re-verify before
  `git mv`.
- R4: Naming overlap between agent permission model and runtime model.
- R5: `OperationHistoryEntity` differs from the FR-009 audit schema.

**Open Questions**
- Q1: Separate runtime least-privilege artifact, or blueprint section?
- Q2: Final Gradle module(s) for AI + policy (ADR-001 decision).
- Q3: Whether INV-005 should be flagged as an ASR (YAML says no).
- Q4: Retention period / deletion entry point for OI-06.
- Q5: `ActionHandler` and `InAppInstallProvider` bypasses wording.
- Q6: `evaluation-table.md` as architecture QA table (this plan).

---

## 9. Approval Checkpoint

Gate-A granted 2026-11-17. Batch execution proceeds one batch at a
time with a review stop after each. No source under `app/` is modified
by this plan.
