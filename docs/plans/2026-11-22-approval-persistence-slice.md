# Execution Plan — ADR-002.3 persistence slice (Room ApprovalStateStore, SubmissionLedger, DecisionAuditRecorder)

**Role:** plan
**Date:** 2026-11-22 (rev.3 — incorporates human decisions Q1–Q11, review changes A–D, and the approval record in §0.1)
**Baseline:** `main` after PR #27 (approval-coordinator-binding-gateway slice, `docs/plans/2026-11-21-approval-coordinator-binding-gateway.md`).
**Requirements:** `docs/req/requirements.yaml` (rev.6 draft_for_group_review).
**Related ADRs:** ADR-002.3 (persistence, not started — this plan proposes its decision), ADR-002.7 (hash/integrity, specified), ADR-001 (accepted, Option B: contract in `domain`, impl in `data`).
**Status:** APPROVED (rev.3); build BLOCKED at step 2; slice DEFERRED by wxxsfxyzm on 2026-11-22. No implementation merged. See §0.2.

**Revision log (rev.2):** Q1 resolved to Robolectric + room3-testing (testImplementation only, from `libs.versions.toml`); Q2–Q10 accepted. Review change **A** (SEC-003 test re-spec), **B** (crash window between `tryAcquire` and gateway result), **C** (audit has no production caller this slice), **D** (test layers → Robolectric) applied. All "inmem/inst" layers replaced with **Robolectric**.

**Revision log (rev.3):** approval recorded; Q11 resolved (make `onRecovered` `suspend`, consult `permit.isConsumed` first); added approved coordinator change: `onRecovered` persists the mapped state (fail-closed) with a test; explicit consumed-permit state mapping added to §2.6; R5 now references `.github/workflows/ci.yml` (CS5351 Gates) as well as `pr-check.yml`.

---

## 0.1 Approval record

**Approved by:** wxxsfxyzm · **Date:** 2026-11-22 · **Plan revision:** rev.3.

This approval covers the plan and the tests in §4 (as listed, plus the added recovery-then-Continue test), with these binding conditions:

1. **Q11 approved:** make `onRecovered` `suspend` and consult `permit.isConsumed` **first**.
2. **Additional approved coordinator changes (inside the step 7 stop rule):**
   - **R-a — persist on recover:** `onRecovered` must persist the mapped state (`load` snapshot → `map` → `store.persist`), returning `Outcome.PersistenceFailed` if the write fails. Rationale: without persisting, a recovered `AWAIT_HUMAN` is unusable because `onContinue` reads the stored state (still `APPROVED`/`EXECUTE_ONCE`) and rejects. Required test: recovered `AWAIT_HUMAN` (permit **not** consumed) followed by `onContinue` reaches revalidation.
   - **R-b — consumed-permit mapping:** as listed explicitly in §2.6.
3. **Tests (§4)** approved as listed, plus the R-a recovery-then-Continue test.
4. **R5** must reference `.github/workflows/ci.yml` (CS5351 Gates), not only `pr-check.yml`.

---

## 0.2 Execution outcome

**Result: build stopped at step 2 (Robolectric smoke). Slice deferred by wxxsfxyzm on 2026-11-22. No implementation merged.**

- **Step 2 outcome:** the Robolectric Room smoke test aborts the JVM with **exit 134** (SIGABRT) under the project toolchain (JDK 25 + macOS arm64) — the Robolectric **native runtime** aborts. A **Robolectric-only** probe (no Room) also aborts, confirming the failure is in the Robolectric runtime itself, not in Room or the new code.
- **Flags tried:** multiple Robolectric/runtime flags were attempted to get the native runtime to load under JDK 25 / arm64; none resolved the abort (details in the AI session record `AI-wxxsfxyzm-0011`).
- **Stop rule:** this is the approved Q1 timebox stop condition working as designed (§4, §5 stop rules): a test runtime that cannot run real SQLite on the JVM cannot be the regression gate, so the build stopped and reported rather than proceeding on unverified Room behavior.
- **Q1 fallback:** the approved fallback (fake-DAO JVM tests + recorded debt entry) was **not** applied, per the lead's decision.
- **Lead decision:** the slice is **deferred** rather than continued with a weaker test gate. No code from this slice is merged.
- **Restart conditions:** resume from **step 2** once a test runtime that runs **real SQLite on the JVM** is available — either (a) a Robolectric release that works on JDK 25, or (b) an approved instrumentation run (an emulator in CI or an equivalent approved device test path). This dependency is tracked as **DEBT-003**.

---

