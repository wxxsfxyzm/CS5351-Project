# Pull Request

## 1. Summary

Adds the rev.7 plan for the AI install-risk requirements: **Approval-Rule Guard and Injection Neutralization**.

The rev.6 review found two defects that survive schema and decision-table validation, because the validator only exercises one input tuple per rule. This plan fixes exactly two of them and defers the rest:

1. **The `human` column is unchecked while advice is unusable.** `DR-09` and `DR-10` match on `advice: ANY` and `evidence: ANY`, so any operation that already holds an approval can reach `EXECUTE_ONCE` even when the current attempt's evidence is `TRUNCATED`, `UNREADABLE`, or `KEY_UNKNOWN`, its advice is `INVALID`, or consent was withdrawn. The safety requirement exists only in prose (`FR-005`, `REL-003`); no rule, no negative fit criterion, and no invariant enforces it.
2. **`SEC-005`'s injection criterion cannot fail.** `SEC-005` requires "neutralize obvious instruction-style text" without defining *obvious*, and its fit criterion is satisfied by `DR-07` even if the sanitizer is deleted. That violates `verification-constraints.md` §1 ("every invariant has at least one negative test that fails when it is violated") and `AGENTS.md` §Verification.

Both defects are requirements-layer. This PR changes no requirements file: it adds the plan only, and `Prompt.md` §2 blocks coding until the plan and its tests are approved.

**Authorized artifacts:** this plan, plus the planned `docs/plans/validate_pr6_rev7.py`. No other file is authorized by this plan.

