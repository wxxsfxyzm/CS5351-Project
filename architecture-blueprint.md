# Architecture Blueprint

**Status:** draft — pending human review.
**Owner:** Member C (Software Architect).
**Baseline:** InstallerX Revived `816ea541`; repository state `0f718739`.
**Requirements:** `docs/req/requirements.yaml` rev.6
(`status: draft_for_group_review`).
**Related:** `adr/001-architecture-choice.md`,
`adr/002-later-important-arch-decisions.md`, `evaluation-table.md`,
`invariants.md`, `runtime-policy-catalog.md`, `memory-design.md`,
`trace-matrix.md`, `permission-model.md` §9.

Course marker D2.1 — master constraint document.

This document is the primary architectural constraint for the project.
It names the architectural elements, their authority, and their
boundaries. It does not name packages or Gradle modules: that decision
belongs to `adr/001-architecture-choice.md`, which is still a proposal
(`docs/code-governance/structural-constraints.md` §1).

Nothing in this document is implemented. Every test named is planned.

Course D2.1 places this file in `docs/architecture/`; the repository
course tree places it at the root. It is at the root, matching the
repository tree. This discrepancy is recorded, not resolved here.

---

## 1. Product scope envelope (REQ-IX-SCOPE-001)

The AI component gives **advisory** install-risk recommendations for a
narrow slice of InstallerX Revived. Deterministic code makes every
install decision; AI output never authorizes an install.

### 1.1 In scope (v1)

| Dimension | Value |
|---|---|
| Input | Single local APK |
| Entry point | `InstallerActivity` |
| Install mode | `InstallMode.AutoDialog` (**Must**), `InstallMode.AutoNotification` (**Should**) |
| Backend | `Authorizer.None` (system `PackageInstaller`) as the demonstration backend |
| Advisory axes | `install_success_likelihood` ∈ {HIGH, MEDIUM, LOW, UNKNOWN}; `security_risk` ∈ {LOW, MEDIUM, HIGH, UNKNOWN} |

`AutoDialog` is the primary v1 flow. `AutoNotification` reuses the same
assessment and decision path and is delivered only to the extent its
Should scope is accepted.

### 1.2 Out of scope (v1)

Batch or split installation, module installation, uninstall, unarchive,
network URL installation, full Root or Shizuku coverage, malware
detection, multi-agent designs, and cloud management consoles. Other
app-initiated sessions, including the `ActionHandler` auto-approval
path, are also out of scope.

Out-of-scope paths make **zero model calls** and behave as the baseline.

### 1.3 Documented bypasses

Two paths can submit an install without passing the in-scope AI path:

1. **`ActionHandler` session bypass.** `ResolveConfirmInstall` and
   `ApproveSession` can auto-approve a system session
   (`ActionHandler.kt:666-770`).
2. **`InAppInstallProviderImpl` self-update path.** It calls
   `appInstaller.doInstallWork(...)` directly
   (`data/updater/provider/InAppInstallProviderImpl.kt:31`), with no
   session and no AI path.

These are known bypasses. v1 does not fix them. The project does not
claim these paths are protected by AI approval.

---

## 2. Non-negotiable rules

These rules override convenience, schedule, and AI output. Each is
derived from `AGENTS.md` and the requirements registry.

1. **AI is advisory only.** The AI component holds no installer,
   uninstallation, Root, Shizuku, Dhizuku, or `app_process` capability
   (REQ-IX-INV-001, REQ-IX-SEC-001).
2. **Deterministic policy is authoritative.** Policy runs without a
   model SDK or network (REQ-IX-INV-006). Only policy produces
   `ALLOW_AUTO`, `REQUIRE_REVIEW`, or `BLOCK` (REQ-IX-FR-005).
3. **Hard prohibition always wins.** Neither AI advice nor human
   confirmation overrides an active hard block (REQ-IX-INV-002,
   REQ-IX-SEC-002).
4. **AI cannot lower a restriction.** AI may only maintain or raise it
   (REQ-IX-INV-007).
5. **One choke point.** Every in-scope submission passes
   `ApprovalCoordinator → InstallationGateway`; UI, AI, and policy never
   call the backend directly (REQ-IX-ARCH-001, REQ-IX-FR-007).
6. **One submission per operation.** Repeated events, duplicate
   notifications, concurrent callbacks, and recovery produce at most one
   submission (REQ-IX-INV-005, REQ-IX-REL-004).
7. **Fail safe.** A model failure never auto-approves
   (REQ-IX-INV-004, REQ-IX-REL-001).