## 0. What already exists (do not re-implement)

| Artifact | Location | Role in this slice |
|---|---|---|
| `ApprovalStateStore` + `ApprovalSnapshot` | `domain/engine/approval/ApprovalStateStore.kt` | contract consumed; `persist` returns `Boolean` (fail-closed) |
| `ApprovalCoordinator` | `domain/engine/approval/ApprovalCoordinator.kt` | calls `store.persist` and stops submission on `false`; `submitOnce` acquires the permit then submits then persists result |
| `SubmissionLedger` | `domain/engine/approval/SubmissionLedger.kt` | in-memory `ConcurrentHashMap`; `tryAcquire(id): Boolean` |
| `ApprovalBinding`, `ApprovalState`, `BindingFacts` | `domain/engine/approval/` | value types to persist |
| `InMemoryApprovalStateStore` | `app/src/test/.../approval/` | test double; NOT durable |
| `InstallerRoom` | `data/settings/local/room/InstallerRoom.kt:33-96` | `INSTALLER_ROOM_SCHEMA_VERSION = 18`, `exportSchema = true`, room3, `AutoMigration` list, `MIGRATION_12_13` precedent |
| Exported schemas | `app/schemas/com.rosan.installer.data.settings.local.room.InstallerRoom/{3..18}.json` | migration verification baseline |
| `SettingsModule` (Koin) | `di/SettingsModule.kt:56-62` | existing Room DAO registrations; no approval module yet |

Gaps confirmed on `main`: no Room table for approval state, no durable ledger, no `DecisionAuditRecorder`. `SubmissionLedger` is memory-only, so INV-005/REL-004 do **not** survive a process restart today.

**Crash window on `main` (drives change B):** `ApprovalCoordinator.submitOnce` (lines 226–248) does `ledger.tryAcquire` → `gateway.submit` → `store.persist(nextState)`. If the process dies **after** the permit is acquired but **before** the result snapshot is persisted, the durable snapshot still reads `APPROVED`/`EXECUTE_ONCE`. With an in-memory ledger that window is harmless (permit lost on restart); with the durable ledger the permit is **consumed**, so recovery must not treat that snapshot as "approved but unsubmitted". See §2.6 and test B.

---

## 1. Traceability

| ID | Priority | What this slice delivers | Fully satisfied by slice? |
|---|---|---|---|
| REQ-IX-REL-002 (persist at transitions; write failure stops submission) | Must (ASR) | Room `approval_snapshot` written at every coordinator transition; `persist` returns `false` when the Room transaction fails; coordinator already stops submission on `false` | **Yes** for state transitions modelled by the coordinator; delayed-notification (B6) transition remains deferred to the notification flow (out of scope, §3) |
| REQ-IX-REL-003 (recovery; RESULT_UNKNOWN; no replay) | Must (ASR) | persisted `RESULT_UNKNOWN`/`SUBMITTED` reloaded after restart; **consumed-permit + non-terminal snapshot recovers as RESULT_UNKNOWN** (crash window B); `onRecovered` never replays | **Yes** for the durable-state part; platform reconciliation stays deferred (no real gateway) |
| REQ-IX-REL-004 (no duplicate submission; retry ≠ new right) | Must | transaction-safe, unique-key durable `tryAcquire`; second acquire after restart returns `false` | **Yes** for the ledger durability |
| REQ-IX-INV-003 (approval binds the object) | Must (ASR) | persisted binding fields round-trip unchanged; a re-Continue after restart rebinds from current facts and any mismatch yields INVALIDATED / 0 submissions | **Yes** for durable binding storage; revalidation logic already merged |
| REQ-IX-INV-005 (≤1 submission per operation) | Must | durable single-consume permit; crash window recovers as RESULT_UNKNOWN with no replay | **Yes** across restart (permits never expire, Q2) |
| REQ-IX-SEC-003 (tamper resistance, invalidation) | Must | a tampered persisted snapshot cannot cause submission; recovery requires a re-Continue that rebinds from current facts; audit tamper detectable via hash chain | **Partial** — detection + non-replay proven; unkeyed SHA-256, no HMAC (Q6, gap §10) |
| REQ-IX-FR-009 (decision audit fields) | Must | `decision_audit` append-only table with the FR-009/memory-design §6.1 columns; recorder implementable but **no production caller this slice** (change C) | **Partial** — storage + append-only + hash chain delivered; no coordinator/entry wiring; export/UI deferred (FR-010) |
| REQ-IX-DATA-001 (append-only, hash-chained, no auto-delete) | Must | append-only DAO (no update/delete), `entry_hash = H(canonical(record) || prev_hash)`, no retention deletion (OI-06 unfrozen) | **Yes** for integrity + retention constraint in storage; export/query deferred |

**Explicitly out of scope (not claimed):** real `InstallationGateway`, ViewModel/`ProgressHandler` wiring, **audit recording at coordinator/entry points** (no production caller — change C), UI/consent screens, audit export/query (FR-010), platform reconciliation after restart, notification-flow (B6) persistence, HMAC key management (Q6).

---

## 2. Interface contract and boundaries

### 2.1 Placement (ADR-001 Option B)

Implementations live in **`data/engine/approval/`** (new package), not `data/settings/local/room/`:

- **Why a new package, not the existing room package:** the room package owns the `InstallerRoom` `@Database`, entities, and DAO for settings/history. Approval persistence is a distinct architectural element (`ApprovalStateStore impl`, `SubmissionPermit impl`, `DecisionAuditRecorder`) that maps-to-one-module per `structural-constraints.md`. Mixing it into the settings room package would couple an install-decision element to the settings element.
- **Shared DB, not a new DB:** entities are added to the **existing** `InstallerRoom` (schema bump) to reuse the migrated, `exportSchema`-tracked database (OL-05 keeps Room over a file store). The `@Database` file stays the single schema owner; it only gains `entities = [...]` entries and `abstract val ...Dao` accessors.
- **DAO interfaces** live beside the entities (`data/engine/approval/entity/`, `data/engine/approval/dao/`), registered via the `InstallerRoom` abstract accessors.

**Layering guard:** `domain/engine/approval/**` (guard-tested by `ApprovalIsolationTest`) must **not** import `data.*` or room. The implementations in `data/engine/approval/` are the only place room types appear. `ApprovalIsolationTest`'s `FORBIDDEN_PREFIXES` already forbids `data.`/room for the domain package; the new domain files (`SubmissionPermit`, `DecisionAuditRecorder`) must satisfy it.

### 2.2 DI (Q3)

- **No new Koin module file.** Register the new DAOs + implementations inside the existing **`di/SettingsModule.kt`** Room block, following the current pattern (`single { get<InstallerRoom>().approvalSnapshotDao }`, etc.).
- The `ApprovalStateStore` implementation is registered bound to the existing domain interface (`single<ApprovalStateStore> { RoomApprovalStateStore(get()) }`).

### 2.3 `approval_snapshot` table (REQ-IX-REL-002)

Proposed entity `ApprovalSnapshotEntity` → table `approval_snapshot`:

| column | type | notes |
|---|---|---|
| `operation_id` | TEXT PK | stable per request (FR-001) |
| `attempt_id` | TEXT | one per attempt |
| `state` | TEXT NOT NULL | `ApprovalState.name` |
| `decision_decision` | TEXT NULL | `PolicyDecision.name` (null before decide) |
| `decision_disposition` | TEXT NULL | `PolicyDisposition.name` |
| `decision_rule_id` | TEXT NULL | DR rule ID |
| `decision_reason_code` | TEXT NULL | reason code |
| `apk_sha256` | TEXT NULL | binding subset, hex |
| `target_package_name` | TEXT NULL | local only (PMA-OUT-001), stored, never egressed (Q10) |
| `target_user_id` | INTEGER NULL | |
| `install_mode` | TEXT NULL | `InstallModeSignal.name` |
| `installation_backend` | TEXT NULL | `Authorizer.value` via existing `AuthorizerConverter` |
| `config_snapshot_hash` | TEXT NULL | |
| `policy_version` | TEXT NULL | |
| `updated_at` | INTEGER NOT NULL | epoch millis (audit/trace) |

- One row per `operation_id` (upsert by PK); mutable-current-state, matching `ApprovalStateStore.load` semantics.
- **No credentials, no raw APK path.** Only `apk_sha256`, hashes, package name, user, mode, backend, policy version.

### 2.4 `submission_ledger` table (INV-005, REL-004) — atomic, durable

Proposed entity `SubmissionLedgerEntity` → table `submission_ledger`:

| column | type | notes |
|---|---|---|
| `operation_id` | TEXT PK | one permit per operation |
| `attempt_id` | TEXT | attempt that consumed the permit |
| `acquired_at` | INTEGER NOT NULL | epoch millis |

- **`tryAcquire` is a single `INSERT` with `OnConflictStrategy.ABORT`** on the PK. Success (first acquirer) or `SQLiteConstraintException` (already consumed) → `false`. One atomic statement; concurrency is SQLite write serialization, not application locking.
- **Durability:** committed before `tryAcquire` returns `true`; after restart (new `InstallerRoom`, same DB file) the PK is seen and `false` returned.
- **Never expires, no delete API (Q2).** No `delete`/`clear` exposed; retention is OI-06 (unfrozen).

### 2.5 `SubmissionPermit` seam (Q4 — approved)

```kotlin
// domain/engine/approval/SubmissionPermit.kt  (new small interface)
/** Implements: ADR-002.3. Enforces: REQ-IX-INV-005, REQ-IX-REL-004. */
fun interface SubmissionPermit {
    /** @return true iff this call durably consumed the permit (at most once, across restarts). */
    suspend fun tryAcquire(operationId: String, attemptId: String): Boolean

    /** @return true iff a permit was already consumed for [operationId] (read-only, used by recovery). */
    suspend fun isConsumed(operationId: String): Boolean
}
```

- `ApprovalCoordinator`'s single call site becomes `permit.tryAcquire(bound.operationId, bound.attemptId)` (suspend, 2-arg).
- `isConsumed` is **new** and is what change B's recovery needs (§2.6); it is read-only and adds no submission capability.
- The in-memory `SubmissionLedger` stays for `app/src/test` and implements `SubmissionPermit` (adapter) so existing tests compile.
- Durable impl `data/engine/approval/RoomSubmissionPermit` maps `SQLiteConstraintException` → `false`.

### 2.6 Crash window between permit and result — recovery consults the permit (change B)

Scenario: process dies after `tryAcquire` commits but before `submitOnce` persists `SUBMITTED`/`RESULT_UNKNOWN`. Durable snapshot still says `APPROVED`/`EXECUTE_ONCE`, but the permit is consumed. Requirement: recovery reports **RESULT_UNKNOWN** (not `AWAIT_HUMAN`, no replay).

**Minimal coordinator change (stated here for approval):** make recovery consult the permit. Approved (Q11) signature change:

```kotlin
suspend fun onRecovered(snapshot: ApprovalSnapshot): Outcome
```

Body order: read the permit first (`permit.isConsumed(snapshot.operationId)`), map the state by §2.6 below, **persist the mapped state** (`store.persist`), and return `Outcome.PersistenceFailed` if that write fails (R-a). Returning `PersistenceFailed` is fail-closed: no submission and no unusable stale state is silently accepted.

**Consumed-permit state mapping (R-b, explicit):**

| persisted snapshot state | permit consumed? | recovered state |
|---|---|---|
| `FAILED` | yes | `FAILED` (terminal, unchanged) |
| `SUBMITTED` | yes | `RESULT_UNKNOWN` |
| `RESULT_UNKNOWN` | yes | `RESULT_UNKNOWN` |
| `AWAIT_HUMAN` | yes | `RESULT_UNKNOWN` |
| `APPROVED` | yes | `RESULT_UNKNOWN` |
| `EXECUTE_ONCE` | yes | `RESULT_UNKNOWN` |
| `CANCELLED` | yes | `CANCELLED` (kept) |
| `BLOCKED` | yes | `BLOCKED` (kept) |
| *(any state)* | **no** | existing R1–R5 mapping unchanged (`AWAIT_HUMAN`/`APPROVED`/`EXECUTE_ONCE` → `AWAIT_HUMAN`; `SUBMITTED`/`RESULT_UNKNOWN` → `RESULT_UNKNOWN`; `FAILED` stays `FAILED`; others kept) |

- Consumed + pre-terminal (`AWAIT_HUMAN`/`APPROVED`/`EXECUTE_ONCE`) ⇒ `RESULT_UNKNOWN`: a submission was attempted; the outcome is unknown; **no replay**.
- `FAILED`, `CANCELLED`, `BLOCKED` keep their state (a consumed permit on these reflects a prior terminal/aborted attempt, not an in-flight submission).
- The mapped result is **persisted** (R-a) so a subsequent `onContinue` reads the correct state instead of a stale `APPROVED`/`EXECUTE_ONCE`.

This changes `onRecovered` from `fun` to `suspend fun` (it reads the permit and writes) and touches the existing `RecoveryTest` calls (wrapped in `runTest`). The coordinator constructor holds a `SubmissionPermit` (already replacing `SubmissionLedger`, §2.5).

### 2.7 Write-failure surfacing (REL-002 fit criterion, Q5 — approved)

```kotlin
suspend fun persist(snapshot: ApprovalSnapshot): Boolean = try {
    dao.upsert(snapshot.toEntity())
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    false   // any write failure => non-durable => stop submission (REL-002)
}
```

`load` returns `null` on any non-cancellation exception. Fail-closed is fixed; tests force a DAO exception (§4).

### 2.8 `decision_audit` table (FR-009, DATA-001)

Proposed entity `DecisionAuditEntity` → table `decision_audit`, append-only:

| column group | columns |
|---|---|
| identity | `id` INTEGER PK autoincrement, `operation_id`, `attempt_id` |
| binding facts | `apk_sha256`, `install_mode`, `installation_backend`, `config_snapshot_hash`, `policy_version` |
| model/prompt | `model_id`, `prompt_version` |
| advisory | `install_success_likelihood`, `security_risk`, `compatibility_result`, `evidence_completeness` |
| outcome | `advice_or_failure_type`, `reason_code` (rule ID), `decision`, `disposition`, `human_action` |
| counters | `attempt_count`, `provider_dispatch_count`, `token_outcome` |
| result | `install_result` |
| timestamps | `created_at`, `decided_at`, `submitted_at` |
| chain | `prev_hash` TEXT, `entry_hash` TEXT NOT NULL |

- **DAO surface is append-only:** only `insert` and read queries. **No `update`, `delete`, `clear`, `trimToLimit`** — declared nowhere.
- `DecisionAuditRecorder` (interface `domain/engine/approval/DecisionAuditRecorder.kt`; impl `data/engine/approval/RoomDecisionAuditRecorder.kt`) computes `entry_hash` reading the latest `entry_hash` as `prev_hash` **inside the same write transaction** as the insert, so appends cannot fork the chain.
- **Canonical form (Q7 — revised, approved):** `"v1|" + join(cols)` where each value is **length-prefixed** as `"<len>:<value>"`; **null ⇒ `"-1:"`** (distinct from empty string ⇒ `"0:"`). Fixed column order (the §2.8 group order, excluding `entry_hash`). UTF-8; hash = SHA-256 hex. Null-vs-empty must hash differently (test B-adjacent §4).
- **Verification:** `verifyChain()` walks rows by `id`, recomputes each `entry_hash`; first mismatch ⇒ `Intact=false` with the offending id.
- **No auto-delete (OI-06):** no retention code; documented as a hard constraint.

### 2.9 Audit recorder has no production caller this slice (change C)

`DecisionAuditRecorder` and its Room impl are delivered and unit-tested, but **nothing in the coordinator, ViewModel, or entry points calls it in this slice**. Wiring audit recording into the decision/Continue/submit flow is a later, separately-approved slice. Therefore FR-009 stays **Partial**: the table + integrity + chain are real, but no live decision is recorded end-to-end yet.

### 2.10 Schema bump and migration (Q8, Q9 — approved)

- `INSTALLER_ROOM_SCHEMA_VERSION = 19`.
- Add three entities to `@Database(entities = [...])` and `AutoMigration(from = 18, to = 19)`.
- New accessors `abstract val approvalSnapshotDao`, `submissionLedgerDao`, `decisionAuditDao`.
- Exported `app/schemas/.../19.json` is generated by the build and **committed** (Q9).

### 2.11 Files (create/change)

| File | Action | Element |
|---|---|---|
| `data/engine/approval/entity/ApprovalSnapshotEntity.kt` | create | snapshot entity |
| `data/engine/approval/entity/SubmissionLedgerEntity.kt` | create | ledger entity |
| `data/engine/approval/entity/DecisionAuditEntity.kt` | create | audit entity |
| `data/engine/approval/dao/ApprovalSnapshotDao.kt` | create | upsert/load |
| `data/engine/approval/dao/SubmissionLedgerDao.kt` | create | insert(ABORT)/exists |
| `data/engine/approval/dao/DecisionAuditDao.kt` | create | insert/read-only |
| `data/engine/approval/RoomApprovalStateStore.kt` | create | `ApprovalStateStore` impl |
| `data/engine/approval/RoomSubmissionPermit.kt` | create | `SubmissionPermit` impl |
| `data/engine/approval/RoomDecisionAuditRecorder.kt` | create | `DecisionAuditRecorder` impl |
| `data/engine/approval/AuditHashing.kt` | create | canonical + SHA-256 (internal) |
| `domain/engine/approval/SubmissionPermit.kt` | create | permit seam (Q4) |
| `domain/engine/approval/DecisionAuditRecorder.kt` | create | recorder contract |
| `domain/engine/approval/ApprovalCoordinator.kt` | change | `tryAcquire(id, attemptId)` + `isConsumed` recovery consult (Q4/B) |
| `data/settings/local/room/InstallerRoom.kt` | change | version 19, entities, accessors, autoMigration |
| `di/SettingsModule.kt` | change | DAO + impl registrations |
| `gradle/libs.versions.toml` | change | **robolectric + room3-testing (testImplementation only)** (Q1, approved) |
| `app/build.gradle.kts` | change | `testImplementation(libs.robolectric)` + `testImplementation(libs.room.testing)`; `unitTests.isIncludeAndroidResources = true` for Robolectric; Room schema as test assets |
| `app/schemas/.../19.json` | generated/commit | migration baseline |
| Robolectric tests under `app/src/test/java/...` (§4) | create | see names |

### 2.12 Files explicitly NOT changed

`DeterministicPolicyEngine`, `PolicyDecisionModels`, AI advisory/provider files, `InstallerViewModel`, `ProgressHandler`, `ActionHandler`, `AppInstallerRepository*`, `InstallationGateway` (no real impl), any UI, workflow/signing files. Only the two approved test dependencies are added to `libs.versions.toml`.

---

## 3. Scope boundaries

- **In:** Room tables + migration, durable `ApprovalStateStore`, durable `SubmissionPermit` (+ crash-window recovery), append-only hash-chained `DecisionAuditRecorder`, DI registration in `SettingsModule`, Robolectric test deps, tests §4.
- **Out:** real gateway; coordinator/entry audit wiring (change C); ViewModel/ProgressHandler wiring; notification flow (B6); UI/consent; audit export/query (FR-010); platform reconciliation; HMAC (Q6); approval TTL (Q2 — permits never expire).

---

## 4. Testing strategy (tests first)

**Layers (change D):** **pure-JVM** = `kotlin.test` with no Room/Android. **Robolectric** = `@RunWith(RobolectricTestRunner)` + Room in-memory builder and/or `MigrationTestHelper`, running under `:app:testUnstableDebugUnitTest` so it is part of the CI regression gate (androidTest is impossible in CI — no emulator).

**Timebox rule (Q1):** if Robolectric cannot run Room under the project JDK/SDK within the build session, **stop and report**; fallback is fake-DAO JVM tests (domain/impl logic only, no real SQLite) **plus a recorded debt entry**, not androidTest.

**Test dependencies:** `robolectric` and `room3-testing` added via `libs.versions.toml` as `testImplementation` only (approved). Migration test uses `MigrationTestHelper` under Robolectric with the exported `app/schemas/.../18.json` and `19.json` as test assets.

