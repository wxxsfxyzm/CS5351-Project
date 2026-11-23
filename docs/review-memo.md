# Review Memo — one entry per pull request

This memo was **recorded retrospectively on 2026-11-23**. During the project,
reviews happened on GitHub (approvals and review comments), inside agent
sessions (the lead's review of OpenCode plans and builds), and in the review
assistant session. They were not collected here at the time. Each entry below
is reconstructed from those sources, and each one names its source.

Where a review left no written finding, the entry says so instead of
supplying one.

## How to read an entry

Every entry follows the D4 headings:

- **Spec**: alignment with the requirements.
- **Arch**: alignment with the ADRs and architecture artifacts.
- **Code & tests**: code and test quality, with results.
- **Security & efficiency**: what was checked on those two axes.
- **Metadata**: author, reviewer, merge date, plan, AI-use log, CI.
- **Verdict**: APPROVE, CHANGES REQUIRED, or REJECT. Earlier rounds are listed
  before the final verdict.

The lead's own PRs were merged by the lead. For those PRs the review was done
in the agent sessions and by the review assistant (Claude Code), as recorded
in `transcripts/wxxsfxyzm-supervision.md`.

**Sources:**

- GitHub PR data and reviews (fetched 2026-11-23);
- `docs/ai-use-log.yaml`;
- `docs/plans/`;
- `transcripts/`;
- `docs/bug-diary.md`, `docs/debt-register.md`.

---

## Requirements, architecture, and governance

### PR #2 — establish PR review governance and default code owner
- **Spec:** REQ-IX-GOV (human review). Adds `.github/pull_request_template.md`
  with the human-approval and test-approval sections, and `CODEOWNERS`.
- **Arch / Code & tests:** governance only; no code.
- **Security & efficiency:** `CODEOWNERS` routes every change to the lead;
  frozen afterwards.
- **Metadata:** `wxxsfxyzm`, merged 2026-11-16.
- **Verdict:** APPROVE.

### PR #3 — fix filename and test PR template
- **Spec / Arch:** renames four QA record files to the course names; no
  content change.
- **Metadata:** `wxxsfxyzm`, merged 2026-11-16. Also exercised the new PR
  template.
- **Verdict:** APPROVE.

### PR #4 — restructure agent governance for OpenCode v1
- **Spec:** D3 deliverables: `AGENTS.md`, `Prompt.md`, `.agent/rules.md`,
  `.opencode/permissions.jsonc`, `docs/code-governance/`.
- **Arch:** three-layer context: a short `AGENTS.md`, rules loaded on demand,
  and a session prompt.
- **Review history:** in session, the lead checked the slimmed `AGENTS.md`
  against the course PDF and found seven missing D3 items. The lead also
  corrected the planned OpenCode v2 permission format to v1.
- **Security & efficiency:** default-deny path whitelists per role;
  `git push` denied.
- **Metadata:** `wxxsfxyzm`, merged 2026-11-16. Transcript A;
  AI-wxxsfxyzm-0001 to 0005.
- **Verdict:** CHANGES REQUIRED (in session) → APPROVE.

### PR #1 — review D1 requirements registry and JSON Schema
- **Purpose:** a review aid. It targeted the pre-upload version so that the
  new registry and schema could be read line by line. Its body records local
  validation: 41 requirements, 10 decision rules, 7 first-match cases.
- **Metadata:** `GArbage823`, closed 2026-11-16 without merge; the review
  continued on PR #5.
- **Verdict:** REJECT (not for merge; superseded by #5).

### PR #5 — D1 rev.6: pre-install compatibility and security assessment
- **Spec:** revises D1 in response to seven review items. It separates install
  success likelihood from security risk, and distinguishes a preflight
  rejection (zero model calls) from a response that exceeded the budget. All
  41 requirement IDs are kept.
- **Arch:** gave the ADD stage a stable baseline.
- **Code & tests:** requirements only. The validation script
  `docs/plans/validate_pr5_rev6.py` passed: Draft 2020-12 schema, 41 unique
  IDs and dependency graph, 14 first-match cases, and three invalid fixtures
  rejected. `git diff --check` was clean. No Android or live-model tests were
  run.
- **Review (GitHub, lead):** "Review Verdict: APPROVE … The separation
  between installation success likelihood and security risk is clear, and the
  deterministic decision rules correctly distinguish automatic continuation,
  human review, and hard prohibitions." Noted that AutoNotification is
  Should-level, so the Must-level acceptance criteria must not require it.
- **Security & efficiency:** outbound allowlist and cost thresholds kept as
  proposals, to be finalised during design.
- **Metadata:** `GArbage823`; approved and merged by `wxxsfxyzm` on
  2026-11-16. Plan `2026-11-16-pr5-rev6-revision.md`; AI-GArbage823-0001;
  Transcript B.
- **Verdict:** CHANGES REQUIRED (seven review items) → APPROVE.

### PR #6 — architecture design (D2)
- **Spec:** populates the D2 artifacts against approved D1: blueprint,
  ADR-001/002, invariants, runtime policy catalog, memory design, permission
  model, trace matrix, evaluation table, option ledger, logbook, and risk list.
- **Arch:** ADR-001 was first drafted as a proposal recommending Option A, a
  separate Gradle module.
- **Review (GitHub, lead):** "I suggest selecting Option B for ADR-001 … keep
  the implementation within the existing `app` module … The AI advisor only
  returns structured recommendations and has no access to installation
  backends. The deterministic policy engine remains independent of model SDKs
  and network operations. The approval coordinator controls the transition
  from assessment to installation submission." Member C applied the decision
  across the D2 documents.
- **Security & efficiency:** the authority separation (advisory, authoritative,
  human) is kept, enforced through interfaces, review, and tests.
- **Metadata:** `yipu02`; approved and merged by `wxxsfxyzm` on 2026-11-18.
  Plan `2026-11-17-d2-architecture-population.md`; AI-memberC-0001 and 0002.
- **Verdict:** CHANGES REQUIRED (ADR-001 option) → APPROVE.

### PR #20 — sync ADR-002 and the blueprint with merged PRs
- **Spec / Arch:** brings ADR-002.1, 002.2, and 002.5, and blueprint sections
  3, 3.1, 10, and 11, up to date with the code merged in #16, #18, and #19.
  Corrects ADR-002.5 to "partially implemented" (gate wired only in tests).
- **Code & tests:** documentation only; zero changes under `app/`.
- **Review:** approved on GitHub with no written comment.
- **Metadata:** `yipu02`; approved and merged by `wxxsfxyzm` on 2026-11-21.
  AI-memberC-0003.
- **Verdict:** APPROVE.

### PR #31 — narrow REQ-IX-ARCH-001 to AI-evaluated requests for v1
- **Spec:** REQ-IX-ARCH-001 statement and fit criterion revised, with a dated
  note. Decision Q9 in the AI advisor plan.
- **Arch:** ADR-002.6 gains "v1 scope of the choke point", with the reason and
  the conditions for reopening it.
- **Review history:** the conflict was found in the lead's review of plan
  rev.1 (an advisor-off bypass). The agent recorded it as a requirement
  conflict instead of choosing a bypass, and the lead decided option (b).
- **Code & tests:** schema gate passed (41 requirements).
- **Metadata:** `wxxsfxyzm`, merged 2026-11-22, before #30 by design.
  AI-wxxsfxyzm-0014.
- **Verdict:** APPROVE.

---

## CI and agent tooling

### PR #17 — fix PR CI
- **Spec:** REQ-IX-VER-001 and GOV: PR checks run on every PR, including
  stacked PRs (#16 based on #13).
- **Code & tests:** removes the upstream dev-only target restriction from
  `pr-check.yml`; keeps format, unit tests, and the Debug APK build.
- **Review history:** OpenCode session `ses_e06534…`. The lead paused the plan
  when the baseline was stale, approved the diff with an execution order
  (disable release workflows first, `git apply --check` first). The lead
  applied the patch. A Copilot review was requested but did not run because
  of quota.
- **Metadata:** `wxxsfxyzm`, merged 2026-11-20. Plan
  `2026-11-21-ci-workflow-tidy.md`; AI-wxxsfxyzm-0006.
- **Verdict:** APPROVE.

### PR #24 — add `project-plan` agent for the OpenCode plan stage
- **Spec / Arch:** the built-in plan agent could not write `docs/plans/`
  (BUG-001). Adds a dedicated `project-plan` agent and updates the permission
  model.
- **Security & efficiency:** a permission probe found that shell redirects
  slip past the path rules for `build` and `verify` (BUG-002, DEBT-001).
- **Metadata:** `wxxsfxyzm`, merged 2026-11-21. AI-wxxsfxyzm-0007.
- **Verdict:** APPROVE (DEBT-001 open).

### PR #26 — CS5351 blocking gates and course compliance gaps
- **Spec:** D7 gates: test approval, lint and type check, safety-path checks,
  permission check, schema, semantic evaluation, regression, AI-use log. Also
  prompt sync. Closes compliance gaps found by checking the repository against
  the course PDF (README, prompts, ADR context engineering, invariant
  fallbacks).
- **Code & tests:** `.github/workflows/ci.yml`, `.github/scripts/course_gates.py`.
- **Review finding (later):** the semantic gate's live step still checks
  `GROQ_API_KEY`, not the `AGENT_*` variables of the course service. It is
  open in Report §7.
- **Metadata:** `wxxsfxyzm`, merged 2026-11-21. AI-wxxsfxyzm-0009.
- **Verdict:** APPROVE (one follow-up open).

---

## Implementation

### PR #13 — AI advisory interfaces (closed; content landed through #16)
- **Spec:** an AI advisor over allowlisted evidence, strict fixed-format
  parsing, and classified failures (REQ-IX-FR-003, FR-004, INV-004).
- **Review (lead's architecture review, relayed in Member E's session S2):**
  four blocking items:
  - **E-1 (High, privacy):** `device_api_level` was transmitted, although it
    is a `local_only` allowlist entry (REQ-IX-SEC-004, NFC-OUT-006).
  - **E-2:** isolation test did not cover `data/ai`.
  - **E-3:** the API key was compiled into `BuildConfig`.
  - **E-4:** the five context fields were missing.
- **Fix:** all four fixed on `fix/pr13-architecture-review`, with a
  regression guard `request_neverContainsDeviceApiLevel`.
- **Metadata:** `yuki11111111111`; closed 2026-11-21. Its commits were carried
  into #16, which combined the advisory code with the policy engine.
  AI-yuki11111111111-0002; plan `2026-11-19-pr13-architecture-review-fixes.md`.
- **Verdict:** CHANGES REQUIRED → fixed; merged as part of #16.

### PR #16 — deterministic install policy engine and decision tests
- **Spec:** DR-01 to DR-10 as a pure function; D1 decision-table tests
  (REQ-IX-FR-005, INV-001, INV-002).
- **Arch:** ADR-001 Option B; policy has no model SDK or network dependency
  (dependency boundary test).
- **Code & tests:** first run failed only on Spotless; formatting fixed, then
  the full re-run passed (259 tests, 0 failures; this batch 6). Both Debug
  APKs built.
- **Review:** approved on GitHub with no written comment. Plan and tests were
  approved in Member B's session ("开始吧" / *"go ahead"*).
- **Metadata:** `GArbage823`; approved and merged by `wxxsfxyzm` on
  2026-11-20. Plan `2026-11-20-pr13-policy-engine.md`; AI-GArbage823-0002.
- **Verdict:** APPROVE.

### PR #18 — local install evidence mapper for single-APK AutoDialog
- **Spec:** maps the selected APK and analysis to `InstallEvidence`; unknown
  stays UNKNOWN (REQ-IX-FR-002, INV-006).
- **Review (in Member B's session):** two decisions:
  - the production analysis does not fill `fileHash`, so a caller-verified
    SHA-256 fallback is used and contradictory digests are rejected;
  - a null installed-app lookup does not prove absence for the target user,
    so affirmative confirmation is required before NEW_INSTALL.
- **Code & tests:** 276 tests, including 17 mapper tests, 0 failures; both
  APKs built.
- **Later evidence:** on a device, the caller never supplied the hash or the
  facts, so the evidence was UNREADABLE and DR-05 was unreachable (BUG-006,
  BUG-007, fixed in #33). The mapper's contract was correct; the wiring did
  not use it.
- **Metadata:** `GArbage823`; approved and merged by `wxxsfxyzm` on
  2026-11-20. Plan `2026-11-20-autodialog-install-evidence.md`;
  AI-GArbage823-0003.
- **Verdict:** APPROVE.

### PR #19 — gate AutoDialog model requests on current consent
- **Spec:** default-deny, request-scoped consent read before building the
  request and again before dispatch (REQ-IX-FR-011, SEC-004).
- **Code & tests:** 266 tests, including 7 gate tests, 0 failures. Real-device
  and live calls were not run.
- **Later evidence:** no production code installed the gate, so the model was
  never called on a device (BUG-008, fixed in #33). ADR-002.5 had recorded the
  gap.
- **Metadata:** `GArbage823`; approved and merged by `wxxsfxyzm` on
  2026-11-20. Plan `2026-11-21-autodialog-model-egress.md`; AI-GArbage823-0004.
- **Verdict:** APPROVE.

### PR #27 — approval coordinator, binding, and installation gateway
- **Spec:** REQ-IX-FR-005/006/007, INV-001/003/005, REL-002/003/004.
- **Review history (OpenCode `ses_e01b23…`):**
  - **Plan round.** The lead required revalidation to re-read facts through a
    `BindingFactsProvider` instead of reusing the first read (INV-003), and
    test fakes to live only in `app/src/test`.
  - **Build round.** `autoSubmit` trusted a caller-supplied `PolicyResult`, so
    a caller could forge EXECUTE_ONCE and bypass the engine. Fixed to load the
    persisted decision, persist before submitting, and make a failed submit
    terminal, with five negative tests.
- **Security & efficiency:** single-use submission permit; binding covers the
  APK hash, package, user, backend, config, and policy version.
- **Metadata:** `wxxsfxyzm`, merged 2026-11-21. Plan
  `2026-11-21-approval-coordinator-binding-gateway.md`; AI-wxxsfxyzm-0008 and
  0010.
- **Verdict:** CHANGES REQUIRED (twice) → APPROVE.

### PR #28 — record deferred ADR-002.3 persistence slice
- **Spec / Arch:** plan rev.3 was approved with conditions. The build stopped
  at step 2 under the plan's own stop rule: Robolectric aborts on JDK 25,
  macOS arm64. The lead deferred the slice and did not apply the fallback.
- **Code & tests:** no code; plan §0.2, BUG-003, DEBT-003.
- **Metadata:** `wxxsfxyzm`, merged 2026-11-22. AI-wxxsfxyzm-0011 and 0012.
- **Verdict:** APPROVE (records only; REL-002/003/004 remain Partial).

### PR #29 — offline fixed-response advisor and hardened advisory path
- **Spec:** `FixedResponseAdvisor` for offline integration tests. Fixes:
  - G-1: the validator is total, including rejecting extra keys (INV-004);
  - G-3: a contradictory prompt line removed;
  - G-4: adapter contract test (MOD-001).
- **Review (review assistant, before merge):**
  - the user prompt changed without a new prompt version, against the prompt
    change policy, so v1.2 was added;
  - AI-use log conflicts with main were resolved.

  Both were fixed on the author's branch by the lead.
- **Code & tests:** 423 tests, 0 failures. Live course suite 8/10 usable at
  the threshold.
- **Metadata:** `yuki11111111111`; approved and merged by `wxxsfxyzm` on
  2026-11-22. AI-yuki11111111111-0003 to 0006; AI-wxxsfxyzm-0015.
- **Verdict:** CHANGES REQUIRED (prompt version) → APPROVE.

### PR #30 — AI install-risk advisor end to end on the course DeepSeek service
- **Spec:** FR-001/002/003/005/006/007/008/011/012, ARCH-001 (as narrowed in
  #31), INV-001 to 007, SEC-004/005.
- **Review history:**
  - **Plan.** rev.1 rejected: an advisor-off bypass, global facts source, and
    unclear trigger. rev.3 approved with tests.
  - **Build.** Rejected three times:
    - fail-open `session.install` fallbacks, gate-withheld cases bypassing the
      engine, hard-coded policy, and whole-file allowlists in
      `ChokePointTest`;
    - an uninstall-retry bypass;
    - the retry reusing a spent operation ID, plus a fall-through to a direct
      install.
  - **Verification.** A vacuous live test (10/10 network errors yet PASS) was
    rejected (BUG-004).
- **Code & tests:** 376 tests, 0 failures; all gates passed; no credential in
  the diff.
- **Security & efficiency:** key stored on first enable in a private DataStore
  (DEBT-004); one model call per operation (COST-001).
- **Metadata:** `wxxsfxyzm`, merged 2026-11-22 after #31. Plan
  `2026-11-22-ai-advisor-end-to-end-slice.md` §13; AI-wxxsfxyzm-0013.
- **Verdict:** CHANGES REQUIRED (plan ×1, build ×3, test ×1) → APPROVE.

### PR #25 — D1 rev.6 test plan and verification suites
- **Spec:** test plan P-01 to P-22, C-01 to C-16, A-01 to A-10, S-01 to S-04;
  verification suites.
- **Review (review assistant):** six of eight suites tested a 691-line copy of
  the engine and coordinator inside the test folder. They would stay green
  whatever the production code did, and they followed a pre-#27 design. The
  two production-wired suites were kept (`AiPolicyIntegrationTest`,
  `PolicyWithoutModelTest`); the copies were removed and recorded as DEBT-002.
  A duplicate AI-use log ID was renumbered.
- **Code & tests:** 436 tests, 0 failures after merging with main.
- **Metadata:** `Eugene12321`; approved and merged by `wxxsfxyzm` on
  2026-11-22. Member D's two AI-use log entries; AI-wxxsfxyzm-0016 and 0017.
- **Verdict:** CHANGES REQUIRED → APPROVE.

### PR #33 — make the AI advisor work end to end on a device
- **Spec:** fixes BUG-005 to BUG-010 found on a real device; adds two outbound
  allowlist fields and prompt v1.3 (lead-approved); ADR-002.2/002.5 notes.
- **Code & tests:** 463 tests, 0 failures. Each fix has a negative control
  that fails on the old code. Live v1.3: 8/10 usable; update-relative
  scenarios rated LOW; high-risk controls stayed HIGH. On the device, a
  calculator reinstall reached DR-05 and installed.
- **Security & efficiency:** whole-call timeout of 7 s, cancellable call, 12 s
  attempt budget routed to DR-08 (PER-001); BLOCK causes shown; no relaxation
  of the deterministic rules.
- **Process note:** the PR records that tests for BUG-005, 008, 009, 010 and
  the UI fixes were written with the fix at the lead's direction, not approved
  before implementation.
- **Metadata:** `wxxsfxyzm`, merged 2026-11-23. AI-wxxsfxyzm-0019; DEBT-006.
- **Verdict:** APPROVE (test-approval deviation recorded).

---

## Records, transcripts, and report

### PR #14, #15 — Member E transcripts
- **Metadata:** `yuki11111111111`, merged 2026-11-21. Raw session exports.
  Consolidated on 2026-11-23 into `transcripts/memberE-yuki11111111111.md`,
  and the raw files were removed (AI-wxxsfxyzm-0020).
- **Verdict:** APPROVE.

### PR #22 — Member E AI-use log entries
- **Metadata:** `yuki11111111111`, merged 2026-11-21. Adds entries for the
  #14 and #15 sessions.
- **Verdict:** APPROVE.

### PR #32 — record BUG-004, DEBT-004, DEBT-005
- **Review (review assistant):** DEBT-004 claimed the key leaks "through an
  app-data backup". The manifest sets `allowBackup="false"` and excludes every
  domain, so the claim was removed before the PR. The error came from the
  instruction, not from the verify agent.
- **Metadata:** `wxxsfxyzm`, merged 2026-11-23. Verify session `ses_dfc354…`;
  AI-wxxsfxyzm-0018.
- **Verdict:** CHANGES REQUIRED (in session) → APPROVE.

### PR #34 — Member B's requirements review transcript
- **Metadata:** `GArbage823`; approved and merged by `wxxsfxyzm` on
  2026-11-23. Updates two evidence links.
- **Verdict:** APPROVE.

### PR #37 — lead's curated supervision transcript
- **Metadata:** `wxxsfxyzm`, merged 2026-11-23. Replaces a raw export with an
  edited record of interventions, following the course guidance.
- **Verdict:** APPROVE.

### PR #39 — Member B reflection in the report
- **Review:** facts checked against the repository: seven PR #5 items,
  14 first-match cases, 6/17/7 tests, BUG-006/008/009. 388 words; no personal
  data in the text.
- **Metadata:** `GArbage823`; approved and merged by `wxxsfxyzm` on 2026-11-23.
- **Verdict:** APPROVE.

---

## Closed without merge

| PR | Author | Reason | Verdict |
|---|---|---|---|
| #7–#11 | `yuki11111111111` | Plan and transcript upload attempts (#7 has an unfilled template); the transcripts were later merged as #14 and #15 | REJECT (superseded) |
| #12 | `yuki11111111111` | First advisory PR, reopened as #13 on the same branch | REJECT (superseded) |
| #21, #23 | `yuki11111111111` | AI-use log entries with wrong IDs and labels; replaced by #22 | REJECT (superseded) |
| #35 | `yuki11111111111` | Transcript swap; consolidated by the lead into a curated file instead (2026-11-23) | REJECT (superseded) |
| #36 | `yuki11111111111` | Empty-template duplicate of #29 | REJECT (duplicate) |
| #38 | `yipu02` | Based on a commit removed from main for personal data; its transcript was taken into `transcripts/memberC-yipu02.md` | REJECT (superseded) |
