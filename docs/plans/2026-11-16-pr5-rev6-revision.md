# PR #5 — rev.6 Revision Plan (D1 Requirements)

**Status:** Awaiting human approval for requirements edits.
**Authorized artifacts:** Planning document only. No rev.6 edits authorized.
**Validation:** Planned, not run. No rev.6 file has been changed.
**Repository state:** Local checkout is clean `main` at `fcdd2348`; PR baseline is `origin/cs5351-project` at `a9e237f6` (rev.5).
**Intended artifact path:** `docs/plans/2026-11-16-pr5-rev6-revision.md` (the calling client saves this document).
**Transcript:** The complete session transcript is required as review evidence.

## 1. Purpose

Revise the D1 requirements artifacts to resolve the seven `CHANGES REQUIRED` items from PR #5:

1. `docs/req/requirements.yaml`
2. `docs/req/req-schema.json`

This is a requirements-only revision. It adds no Android code, tests, modules, CI changes, or architecture decisions. All existing requirement meanings are preserved unless explicitly listed below. `FR-012` remains the AI-toggle requirement and is not reused.

## 2. Reviewer Item Mapping

| # | Reviewer requirement | Rev.6 response | Primary IDs | Source pointer |
|---|---|---|---|---|
| 1 | Clarify AI purpose: advisory assessment with installation success likelihood and security risk; no approval, no execution, no trial install, no guarantee | Add two independent advisory dimensions; prohibit action fields and guarantee language; keep AI outside installer capability | `FR-003`, `FR-004`, `SEC-001`, `AIQ-001`, `INV-001` | PR #5 review items 1 and 7; `docs/req/requirements.yaml` rev.5 |
| 2 | Revise the automatic-install decision model | Keep deterministic policy authoritative; only hard rules produce `BLOCK`; adverse advice or uncertainty routes to human review; user may continue manually | `FR-005`, `DR-01`–`DR-10`, `SEC-002`, `INV-002`, `INV-007` | PR #5 review item 2; rev.5 decision rules |
| 3 | Add AutoDialog and AutoNotification flows | AutoDialog remains **Must**; AutoNotification remains **Should**; reuse one assessment/decision path; evaluate before submission; cover delayed confirmation, recovery, and duplicate events | `SCOPE-001`, `FR-001`, `FR-006`, `FR-007`, `FR-008`, `REL-002`, `REL-003`, `REL-004`, `INV-005`, `ARCH-001` | PR #5 review item 3; `InstallerViewModel.kt:598`; `ProgressHandler.kt:51-69`; `ActionHandler.kt:340-355`, `1020`; `InstallModes.kt:22-26` |
| 4 | Reconsider compatibility evidence | Collect compatibility facts locally and deterministically; send only derived signals; keep raw identifiers and target/source package names local; never auto-install when compatibility is unknown or incompatible | `FR-002`, `FR-005`, `SEC-004`; proposed `COMP-002` | PR #5 review item 4; rev.5 allowlist and `SEC-004` |
| 5 | Synchronise requirements, acceptance criteria, and decision rules | Update `SCOPE-001`, `FR-002`–`FR-007`, `AIQ-001`, and `DR-01`–`DR-10`; add acceptance cases for automatic success, human handover, AI failure, compatibility uncertainty, elevated risk, and hard prohibition | Same IDs; `VER-001` | PR #5 review item 5 |
| 6 | Clarify outbound metadata and token policy | Distinguish field-present from value-known; truncation is incomplete; clarify token counting and evaluation population; separate 95%/2000 aggregate target from 4000 hard cap; remove reviewer-identity and pending-status `const` values from schema; reframe `SEC-005` as observable safety bounds | `FR-002`, `FR-003`, `SEC-004`, `SEC-005`, `COST-001`, allowlist, schema | PR #5 review item 6; `req-schema.json` rev.5 |
| 7 | Preserve review and AI-use evidence | Record reproducible validation commands and outputs; update AI-use log; link/export the full transcript; map each review item to a change; continue the existing PR #5 branch | `GOV-001`, `GOV-002`, `VER-001` | PR #5 review items 1–7; `docs/ai-use-log.yaml`; `AGENTS.md:33-39` |

## 3. ID Mapping and Compatibility Decision

### Preserved constraints