8. **Network stays off when the setting is off** (`AGENTS.md`; the
   in-app network switch). Outbound data obeys consent and the allowlist
   (REQ-IX-SEC-004, REQ-IX-FR-011).
9. **Preserve the baseline.** Existing signature, blacklist, permission,
   and policy checks keep their meaning and pass rate
   (REQ-IX-COMP-001).
10. **Extend, never replace.** New work adds to existing modules
    (`docs/code-governance/structural-constraints.md` §2).

### 2.1 Authority triad

| Authority | Elements | May | May not |
|---|---|---|---|
| Advisory | `AIRiskAdvisor`, `EvidenceSanitizer`, `AIOutputValidator` | produce a structured recommendation | hold or call any backend; decide an install |
| Authoritative | `DeterministicPolicyEngine` | produce the decision and reason | depend on a model SDK or network |
| Human | `HumanReviewHandler`, `ConsentGate` | confirm, refuse, withdraw | override a hard block |
| Execution | `ApprovalCoordinator` → `InstallationGateway` | submit once, after an allowed decision | be bypassed by UI, AI, or policy |

---

## 3. Architectural elements

The 13 runtime elements below are the `enforcing_component` names in
`requirements.yaml`. No name is invented. Layer names refer to the
baseline layering (`core`, `data`, `di`, `domain`, `framework`, `ui`).

| # | Element | Responsibility | Inputs | Outputs | Allowed dependencies | Layer |
|---|---|---|---|---|---|---|
| 1 | `ApprovalCoordinator` | sequence evidence → advice → policy → human → submit; own failure routing | install request, policy decisions, human actions | operation/attempt IDs, decision, submission command | `ApprovalStateStore`, `InstallationGateway`, advisory elements, policy, audit | `domain` (element), wired in `di` |
| 2 | `InstallEvidenceCollector` | collect local evidence with completeness state | `PackageAnalysisResult`, installed info, device facts | evidence summary + completeness | analysis models, device providers | `domain` |
| 3 | `AIRiskAdvisor` | call the model adapter, return structured advice | sanitized evidence | `install_success_likelihood`, `security_risk`, reasons, evidence refs | model adapter, shared models only | `data` behind `domain` contract |
| 4 | `AIOutputValidator` | structural + semantic-reference validation | model response | valid advice or `INVALID` | shared models | `domain` |
| 5 | `DeterministicPolicyEngine` | decide `ALLOW_AUTO` / `REQUIRE_REVIEW` / `BLOCK` | evidence completeness, compatibility, advice, policy version, human state | decision, disposition, DR rule ID | shared models only | `domain` |
| 6 | `HumanReviewHandler` | present review, capture Continue/Cancel | decision, evidence, advice | approval request, cancel | UI contract, `ApprovalStateStore` | `domain` + `ui` |
| 7 | `InstallationGateway` | single authorized backend entry point | revalidated approval | backend call | `AppInstallerRepository` | `domain` (element), `data` (impl) |
| 8 | `InstallerSession UI` | progress, cancellation, result display | stage model | user actions | `InstallerSessionRepository` | `ui` |
| 9 | `DecisionAuditRecorder` | append-only, hash-chained audit | decision record fields | audit entry | persistence | `data` behind `domain` contract |
| 10 | `AuditQuery UI` | view and export audit records | audit store | UI, export file | audit repository | `ui` |
| 11 | `ConsentGate` | first-use outbound consent, withdrawal | field list, purpose, retention | consent state | settings, UI contract | `ui` + `domain` |
| 12 | `ApprovalStateStore` | own approval binding and persisted state | binding fields, decisions | binding record, state | persistence | `data` behind `domain` contract |
| 13 | `NetworkGate` | enforce consent + network switch on egress | consent state, network setting | allow/deny egress | settings | `domain` |

Process components, not runtime elements: `Baseline regression suite`,
`Test suite`, `Evaluation harness`, `requirements registry`,
`Process records`, `Scope inventory`.

Element → driver and invariant mapping is in
`evaluation-table.md` §6 and `invariants.md` §2.

---

## 4. Code interception points and the choke point

Verified at baseline `0f718739`. Paths are relative to
`app/src/main/java/com/rosan/installer/` unless absolute.

### 4.1 Current install call graph

    UI  InstallerViewModel.install()                  ui/page/main/installer/InstallerViewModel.kt:817-820
        InstallerViewModel AutoDialog auto-install    ui/page/main/installer/InstallerViewModel.kt:598-604
        ProgressHandler.onAnalysedSuccess()           data/session/handler/ProgressHandler.kt:51-69
    ->  InstallerSessionRepository.install(triggerAuth)
                                                      domain/session/repository/InstallerSessionRepository.kt
    ->  ActionHandler.handleSingleInstall             data/session/handler/ActionHandler.kt:473-479
        ActionHandler.performInstallLogic             data/session/handler/ActionHandler.kt:573-600
    ->  ProcessInstallationUseCase.invoke             domain/engine/usecase/ProcessInstallationUseCase.kt:86-152
        checkBlockedByProfile()                       domain/engine/usecase/ProcessInstallationUseCase.kt:200-249
        installApp()                                  domain/engine/usecase/ProcessInstallationUseCase.kt:280-324
        installWithResolvedAuthorizer()               domain/engine/usecase/ProcessInstallationUseCase.kt:327-413
    ->  AppInstallerRepository.doInstallWork          domain/engine/repository/AppInstallerRepository.kt:34-44
    ->  AppInstallerRepositoryImpl.doInstallWork      data/engine/repository/AppInstallerRepositoryImpl.kt:60-101
        executeWithRepo()                             data/engine/repository/AppInstallerRepositoryImpl.kt:176-215
        resolveRepo(config)                           data/engine/repository/AppInstallerRepositoryImpl.kt:233-249
    ->  data/engine/executor/appinstaller/*AppInstallerRepoImpl.kt
        (None / System / Shizuku / Dhizuku / Process)

### 4.2 Interception seams

| Seam | Purpose | Location | Requirement |
|---|---|---|---|
| S1 AI assessment (AutoDialog) | run assessment before submission | `InstallerViewModel.install()` `InstallerViewModel.kt:817-820`; auto-trigger `:598-604` | FR-001, FR-002 |
| S2 AI assessment (AutoNotification) | same path, background mode | `ProgressHandler.onAnalysedSuccess()` `ProgressHandler.kt:51-69` | FR-001, FR-007 |
| S3 InstallationGateway | single authorized backend call | wrap `AppInstallerRepository.doInstallWork` / `executeWithRepo` (`AppInstallerRepositoryImpl.kt:60-101, 176-215`) | FR-007, ARCH-001 |
| S4 hard prohibition | existing deterministic blocks | `checkBlockedByProfile()` `ProcessInstallationUseCase.kt:200-249`; `PlatformInstallPolicyChecker.kt:30-175`; blacklist in `IBinderAppInstallerRepoImpl.kt:244-267` | INV-002, SEC-002 |
| S5 persistence | approval binding + audit | `InstallerRoom.kt`, `OperationHistoryDao.kt`, `OperationHistoryEntity.kt` | INV-003, REL-002, DATA-001 |
| S6 DI | register new elements | `di/EngineModule.kt:94,102`, `InstallerSessionModule.kt`, `NetworkModule.kt`, `PrivilegedModule.kt` | ARCH-001 |
| S7 network boundary | egress gating | `BooleanSetting.AllowInternetAccess` (`AppSettingsRepository.kt:37`); `OkHttpNetworkResolver.kt` | SEC-004 |
| S8 dual UI | review/consent in both families | `ui/page/main/installer/**`, `ui/page/miuix/installer/**`, selection in `InstallerActivityContent.kt:46` | ARCH-002 |

### 4.3 Why the gateway sits at `doInstallWork`

That is where authorizer resolution, blacklist parameters, and
`PlatformInstallPolicyChecker.check()` already converge
(`AppInstallerRepositoryImpl.kt:84-97`). Putting the gateway there
preserves the existing check order and gives one place that cannot be
reached by UI, AI, or policy.

The advisory elements must never import
`AppInstallerRepository`, `AppInstallerRepositoryImpl`, the executor
implementations, `domain/privileged/**`, `framework/privileged/**`,
`domain/session/**`, or `ActionHandler`. See `permission-model.md` §9.2.

---

## 5. Decision-flow summary

The full table, in first-match order, is in
`runtime-policy-catalog.md` §1. Summary:

1. **Active hard prohibition → `BLOCK` / `BLOCKED` (DR-01).** Evaluated
   from the existing signature, platform, user-restriction, and blacklist
   checks (§4.2 S4). Dominates every other rule.
2. **Approval invalidated after revalidation → `REQUIRE_REVIEW` /
   `INVALIDATED` (DR-10).**
3. **Evidence unreadable, key unknown, or truncated → `REQUIRE_REVIEW` /
   `RECOLLECT` (DR-03).**
4. **Consent declined or withdrawn → `REQUIRE_REVIEW` / `AWAIT_HUMAN`
   (DR-02).**
5. **Contradictory evidence → `REQUIRE_REVIEW` / `AWAIT_HUMAN`
   (DR-04).**
6. **Complete, compatible, HIGH success, LOW risk, auto rules met →
   `ALLOW_AUTO` / `EXECUTE_ONCE` (DR-05).**
7. **Complete, compatible, HIGH/LOW, auto rules not met →
   `REQUIRE_REVIEW` / `AWAIT_HUMAN` (DR-06).**
8. **Complete, any valid advice, not hard block → `REQUIRE_REVIEW` /
   `AWAIT_HUMAN` (DR-07).**
9. **Advice unusable → `REQUIRE_REVIEW` / `AWAIT_HUMAN` (DR-08).**
10. **Valid human approval and revalidation passed → `REQUIRE_REVIEW` /
    `EXECUTE_ONCE` (DR-09).**

Continue creates the binding and makes zero additional model calls.
Retry is a separate assessment and grants no submission right.

---

## 6. Data and privacy boundary

`outbound_metadata_allowlist` in `requirements.yaml` is the single
source of truth for collection review, consent display, request
construction, and outbound validation.

> **Provisional.** The allowlist contents and the token caps are part of
> the rev.6 draft. PMA-OUT-002 … PMA-OUT-006 and PMA-COST-001 …
> PMA-COST-004 are pending Member A review, and PMA-OUT-001 (target APK
> package name stays local) is approved. This section records the
> boundary; it does not upgrade a proposed field into a guarantee.

### 6.1 Transmitted (allowlisted) fields

Derived, non-identifying signals only: `version_code`, `version_name`
(sanitized, capped), `min_sdk`, `target_sdk`, `declared_permissions`
(names only, capped), `signature_verification_status`,
`installed_signature_match`, `install_relation`, `apk_identity`,
`install_mode`, `target_user_scope`, `package_source`,
`source_confidence`, `has_shared_uid`, `is_xposed_module`,
`platform_compatibility`, `abi_compatibility`, `storage_sufficient`,
`installer_capability`.

### 6.2 Local-only evidence (never transmitted)

Target and source package names, source UID, APK SHA-256, signer
SHA-256 set, certificate details, installed version and signer hashes,
label, architecture, size, raw `sharedUserId`, install location, file
paths and source directory, full config snapshot, installer/requester/
authorizer identifiers, device API level, supported ABI set,
native-library ABIs, and raw authorizer capability.

**APK bytes are never transmitted** — `egress: never` in the registry.

### 6.3 Approval binding stays local

Removing a field from egress does not remove it from the local approval
binding or the audit (`local_approval_binding.retains`): operation ID,
attempt ID, APK SHA-256, package name, target user ID, install mode,
installation backend, config snapshot, policy version.

### 6.4 Negative fit criteria

NFC-OUT-001 … NFC-OUT-006 must hold. In particular, with consent
granted and free-text fields sanitized, the complete outbound request —
including the assembled prompt — contains zero occurrences of any
local-only identifier (NFC-OUT-001), and contains exactly the allowlisted
leaf paths (NFC-OUT-002).

---

## 7. Runtime AI-adapter least-privilege isolation

This is the runtime counterpart of the agent access rules in
`permission-model.md` §1–§8. Full detail and the forbidden-dependency
list are in `permission-model.md` §9; this section states the model.

### 7.1 Isolation properties

1. **Read-only, allowlisted input.** `AIRiskAdvisor` may read only
   allowlisted advisory fields, after `EvidenceSanitizer` and only when
   `NetworkGate` and `ConsentGate` permit. It cannot read raw APK bytes.
2. **Structured advisory output only.** `AIRiskAdvisor` returns
   `install_success_likelihood`, `security_risk`, reasons, evidence
   references, model ID, and prompt version. Any action, approval, or
   installation instruction is `INVALID` (REQ-IX-FR-004).
3. **No backend capability.** No advisory element references the
   installer backend, a privileged provider, a session repository, or
   `ActionHandler`.
4. **No policy capability.** Advisory elements cannot change a policy
   input or a decision.
5. **Least privilege by construction.** Under ADR-001 Option A, module
   dependency direction fails the build on a forbidden import. Under
   Option B, a lint or test rule replaces the module boundary.

### 7.2 Enforcement

- `planned: AdvisorIsolationTest` — static import-graph check plus a
  model stub returning install instructions producing 0 backend calls.
- `planned: AdapterPermissionTest` — REQ-IX-SEC-001 static and negative
  checks.
- `planned: PolicyWithoutModelTest` — REQ-IX-INV-006.
- `planned: HardBlockPrecedenceTest`, `planned: NoDowngradeTest` —
  REQ-IX-INV-002 and REQ-IX-INV-007.

### 7.3 Residual risk

Sanitization reduces but does not eliminate prompt-injection influence
(REQ-IX-SEC-005). Authorization remains deterministic. No sanitized text
can create an approval or a backend call.

---

## 8. Persistence and recovery architecture

Full detail is in `memory-design.md`. Summary:

- **`ApprovalStateStore`** persists the request, the binding, and the
  decision at the transitions named by REQ-IX-REL-002 (request received,
  evidence complete, advice complete, decision, approval, delayed
  notification confirmation, pre-submission). If a reliable write is
  impossible, automatic submission stops.
- **Persistence medium.** The baseline uses a Room database
  (`data/settings/local/room/InstallerRoom.kt`, schema version 18, with
  `exportSchema = true` and auto-migrations) and a separate DataStore for
  self-update recovery (`SelfUpdateRecoveryRepositoryImpl.kt`, whose
  `arm()` returns only after a durable write). The approval store maps
  onto the Room pattern; the exact table and DAO are deferred to
  ADR-002.3.
- **`DecisionAuditRecorder`** writes append-only, hash-chained records
  containing the REQ-IX-FR-009 fields. No credentials. Retention and
  deletion entry point are pending OI-06; no automatic deletion occurs
  until they are frozen.
- **Crash recovery (REQ-IX-REL-003).** After restart, unsubmitted
  requests are revalidated and re-approved; a submitted request with an
  unknown result is reconciled with platform state, or marked
  `RESULT_UNKNOWN` with automatic replay forbidden. A notification
  awaiting the user stays `AWAIT_HUMAN` and is not auto-approved during
  recovery.
- **Reconciliation precedent.** The baseline already tracks live platform
  session IDs (`InstallerSessionRepositoryImpl.kt:62,177-180`,
  `setPlatformSessionActive`) and arms a durable self-update recovery
  record before handing control to `PackageManager`
  (`SelfUpdateRecoveryManager.arm`). The new recovery protocol extends
  these mechanisms rather than replacing them.

---

## 9. Module organization

- Each architectural element maps to exactly one module or package
  (`docs/code-governance/structural-constraints.md` §1).
- Element names in this document match the names in the ADRs and the
  requirements registry.
- **Package and Gradle-module assignment is deferred to ADR-001.** Until
  ADR-001 is accepted, no module is created and this document names no
  final package path.
- New injectable elements are registered in an existing Koin module under
  `di/`.
- Every new public class or function carries a KDoc tag naming the ADR
  and, where relevant, the invariant it implements.

---

## 10. Traceability index

| ASR / invariant | Blueprint section | Primary artifact |
|---|---|---|
| REQ-IX-ARCH-001 | §2.1, §4 | `evaluation-table.md` D1 |
| REQ-IX-INV-003 | §6.3, §8 | `memory-design.md`, `invariants.md` |
| REQ-IX-INV-004 | §5, §7 | `runtime-policy-catalog.md` §3 |
| REQ-IX-INV-006 | §2.1, §7.1 | `adr/001-architecture-choice.md` |
| REQ-IX-INV-007 | §2, §5 | `evaluation-table.md` D1 |
| REQ-IX-SEC-005 | §6, §7.3 | `permission-model.md` §9 |
| REQ-IX-REL-002 | §8 | `memory-design.md` |
| REQ-IX-REL-003 | §8 | `memory-design.md` |
| REQ-IX-MOD-001 | §3 #3, §9 | ADR-002.2 |
| All 41 requirements | — | `trace-matrix.md` |

---

## 11. Open questions

1. Final Gradle module and package names (ADR-001 team decision).
2. Whether the runtime AI-adapter isolation needs a separate artifact, or
   §7 plus `permission-model.md` §9 is enough (plan Q1).
3. Approval/audit table shape on top of the Room database (ADR-002.3).
4. Retention period and deletion entry point for OI-06.
5. Whether `ActionHandler` and `InAppInstallProviderImpl` bypasses get
   any wording change in the product docs; v1 leaves them unfixed.
6. INV-005 is not flagged `asr: true` in the registry (plan Q3).