| Requirement | Test | Layer | Negative case (fails if protection removed) |
|---|---|---|---|
| REL-002 | `RoomApprovalStateStoreTest.persistThenLoadRoundTrips` | Robolectric | round-trip of all binding fields + decision |
| REL-002 | `RoomApprovalStateStoreTest.writeFailureReturnsFalseAndDoesNotSubmit` | Robolectric | force DAO exception ⇒ `persist` false; coordinator `Outcome.PersistenceFailed`, gateway submissions 0 |
| REL-002 | `Migration18To19Test` (open v18 schema, migrate, 3 tables exist, v18 rows intact) | Robolectric (`MigrationTestHelper`) | missing table or data loss ⇒ fail |
| REL-004 / INV-005 | `DurableLedgerTest.secondAcquireAfterRestartReturnsFalse` | Robolectric | close DB, reopen, `tryAcquire` again ⇒ `false`; 0 extra submissions |
| INV-005 | `DurableLedgerTest.concurrentAcquireExactlyOneWins` | Robolectric | N concurrent `tryAcquire` ⇒ exactly 1 `true` |
| REL-003 | `RecoveryFromPersistedStateTest.resultUnknownReloadedNoReplay` | Robolectric | persist `RESULT_UNKNOWN`; recover ⇒ `Recovered(RESULT_UNKNOWN)`; gateway submissions 0 |
| REL-003 | `RecoveryFromPersistedStateTest.approvedUnsubmittedRequiresUserAction` | Robolectric | `APPROVED`/`EXECUTE_ONCE` with permit **not** consumed ⇒ `AWAIT_HUMAN`; no auto-submit |
| **REL-003 / INV-005 (change B)** | `RecoveryFromPersistedStateTest.consumedPermitButNoResultRecoversAsResultUnknown` | Robolectric | acquire permit (durable), leave snapshot at `APPROVED`/`EXECUTE_ONCE` (simulate crash before result write); reopen DB; `onRecovered` ⇒ `Recovered(RESULT_UNKNOWN)`; gateway submissions 0; a later Continue does **not** re-submit |
| **REL-003 (R-a, approved)** | `RecoveryFromPersistedStateTest.recoveredAwaitHumanThenContinueReachesRevalidation` | Robolectric | permit **not** consumed, snapshot `APPROVED`/`EXECUTE_ONCE` ⇒ `onRecovered` persists `AWAIT_HUMAN`; a follow-up `onContinue` passes the state guard and reaches revalidation (not `ContinueRejected`); proves the recovered state was persisted, not left stale |
| REL-003 (R-a write failure) | `RecoveryFromPersistedStateTest.recoveryPersistFailureReturnsPersistenceFailed` | Robolectric | force store write failure during `onRecovered` ⇒ `Outcome.PersistenceFailed`; no submission; stale state not silently accepted |
| **SEC-003 (change A, re-specified)** | `RecoveryFromPersistedStateTest.tamperedSnapshotCannotCauseSubmission` | Robolectric | after restart, mutate persisted binding `apk_sha256` in the DB so the stored snapshot differs from current facts; recovery never submits; a **new** `onContinue` rebinds from current facts and yields `INVALIDATED`, 0 submissions — proves submission depends on fresh facts, not the stored row |
| FR-009 / DATA-001 | `AuditHashChainTest.appendOnlyAndChainIntact` | Robolectric | append N rows; `verifyChain` intact; **mutate a row in the DB** ⇒ `verifyChain` detects break at that id |
| FR-009 / DATA-001 | `AuditHashChainTest.nullAndEmptyStringHashDifferently` | **pure-JVM** | canonicalizing `null` vs `""` for a column yields different `entry_hash` (Q7) |
| FR-009 | `AuditRecordTest` (field groups present; no credential columns) | **pure-JVM** | entity has no token/key column |
| Q7 canonical | `AuditCanonicalizationTest.lengthPrefixAndNullSentinel` | **pure-JVM** | `"v1|"` prefix, `"<len>:<value>"`, null `"-1:"`, empty `"0:"` |

**Which run where (honest):**
- **Pure-JVM:** `AuditCanonicalizationTest`, `AuditHashChainTest.nullAndEmptyStringHashDifferently`, `AuditRecordTest`.
- **Robolectric:** all Room/SQLite tests and the migration test — part of `:app:testUnstableDebugUnitTest`.

**Regression re-run:** all existing `app/src/test` suites (`PolicyDecisionTableTest`, `ApprovalCoordinatorTest`, `IdempotencyTest`, `RecoveryTest`, `StatePersistenceTest`, `ChokePointTest`, `ApprovalIsolationTest`, AI suites) with zero regressions. `ApprovalCoordinator`/`RecoveryTest` changes (Q4/B) must keep these passing.

---

## 5. Steps (ordered; each reviewable; tests written with/before code)

1. **ADR-002.3 decision text** — recorded as §6; human copies into `docs/architecture/adr/002-...` after approval (no doc edit by agent).
2. **Test deps + Robolectric smoke** — add `robolectric` + `room3-testing` (testImplementation) to `libs.versions.toml`; add to `app/build.gradle.kts`; prove one trivial Robolectric Room test runs. **If it cannot run, stop and report (Q1 timebox).**
3. **`Migration18To19Test` + schema** — entities + `INSTALLER_ROOM_SCHEMA_VERSION=19` + `AutoMigration(18,19)` + accessors; export `19.json`; migration preserves v18 rows.
4. **`SubmissionPermit` seam + `RoomSubmissionPermit`** — insert(ABORT) + `isConsumed`; tests: durably-once-after-restart, concurrent-exactly-one.
5. **`RoomApprovalStateStore`** — upsert/load fail-closed; tests: round-trip, write-failure-stops-submission.
6. **`DecisionAuditRecorder` + `AuditHashing` + append-only DAO** — chain in one transaction; tests: chain intact, mutation detected, canonicalization/null-vs-empty, no credential columns.
7. **Coordinator change (Q4 + B + R-a, approved)** — `tryAcquire(id, attemptId)`; `onRecovered` suspend + permit consult + **persist mapped state, `PersistenceFailed` on write failure**; tests: crash-window RESULT_UNKNOWN, recovered-AWAIT_HUMAN-then-Continue, recovery-persist-failure, tampered-snapshot-no-submission; re-run `IdempotencyTest`/`ChokePointTest`/`RecoveryTest`.
8. **DI registration** in `SettingsModule`; build smoke.
9. **Verify** — all tests + `spotlessCheck` + `assembleUnstableDebug`/`assemblePreviewDebug`; report honestly.

**Stop rules:** if step 2 fails and no fake-DAO fallback is accepted → stop. If step 7 needs coordinator changes beyond (a) the `tryAcquire` signature, (b) the `onRecovered` permit consult, and (c) persisting the mapped recovery state → stop and return to plan.

---

## 6. Proposed ADR-002.3 decision (for human to move into the ADR)

> **ADR-002.3 — Persistence for approval state and decision audit (decided).**
> Store approval state, the submission permit, and the decision audit in the existing `InstallerRoom` (room3), schema `18 → 19` via additive auto-migration. Tables: `approval_snapshot` (mutable current state, PK `operation_id`), `submission_ledger` (append-only permit, PK `operation_id`, single atomic unique-key insert, **never expires, no delete**), `decision_audit` (append-only, no update/delete, hash-chained `entry_hash = SHA-256("v1|" + length-prefixed, null-sentineled columns || prev_hash)`, chain append in one transaction; **unkeyed SHA-256, HMAC deferred**). Transaction boundaries follow REQ-IX-REL-002 (`memory-design.md` §4); a failed write returns `false` and stops automatic submission. Recovery consults the durable permit: a consumed permit with a non-terminal snapshot recovers as `RESULT_UNKNOWN` (no replay). Audit and snapshot store no credentials and no raw APK path (`target_package_name` stored locally, never egressed). No automatic deletion while OI-06 is unfrozen. **Audit recording has no production caller in the delivering slice** (FR-009 Partial); approval TTL and platform reconciliation remain follow-ups.

---

## 7. Decisions (resolved) and open questions

**Resolved (Q1–Q11):** Robolectric + room3-testing (testImplementation); permits never expire; `SettingsModule` DI; adopt `SubmissionPermit`; catch all non-cancellation → `false`/`null`; unkeyed SHA-256 (HMAC deferred, gap §10); length-prefixed canonical with null sentinel + version prefix; `AutoMigration(18,19)`; commit `19.json`; store `target_package_name` locally; **Q11 — `onRecovered` is `suspend` and consults `permit.isConsumed` first, and persists the mapped state (R-a).**

**Open questions:** none blocking. Any divergence from the approved step 7 scope (see stop rules) requires returning to plan.

---

## 8. Risks

- **R1 — Robolectric/Room on JVM may not run** under the project JDK 25 / Android SDK. Mitigation: step 2 smoke test before any Room code; timebox fallback (fake-DAO + debt record) per Q1.
- **R2 — chain fork under concurrency:** append + read-latest in one write transaction (single writer).
- **R3 — coordinator churn:** `tryAcquire` signature (Q4) + `onRecovered` suspend (B). Mitigation: two localized spots; existing tests must pass; stop rule if wider.
- **R4 — migration data loss:** verified by `Migration18To19Test`, not assumed.
- **R5 — CI impact of new test deps:** Robolectric adds download size/time; verify both `.github/workflows/ci.yml` (CS5351 Gates) and `pr-check.yml` still pass, since the regression gate runs there. No signing/release change.

---

## 9. Approval checklist (recorded)

Approved by wxxsfxyzm on 2026-11-22 (rev.3), with the conditions in §0.1.

- [x] ADR-002.3 decision (§6) approved; human moves it into the ADR
- [x] Robolectric + room3-testing test deps approved (Q1); timebox/fallback accepted
- [x] `SubmissionPermit` seam (Q4) + Q11 recovery signature approved (`suspend` + consult `isConsumed` first)
- [x] Coordinator change scope approved: `tryAcquire` signature + `onRecovered` permit consult + persist mapped state (R-a) + consumed-permit mapping (R-b)
- [x] Package placement + `SettingsModule` DI (Q3) approved
- [x] Write-failure surfacing (Q5) approved
- [x] Canonical format (Q7) + unkeyed SHA-256/HMAC gap (Q6) approved
- [x] Migration (Q8) + commit `19.json` (Q9) + local package name (Q10) approved
- [x] Audit has no production caller (C) acknowledged; FR-009 stays Partial
- [x] Test list (§4) approved, incl. crash-window, recovered-AWAIT_HUMAN-then-Continue, recovery-persist-failure, re-specified SEC-003, canonicalization negatives
- [x] Scope boundary (§3) approved; R5 references `ci.yml` (CS5351 Gates) and `pr-check.yml`

**Approved.** Build may proceed within the approved scope and stop rules. No commit, push, or PR without separate authorization.