- All 41 rev.5 requirement IDs remain.
- `FR-012` remains **AI toggle semantics**: AI off preserves baseline behaviour, makes zero model calls, records `AI_DISABLED`, and does not weaken existing checks. It is not the consent-declined path.
- `AutoDialog = Must` and `AutoNotification = Should` are settled through `SCOPE-001.mode_priorities`.
- `FR-001` remains the approval-request requirement; its supported-path list includes both modes, with mode priority supplied by `SCOPE-001`.
- `REL-003` retains recovery semantics; `REL-004` and `INV-005` retain duplicate-submission prevention.
- `DR-09` remains `REQUIRE_REVIEW / EXECUTE_ONCE`; `DR-10` remains `REQUIRE_REVIEW / INVALIDATED`; `DR-01` remains `BLOCK / BLOCKED`.

### Revised IDs

- `SCOPE-001`: scope envelope with `mode_priorities`.
- `FR-001`–`FR-009`: two-dimensional advice, compatibility evidence, completeness, notification flow, and audit fields.
- `FR-011`: consent list follows the revised allowlist; meaning unchanged.
- `INV-002`, `INV-003`, `INV-004`, `INV-005`, `INV-006`, `INV-007`: acceptance cases strengthened; meanings unchanged.
- `SEC-004`, `SEC-005`: allowlist and observable safety bounds.
- `REL-001`–`REL-004`: token rejection, notification recovery, and deduplication cases.
- `COST-001`: preflight-versus-post-call distinction.
- `COMP-001`: preserve existing constraints.
- `AIQ-001`, `QUAL-001`, `VER-001`, `ARCH-001`, `ARCH-002`, `DATA-001`: corresponding acceptance updates.

### Proposed new ID

`REQ-IX-COMP-002` is retained **only if it remains an independent, testable requirement**:

- It determines `COMPATIBLE`, `INCOMPATIBLE`, or `UNKNOWN` locally from platform and APK facts, without a model call.
- It has an independent negative test: no AI dependency; unknown or incompatible never auto-installs.
- Compatibility **collection** remains in `FR-002`; compatibility **decision use** remains in `FR-005`.

If Member A decides that this is not independent enough to justify a new ID, fold it into `FR-002` and `FR-005` and drop `COMP-002`. This is an open decision, not a settled requirement.

## 4. Interface Contract

### AI advisory output

`FR-003` and `FR-004` define:

- `install_success_likelihood`: `HIGH | MEDIUM | LOW | UNKNOWN`
- `security_risk`: `LOW | MEDIUM | HIGH | UNKNOWN`
- `reasons`, `evidence_refs`, `model_id`, `prompt_version`
- No action, approval, execution, or installation instruction fields
- No guarantee language and no trial-install claim

Missing dimensions, invalid enums, fabricated evidence references, or oversized output are `INVALID`.

### Deterministic policy

`FR-005` consumes:

- Evidence completeness
- Local compatibility result
- Both advisory dimensions
- Effective policy version
- Human approval state

Outputs remain `ALLOW_AUTO`, `REQUIRE_REVIEW`, or `BLOCK`. Hard prohibitions always win. AI advice cannot lower a deterministic restriction (`INV-007`).

### AutoNotification

- Must use the same assessment and policy path as AutoDialog.
- Must evaluate before submission.
- Delayed confirmation, process recovery, and duplicate notification events must not create a new submission right.
- Notification interaction cannot bypass approval binding or the single authorization choke point.

### Counters

- **Attempts:** attempt 1 is automatic; at most one explicit attempt 2 is allowed in v1. Preflight rejection still consumes an attempt.
- **Provider dispatches:** 0 when rejected before dispatch; 1 only when sent.
- **Backend submissions:** at most 1 cumulative per operation.
- **Additional** counters are caused by the named action; **cumulative** counters are for the same operation ID.

## 5. Scope

### In scope

- `docs/req/requirements.yaml`
- `docs/req/req-schema.json`
- This plan artifact
- `docs/ai-use-log.yaml` after the human synchronises the branch
- Draft PR response text

### Out of scope

- Android source or tests
- Architecture blueprint, ADRs, trace matrix
- CI workflows, permission files, `AGENTS.md`, `Prompt.md`
- Any requirement implementation claim
- Commit, push, merge, rebase, or new PR