**Repo state at planning time:** the plan was drafted when local `main` was at `0f718739` (rev.6 = PR #5, still `draft_for_group_review`). It is published on branch `plan/rev7-approval-guard`, based on `origin/arch/c-architecture-design` at `a3f2bd9c`, where D2 is present: ADR-001 is accepted on **Option B** (in-tree packages, no new Gradle module) and ADR-002.1–002.5 remain `not started`. Option B removes the "no new module" blocker but does not change scope: rev.7 is still a requirements-only revision. See §7 for what D2 acceptance changes.

**Role:** `plan` (per `Prompt.md` §2). This document stops the session.
**Session:** `CS5351-Project` plan session, 2026-11-18.

## 2. Change Type

- [ ] Requirements
- [ ] Architecture / ADR
- [ ] Implementation
- [ ] Tests / Verification
- [ ] AI Prompt / Model
- [ ] Agent Governance
- [x] Documentation only

Only `docs/plans/2026-11-18-pr6-rev7-approval-guard.md` is added. The rev.7 requirements edits it describes are **not** part of this PR.

## 3. Traceability

- **Related Requirements:**
  - Guard: no execution on unusable current-attempt evidence — `DR-09`, `DR-10`, `FR-005`, `INV-007`, `REL-001`, new `NFC-GRD-001`…`NFC-GRD-004` (rule predicates, fit criterion, negative tests)
  - Injection neutralization made testable — `SEC-005`, `FR-004`, new `NFC-INJ-001`…`NFC-INJ-003` (named algorithm, falsifiable criterion)
  - Validation of both — `GOV-001`, `VER-001`, new script `validate_pr6_rev7.py`
- **Related ADR(s):** ADR-001 (accepted, Option B — in-tree packages, no new Gradle module); ADR-002.1 and ADR-002.2 remain `not started` and are not dependencies of this plan.
- **Related Invariants:** `INV-002`, `INV-006` keep their meaning and are unchanged; `INV-007` gains the "unusable current-attempt advice never executes" clause in rev.7 (planned, not in this PR).
- **Related Issue / Approved Plan:** plan document `docs/plans/2026-11-18-pr6-rev7-approval-guard.md` (this PR). Approval is still outstanding; see §4.

**Unchanged and explicitly preserved:** `DR-01`…`DR-08` predicates; `DR-11` is not introduced; `REQ-IX-FR-012` keeps its AI-toggle meaning (`FR-012` is not reused, following the rev.6 convention); the 41 existing IDs stay.

**Requirement-count effect:** **41 → 41 IDs**, plus 7 new negative fit criteria (`NFC-GRD-*`, `NFC-INJ-*`). No new requirement ID is added, so the `revision: 6 → 7` bump is the only registry-level identity change.

## 4. Human Approval

- **Was the implementation plan approved before coding?** N/A — no code is included or authorized. The plan itself is awaiting approval.
- **Were the relevant tests approved before implementation?** N/A — `validate_pr6_rev7.py` is planned, not written.
- **Approval evidence (PR/Issue/Transcript link):** yes. This PR is the approval request. Session transcript: `transcripts/transcriptD-wxxsfxyzm-20261118.md` and `transcripts/60267540-Chengqian He-pr6.md`.

For documentation-only changes without code implementation, state N/A where appropriate.

- **Implementation plan approved before coding: N/A.** No code, test, Gradle file, or module is included in this PR, and this plan authorizes none. The requirement edits it describes continue to wait for a separate approval.
- **Tests approved before implementation: N/A.** `docs/plans/validate_pr6_rev7.py` is planned in §5 but not written; a test that does not exist cannot be approved or run.
- **Approval evidence: N/A for code, yes for the plan itself.** No code approval exists to cite. The evidence for this document is the PR itself plus the two session transcripts named above.
- **Security and architecture checks: N/A.** No request path, network call, permission, or trust boundary is touched by a Markdown file.

**Stop point:** rev.7 steps 1–9 modify governed requirement artifacts and require a human to approve §3, §5 and §6 of the plan, then re-approve each `docs/req/` edit. Silence is not approval.

## 5. Verification

- **Tests executed:** none. This PR adds a Markdown document only.
- **Tests passed / failed:** N/A.
- **Regression impact:** none — no requirement, schema, script, or source file is modified.
- **Security / architecture checks:** N/A for this PR. The plan's own planned checks are defined below.
- **Evidence or CI links:** none.

**Planned checks once rev.7 is approved** (all deterministic, offline, no network or model call, per `verification-constraints.md` §4). New executable check `docs/plans/validate_pr6_rev7.py`, which supersedes nothing — `validate_pr5_rev6.py` stays as the rev.6 record:

| Check | Method | Fails when |
|---|---|---|
| C1 schema | `Draft202012Validator.check_schema` + instance validation | schema or instance invalid |
| C2 identity | 41 IDs, unique, acyclic, `Must 34 / Should 7` | count or priority drifts |
| C3 rev.6 regression | replay all 14 tuples from `validate_pr5_rev6.py` verbatim | any tuple changes rule ID |
| C4 exhaustive rule table | enumerate the full reachable domain (5 evidence × 10 advice × 4 policy × 3 human × 3 compatibility = 1,800 cells) | any cell is unmatched, or a cell that was `DR-0N` in rev.6 changes `decision` without an approved reason |
| C5 guard (negative) | assert every cell with `advice=CURRENT_ATTEMPT_UNUSABLE` yields `decision != EXECUTE_ONCE` | **any** such cell yields `EXECUTE_ONCE` — this is the test that fails if the guard is removed |
| C6 hard-block precedence | assert every `policy=HARD_BLOCK` cell yields `BLOCK`/`BLOCKED` regardless of the other four columns | any escape |
| C7 auto-approval minimality | assert exactly one cell yields `ALLOW_AUTO` | more than one `ALLOW_AUTO` cell, or zero (which would mean auto-approval is unreachable) |
| C8 fixture rejection | three invalid documents (missing `fit_criterion`, `priority: Urgent`, `decision: APPROVED`) | any fixture validates |

C5, C6 and C7 are the mutation tests: each fails if its protection is removed.

**Requirements-level criteria to add in rev.7:** `NFC-GRD-001` (guard), `NFC-GRD-002` (revalidation binding), `NFC-GRD-003` (positive reachability), `NFC-GRD-004` (keyword state never read from model text), `NFC-INJ-001` (sanitizer is load-bearing), `NFC-INJ-002` (no sanitized text creates authority), `NFC-INJ-003` (truncation).

**Environment correction:** `docs/review-memo.md` instructs `python3 docs/plans/validate_pr5_rev6.py docs/req`, which is not reproducible on the development machine: `python` resolves to Python 3.6.1, `jsonschema` is absent on both interpreters found, and Python 3.6 cannot import `graphlib`. Either pin `jsonschema` and `PyYAML` in `docs/plans/requirements.txt` with a documented Python 3.10+ command, or vendor a minimal first-match evaluator. See Q3.

## 6. AI Coding Agent Usage

- **Was an AI coding agent used?** Yes
- **Agent and model:** OpenCode v2.3.2, `cityu/CS5351/DeepSeek-V4-Flash` (variant high)
- **Related transcript:** `transcripts/transcriptD-wxxsfxyzm-20261118.md`; `transcripts/60267540-Chengqian He-pr6.md`
- **AI-use log updated:** Yes — `AI-wxxsfxyzm-0006`, flagged as agent-authored and pending human confirmation
- **Significant human corrections:** the plan was requested after the lead asked for a repo walkthrough and an AI-requirements review. The lead rejected an unrelated earlier draft and asked for the plan under `docs/plans/`. The plan was later published on a branch based on C's D2 work rather than `main`, and a §7 section recording what D2 acceptance changed was added at that point.

**Governance note:** `AGENTS.md` §Reading Order names `Prompt.md` step 0, whose first-message form this session did not use, and the `OPENCODE_CONFIG` launch could not be verified from inside the session. The transcript retains this; a human must confirm or amend the log entry rather than treating the agent's own statement as certification.

## 7. Risk and Scope

- **Affected modules:** documentation only — `docs/plans/`. No module, package, or build file is touched.
- **Safety-critical changes:** none in this PR. The rev.7 edits this plan describes touch the deterministic decision-rule table, but they are not applied here.
- **Known limitations:**
  - Adding a value to `advice` changes the rule-input domain, so rev.6's "14 first-match cases" no longer describe the full table; C4 pins every cell to compensate.
  - `DR-10` broadens `human:ANY` intent: an approval invalidated by an unrelated attempt could surface as `DR-08` instead of `DR-10`, hiding the invalidation reason.
  - The `SEC-005` lexicon is a moving target; any fixed word list invites false positives on legitimate `version_name` values.
  - `validate_pr5_rev6.py` will fail after the revision bump, because it asserts `revision == 6`.
  - `VER-001` still cannot pass at rev.7: D2 populated `trace-matrix.md` and `invariants.md`, but every mapped test is still `planned:`.
  - The `FixedResponseAdvisor` fixture requested for A/D integration testing could not be written. No branch contains the frozen `AssessmentEvidence`/`AssessmentResult` contract, ADR-002.2 is `not started`, and `AGENTS.md` forbids inventing a contract.
- **Follow-up tasks:**
  - Approve or amend §3, §5 and §6, then apply the rev.7 requirement edits under separate approval.
  - Regenerate §1 and §3 of `runtime-policy-catalog.md` in the same PR as the rev.7 rule change (governed path, separate approval).
  - Decide Q1–Q6 below.
  - Deferred and needing their own plan: joint-axis decision boundary; `version_name` character-set rule; device API re-derivation from allowlisted fields; prompt size vs. the 2,000-token target; unapproved cost/threshold baselines; traceability that depends on tests that do not exist; an agent-to-agent review in `AI-GArbage823-0001` where `Prompt.md` §2 intends a human gate.
  - Name the frozen `AssessmentEvidence`/`AssessmentResult` contract so the five fixture cases (HIGH/LOW, low success, high risk, UNKNOWN, INVALID) can be written.

**Open questions for the reviewer:**

- **Q1.** Is `CURRENT_ATTEMPT_UNUSABLE` the acceptable mechanism, or should the guard be a sixth `revalidation` column? (A sixth column is cleaner but rewrites all ten rules.)
- **Q2.** Should `DR-10` keep precedence over `DR-08` for an invalidated approval?
- **Q3.** Which environment option: A (pin dependencies) or B (vendor the evaluator)?
- **Q4.** Should `validate_pr5_rev6.py` be retired, kept as a rev.6 artifact, or generalized to be revision-aware?
- **Q5.** Should the §3.4 lexicon live in `prompts/` (build-stage artifact) or in `docs/architecture/runtime-policy-catalog.md`?
- **Q6.** Where is the frozen `AssessmentEvidence`/`AssessmentResult` contract? Until the team names it (an ADR-002.2 draft, a commit, or a transcript), the fixed-response fixture cannot be written without inventing a contract.

**What D2 acceptance changed:** ADR-001 is accepted on Option B, so no new Gradle module will be created and the "no new module" blocker is moot, though this plan authorizes no code. ADR-002.1/002.2 are still `not started`; rev.7 does not depend on them because it changes rule semantics and criteria, not interfaces. `runtime-policy-catalog.md` §1 restates the rev.6 rule table and must be regenerated if those rules change, which is why it is a follow-up task and not work this plan does.

## 8. Reviewer Decision

(To be recorded by the reviewer)

- Verdict: APPROVE / CHANGES REQUIRED / REJECT
- Reviewer:
- Date:
- Reasoning:
- Required follow-up actions:
