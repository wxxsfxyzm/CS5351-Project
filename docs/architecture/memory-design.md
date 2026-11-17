# Memory and Persistence Design

**Status:** draft — pending human review.
**Owner:** Member C (Software Architect).
**Baseline:** InstallerX Revived `816ea541`; repository state `0f718739`.
**Requirements:** `docs/req/requirements.yaml` rev.6
(`status: draft_for_group_review`).
**Related:** `architecture-blueprint.md` §8,
`adr/002-later-important-arch-decisions.md` ADR-002.3 and ADR-002.7,
`runtime-policy-catalog.md`, `invariants.md`.

Course marker `[M]` — mandatory artifact.

This document describes the state model, the local approval binding,
persistence, transaction boundaries, crash recovery, and the audit
schema. No code is implemented. The exact approval/audit table and DAO
are deferred to ADR-002.3; the record shape below is fixed by the
requirements registry and is not an invention.

---

## 1. Operation state machine

The machine is derived from the `disposition` enum in
`requirements.yaml` and the instructions in REQ-IX-FR-001, FR-006, FR-007,
FR-008, REL-002, and REL-003.

### 1.1 States

| State | Meaning | Source disposition |
|---|---|---|
| `RECEIVED` | request accepted, operation ID created, attempt ID created | FR-001 |
| `COLLECTING` | evidence collection in progress | FR-002 |
| `AWAITING_AI` | consent granted, network on, waiting for the model | FR-003 |
| `AWAIT_HUMAN` | decision requires human review | AWAIT_HUMAN (DR-02, 04, 06, 07, 08, 09) |
| `RECOLLECT` | evidence incomplete; Continue disabled | RECOLLECT (DR-03) |
| `BLOCKED` | hard prohibition active; no Continue | BLOCKED (DR-01) |
| `APPROVED` | valid approval bound to the current object | FR-006, INV-003 |
| `EXECUTE_ONCE` | one permitted submission; consumed on use | EXECUTE_ONCE (DR-05, 09) |
| `INVALIDATED` | approval failed revalidation; no submission | INVALIDATED (DR-10) |
| `SUBMITTED` | backend accepted the submission | FR-007 |
| `RESULT_UNKNOWN` | submitted, outcome not reconcilable | FR-008, REL-003 |
| `COMPLETED` | platform result known | FR-008 |
| `CANCELLED` | user cancelled before submission | FR-008 |

`RESULT_UNKNOWN` is a post-submission installation result, not a policy
disposition. It is deliberately not a value of the `disposition` enum.

### 1.2 Transitions

| From | Event | To | Notes |
|---|---|---|---|
| `RECEIVED` | evidence complete | `AWAITING_AI` | records attempt ID; 0 backend calls |
| `RECEIVED` | evidence incomplete | `RECOLLECT` | Continue disabled |
| `AWAITING_AI` | advice valid, policy `REQUIRE_REVIEW` | `AWAIT_HUMAN` | |
| `AWAITING_AI` | advice valid, policy `BLOCK` | `BLOCKED` | hard prohibition |
| `AWAITING_AI` | advice valid, policy `ALLOW_AUTO` | `EXECUTE_ONCE` | one submission |
| `AWAITING_AI` | advice unusable | `AWAIT_HUMAN` | DR-08 |
| `AWAIT_HUMAN` | first Continue; binding created; revalidation passes | `EXECUTE_ONCE` | DR-09; 0 additional model calls |
| `AWAIT_HUMAN` | first Continue; revalidation fails | `INVALIDATED` | DR-10 |
| `AWAIT_HUMAN` | Cancel | `CANCELLED` | no submission, no approval reuse |
| `EXECUTE_ONCE` | submit accepted | `SUBMITTED` | consumed once |
| `SUBMITTED` | platform result known | `COMPLETED` | |
| `SUBMITTED` | result not reconcilable | `RESULT_UNKNOWN` | replay forbidden |
| any pre-submission | user Cancel | `CANCELLED` | no submission |

`BLOCKED` and `RECOLLECT` have no Continue transition. `INVALIDATED`
requires a fresh approval and revalidation.

---

## 2. Local approval binding record

The binding record is the source of truth for REQ-IX-INV-003. It is
local-only. Its fields are exactly
`outbound_metadata_allowlist.local_approval_binding.retains`:

| Field | Type | Purpose |
|---|---|---|
| `operation_id` | string | stable per request (FR-001) |
| `attempt_id` | string | one per analysis attempt (FR-001) |
| `apk_sha256` | string (hex) | binds the exact bytes (INV-003) |
| `target_package_name` | string | binds the object; stays local (PMA-OUT-001) |
| `target_user_id` | int | binds the target user |
| `install_mode` | enum | `AutoDialog` or `AutoNotification` |
| `installation_backend` | enum | authorizer used |
| `config_snapshot` | object/hash | binds install-affecting configuration |
| `policy_version` | string | binds the rule set used |

Binding rules:

- All fields are mandatory. A missing field prevents dispatch.
- A changed field invalidates the approval (`INVALIDATED`).
- The binding is created by the **first Continue** in `AWAIT_HUMAN`,
  from the current installation object.
- Before submission, `ApprovalCoordinator` rechecks every field,
  including a re-read of the APK SHA-256 versus the stored value
  (ADR-002.7).

---

## 3. Room database mapping

### 3.1 Existing baseline structures

| Structure | Location | Notes |
|---|---|---|
| `InstallerRoom` | `data/settings/local/room/InstallerRoom.kt:33-60` | `@Database`, `version = INSTALLER_ROOM_SCHEMA_VERSION` (= 18), `exportSchema = true`, auto-migrations |
| `OperationHistoryEntity` | `data/settings/local/room/entity/OperationHistoryEntity.kt` | table `operation_history`; unique index on `(operation_session_key, operation_type, package_name)` |
| `OperationHistoryDao` | `data/settings/local/room/dao/OperationHistoryDao.kt` | `all`, `flowAll`, `insert` (`REPLACE`), `deleteById`, `trimToLimit`, `clear` |
| `OperationHistoryRepositoryImpl` | `data/settings/repository/OperationHistoryRepositoryImpl.kt` | `Mutex`-guarded `insert`; replaces the previous row for the same session key |

### 3.2 Gap analysis for the approval store and audit

`OperationHistoryEntity` is close to, but not equal to, the FR-009 audit
schema:

| FR-009 field | Present today | Gap |
|---|---|---|
| package name, install mode, backend, initiator, versions | yes | — |
| APK hash | **no** | add `apk_sha256` |
| config and policy versions | no | add `config_snapshot_hash`, `policy_version` |
| model and prompt versions | no | add |
| both advisory dimensions | no | add |
| local compatibility result | no | add |
| evidence completeness | no | add |
| advice or failure type | partial (`error_type`) | extend |
| DR rule ID | no | add |
| final decision, human action | no | add |
| attempt count, provider-dispatch count, token outcome | no | add |
| installation result | partial (`status`) | extend |
| hash chain (`prev_hash`, `entry_hash`) | no | add for REQ-IX-DATA-001 |

This gap is recorded, not implemented. Table shape is ADR-002.3.

### 3.3 Mapping decision (deferred)

Two options, to be decided in ADR-002.3:

1. Extend `OperationHistoryEntity` and bump the schema with a
   migration.
2. Add separate `approval_binding` and `decision_audit` tables in the
   same `InstallerRoom` database.

This document assumes option 2 for the description below, because the
audit is append-only while history is replace-in-place; keeping them
apart avoids changing history semantics. The assumption is provisional.

---

## 4. Transaction boundaries

REQ-IX-REL-002 requires a durable write at each of these points. A
failure to write reliably stops automatic submission.

| Boundary | Written before | Table (assumed) |
|---|---|---|
| B1 request received | operation ID, attempt ID created | `approval_binding` |
| B2 evidence complete | evidence summary + completeness | `approval_binding` |
| B3 advice complete | advisory result + validity | `decision_audit` (draft row) |
| B4 decision | DR rule ID, decision, disposition | `decision_audit` |
| B5 approval | binding record finalized | `approval_binding` |
| B6 delayed notification confirmation | confirmation recorded | `approval_binding` |
| B7 pre-submission | revalidation result, submission permit | `decision_audit` |

Rules:

- Each boundary is a single durable transaction. A partial write is not a
  valid state.
- Write failure at B1–B7 means **no automatic submission**. The request
  remains `AWAIT_HUMAN` or becomes `RECOLLECT` as appropriate.
- The audit append and the decision write may share a transaction, but
  the audit row must be durable before the submission permit is issued.

---

## 5. Crash-recovery protocol

REQ-IX-REL-003 defines five recovery scenarios. The protocol extends two
existing baseline mechanisms rather than inventing a second one:

- **Live platform session tracking.** `InstallerSessionRepositoryImpl`
  keeps `activePlatformSessionIds` and exposes
  `setPlatformSessionActive(sessionId, active)`
  (`data/session/repository/InstallerSessionRepositoryImpl.kt:62,177-180`),
  passed to the backend via `InstallMetadata.onPlatformSessionActiveChanged`
  (`domain/engine/model/install/InstallMetadata.kt:9`).
- **Durable pre-action record.** `SelfUpdateRecoveryManager.arm()` writes
  a recovery record through DataStore and returns only after the write is
  durable (`framework/packageupdate/SelfUpdateRecoveryManager.kt:40-70`;
  `data/packageupdate/repository/SelfUpdateRecoveryRepositoryImpl.kt:22-30`).

### 5.1 Recovery scenarios

| Scenario | On restart | Outcome |
|---|---|---|
| R1 crashed during model wait | state is `AWAITING_AI`, no approval | revalidate; no auto-approval; route back to review or retry |
| R2 crashed pending review | state is `AWAIT_HUMAN` | remains `AWAIT_HUMAN`; user must Continue or Cancel |
| R3 crashed during delayed notification | state is `AWAIT_HUMAN` with notification pending | remains `AWAIT_HUMAN`; no submission before assessment |
| R4 crashed after approval, before submission | state is `APPROVED`/`EXECUTE_ONCE`, no submission record | revalidate binding; if it still matches, require user action; no automatic replay |
| R5 crashed after submission, result unknown | `SUBMITTED` recorded, no platform result | reconcile with platform state; if not reconcilable, `RESULT_UNKNOWN`, replay forbidden |

Invariants across recovery: no reuse of an expired approval, no duplicate
submission, and the unknown result is identifiable (REQ-IX-REL-003 fit
criterion).

### 5.2 Test obligations

`planned: RecoveryTest` (five scenarios), `planned: StatePersistenceTest`
(restart + write fault), `planned: IdempotencyTest` (concurrency),
`planned: DedupTest`.

---

## 6. Decision audit schema (FR-009, DATA-001)

### 6.1 Record fields

Per operation ID and attempt ID:

- `operation_id`, `attempt_id`
- `evidence_summary` (allowlisted summary, no local-only identifiers)
- `apk_sha256`
- `install_mode`, `installation_backend`
- `config_snapshot_hash`, `policy_version`
- `model_id`, `prompt_version`
- `install_success_likelihood`, `security_risk`
- `compatibility_result`, `evidence_completeness`
- `advice_or_failure_type`
- `reason_code` (DR rule ID), `decision`, `disposition`
- `human_action`
- `timestamps` (created, decided, submitted)
- `attempt_count`, `provider_dispatch_count`, `token_outcome`
- `install_result`
- `prev_hash`, `entry_hash` (chain)

No credentials, tokens, or API keys are ever stored (REQ-IX-FR-009).

### 6.2 Integrity

- **Append-only.** No update or delete of an audit row except the
  explicit user-initiated deletion operation, which itself leaves a
  trace (REQ-IX-DATA-001).
- **Hash-chained.** `entry_hash = H(canonical(record) || prev_hash)`.
  Tampering with any row breaks the chain from that row onward.
- **Local only.** Records are stored on the device and are exportable to
  a machine-readable file.

### 6.3 Retention

Retention period and deletion entry point are **pending OI-06**. Until
they are frozen, no automatic deletion occurs. This is a requirement
condition, not a design choice.

### 6.4 Test obligations

`planned: AuditRecordTest` (all field groups reconstructable; attempts
and dispatches distinguishable; no credentials),
`planned: AuditIntegrityTest` (tamper detected; export replays the
decision), `planned: AuditQueryTest`.

---

## 7. What is not decided here

- The final table and DAO shape (ADR-002.3).
- Whether the binding and audit share one table (assumed separate above).
- The canonical hash input format for the chain.
- The audit export file format.
- The retention policy (OI-06).

---

## 8. Traceability

Covers: REQ-IX-INV-003, REQ-IX-INV-005, REQ-IX-REL-002, REQ-IX-REL-003,
REQ-IX-REL-004, REQ-IX-SEC-003, REQ-IX-FR-006, REQ-IX-FR-007,
REQ-IX-FR-008, REQ-IX-FR-009, REQ-IX-FR-010, REQ-IX-DATA-001,
REQ-IX-ARCH-001.
