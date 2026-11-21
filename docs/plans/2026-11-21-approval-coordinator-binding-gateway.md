# Execution Plan — ApprovalCoordinator + ApprovalBinding + InstallationGateway (first slice)

**Role:** plan
**Date:** 2026-11-21
**Baseline:** latest `main` at commit `4327c9d4` (PRs #16, #18, #19, #20 merged).
**Requirements:** `docs/req/requirements.yaml` rev.6 (draft_for_group_review).
**Related ADRs:** ADR-001 (accepted, Option B), ADR-002.3 (persistence, not started), ADR-002.6 (authority separation, specified), ADR-002.7 (hash/integrity, specified).
**Status:** PLAN ONLY — awaiting explicit human approval of plan and tests. No implementation, commit, push, or PR until approved.

> **Setup note.** An earlier save attempt of this plan failed because that session was running the `build` agent, which has no `docs/plans/` edit permission. The project configuration was loaded correctly. This was saved after switching to the `project-plan` agent.

---

## 0. What already exists on `main` (do not re-implement)

| Area | Merged code | PR |
|---|---|---|
| AI advisory + validator + provider | `domain/ai/**`, `data/ai/**`, `di/AiModule.kt` | #13/#16 |
| Deterministic policy engine (DR-01…DR-10) | `domain/engine/policy/DeterministicPolicyEngine.kt`, `PolicyDecisionModels.kt` | #16 |
| AutoDialog evidence mapper | `domain/ai/usecase/AutoDialogInstallEvidenceMapper.kt` | #18 |
| Consent-scoped egress gate | `domain/ai/repository/ModelRequestGate.kt` | #19 |
| One-call advisory entry | `domain/ai/usecase/RequestInstallRiskAssessmentUseCase.kt` | #13/#16 |

`grep` confirms **no** `ApprovalCoordinator`, `InstallationGateway`, `ApprovalBinding`, or `ApprovalStateStore` exists in `app/src/main`. The blueprint §3.2 lists exactly these as the missing downstream work. This plan is that work's first slice.

**Authoritative types we must consume, not redefine**, from `domain/engine/policy/PolicyDecisionModels.kt`:
`EvaluatedPolicy`, `HumanApprovalState`, `PolicyAdvice`, `PolicyInput`, `PolicyDecision` (enum), `PolicyDisposition` (enum), `PolicyResult`.

**The hard constraint this plan centers on:** `DeterministicPolicyEngine.evaluate()` returns `EXECUTE_ONCE` but *performs no submission* (`PolicyResult` KDoc, FR-005). DR-09 produces `REQUIRE_REVIEW/EXECUTE_ONCE` only when the caller passes `HumanApprovalState.VALID_APPROVAL_AND_REVALIDATION_PASSED`. Therefore the coordinator must **create the binding, revalidate it, prove revalidation, then submit exactly once** — in that order. A caller that fabricates `VALID_APPROVAL_AND_REVALIDATION_PASSED` without a real revalidation would violate INV-003; the coordinator must be the only place that maps a passed revalidation to that enum value.

---

## 1. Traceability

### 1.1 Requirements / invariants covered by this slice

| ID | Priority | How this slice advances it | Fully satisfied by this slice? |
|---|---|---|---|
| REQ-IX-FR-001 (operation/attempt IDs, 0 backend before approval) | Must | `InstallOperationId`, `AttemptId`; coordinator starts approva before gateway | **Partial** — in-memory IDs; persistence deferred (see §9 gaps) |
| REQ-IX-FR-005 (first Continue creates binding, immediate revalidation, DR-09 submit; DR-10 invalidate) | Must | coordinator `onContinue()` state machine | **Yes for the coordinator branch** (engine already tested) |
| REQ-IX-FR-006 (first Continue binds + revalidates; duplicate Continue ≤1 submission; Cancel no submission) | Must | coordinator Continue/Cancel + idempotency | **Partial** — no UI; domain behavior only |
| REQ-IX-FR-007 (single authorized submission, recheck all binding fields) | Must | `InstallationGateway` + coordinator revalidation | **Partial** — Fake gateway proves logic; real backend wiring deferred |
| REQ-IX-INV-001 (AI has no privileged capability) | Must | static isolation check extended to coordinator/gateway boundary | **Yes (structural)** for the new packages |
| REQ-IX-INV-003 (approval binds the object; change ⇒ invalid) | Must (ASR) | `ApprovalBinding` value type + revalidation | **Partial** — type + revalidation in memory; persistence is REL-002 |
| REQ-IX-INV-004 (model failure never auto-approves) | Must (ASR) | coordinator maps `PolicyAdvice.Failed/Unusable` → no submit | **Partial** — routing proven; audit deferred |
| REQ-IX-INV-005 (≤1 submission per operation; retry adds attempt) | Must | `SubmissionLedger` single-consume | **Partial** — in-memory; REL-004 persistence deferred |
| REQ-IX-INV-006 (policy model-independent) | Must (ASR) | coordinator depends on engine only; no SDK/network import | **Yes (unchanged)** — regression re-run |
| REQ-IX-INV-007 (AI never lowers restriction) | Must (ASR) | coordinator never overrides engine decision | **Yes** — engine enforces; coordinator must not bypass |
| REQ-IX-SEC-003 (tamper resistance, invalidation) | Must | binding equality revalidation; tamper test | **Partial** — in-memory; durable tamper is DATA-001 |
| REQ-IX-REL-002 (persist at transitions; write failure stops submission) | Must (ASR) | `ApprovalStateStore` interface + fail-closed contract | **Partial** — interface + in-memory fake; Room deferred (ADR-002.3) |
| REQ-IX-REL-003 (recovery, RESULT_UNKNOWN, no replay) | Must (ASR) | coordinator recovery entry points + RESULT_UNKNOWN result | **Partial** — logic modelled; real crash/restart deferred |
| REQ-IX-REL-004 (no duplicate submission; retry ≠ new right) | Must | `SubmissionLedger` + attempt accounting | **Partial** — in-memory |
| REQ-IX-ARCH-001 (single choke point) | Must (ASR) | gateway is the only submission path; spy test | **Partial** — fake path proven; production wiring deferred |

### 1.2 Explicitly out of scope for this slice (gap ledger, §10)

AutoNotification flow, dual-UI review/consent screens (FR-008, ARCH-002), `DecisionAuditRecorder` (FR-009, DATA-001), `AuditQuery UI` (FR-010), durable Room persistence (ADR-002.3), production gateway wiring to `session.install(true)`, consent UI (FR-011). These are **not** claimed as done.

---

## 2. Interface contract

### 2.1 New packages (ADR-001 Option B, one element per package)

Placement follows the merged convention: `domain/engine/<area>/` for pure logic. This slice adds **only** domain contracts under `domain/engine/` plus **test-only** doubles under `app/src/test/`. No `di/` file is added (Q4). No Gradle module is added.

```
domain/engine/approval/            # ApprovalBinding + ApprovalStateStore contract + coordinator
  ApprovalBinding.kt               # value type (REQ-IX-INV-003)
  ApprovalState.kt                 # operation state enum + transitions (memory-design §1)
  ApprovalStateStore.kt            # persistence contract (REL-002), fail-closed
  BindingFactsProvider.kt          # re-read source of current facts for revalidation (§2.6)
  ApprovalCoordinator.kt           # sequencer (FR-005/006/007)
  SubmissionLedger.kt              # single-consume permit (INV-005/REL-004)

domain/engine/install/
  InstallationGateway.kt           # the single authorized submission seam (FR-007, ARCH-001)

app/src/test/java/com/rosan/installer/domain/engine/approval/   # test-only, NOT main
  InMemoryApprovalStateStore.kt    # test double; fail-closed on write error
  FakeInstallationGateway.kt       # test double; records submissions
  FakeBindingFactsProvider.kt      # test double; scriptable sequential facts
```

`InMemoryApprovalStateStore`, `FakeInstallationGateway`, and `FakeBindingFactsProvider` are **test doubles under `app/src/test/` only**; none is added to the `main` source set. The production implementations (Room store, real gateway, real facts provider) are later, separately-approved work (§9).

### 2.2 ApprovalBinding — the bound installation object (REQ-IX-INV-003)

Fields are **exactly** `outbound_metadata_allowlist.local_approval_binding.retains` in `requirements.yaml` (lines 372–384) and `memory-design.md` §2:

```kotlin
/**
 * Implements: ADR-002.7
 * Enforces: REQ-IX-INV-003, REQ-IX-SEC-003
 */
data class ApprovalBinding(
    val operationId: String,        // operation_id
    val attemptId: String,          // attempt_id
    val apkSha256: String,          // apk_sha256 (hex, 64 chars; non-null)
    val targetPackageName: String,  // target_package_name (local only, PMA-OUT-001)
    val targetUserId: Int,          // target_user_id
    val installMode: InstallModeSignal,        // AutoDialog | AutoNotification
    val installationBackend: Authorizer,       // backend enum (reused), non-nullable; v1 = None
    val configSnapshotHash: String, // config_snapshot (normalized hash, caller-supplied)
    val policyVersion: String,      // policy_version
)
```

Design decisions (confirmed):
- **Q1 — backend (confirmed):** `installationBackend` reuses the existing `com.rosan.installer.domain.settings.model.config.Authorizer` (v1 = `None`) rather than a new enum, to avoid a parallel backend vocabulary. The field is **non-nullable**: a missing backend prevents dispatch, never defaults.
- **Q2 — config snapshot (confirmed):** `configSnapshotHash` is a caller-supplied, normalized canonical hash string in this slice. No baseline `ConfigModel` serializer is introduced here; the real canonicalization is a §9 follow-up.
- **Q3 — binding construction (confirmed):** `ApprovalBinding` is non-null-only and built by a builder that returns `null` on any missing field, so "a missing binding field prevents dispatch" is structural rather than a silent default.
- Equality is structural (`data class`); revalidation compares a freshly constructed binding against the stored one field by field and reports the first mismatch.

### 2.3 InstallationGateway — single authorized seam (REQ-IX-FR-007, ARCH-001)

```kotlin
/**
 * Implements: ADR-002.6
 * Enforces: REQ-IX-FR-007, REQ-IX-ARCH-001
 */
interface InstallationGateway {
    /**
     * Submits [binding]'s installation object exactly once. The gateway trusts
     * only that the coordinator already performed pre-submission revalidation;
     * it performs no AI/policy work and holds no model or network capability.
     */
    suspend fun submit(binding: ApprovalBinding): GatewayResult
}

sealed interface GatewayResult {
    data object Submitted : GatewayResult                 // backend accepted
    data class Failed(val reason: String) : GatewayResult  // backend reported failure
    data object ResultUnknown : GatewayResult              // submitted, outcome unknown (FR-008/REL-003)
}
```

- `FakeInstallationGateway` (data, test-first-slice) appends each `submit` to a list of `ApprovalBinding` for assertions.
- **Do not** put retry, model, or policy inside the gateway. It is the *only* interface that may later wrap `session.install(true)`.
- Real-implementation seam (deferred wiring, §10): the production `InstallationGateway` will construct `ConfigModel` + `List<InstallEntity>` from the session and call `AppInstallerRepository.doInstallWork` / `InstallerSessionRepository.install(true)` **only** after the coordinator revalidated. The gateway must not be reachable by UI/AI/policy per ARCH-001.

### 2.4 ApprovalCoordinator — the sequencer (FR-005/006/007)

Inputs: a `PolicyInput` assembled by the caller (evidence completeness, compatibility, advice from `RequestInstallRiskAssessmentUseCase`, `EvaluatedPolicy`, current `HumanApprovalState`), plus a `BindingFactsProvider` that re-reads the current object's facts. The coordinator reads the provider twice on Continue (§2.5) rather than reusing one snapshot.

State model (from `memory-design.md` §1; `RESULT_UNKNOWN` is a post-submission result, **not** a disposition):

```
RECEIVED → AWAITING_AI | RECOLLECT
AWAITING_AI → AWAIT_HUMAN | BLOCKED | EXECUTE_ONCE
AWAIT_HUMAN --Continue(first)--> [create binding] → revalidate
                revalidate PASS → EXECUTE_ONCE → submit once → SUBMITTED → COMPLETED|RESULT_UNKNOWN
                revalidate FAIL → INVALIDATED (no submission)
AWAIT_HUMAN --Cancel--> CANCELLED (no submission)
any pre-submission --Cancel--> CANCELLED
```

Public operations (each guarded so a repeated event is a no-op, INV-005):

| Operation | Behavior | Requirements |
|---|---|---|
| `decide(input, bindingFacts)` | run engine once; route to `AWAIT_HUMAN`/`BLOCKED`/`EXECUTE_ONCE`; persist decision (fake store) | FR-005, INV-004 |
| `onContinue()` | if state≠`AWAIT_HUMAN` or Continue disabled ⇒ no-op/reject; read facts once via `BindingFactsProvider`, build binding from them, store; **then re-read current facts via `BindingFactsProvider` again** and compare field by field against the stored binding; if PASS ⇒ engine with `HumanApprovalState.VALID_APPROVAL_AND_REVALIDATION_PASSED` ⇒ DR-09 `EXECUTE_ONCE` ⇒ `ledger.tryAcquire(operationId)` ⇒ gateway.submit(binding) once; if FAIL ⇒ engine with `..._FAILED_AFTER_APPROVAL` ⇒ DR-10 `INVALIDATED`, 0 submissions | FR-005/006, INV-003 |
| `onCancel()` | `CANCELLED`; no submission; no approval reuse | FR-006 |
| `onModelFailure(code)` | route `PolicyAdvice.Failed/Unusable` ⇒ no submit (DR-08) | INV-004 |
| `revalidatedSubmit(binding, provider)` (internal) | the only producer of `VALID_APPROVAL_AND_REVALIDATION_PASSED`; re-reads facts from `provider` and compares to `binding` | INV-003, SEC-003 |
| `onRecovered(state)` | R1–R5 recovery: `AWAIT_HUMAN` stays awaited; `EXECUTE_ONCE` unsubmitted ⇒ require user action (no auto-replay); submitted+unknown ⇒ `RESULT_UNKNOWN`; never auto-approve | REL-003 |

The coordinator holds **no** model SDK, network, or backend except the `InstallationGateway` and `BindingFactsProvider` references (permission-model §9.1 allows exactly this). Submission happens **only** via `gateway.submit`, **only** after the binding re-read passes and `ledger.tryAcquire` succeeds. Critically, the coordinator does **not** reuse the first fact snapshot: revalidation must read current facts afresh (§2.5).

### 2.5 BindingFactsProvider — fresh facts for revalidation (INV-003, TOCTOU)

The apk sha256, config snapshot, target user, package, install mode, backend, and policy version must be re-read from current state **after** the binding is created, immediately before `ledger.tryAcquire` and `gateway.submit`. Reusing the snapshot that built the binding would let an APK replacement or config change between evidence read and submission go undetected (INV-003, ADR-002.7).

```kotlin
/**
 * Implements: ADR-002.7
 * Enforces: REQ-IX-INV-003, REQ-IX-SEC-003
 *
 * Re-reads the current installation facts so the coordinator can rebuild an
 * ApprovalBinding and compare it field by field to the stored bound object.
 * A provider that returns different facts than the stored binding drives
 * DR-10 (INVALIDATED) and zero submissions. It holds no AI/policy/backend
 * capability; it only observes current local facts.
 */
interface BindingFactsProvider {
    /** Current facts for [operationId]; called at least twice on Continue. */
    suspend fun currentFacts(operationId: String): BindingFacts?
}

/**
 * The raw inputs a [ApprovalBinding] is built from. A null field means "not
 * currently known"; the builder maps any null to a null binding (Q3), so a
 * missing fact blocks dispatch instead of defaulting.
 */
data class BindingFacts(
    val apkSha256: String?,
    val targetPackageName: String?,
    val targetUserId: Int?,
    val installMode: InstallModeSignal?,
    val installationBackend: Authorizer?,
    val configSnapshotHash: String?,
    val policyVersion: String?,
)
```

Revalidation contract: the coordinator calls `currentFacts(operationId)` once to build the binding, then calls it **again** before acquiring the submission permit; it reconstructs a binding from the second read and compares every field (including `apkSha256`, `configSnapshotHash`, `targetUserId`) to the stored binding. Any difference ⇒ DR-10 `INVALIDATED`, 0 submissions. If the second read yields a null (fact no longer known), that is also a mismatch ⇒ `INVALIDATED` (missing field prevents dispatch).

### 2.6 ApprovalStateStore (REL-002 contract, fail-closed)


```kotlin
/**
 * Implements: ADR-002.3 (contract only; Room impl deferred)
 * Enforces: REQ-IX-REL-002
 */
interface ApprovalStateStore {
    /** Returns true iff the write is durable. A false MUST stop automatic submission. */
    suspend fun persist(snapshot: ApprovalSnapshot): Boolean
    suspend fun load(operationId: String): ApprovalSnapshot?
}
```

`ApprovalSnapshot` = `operationId`, `attemptId`, `ApprovalBinding?`, `ApprovalState`, `PolicyResult?`. This slice uses a **test-only** `InMemoryApprovalStateStore` (under `app/src/test/`); a write failure is simulated in tests to prove **no automatic submission** (REL-002 fit criterion). Room table + DAO remain ADR-002.3 (not started).

### 2.7 SubmissionLedger (INV-005, REL-004)

Single-consume per `operationId`; thread-safe (`Mutex` or synchronized). `tryAcquire(id): Boolean` returns true at most once per id for the lifetime of the configured dedup window. Retry replays call `onContinue` again but `tryAcquire` fails ⇒ no second submission.

---

## 3. Scope boundaries

### 3.1 Files to create (first slice)

| File | Element | Source set | Notes |
|---|---|---|---|
| `domain/engine/approval/ApprovalBinding.kt` | ApprovalBinding | main | INV-003 value type |
| `domain/engine/approval/ApprovalState.kt` | state enum | main | mirrors memory-design §1 |
| `domain/engine/approval/ApprovalStateStore.kt` | ApprovalStateStore (contract) | main | REL-002 |
| `domain/engine/approval/BindingFactsProvider.kt` | BindingFactsProvider (contract) + `BindingFacts` | main | INV-003/TOCTOU |
| `domain/engine/approval/SubmissionLedger.kt` | INV-005/REL-004 | main | |
| `domain/engine/approval/ApprovalCoordinator.kt` | ApprovalCoordinator | main | FR-005/006/007 |
| `domain/engine/install/InstallationGateway.kt` | InstallationGateway (contract) | main | FR-007/ARCH-001 |
| `.../domain/engine/approval/InMemoryApprovalStateStore.kt` | test double | **test only** | fail-closed; not in main |
| `.../domain/engine/approval/FakeInstallationGateway.kt` | test double | **test only** | records submissions; not in main |
| `.../domain/engine/approval/FakeBindingFactsProvider.kt` | test double | **test only** | scriptable sequential facts; not in main |
| `.../domain/engine/approval/ApprovalBindingTest.kt` | test | test | INV-003 |
| `.../domain/engine/approval/ApprovalCoordinatorTest.kt` | test | test | FR-005/006/007 |
| `.../domain/engine/approval/IdempotencyTest.kt` | test | test | INV-005/REL-004 |
| `.../domain/engine/approval/RecoveryTest.kt` | test | test | REL-002/003 |
| `.../domain/engine/approval/StatePersistenceTest.kt` | test | test | REL-002 |
| `.../domain/engine/approval/ChokePointTest.kt` | test | test | ARCH-001 |
| `.../domain/engine/approval/ApprovalIsolationTest.kt` | static test | test | INV-001 (extend existing pattern) |

(`.../` = `app/src/test/java/com/rosan/installer`.)

No `di/ApprovalModule.kt` is added (Q4). No `data/engine/approval/` production files are added this slice.

### 3.2 Files NOT changed (explicitly out of scope)

- `DeterministicPolicyEngine.kt`, `PolicyDecisionModels.kt` — consumed as-is; no new DR rules.
- `AIRiskAdvisor`, `DefaultAiRiskAdvisor`, `AiRequestBuilder`, `ModelRequestGate`, `RequestInstallRiskAssessmentUseCase` — unchanged.
- `InstallerViewModel.kt`, `ProgressHandler.kt`, `ActionHandler.kt`, `AppInstallerRepository*` — not touched this slice (production wiring is a later, separately-approved step, §10).
- No build files, no `libs.versions.toml`, no new dependency, no new Gradle module.
- No UI, no consent screen, no audit recorder.

---

## 4. Testing strategy (offline, deterministic)

Test style: `kotlin.test` + `assertEquals`, matching `PolicyDecisionTableTest.kt`. No MockK, no network, no Android runtime. Every test names its requirement/invariant in KDoc.

| Requirement | Test | Type | Negative case (must fail if protection removed) |
|---|---|---|---|
| FR-001 | `ApprovalCoordinatorTest.receivedCreatesIdsAndNoSubmission` | behavioral | before decision, gateway submissions == 0 |
| FR-005 DR-09 | `ApprovalCoordinatorTest.firstContinueBindsRevalidatesAndSubmitsOnce` | behavioral | PASS ⇒ exactly 1 submission, 0 extra model calls |
| FR-005 DR-10 | `ApprovalBindingTest.changedFieldInvalidatesAndSubmitsZero` | **negative** | change any binding field ⇒ 0 submissions |
| FR-005 | `ApprovalCoordinatorTest.continueWithIncompleteEvidenceDoesNotSubmit` | negative | state RECOLLECT ⇒ rejected |
| FR-006 | `ApprovalCoordinatorTest.cancelYieldsZeroSubmissions` | negative | |
| FR-006 | `IdempotencyTest.repeatedContinueSubmitsAtMostOnce` | **negative** | 2nd Continue ⇒ no 2nd submit |
| FR-007 | `ChokePointTest.onlyGatewaySubmitsAfterRevalidation` | behavioral + spy | fake gateway spy count |
| INV-002 (regression) | `PolicyDecisionTableTest` (existing) | regression | hard block stays BLOCK with human approval |
| INV-003 | `ApprovalBindingTest.everyFieldIsBound` + TOCTOU (identical sha ⇒ ok; changed sha ⇒ invalid) | **negative + TOCTOU** | changing only target package (source distinct, fixture per NFC-OUT-004) invalidates |
| INV-003 | `ApprovalCoordinatorTest.callerCannotForgeValidApproval` | negative | passing VALID enum without revalidation path is not a public API |
| INV-003 | `ApprovalCoordinatorTest.providerSecondReadDiffersInvalidates` (apk_sha256 / configSnapshotHash / targetUserId, parametrized) | **negative (DR-10)** | `FakeBindingFactsProvider` returns different facts on the 2nd call ⇒ `INVALIDATED`, gateway submissions == 0 |
| INV-004 | `ApprovalCoordinatorTest.everyFailureCodeDoesNotSubmit` | **negative** | each `FailureCode` ⇒ 0 submissions; hard block preserved |
| INV-005 | `IdempotencyTest.twentyConcurrentExecutesSubmitAtMostOnce` | **concurrency negative** | 20 concurrent onContinue ⇒ ≤1 submit |
| INV-001 | `ApprovalIsolationTest` (static) | structural | coordinator/gateway packages import no forbidden types beyond gateway |
| INV-006/007 | existing `PolicyWithoutModelTest`/`NoDowngradeTest` (if present) or `PolicyDecisionTableTest` | regression | unchanged |
| SEC-003 | `ApprovalBindingTest.revalidationFailsOnTamper` | negative | tampered stored binding ⇒ INVALIDATED, 0 submit |
| REL-002 | `StatePersistenceTest.writeFailureStopsSubmission`; `...restoreReadsLastDurableState` | **negative + restart** | fake store returns false ⇒ 0 submissions; load after "restart" returns last snapshot |
| REL-003 | `RecoveryTest` 5 scenarios R1–R5 | **recovery negative** | AWAIT_HUMAN not auto-approved; submitted+unknown ⇒ RESULT_UNKNOWN, 0 replay |
| REL-004 | `IdempotencyTest.retryAddsAttemptNotSubmissionRight` | negative | retry ⇒ new attemptId, still 0 additional submit |

**Cross-cutting negatives required by AGENTS.md / verification-constraints.md:**
- Every invariant has ≥1 test that **fails when the protection is removed** (e.g. delete the revalidation call ⇒ `ApprovalBindingTest` fails; delete `tryAcquire` ⇒ `IdempotencyTest` fails).
- Duplicate-submission, stale-approval, APK-replacement, config-change, post-cancel-continue are each an explicit negative case (§6).

**Regression suites to re-run:** all existing `app/src/test` suites (incl. `PolicyDecisionTableTest`, `AdvisorIsolationTest`, `ModelRequestGateTest`, `AutoDialogInstallEvidenceMapperTest`, `StrictAiOutputValidatorTest`), plus `./gradlew --offline spotlessCheck :app:testUnstableDebugUnitTest assembleUnstableDebug assemblePreviewDebug` after approval (per prior plan practice). Exact Gradle tasks confirmed against `installerx-baseline.md` before build role.

---

## 5. Steps (ordered, each small enough to review)

1. **ApprovalBinding + ApprovalState** — value types + structural equality + non-null builder (Q3) + state enum. Tests: `ApprovalBindingTest.everyFieldIsBound`, `changedFieldInvalidates`.
2. **ApprovalStateStore contract + test-only `InMemoryApprovalStateStore`** — fail-closed durability flag. Test: `StatePersistenceTest`.
3. **BindingFactsProvider contract + `BindingFacts` + test-only `FakeBindingFactsProvider`** — scriptable sequential facts. Test: covered in steps 6.
4. **SubmissionLedger** — single-consume, thread-safe. Test: `IdempotencyTest` (concurrency + retry).
5. **InstallationGateway contract + test-only `FakeInstallationGateway`** — records submissions. Test: `ChokePointTest` spy.
6. **ApprovalCoordinator.decide** — engine wiring + state routing. Test: `ApprovalCoordinatorTest` decision branch.
7. **ApprovalCoordinator.onContinue (create → re-read via provider → revalidate → submit once)** — the critical path. Tests: `firstContinueBinds...`, `changedFieldInvalidates...`, `providerSecondReadDiffersInvalidates`, `callerCannotForgeValidApproval`.
8. **ApprovalCoordinator.onCancel / onModelFailure** — negative routing. Tests: cancel, every failure code.
9. **ApprovalCoordinator.onRecovered (R1–R5)** — recovery + RESULT_UNKNOWN. Test: `RecoveryTest`.
10. **`ApprovalIsolationTest`** — static import boundary for the new packages. Structural.

No `di/ApprovalModule.kt` in this slice (Q4). No production `data/engine/approval/` files. Wiring is deferred to §9.

Each step's tests are written before/with the code. If step 7 reveals the engine cannot be driven to DR-09 without changing `PolicyInput`, **stop and return to plan** (do not change the engine).

---

## 6. DR-01…DR-10 handling specifically

The engine already implements DR-01…DR-10 (`DeterministicPolicyEngineTest` §… ). The coordinator's job per rule:

| Rule | Coordinator action |
|---|---|
| DR-01 HARD_BLOCK | even with human approval, `decide`/`onContinue` must not reach gateway. Engine returns BLOCK regardless of `human`; coordinator submits only on `EXECUTE_ONCE`. **Negative test:** hard block + approved + Continue ⇒ 0 submissions. |
| DR-10 INVALIDATED | occurred when `onContinue` revalidation (second provider read vs stored binding) fails ⇒ engine `..._FAILED_AFTER_APPROVAL` ⇒ 0 submissions; state INVALIDATED. |
| DR-03 RECOLLECT | Continue disabled; `onContinue` rejected; 0 submissions. |
| DR-02/04/06/07/08 AWAIT_HUMAN | `onContinue` available; only revalidation pass + DR-09 ⇒ submit. |
| DR-05 ALLOW_AUTO | direct `EXECUTE_ONCE` without human; coordinator acquires ledger + submits once. |
| **DR-09** | produced **only** by revalidation pass inside `onContinue`; the coordinator is the sole producer of the `VALID_APPROVAL_AND_REVALIDATION_PASSED` input. This is the INV-003 boundary. |

**CRITICAL** — first Continue creates approval then immediately revalidates, then submits per DR-09; this must be one flow. The test `firstContinueBindsRevalidatesAndSubmitsOnce` asserts 1 submission and 0 extra model calls.

---

## 7. Preventing duplicate submission, expiry, APK replacement, config change, post-cancel continue

| Threat | Mechanism (this slice) | Test |
|---|---|---|
| Duplicate submit / repeat Continue | `SubmissionLedger.tryAcquire` | `IdempotencyTest` |
| APK replaced between evidence and submit | re-read/compute apk_sha256 into `freshFacts`, compare to binding | `ApprovalBindingTest` TOCTOU |
| Config changed | `configSnapshotHash` mismatch ⇒ INVALIDATED | `changedFieldInvalidates` |
| Approval stale/expired | revalidation recompute + (later) TTL; this slice: any mismatch invalidates; TTL is REL-002/003 open item | `RecoveryTest` R4 |
| Continue after Cancel | state `CANCELLED` ⇒ `onContinue` no-op | `cancelYieldsZeroSubmissions` |
| Concurrent events | `Mutex`/synchronized ledger + state guard | 20-concurrency test |

---

## 8. Persistence, restart, RESULT_UNKNOWN honesty (REL-002/003/004)

- This slice **does not** persist to Room. It provides the `ApprovalStateStore` contract with an explicit **fail-closed** bool and an in-memory impl, plus tests that inject write failure and a simulated restart (load after store reset). This **does not** by itself satisfy REL-002/003/004 for real process death; it proves the *logic and contract*. §10 lists it as an unmet Must gap until Room (ADR-002.3) is built.
- `RESULT_UNKNOWN` is modelled as a `GatewayResult.ResultUnknown` → state `RESULT_UNKNOWN`; `onRecovered` never replays a submission. We must **not** claim the unknown as uninstalled.
- **No memory-only lock is claimed to satisfy REL-002/003/004.** The plan and its tests explicitly state the storage is in-memory for the slice and the durable requirement is deferred.

---

## 9. Sequenced follow-up (Android wiring order), each separately approved

1. **ADR-002.3 approval** — durable schema (Room `approval_binding` + `decision_audit` tables, migration bump from schema 18).
2. Persist-backed `ApprovalStateStore` + `DecisionAuditRecorder` (FR-009, DATA-001).
3. Real `InstallationGateway` wrapping `AppInstallerRepository.doInstallWork` / `session.install(true)` at the S3 seam (`AppInstallerRepositoryImpl.kt:60-101`); replace fake in `ApprovalModule`.
4. Wire assessment entry at S1/S2 (`InstallerViewModel.install()` `:817-820`; `ProgressHandler.onAnalysedSuccess()` `:51-69`) so every in-scope submission goes through the coordinator. Preserve `ModelRequestGate` install at the production assessment entry (ADR-002.5 remaining work).
5. Dual-UI review/Continue/Cancel + consent screens (FR-006/008/011, ARCH-002).
6. Real-model end-to-end + audit query (FR-010), then acceptance claims.

---

## 10. Remaining gaps (honest status; Must not claimed done)

Not satisfied by this slice alone — must be tracked to completion before final acceptance:

- **REL-002, REL-003, REL-004, INV-003, INV-005, SEC-003:** durable persistence and real crash-recovery — deferred to ADR-002.3/Room.
- **REL-002 approval TTL (Q6):** no approval expiration window this slice; "any binding mismatch ⇒ INVALIDATED" only. TTL deferred to ADR-002.3 — **unfinished**.
- **FR-006, FR-008, ARCH-002:** no UI.
- **FR-007, ARCH-001:** real backend wiring not done; only fake-gateway logic proven.
- **FR-009, FR-010, DATA-001:** no audit recorder/query.
- **FR-011:** consent UI not built (gate logic from #19 exists).
- **SCOPE-001:** AutoNotification flow not wired.
- **Timebox risk:** with ~1 day left, the safest *valuable* unit is steps 1–10 (domain coordinator + tests + static check). No `di/` wiring this slice.

---

## 11. Resolved decisions and risks

**Resolved decisions (approved):**
1. **Backend enum (Q1 — decided):** reuse `com.rosan.installer.domain.settings.model.config.Authorizer`; `installationBackend` is **non-nullable** (v1 = `None`), a missing backend blocks dispatch.
2. **`configSnapshotHash` (Q2 — decided):** caller supplies a normalized canonical hash in this slice; real canonicalization is a §9 follow-up.
3. **Binding construction (Q3 — decided):** `ApprovalBinding` is non-null-only, built by a builder that returns `null` on any missing field; "missing field prevents dispatch" is structural.
4. **PR scope (Q4 — decided):** steps 1–10 only; **no** `di/ApprovalModule.kt` and no production `data/engine/approval/` this slice. Only test doubles under `app/src/test/`.
5. **Doc edits (Q5 — decided):** build role does **not** modify `docs/architecture`; status notes are deferred.
6. **REL-002 TTL (Q6 — decided):** approval TTL/window is deferred to ADR-002.3; recorded as unfinished in §10.

**Risks**
- **R1 — false sense of completion:** test-only in-memory store could be read as satisfying REL-002/003/004. Mitigation: gap ledger (§10), fail-closed contract, and tests that inject write failure.
- **R2 — fabricating revalidation:** if any other caller can pass `VALID_APPROVAL_AND_REVALIDATION_PASSED` to the engine, INV-003 is broken. Mitigation: coordinator is the only producer; `callerCannotForgeValidApproval` test; the enum input stays behind the coordinator.
- **R3 — stale facts (updated):** reusing the first fact snapshot would miss APK replacement / config change. Mitigation: `BindingFactsProvider` is read **twice** (build + revalidation); `providerSecondReadDiffersInvalidates` test.
- **R4 — gateway reachability:** `FakeInstallationGateway` is only a test seam; the real one later must not be injectable by UI/AI/policy. Mitigation: keep the interface minimal; review production DI at wiring.
- **R5 — schedule:** ~1 day. The slice is deliberately thin; production wiring will not fit. Plan is honest about gaps.

**Open questions for approval (answer these):**
1. **Backend enum** — reuse `domain/settings/model/config/Authorizer` for `installationBackend` (v1 = `None`), or introduce a minimal `InstallationBackend` enum in the approval package? (Recommend reuse; avoids parallel vocabulary. Reusing a `domain/settings` type in `domain/engine/approval` does not cross an authority boundary.)
2. **`configSnapshotHash`** — accept a caller-supplied canonical hash string in this slice (no baseline `ConfigModel` serializer introduced here)? (Recommend yes; the real canonicalization is a §9 follow-up.)
3. **Bind vs. build split** — accept `ApprovalBinding` non-null-only with a builder that returns `null` on any missing field, so "missing field prevents dispatch" is structural? (Recommend yes.)
4. **Scope of the PR** — is steps 1–8 + static check the approved slice, or do you also want a *compiling* `di/ApprovalModule.kt` wiring (step 9) that registers only fake implementations (no production calls)?
5. **Doc edits** — may the build role add a short ADR-002.3/002.7 status note after this slice (docs/architecture is human-approval-required)? Or keep all doc updates for the verify role.
6. **REL-002 TTL** — for this slice, is "any binding mismatch ⇒ INVALIDATED" sufficient, with an explicit approval TTL/window deferred to ADR-002.3?

---

## 12. Approval checklist (to be filled by human)

- [ ] Approach and package placement approved
- [ ] `ApprovalBinding` field set + backend enum decision approved (Q1/Q2/Q3)
- [ ] Test plan (per-row negatives, concurrency, restart, TOCTOU) approved
- [ ] Scope boundary (no production wiring, no UI, no audit, fake store) approved (Q4)
- [ ] Gap ledger (§10) accepted as not-done
- [ ] Doc-edit policy approved (Q5)
- [ ] REL-002 TTL deferral approved (Q6)

**No code will be written until this plan and the tests are explicitly approved. Silence is not approval.**
