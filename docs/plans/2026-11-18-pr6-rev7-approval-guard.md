# PR #6 — Rev.7 Plan: Approval-Rule Guard and Injection Neutralization (D1 Revision)

**Status:** Awaiting human approval for revision 7 requirements edits and for the
new validation script. No `docs/req/` file has been changed.
**Authorized artifacts:** This plan, plus the planned `docs/plans/validate_pr6_rev7.py`.
No other file is authorized.
**Repo state at planning time:** the plan was drafted when local `main` was at
`0f718739` (rev.6 = PR #5, still `draft_for_group_review`). It is now published on
branch `plan/rev7-approval-guard`, based on `origin/arch/c-architecture-design`
at `a3f2bd9c`, where D2 is present: ADR-001 is accepted on **Option B**
(in-tree packages, no new Gradle module) and ADR-002.1–002.5 remain
`not started`. Option B removes the "no new module" blocker in §4 but does
not change this plan's scope: rev.7 is still a requirements-only revision.
See §9 for what D2 acceptance changes.
**Role:** `plan` (per `Prompt.md` §2). This document stops the session.
**Session:** `CS5351-Project` plan session, 2026-11-18.

## 1. Purpose

The rev.6 review in this session found two defects that survive schema and
decision-table validation because the validation script only exercises one input
tuple per rule. This plan fixes exactly two of them and defers the rest to §8.

1. **The `human` column is unchecked while advice is unusable.** `DR-09` and
   `DR-10` match on `advice: ANY` and `evidence: ANY`. Any operation that already
   holds an approval can therefore reach `EXECUTE_ONCE` even when the current
   attempt's evidence is `TRUNCATED`, `UNREADABLE`, or `KEY_UNKNOWN`, its advice
   is `INVALID`, or consent was withdrawn. The safety requirement exists only in
   prose (`FR-005`, `REL-003`); no rule, no negative fit criterion, and no
   invariant enforces it.
2. **`SEC-005`'s injection criterion cannot fail.** `SEC-005` requires
   "neutralize obvious instruction-style text" without defining *obvious*, and its
   fit criterion is satisfied by `DR-07` even if the sanitizer is deleted. That
   violates `verification-constraints.md` §1 ("every invariant has at least one
   negative test that fails when it is violated") and `AGENTS.md` §Verification.

Both defects are requirements-layer. Neither authorizes implementation work;
`Prompt.md` §2 still blocks coding until the plan and its tests are approved.

## 2. Traceability

| Item | IDs touched | Kind of change |
|---|---|---|
| Guard: no execution on unusable current-attempt evidence | `DR-09`, `DR-10`, `FR-005`, `INV-007`, `REL-001`, new `NFC-GRD-001`…`NFC-GRD-004` | rule predicates, fit criterion, negative tests |
| Injection neutralization made testable | `SEC-005`, `FR-004`, new `NFC-INJ-001`…`NFC-INJ-003` | named algorithm, falsifiable criterion |
| Validation of both | `GOV-001`, `VER-001`, new script `validate_pr6_rev7.py` | executable check |

Unchanged and explicitly preserved: `DR-01`…`DR-08` predicates, `DR-11` is not
introduced, `REQ-IX-FR-012` keeps its AI-toggle meaning (`FR-012` is not reused,
following the rev.6 convention), the 41 existing IDs stay, and
`INV-002`/`INV-006` keep their meaning.

Requirement-count effect: **41 → 41 IDs**, plus 7 new negative fit criteria
(`NFC-GRD-*`, `NFC-INJ-*`). No new requirement ID is added, so the
`revision: 6 → 7` bump is the only registry-level identity change.

## 3. Interface Contract

### 3.1 Decision-function inputs

The decision function keeps its five inputs, with one value added to an existing
column. No new column is introduced; adding a sixth column would change every
rule and invalidate the rev.6 review evidence.

```
decide(evidence, advice, policy, human, compatibility) -> (ruleId, decision, disposition, reasonCode)
```

- `advice` gains one keyword: `CURRENT_ATTEMPT_UNUSABLE`. It is produced by
  `AIOutputValidator`/`ApprovalCoordinator`, **not** by the model, and it means:
  "this attempt produced no valid two-axis advice for the current binding"
  (invalid output, structure failure, consent withdrawn mid-attempt, or a
  revalidation that did not re-run).
- Nothing else in the input domain changes. `evidence` keeps its five states,
  `human` its three, `policy` its four keywords, `compatibility` its three.

### 3.2 Output authority (unchanged, restated)

- The AI component contributes only to `advice`. It cannot set `policy`,
  `human`, `evidence`, or `compatibility`. This is the existing
  **advisory-versus-deterministic boundary** in `AGENTS.md`
  §Integration Contracts; the new keyword does not widen it.
- `CURRENT_ATTEMPT_UNUSABLE` must be derived from deterministic signals only
  (validator result, consent state, revalidation result). It must not be
  inferred from model text.

### 3.3 Rule changes (exact)

Only these two rows change; order among `DR-01`…`DR-10` is preserved so rev.6
witness cases still resolve identically for all currently-valid inputs.

| Rule | Current `advice` | Planned `advice` | Everything else |
|---|---|---|---|
| `DR-09` | `ANY` | `NOT_CURRENT_ATTEMPT_UNUSABLE` | unchanged |
| `DR-10` | `ANY` | `NOT_CURRENT_ATTEMPT_UNUSABLE` | unchanged |

`NOT_CURRENT_ATTEMPT_UNUSABLE` is the complement of the new keyword, which keeps
the existing `NOT_HARD_BLOCK` / `VALID_ANY` complement style already used in the
file. The new keyword is then routed by the **existing** `DR-08`
(`INVALID_OR_UNAVAILABLE_OR_BUDGET_REJECTED_OR_OVERAGE_OR_BLOCKED_THIRD`) only if
`DR-08` also names it; see step 2 in §6, which adds it to `DR-08`'s `_OR_` list
so no new rule is needed.

### 3.4 Sanitizer contract (`SEC-005`)

The named algorithm, replacing "obvious instruction-style text":

```
sanitize(text):
  1. reject non-UTF-8 or NUL
  2. strip C0 (U+0000–U+001F except TAB/LF) and C1 (U+0080–U+009F)
  3. NFKC normalize
  4. collapse Unicode whitespace runs (incl. zero-width U+200B–U+200D, U+FEFF)
  5. casefold for matching only (never for output)
  6. neutralize by replacing with `[redacted-instruction]` if any of:
       a. a line matches an imperative-install pattern from a versioned
          lexicon (install/run/allow/approve/ignore/override/execute...)
          within 12 tokens of an object term (this apk, the package,
          validation, policy, signature, risk)
       b. a chat-role marker appears (`system:`, `assistant:`,
          `user:`, `<|im_start|>`, `<|system|>`, or a base64/hex blob >= 512 chars)
       c. a JSON or YAML structure attempt appears inside a scalar field
  7. JSON-escape, cap field at 4 KB, total input at 32 KB, record truncation
```

The lexicon file and its version string are an implementation artifact under
`prompts/` (readable per `permission-model.md` §Read Rights) and are recorded in
the audit alongside the prompt version (`FR-009` already requires model
**and** prompt versions).

The requirements layer states the algorithm and its observable properties only;
the lexicon's *contents* are out of scope for this revision and belong to the
build stage of `SEC-005`.

## 4. Scope Boundaries

### In scope (planned rev.7 edits)

| File | Change |
|---|---|
| `docs/req/requirements.yaml` | `revision: 7`; two `decision_rules` rows; one `_OR_` token in `DR-08`; `FR-005` statement and fit criterion; `SEC-005` statement and fit criterion; `FR-004` fit criterion addition; `INV-007` fit criterion; `REL-001` fit criterion; new `negative_fit_criteria` entries; `notes` update |
| `docs/req/req-schema.json` | `decisionRule.advice`/`evidence`/`human`/`policy` are free-form `\S` strings already, so **no schema change is expected**; the only planned edit is if §6 step 7 proves a constraint is needed |
| `docs/plans/validate_pr6_rev7.py` | new exhaustive validator (see §5) |
| `docs/review-memo.md` | PR #6 entry drafted by the `verify` role, not by this plan |
| `docs/ai-use-log.yaml` | one appended entry (AI-…-00NN) at session close |

### Explicitly out of scope

- Any Kotlin source, test source, Gradle file, or module creation. ADR-001 is
  now accepted on Option B (`a3f2bd9c`), so no new module will ever be created
  for this work; the advisory and policy code lands in in-tree packages under
  `app/src/main/java/com/rosan/installer/ai/` once a build plan is approved.
  Rev.7 does not authorize that build plan.
- `docs/architecture/` artifacts (`invariants.md`, `trace-matrix.md`,
  `runtime-policy-catalog.md`) — they are governed paths needing separate
  approval; §9 records the conflict this creates.
- `outbound_metadata_allowlist` values, including `PMA-OUT-002`…`006` and the
  `version_name` character-set question (deferred, §8).
- Cost and quality thresholds (`PMA-COST-001`…`004`, `AIQ-001` agreement
  threshold, 30-call cohort).
- `AGENTS.md`, `Prompt.md`, `architecture-blueprint.md`, `.opencode/*`,
  `.agent/*`, `.github/*` (human-approval or frozen per `AGENTS.md`
  §Access Scope).

## 5. Testing Strategy

All planned checks are deterministic, offline, and require no network or model
call (`verification-constraints.md` §4).

### 5.1 New executable check: `docs/plans/validate_pr6_rev7.py`

It supersedes nothing; `validate_pr5_rev6.py` stays as the rev.6 record.

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

C5, C6, C7 are the mutation tests: each fails if its protection is removed.

### 5.2 Requirements-level criterion changes

**`NFC-GRD-001` (negative, guard):** With a stored approval valid for the bound
installation object, if the current attempt's evidence is `TRUNCATED`,
`UNREADABLE`, or `KEY_UNKNOWN`, or its advice is `INVALID`, or consent is
withdrawn before revalidation, then the rule is `DR-08` or `DR-03`
(never `DR-09`), the disposition is not `EXECUTE_ONCE`, and backend submissions
are 0. *Fails today:* the current `DR-09` yields `EXECUTE_ONCE` for
`evidence=TRUNCATED, human=VALID_APPROVAL_AND_REVALIDATION_PASSED`.

**`NFC-GRD-002` (negative, revalidation binding):** A `Continue` whose
revalidation observes a changed `apk_sha256`, `target_package_name`,
`target_user_id`, `install_mode`, `installation_backend`, or `policy_version`
yields `DR-10` `INVALIDATED` and 0 submissions. Complements existing
`NFC-OUT-004`, which covers only the target package name.

**`NFC-GRD-003` (positive, required for reachability):** A `Continue` with
`COMPLETE` evidence, `COMPATIBLE` compatibility, valid advice, and a matching
binding yields `DR-09` `EXECUTE_ONCE` with exactly one submission. Prevents the
guard from being "fixed" by deleting human approval.

**`NFC-GRD-004` (state):** `CURRENT_ATTEMPT_UNUSABLE` is set only by the
validator/coordinator and never copied from model output; a response whose text
includes the keyword name does not set it.

**`NFC-INJ-001` (negative, sanitizer is load-bearing):** With the sanitizer
replaced by a pass-through, the `SEC-005` corpus produces at least one changed
sanitized field. *Fails today:* no such test exists, and the current criterion
passes with a pass-through.

**`NFC-INJ-002` (negative, no sanitized text creates authority):** For all 20+
corpus samples, the output state contains no approval and no backend call, and
the audit shows `CURRENT_ATTEMPT_UNUSABLE` when validation rejected the output.

**`NFC-INJ-003` (negative, truncation):** A field exceeding 4 KB and a total
exceeding 32 KB are truncated, recorded, and classified `TRUNCATED` — not
`COMPLETE` — and the disposition for any resulting approval is not
`EXECUTE_ONCE`.

### 5.3 Regression suites to re-run

- `python docs/plans/validate_pr6_rev7.py docs/req` (must print one `PASS` line
  per check C1–C8).
- `python docs/plans/validate_pr5_rev6.py docs/req` — **expected to fail** after
  rev.7 edits, because it asserts `revision == 6`; the plan records this as an
  anticipated, documented failure rather than a regression. If reviewers prefer,
  the C3 replay inside `validate_pr6_rev7.py` replaces it.
- No Gradle, Android, or live-model suite exists yet
  (`verification-constraints.md` §6), so none can be reported as run.

### 5.4 Environment correction (addresses review item 8)

`docs/review-memo.md` instructs `python3 docs/plans/validate_pr5_rev6.py
docs/req`, which is not reproducible on the development machine: `python`
resolves to Python 3.6.1, `jsonschema` is absent on both interpreters found, and
Python 3.6 cannot import the `graphlib` module the script uses. Options, to be
chosen by the reviewer in §7 Q3:

- **Option A (preferred):** add `docs/plans/requirements.txt` pinning
  `jsonschema` and `PyYAML`, read the registry from
  `docs/req/requirements.yaml`, and require Python 3.10+ in a documented
  command. This is a tooling-only change.
- **Option B:** vendor a minimal first-match evaluator in the script and drop
  the `jsonschema` dependency, accepting a weaker schema check.

## 6. Steps

Each step is small enough for a single review. Steps 1–6 are one commit;
steps 7–9 are a second commit.

1. **Bump and record.** Set `revision: 7`, update `notes` to state what rev.7
   changes and that no verification result is claimed.
2. **Rule rows.** Add `CURRENT_ATTEMPT_UNUSABLE` to `DR-08`'s `advice` `_OR_`
   list; change `DR-09` and `DR-10` `advice` to
   `NOT_CURRENT_ATTEMPT_UNUSABLE`. Touch nothing else in `decision_rules`.
3. **`FR-005`.** Add to the statement: a stored approval is evaluated only after
   revalidation against the same binding and the same `policy_version`; a
   current attempt whose advice is `CURRENT_ATTEMPT_UNUSABLE` or whose evidence
   is not `COMPLETE` cannot yield `EXECUTE_ONCE`. Extend the fit criterion with
   the `NFC-GRD-*` cases.
4. **`INV-007` and `REL-001`.** Add the "unusable current-attempt advice never
   executes" clause and its negative case.
5. **`SEC-005`.** Replace "neutralize obvious instruction-style text" with the
   §3.4 algorithm reference and a versioned-lexicon reference; restate the fit
   criterion so that a pass-through sanitizer fails
   (`NFC-INJ-001`), while keeping the existing 20-sample corpus requirement and
   the honest "reduces, does not guarantee" wording.
6. **`FR-004`.** Add: a response containing the literal keyword
   `CURRENT_ATTEMPT_UNUSABLE` (or `NOT_CURRENT_ATTEMPT_UNUSABLE`) is `INVALID`;
   keyword state is never read from model text (`NFC-GRD-004`).
7. **Schema check.** Run C1; only if a new constraint is genuinely needed, edit
   `req-schema.json` in the same commit and note it in the review memo.
8. **Write `validate_pr6_rev7.py`** implementing C1–C8, including the exact
   rev.6 tuple replay.
9. **Run it** and paste the full output into `docs/review-memo.md` as a PR #6
   entry (drafted by the `verify` role, not by this plan).

**Stop point:** steps 1–9 modify governed requirement artifacts and require a
human to approve §2, §3, §5 and then re-approve each `docs/req/` edit.

## 7. Risks and Open Questions

| # | Risk / question | Impact | Proposed handling |
|---|---|---|---|
| R1 | Adding a value to `advice` changes the rule-input domain, so rev.6's "14 first-match cases" no longer describe the full table. | Reviewers may treat rev.6 evidence as superseded. | C4 pins every cell; the memo states the rev.6 replay still passes. |
| R2 | `DR-10` broadens `human:ANY` intent. An approval invalidated by an unrelated attempt could now surface as `DR-08` instead of `DR-10`, hiding the invalidation reason. | Audit clarity. | Keep `DR-10` before `DR-08` in order (unchanged), and add a `reason_code` precedence note; reviewers should confirm they want `INVALIDATED` to win over `ADVICE_UNUSABLE`. |
| R3 | The lexicon in §3.4 is a moving target; any fixed word list invites false positives on legitimate `version_name` values. | `INVALID`/`REQUIRE_REVIEW` over-triggering. | `AIQ-001` already measures per-dimension quality; add a false-positive count to its report (deferred work, noted in §8). |
| R4 | `validate_pr5_rev6.py` will fail after the bump. | CI or reviewers see a red check. | §5.3 records the expected failure; Q3 decides whether to keep, retire, or generalize the script. |
| R5 | `verification-constraints.md` §1 requires the trace matrix to map each requirement to its tests. D2 has now populated `trace-matrix.md` and `invariants.md` (`a3f2bd9c`), but no requirement has an implemented test, so `VER-001` still cannot pass at rev.7. | `VER-001`'s fit criterion cannot pass until the first build plan lands tests. | Out of scope here; rev.7 can now cite the D2 mapping instead of an empty file. |
| R6 | `AGENTS.md` §Reading Order names `Prompt.md` step 0, whose first-message form this session did not use; the `OPENCODE_CONFIG` launch could not be verified from inside the session. | Governance-evidence gap in this transcript. | Recorded in `docs/ai-use-log.yaml` as `AI-wxxsfxyzm-0006`, flagged as agent-authored and unverified; a human must confirm or amend it. |

**Q1.** Is `CURRENT_ATTEMPT_UNUSABLE` the acceptable mechanism, or should the
`safety` guard instead be a sixth `revalidation` column? (A sixth column is
cleaner but rewrites all ten rules.)
**Q2.** Should `DR-10` keep precedence over `DR-08` for an invalidated
approval? (R2)
**Q3.** Which environment option in §5.4: A (pin dependencies) or B (vendor the
evaluator)?
**Q4.** Does the reviewer want `validate_pr5_rev6.py` retired, kept as a rev.6
artifact, or generalized to be revision-aware?
**Q5.** Should §3.4's lexicon live in `prompts/` (build-stage artifact) or in
`docs/architecture/runtime-policy-catalog.md` (merge it into this plan)?

## 8. Deferred (found in the same review, not in this plan)

Recorded here so they are not lost; each needs its own plan and approval.

- **D-1** Joint-axis decision boundary: auto-approval needs `HIGH` **and**
  `LOW`; unknown on one axis is indistinguishable from bad on that axis; all
  `REQUIRE_REVIEW` reasons collapse into one `AWAIT_HUMAN`. Affects `FR-005`,
  `DR-07`, `QUAL-001`, and the UI contract.
- **D-2** `version_name` is the only allowlisted free-text field, with a length
  cap but no character-set rule. Affects the allowlist and `SEC-005`.
- **D-3** `min_sdk`/`target_sdk`/`version_code` plus `platform_compatibility`
  allow device API re-derivation, weakening the `NFC-OUT-006` privacy claim.
- **D-4** Prompt size vs. the 2,000-token 95% target: 200 permission names plus
  version fields can exceed it, making `BUDGET_REJECTED` routine for
  high-permission APKs.
- **D-5** Unapproved baselines: `outbound_metadata_allowlist.status` and
  `PMA-COST-001`…`004` are `proposed_pending_member_a_review`.
- **D-6** Traceability depends on tests that do not exist yet. D2 (`a3f2bd9c`)
  filled `trace-matrix.md` and `invariants.md`, but every mapped test is still
  `planned:`, so `VER-001` remains unsatisfiable until a build plan lands tests.
- **D-7** Process: `ai-use-log.yaml` entry `AI-GArbage823-0001` records a
  second agent (Codex) reviewing and applying another agent's proposal;
  `Prompt.md` §2 intends a human gate there.

## 9. What D2 acceptance changed (added on publication)

The plan was drafted before `origin/arch/c-architecture-design` reached
`a3f2bd9c`. Re-reading it against the accepted D2 documents changes four
things and leaves §2, §3 and §5 unaffected:

1. **ADR-001 is accepted on Option B** — in-tree packages, no new Gradle
   module. §4's "no new module" blocker is therefore moot, but the plan still
   authorizes no code.
2. **ADR-002.2 (AI adapter interface) and ADR-002.1 (policy placement) are
   still `not started`.** Rev.7 does not depend on them, because it changes rule
   semantics and criteria, not interfaces. Any later build plan does.
3. **`runtime-policy-catalog.md` §1 restates the rev.6 rule table** and says it
   "must be regenerated if those rules change." Rev.7 changes `DR-08`…`DR-10`,
   so step 1 gains a companion task: after rev.7 is approved, regenerate §1 and
   §3 of the catalog in the same PR. That file is a governed path and needs its
   own approval, so it is listed as a dependency, not as work this plan does.
4. **The `FixedResponseAdvisor` request could not be satisfied.** A/D asked
   for a fixture implementing the `AssessmentEvidence`/`AssessmentResult`
   contract that C froze. No such type exists in any branch: ADR-002.2 is
   `not started`, and the blueprint only names the advisory output fields
   (`install_success_likelihood`, `security_risk`, reasons, evidence
   references, model ID, prompt version). The fixture is therefore blocked on
   a missing frozen contract, not on missing effort, and it belongs to the
   ADR-002.2 plan. Recorded as **Q6** below.

**Q6.** Where is the frozen `AssessmentEvidence`/`AssessmentResult` contract?
Until the team names it (an ADR-002.2 draft, a commit, or a transcript), the
five required fixture cases — HIGH/LOW, low success, high risk, UNKNOWN,
INVALID — cannot be written without inventing a contract, which `AGENTS.md`
forbids ("stop and ask rather than invent a contract").