## 6. Rev.6 Artifact Changes

### `requirements.yaml`

- Revision 6; status remains a draft awaiting approval.
- Add a product-model section distinguishing advisory assessment from deterministic authorization.
- Update `SCOPE-001` with mode priorities and notification scope.
- Update `FR-001`–`FR-009` and `FR-011`.
- Preserve `FR-012`.
- Update `INV-002`–`INV-007` acceptance language.
- Update `SEC-004`, `SEC-005`, `REL-001`–`REL-004`, `COST-001`, `AIQ-001`, `QUAL-001`, `VER-001`, `ARCH-001`, `ARCH-002`, and `DATA-001`.
- Rewrite `DR-01`–`DR-10` for two dimensions, completeness, and compatibility.
- Add `COMP-002` only if the independent-test justification holds.

### `req-schema.json`

- Remove `const` values that hard-code reviewer identity or pending approval status.
- Add structures for both advisory dimensions, compatibility, completeness, notification modes, attempts, dispatches, and cost outcomes.
- Require the new fields on the affected requirements.
- Keep Draft 2020-12 compatibility.

### Evidence

- Update `docs/ai-use-log.yaml` with this review-driven revision.
- Link or export the complete transcript.
- Record exact validation commands and raw output in the PR response.

## 7. Acceptance Scenarios

### Core decision scenarios

| ID | Scenario | Decision | Disposition | Add. attempts | Cum. attempts | Add. dispatches | Cum. dispatches | Add. submissions | Cum. submissions | Continue / Retry / Cancel |
|---|---|---|---|---|---|---|---|---|---|---|
| S1 | `COMPLETE`, `COMPATIBLE`, success `HIGH`, risk `LOW`, `AUTO_RULES_MET` | `ALLOW_AUTO` | `EXECUTE_ONCE` | 1 automatic | 1 | 1 | 1 | 1 | 1 | No Continue required; submit once; Cancel only before submission |
| S2 | `COMPLETE`, `COMPATIBLE`, `HIGH`/`LOW`, `AUTO_RULES_NOT_MET` | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | 1 automatic | 1 | 1 | 1 | 0 | 0 | Continue creates approval, revalidates, DR-09, 0 additional calls; Retry only as attempt 2; Cancel: no submission |
| S3 | Success not `HIGH`, or risk not `LOW` | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | 1 automatic | 1 | 1 | 1 | 0 | 0 | Continue manual; Retry as attempt 2; Cancel: no submission |
| S4 | AI invalid, unavailable, or timeout | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | 1 | 1 | 0 or 1 | 0 or 1 | 0 | 0 | Continue 0 additional calls; Retry as attempt 2; Cancel: no submission |
| S5 | `KEY_UNKNOWN` before any attempt | `REQUIRE_REVIEW` | `RECOLLECT` | 0 | 0 | 0 | 0 | 0 | 0 | Continue disabled; Cancel only |
| S6 | `TRUNCATED` allowlisted evidence | `REQUIRE_REVIEW` | `RECOLLECT` | 0 | 0 | 0 | 0 | 0 | 0 | Continue disabled; Cancel only |
| S7 | `CONTRADICTORY` evidence | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | 1 | 1 | 1 | 1 | 0 | 0 | Continue manual; Retry as attempt 2; Cancel: no submission |
| S8 | Compatibility `UNKNOWN` | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | 1 | 1 | 1 | 1 | 0 | 0 | Continue manual; Retry as attempt 2; Cancel: no submission |
| S9 | Compatibility `INCOMPATIBLE` | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | 1 | 1 | 1 | 1 | 0 | 0 | Continue manual; Retry as attempt 2; Cancel: no submission |
| S10 | **Hard prohibition preserved**: previous attempts may be 0, 1, or 2; detection adds nothing | `BLOCK` | `BLOCKED` | 0 | 0, 1, or 2 | 0 | 0, 1, or 2 | 0 | 0 | Continue disabled; Retry adds 0 attempts/dispatches; human approval cannot override; no submission |
| S11 | Consent declined or withdrawn | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | 0 | 0 | 0 | 0 | 0 | 0 | Continue manual; Retry requires fresh consent; Cancel: no submission |
| S11a | AI toggle off (`FR-012`) | No AI decision; baseline path | Baseline | 0 | 0 | 0 | 0 | Baseline | Baseline | Baseline UI behaviour; existing checks still run |
| S12 | **Preflight rejection**: zero provider requests | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | 1 | 1 | **0** | 0 | 0 | 0 | Continue 0 additional calls; Retry adds 1 attempt, 0 dispatches if rejected again; Cancel: no submission |
| S13a | **Post-call overage**: one completed call reports `T >= 4001` | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | 1 | 1 or 2 | **1** | 1 or 2 | 0 | 0 | Continue 0 additional calls; Retry adds attempt 2 with 1 dispatch if sent; Cancel: no submission |
| S13b | **Blocked third request**: cumulative attempts already 2 | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | **0** | 2 | **0** | 2 | 0 | 0 | Continue 0 additional calls; Retry disabled; Cancel: no submission |

### Corrected Continue, Retry, and S16 scenarios

**Sequence:** with complete evidence, no hard prohibition, and no approval yet, the first explicit Continue in `AWAIT_HUMAN` creates approval bound to the current `INV-003` object, then immediately revalidates before submission. A pre-existing approval is not required.

| ID | Scenario | Decision | Disposition | Add. attempts | Cum. attempts | Add. dispatches | Cum. dispatches | Add. submissions | Cum. submissions | Continue / Retry / Cancel |
|---|---|---|---|---|---|---|---|---|---|---|
| S14a | First Continue after declined consent; zero prior calls | `REQUIRE_REVIEW` → after Continue DR-09 | `AWAIT_HUMAN` → `EXECUTE_ONCE` | 0 | 0 | 0 | 0 | 1 | 1 | Continue creates approval, revalidates, 0 additional calls; Retry disabled without consent; Cancel: no submission |
| S14b | First Continue after post-call failure; one prior call | `REQUIRE_REVIEW` → after Continue DR-09 | `AWAIT_HUMAN` → `EXECUTE_ONCE` | 0 | 1 | 0 | 1 | 1 | 1 | Continue creates approval, revalidates, 0 additional calls; Retry only as attempt 2; Cancel: no submission |
| S14c | First Continue after review; complete evidence, no hard prohibition, no approval | `REQUIRE_REVIEW` → DR-09 | `AWAIT_HUMAN` → `EXECUTE_ONCE` | 0 | 0, 1, or 2 | 0 | 0, 1, or 2 | 1 | 1 | Continue creates approval, revalidates, 0 additional calls; Retry remains separate; Cancel: no submission |
| S15 | Changed `INV-003` binding after approval | `REQUIRE_REVIEW` | `INVALIDATED` | 0 | Unchanged | 0 | Unchanged | 0 | 0 | Continue disabled until fresh approval and revalidation; Retry grants no submission right; Cancel: no submission |
| S16.1 | First Continue through notification flow | `REQUIRE_REVIEW` → DR-09 | `AWAIT_HUMAN` → `EXECUTE_ONCE` | 0 | Unchanged | 0 | Unchanged | 1 | 1 | Continue creates approval, revalidates, 0 additional calls; Cancel: no submission |
| S16.2 | **Duplicate notification or repeated Continue click** for the same operation | Same as the original operation | Same as the original; at most one `EXECUTE_ONCE` | 0 | Unchanged | 0 | Unchanged | 0 for the duplicate | ≤1 | Only an explicit user Continue may create the first approval. A duplicate notification cannot approve. Repeated clicks share the same operation, with at most one backend submission. |
| S16.3 | **Recovery while still awaiting the user** | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | 0 | Unchanged | 0 | Unchanged | 0 | ≤1 | No auto-approval; no submission; user may Continue or Cancel later |
| S16.4 | **Recovery after submission when outcome is unknown** | Prior decision unchanged | `RESULT_UNKNOWN` | 0 | Unchanged | 0 | Unchanged | 0 | 1 | No automatic replay; no new Continue/Retry submission until reconciliation resolves the prior outcome |
| S16.5 | **Changed binding during recovery** | `REQUIRE_REVIEW` | `INVALIDATED` | 0 | Unchanged | 0 | Unchanged | 0 | 0 | Fresh approval and revalidation required; Retry grants no submission right; Cancel: no submission |
| S16.6 | **Hard block during notification or recovery** | `BLOCK` | `BLOCKED` | 0 | Unchanged | 0 | Unchanged | 0 | 0 | Human confirmation and Retry cannot override; no submission |
| S16.7 | Cancel before submission | No submission | No approval reuse | 0 | Unchanged | 0 | Unchanged | 0 | ≤1 | Cancel ends the operation |

## 8. Token and Cost Policy

`COST-001` preserves:

- At most 2 cumulative attempts per operation.
- 95% of calls in the evaluation population at `<= 2,000` total tokens is an aggregate target.
- 4,000 total tokens is the inclusive per-call hard cap.

Boundary behaviour:

| Total tokens | Runtime outcome | Evaluation outcome |
|---|---|---|
| `2,000` | Allowed | Inside 95% target |
| `2,001`–`3,999` | Allowed | 5% tail; not a runtime failure |
| `4,000` | Allowed for a completed valid call | 5% tail |
| `4,001+` | Failed attempt; `REQUIRE_REVIEW`; no auto-approval; no automatic retry | Counts as a miss |

Required distinctions:

- **Preflight rejection:** 0 provider requests, 1 attempt consumed, no auto-approval.
- **Reported post-call overage:** 1 completed call, no auto-approval, no automatic retry.
- **Blocked third request:** 0 additional attempts and 0 additional dispatches, because the operation budget is exhausted.

Proposals requiring review:

- Token counting method: provider-reported usage for accounting, local tokenizer for preflight enforcement; treatment of cached/hidden reasoning tokens.
- Evaluation population: fixed 30-call sample per model and prompt version; both attempts counted; proposal only.

## 9. Planned Tests

No rev.6 check has been run. All of the following are **planned tests**, not completed validation:

- YAML parsing and top-level field checks.
- JSON Schema self-validation and instance validation.
- Unique IDs, no dangling dependencies, acyclic dependency graph.
- English-only text; zero CJK characters.
- `DR-01`–`DR-10` coverage and ordering.
- Allowlist leaf-path, required-field, value-known, and truncation checks.
- COST-001 boundary cases: 2,000 / 2,001–3,999 / 4,000 / 4,001.
- Schema check that reviewer identity and pending status are not hard-coded with `const`.
- `SEC-005` observable-boundary check.
- Negative tests N1–N10 from the prior plan, mapped to requirement IDs.
- Regression check that rev.5 IDs and meanings are preserved.
- Confirmation that the diff contains only authorized files.

## 10. Steps

1. Human synchronises the PR branch with `main`; the agent cannot merge or push.
2. Write the rev.6 plan and test matrix; obtain human approval.
3. Edit `docs/req/requirements.yaml` with per-edit approval in the plan role.
4. Edit `docs/req/req-schema.json` with per-edit approval.
5. Run the planned validation commands and capture raw output.
6. Update `docs/ai-use-log.yaml`.
7. Draft the PR #5 response mapping each review item to a change and evidence.
8. Human commits and pushes to the existing `cs5351-project` branch.
9. Verify role independently re-runs validation and records the review outcome.

## 11. Approval Gates

- **Gate A:** Human approval of this plan and its test strategy.
- **Gate B:** Per-file approval before each `docs/req/` edit.
- **Gate C:** Human review of validation output before PR text is finalised.
- **Gate D:** Human authorisation for commit and push.
- **Gate E:** Human review before merge.
- **Gate F:** Complete transcript and AI-use log recorded.

## 12. Open Decisions

1. Whether `COMP-002` remains a separate requirement or is folded into `FR-002` and `FR-005`.
2. Final token-counting method and treatment of cached or hidden reasoning tokens.
3. Whether the 30-call evaluation sample is accepted.
4. Whether `INCOMPATIBLE` compatibility defaults to `REQUIRE_REVIEW` or an existing hard rule.
5. Scheduling of the Should-level AutoNotification integration; its scope and priority are settled.
6. How the PR branch is synchronised with `main`.
7. Where the complete transcript is attached, since `transcripts/` is denied to agents.
8. Whether validation is reproducible inline or requires a human-approved script.

## Approval Checkpoint

This document is a planning artifact for human approval. Rev.6 requirements edits are not authorized. Validation is planned, not completed. No commit, push, merge, rebase, or PR action has been taken. Approval of this plan and its tests is required before any requirement file is changed.
