# CS5351 Project Report — InstallerX Revived with an AI Install-Risk Advisor

> **Status: framework draft.** Sections marked **[TO FILL]** need input from
> the named member. Facts in the other sections come from the repository
> (PRs, `docs/ai-use-log.yaml`, `docs/bug-diary.md`, `docs/debt-register.md`,
> `docs/plans/`, transcripts) as of `main` on 2026-11-23. Check them before
> submission. Delete this box when done.

## 0. Team and relative contribution

### 0.1 Members

| Member | GitHub | Main responsibility |
|---|---|---|
| A | `wxxsfxyzm` | Project lead: governance (D3), CI blocking gates (D7), approval coordinator and gateway, AI advisor end-to-end integration, on-device validation |
| B | `GArbage823` | Requirements registry D1 (rev.6), deterministic policy engine, evidence mapper, consent-scoped model request gate |
| C | `yipu02` | Architecture artifacts D2: ADR-001/002, invariants, permission model, runtime policy catalog, blueprint sync |
| D | `Eugene12321` | Verification: D1 rev.6 test plan, offline verification suites |
| E | `yuki11111111111` | AI advisory path: strict output parsing, fixed-response advisor, failure-escalation tests |

Names and student IDs are omitted because the repository is public. They are
given in the submitted copy of this report. **[TO FILL in the submitted copy
only]**

Member E's work overlapped with the lead's AI-advisor scope. E implemented
the lead's review fixes for PR #13, and E's fixed-response advisor (PR #29)
was integrated into the lead's end-to-end slice (PR #30). E's first two AI-use
log entries (AI-yuki11111111111-0001 and 0002) were labelled "Member A"; the
label was corrected to Member E on 2026-11-23, confirmed by the lead.

### 0.2 Relative contribution

The course asks for contribution weights with a mean of 1.0. The team agreed
to measure effort in productive hours per sprint.

| Member | Sprint 1 (to 11-16) | Sprint 2 (11-17 to 11-19) | Sprint 3 (11-20 to 11-21) | Sprint 4 (11-22 to 11-23) | Total hours | Weight |
|---|---|---|---|---|---|---|
| A | **[TO FILL]** | | | | | |
| B | **[TO FILL]** | | | | | |
| C | **[TO FILL]** | | | | | |
| D | **[TO FILL]** | | | | | |
| E | **[TO FILL]** | | | | | |

Weight = member total / mean total.

Repository evidence the team can use when agreeing the hours. These are
counts, not weights:

| GitHub | PRs merged | AI-use log entries | Transcript |
|---|---|---|---|
| `wxxsfxyzm` | 13 (#2–#4, #17, #24, #26–#28, #30–#33, #37) | 19 | `wxxsfxyzm-supervision.md`, `transcriptA-wxxsfxyzm.md` |
| `GArbage823` | 6 (#5, #16, #18, #19, #34, #39) | 4 | Member B transcript |
| `yipu02` | 2 (#6, #20) | 3 (`AI-memberC-*`) | Member C transcript |
| `Eugene12321` | 1 (#25) | 2 | Member D transcript |
| `yuki11111111111` | 4 (#14, #15, #22, #29) | 6 | Member E transcripts |

---

## 1. Project overview

**What we built.** InstallerX Revived is an open-source Android package
installer. We extended it with an **AI install-risk advisor**. When a user
opens a single APK in the AutoDialog install mode, the app derives a
non-identifying evidence set from the APK and the device: version, SDK
levels, declared permissions, signature verification, installed-signature
match, install relation, and compatibility. It sends only allowlisted fields
to the course model `CS5351/DeepSeek-V4-Flash`. The model returns a
fixed-format advisory: install-success likelihood, security risk, reasons,
and evidence references.

**The safety boundary.** The AI is advisory only. A **deterministic policy
engine** decides every install using ten ordered decision rules, DR-01 to
DR-10. A hard prohibition such as a signature mismatch always blocks,
whatever the model says. An `ApprovalCoordinator` is the single path to the
installer backend. It binds each approval to the exact APK hash, package,
user, backend, and configuration, re-reads those facts before submitting,
and submits at most once per operation. The relevant requirements are
REQ-IX-ARCH-001 and INV-001 to INV-007.

**Outcomes the user sees:**

- **Auto-install.** A verified, compatible, low-risk install or update goes
  ahead automatically (DR-05).
- **Review.** Anything else waits for the user's review (REQUIRE_REVIEW), and
  the card explains why.
- **Block.** A signature mismatch or a failed signature verification is
  blocked, and the cause is shown.
- **AI unavailable.** If the model fails, times out, or consent is off, the
  install falls back to review. It never auto-approves.

**Where things are.**

- Architecture: `architecture-blueprint.md`, `docs/architecture/`. ADR-001
  selects Option B: in-tree packages inside the existing `app` module.
- Requirements: `docs/req/requirements.yaml`, 41 requirements: 34 Must and 7
  Should; 12 functional, 7 invariants, 5 security, and 17 other.
- Source: `app/src/main`. Tests: `app/src/test`, 463 unit tests.
- Runtime prompts: `prompts/`, v1.0 to v1.3.

---

## 2. Our software engineering process

### 2.1 Lifecycle

| Stage | What happened | Main artifacts |
|---|---|---|
| Requirements | A machine-readable registry with fit criteria, MoSCoW priorities, and a JSON schema validated in CI; revised to rev.6 after review | `docs/req/`, PR #5 |
| Architecture | Candidate options evaluated (`evaluation-table.md`); ADR-001 chose Option B; ADR-002 recorded later decisions; invariants, permission model, runtime policy catalog | `docs/architecture/`, PRs #6, #20 |
| Coding governance | `AGENTS.md` as the single agent entry point; `Prompt.md` as the launch prompt; OpenCode roles `plan`/`build`/`verify` with per-path permissions | PRs #4, #24 |
| Planned slices | Each feature started as a plan in `docs/plans/` with traceability, interface contract, scope, and test strategy; the lead approved the plan and its tests before any code | 10 plans |
| Build | OpenCode `build` agent, checkpoint by checkpoint, each ending with format, compile, and unit tests green | PRs #16, #18, #19, #27, #29, #30 |
| Verification | `verify` agent and human review; QA records in the bug diary and debt register | PRs #25, #28, #32 |
| On-device validation | The lead ran the real app and fixed what unit tests could not see | PR #33 |

### 2.2 Agent governance

- **Entry point.** `AGENTS.md` is short on purpose. It holds the build target,
  reading order, baseline commit, access scope, integration contracts, and
  critical constraints. Detailed rules load on demand from
  `docs/code-governance/`. We chose this three-layer structure because the
  original 18 KB file diluted the safety rules (Transcript A).
- **Launch.** `Prompt.md` is injected through OpenCode `instructions`. The
  first message of every session must name the role, task, related IDs, and
  approved inputs. The prompt makes the agent plan first and stop for human
  approval.
- **Roles and permissions.** `.opencode/permissions.jsonc` gives each role a
  default-deny file whitelist:
  - `project-plan` writes only `docs/plans/`;
  - `build` writes source, tests, and the AI-use log, and must ask before
    changing Gradle files or committing;
  - `verify` writes the QA records;
  - `git push` is denied for every role.

  The lead tested enforcement with a permission probe (Section 3.2).
- **Human-only files.** `.opencode/rules.md`, `.github/CODEOWNERS`, signing
  configuration, and release workflows are frozen. `docs/req/`,
  `docs/architecture/`, and `.github/workflows/` change only with human
  approval.

### 2.3 Pull requests and CI blocking gates

Every change went through a task branch and a PR. Agents never pushed to main.
`.github/workflows/ci.yml` implements the six gates required by the course:

| Course gate | Implementation |
|---|---|
| 1. Test approval | The PR body must answer "Were the relevant tests approved before implementation? Yes" and cite approval evidence; code-only changes are checked |
| 2. Static analysis | Spotless/ktlint, Kotlin compile as a type check, version-catalog dependency check; for safety-relevant paths also boundary, contract, and permission checks |
| 3. Schema | `requirements.yaml` validated against `req-schema.json` |
| 4. Semantic evaluation | For prompt or model changes, a fixed offline evaluation set must pass its thresholds; the live suite runs only with credentials |
| 5. Regression | The full offline unit suite on every PR |
| 6. AI-use log | A PR that changes code or prompts without a log entry is blocked |

Also: a prompt-sync check (the prompt file must match the code constant) and
branch protection. **[TO FILL: confirm the branch-protection settings and the
required checks in GitHub.]**

---

## 3. How we optimised the process

Each change below responded to a problem we observed.

### 3.1 From a long rule file to layered context
The first `AGENTS.md` mixed course rules with about 18 KB of upstream
engineering rules. We split it so that only global rules load every time, then
checked it against the course PDF. That check found that the agent's slimmed
version had dropped seven items D3 requires, so they were restored (PR #4).

### 3.2 Testing the guardrails instead of assuming them
OpenCode's built-in `plan` agent could not write plan files (BUG-001), so we
added a dedicated `project-plan` agent (PR #24). We then ran a permission
probe: seven actions, each expected to be allowed or denied. It showed that a
shell redirect such as `git status > file` slips past the path rules in the
`build` and `verify` roles (BUG-002, DEBT-001).

### 3.3 Plans with explicit stop rules and timeboxes
Plans included stop rules ("if X, stop and report"). The persistence slice had
a timebox: if Robolectric could not run Room under the project JDK, stop and
report rather than switch approach. That is exactly what happened. Robolectric
aborted (exit 134) on JDK 25 under macOS arm64. The agent stopped and reverted
its changes, and the lead deferred the slice with a recorded reason (BUG-003,
DEBT-003, PR #28). The stop rule kept us from spending the remaining time on
an environment problem.

### 3.4 Checkpointed builds and gated live tests
The AI slice was built in six checkpoints (0, A–E), each ending green.
Checkpoint 0 was a go/no-go on the real course service. The lead measured the
endpoint first: chat/completions with JSON mode works, and `/v1/responses` is
not suitable. The live test was made to fail unless at least 8 of 10 fixtures
returned a usable assessment, after an earlier version passed while proving
nothing (BUG-004).

### 3.5 A second agent as a reviewer
For the lead's slices, a second agent (Claude Code) read each OpenCode plan
and build report against the course PDF and the repository rules, then drafted
the next instruction for the lead to send. This caught design-level defects
before code existed. For example, a plan that let the advisor-off path bypass
the approval coordinator was turned into an explicit requirement conflict
(Q9). It also caught defects that green tests hid, such as fail-open fallbacks
and an uninstall-retry bypass (`wxxsfxyzm-supervision.md`, stage 7).

### 3.6 Testing the real app
Unit tests passed while the feature never worked on a phone. Running the app
on a device found five defects in one evening, BUG-005 to BUG-009. Since then,
every fix carries a test that fails on the old code. The missing automated
on-device test is recorded as DEBT-006.

### 3.7 Team-level process

**Requirements review (PR #5).** The lead raised seven points. Member B had
OpenCode map each one to a requirement and an acceptance case, then authorised
saving the plan. Edits to the registry followed once Member B approved the
plan and its tests. We updated the registry and schema together and kept all
41 IDs. All 14 decision cases passed, and the schema rejected all three invalid
fixtures ([review memo](docs/review-memo.md)). The policy engine, evidence
mapper and model request gate each had a separate plan and test scope for
Member B to approve (PRs #16, #18 and #19).

**[TO FILL: C, D, E: how your part of the process worked or was improved,
including architecture review fixes and the verification test plan.]**

---

## 4. Process quality evidence

### 4.1 Requirement volatility

| Date | Change | Trigger | PR |
|---|---|---|---|
| 11-14 to 11-16 | rev.3 registry, consolidated YAML and schema in rev.4, English requirements with a proposed outbound allowlist and token budget in rev.5 | Group review of which APK fields could leave the device and how to count tokens | [rev.3](https://github.com/wxxsfxyzm/CS5351-Project/commit/6bd3dbf8), [rev.4](https://github.com/wxxsfxyzm/CS5351-Project/commit/54072d0e), [rev.5](https://github.com/wxxsfxyzm/CS5351-Project/commit/a9e237f6) (PR #5) |
| 11-16 | rev.6: two independent advisory axes, mode priorities, compatibility folded into FR-002/FR-005, completeness-aware evidence, separate attempt and dispatch accounting, corrected Continue/Retry | Seven review items on PR #5 | #5 |
| 11-22 | REQ-IX-ARCH-001 narrowed for v1 to AI-evaluated requests; AI-off keeps the baseline path | Plan review found that the advisor-off path cannot go through the coordinator without a new decision rule (Q9) | #31 |
| 11-23 | Two optional outbound fields: `permissions_added_since_installed`, `installed_is_system_app` | On-device runs rated routine updates of permission-heavy apps MEDIUM/HIGH, so DR-05 was never reached | #33 |

All 41 requirement IDs were stable from rev.5 onward. Changes refined
statements and fit criteria rather than adding or removing requirements. Each
change carries a dated note in the registry and a matching ADR note.

**Approval status.** The lead approved rev.6 as the D1 baseline in the PR #5
review on 2026-11-16, but the registry kept its `draft_for_group_review`
label. On 2026-11-23 the record was brought in line with what had happened:

- the registry is marked `approved_baseline`, citing the PR #5 review;
- the outbound allowlist is marked `approved`, and its five open proposals,
  which had shipped unchanged, are recorded as approved;
- each requirement's status was reviewed against code and tests:
  21 `implemented`, 19 `conceptual` (partial or not started), 1 `future`.
  Every one carries a dated note naming its evidence or its gap.

The COST-001 token thresholds are still pending, because the token preflight
is not implemented.

### 4.2 Defect trends

| ID | Found | Phase | Class | How found |
|---|---|---|---|---|
| BUG-001 | 11-21 | Governance | Tooling | Plan agent could not save a plan |
| BUG-002 | 11-21 | Governance | Guardrail gap | Permission probe |
| BUG-003 | 11-22 | Build | Environment | Stop rule in the persistence plan |
| BUG-004 | 11-22 | Verification | Vacuous test | Lead review of a green live run |
| BUG-005 | 11-23 | Device | Wiring (DI) | Crash on device |
| BUG-006 | 11-23 | Device | Data | Every APK judged UNREADABLE |
| BUG-007 | 11-23 | Device | Data | DR-05 never reached |
| BUG-008 | 11-23 | Device | Wiring (consent gate) | Model never called |
| BUG-009 | 11-23 | Device | Transport | Every reply failed with EOF |
| BUG-010 | 11-23 | Device / review | Requirement gap (PER-001) | Lead asked about timeouts |

**Trend.** Defects moved from governance and tooling (BUG-001/002), to test
quality (BUG-004), to integration (BUG-005 to BUG-009). Five of ten were found
only once the real app ran; BUG-010 came from a review against REQ-IX-PER-001.

**Shared cause of BUG-005 to BUG-009.** In each case the tests supplied what
the device lacks:

- the tests constructed objects directly instead of through Koin;
- the fixtures preset the APK hash;
- the tests installed the consent gate themselves;
- the live test used a different HTTP stack.

**Defects caught in review before merge.** These never reached `main`:

- the plan's advisor-off bypass;
- a forgeable `PolicyResult` in `autoSubmit`;
- fail-open install fallbacks;
- an uninstall-retry bypass, then the retry reusing a spent operation ID;
- a mis-specified SEC-003 test;
- six verification suites that tested copies of production code (PR #25).

**Evidence mapper (PR #18).** Member B confirmed two issues and
approved the following changes during review:

- The parser left `fileHash` empty. We let the caller supply a verified
  SHA-256 for the selected APK. If the two hashes disagree, the mapper
  rejects the evidence.
- An empty package lookup could leave the target user's install status
  unknown. The mapper now requires confirmation that the app is absent for
  that user before it returns `NEW_INSTALL`.

The PR passed all 276 local JVM tests, including 17 mapper tests, and built
both debug APKs ([AI-use log](docs/ai-use-log.yaml), AI-GArbage823-0003). At
merge, only the tests supplied a caller hash; PR #33 added the call-site
hashing.

**[TO FILL: C, D, E: defects found and fixed in your PRs, for example the
PR #13 review fixes.]**

### 4.3 Test growth

| Point | Unit tests |
|---|---|
| After PR #25 was rebased onto #27 (11-21) | 336 |
| PR #30 (AI advisor end to end) | 376 |
| PR #29 merged | 423 |
| PR #25 merged | 436 |
| PR #33 (on-device fixes) | 463 |

A live suite of 10 fixed scenarios plus 3 update-relative scenarios runs only
with credentials. On prompt v1.3 it reached 8/10 usable, which meets the
threshold of 8.

### 4.4 Ambiguity resolution

Open questions were written into the plan as numbered questions (Q1, Q2, …).
The lead answered each before approval, and the answers were recorded in the
plan's approval section. Examples:

| Question | Decision | Where |
|---|---|---|
| OpenCode v1 or v2 permission format | v1, the format the course layout follows | Transcript A |
| Backend field, config hash, binding construction (Q1–Q3) | Reuse `Authorizer` as non-null; caller-supplied hash; builder that returns null on any missing field | Coordinator plan §11 |
| How revalidation reads facts | A second read through `BindingFactsProvider` before the permit, not reuse of the first read | Coordinator plan |
| JVM test runtime for Room (persistence Q1) | Robolectric with a timebox; no androidTest | Persistence plan |
| Advisor-off path vs ARCH-001 (Q9) | Narrow ARCH-001 for v1 | AI plan §13, PR #31 |
| Where the API key lives | Asked on first enable, stored in an app-private DataStore, never in the APK | AI plan |
| Consent UX | One switch; consent shown on every enable; disabling withdraws consent | PR #33 |
| MEDIUM-risk updates and DR-05 | Keep DR-05 strict; give the model update-relative evidence instead | PR #33 |
| Meaning of FR-012 | Keep FR-012 for the AI switch. Fold compatibility into FR-002 and FR-005 | Transcript B, messages 38 and 51 |
| Continue, Retry or Cancel | Continue rechecks approval locally. Retry starts a fresh assessment if consent, network and budget allow. Cancel submits nothing | Transcript B, messages 43–44 |
| Retry rejected before dispatch | Count the attempt. Add one provider call only when the request is sent | Transcript B, messages 45–46 |
| First Continue and repeated clicks | Bind approval to the current installation object on the first Continue. Revalidate before submission and submit at most once per operation | Transcript B, messages 47–48 |
| Empty installed-app lookup | Require confirmed absence for the target user before returning `NEW_INSTALL` | PR #18 |
| Consent withdrawn during assessment | Check consent again before sending the request. Missing or withdrawn consent blocks dispatch | PR #19 |

**[TO FILL: C: ambiguities resolved in the architecture reviews.]**

---

## 5. Keeping artifacts co-evolving

When code changed, the documents changed in the same PR. Four examples:

1. **ARCH-001 narrowing.** The plan review found a conflict. It was recorded
   as Q9 in the plan, decided by the lead, then applied in order:
   - `requirements.yaml`: statement, fit criterion, and dated note;
   - ADR-002.6: a new bullet;
   - the plan's traceability table.

   The code PR (#30) was merged only after the docs PR (#31).
2. **Prompt versions.** Every prompt text change shipped as a new
   `prompts/prompt_vX.Y.yaml` with a matching `PROMPT_VERSION` constant:
   v1.1 for the course model, v1.2 for PR #29, v1.3 for update-relative
   evidence. CI fails if the version, system prompt, model, or output cap in
   the file differ from the code; the user prompt template is not compared. PR #29 first changed
   the prompt without a version bump; review caught it and added v1.2.
3. **Outbound allowlist.** The allowlist in `requirements.yaml`, the code
   constant `ALLOWLISTED_LEAF_PATHS`, the consent dialog (generated from that
   constant), and the tests change together. Adding two fields in PR #33
   touched all four.
4. **Stale ADR text.** ADR-002.5 said no production code installed the consent
   gate. That turned out to be the cause of BUG-008, and the ADR was updated in
   the fix PR.

**Records.** Every agent session has an AI-use log entry (34 entries). Each
significant defect has a bug diary entry. Each known shortfall has a debt
entry. The CI AI-use log gate enforces the log.

**Gap.** `docs/architecture/trace-matrix.md` has not been updated for PRs #30
to #33. **[TO FILL: update it, or record it as debt.]**

---

## 6. What human oversight caught that the agents did not

From the lead's supervision record (`transcripts/wxxsfxyzm-supervision.md`):

1. **Optimising the wrong goal.** The agent slimmed `AGENTS.md` and dropped
   content the course requires.
2. **Wrong self-diagnosis.** One agent blamed the configuration when the wrong
   agent was selected. Another blamed the phone's network for what was a
   transport bug.
3. **Safety bypasses behind green tests:**
   - a forgeable decision in `autoSubmit`;
   - an advisor-off path around the coordinator;
   - fail-open fallbacks;
   - an uninstall-retry bypass.
4. **Tests that prove nothing:**
   - a live test that passed with 10/10 network errors;
   - a test that checked the in-memory binding instead of the persisted one;
   - suites that tested copies of production code.
5. **Gaps only a real device shows.** BUG-005 to BUG-009.
6. **Product judgement:**
   - how consent should work;
   - that routine updates should not be rated high risk;
   - when to defer a slice rather than force it.

From Member B's [requirements review](transcripts/transcriptB-chaojiao2-60687558.md)
for PR #5:

1. **No approval on the first click.** OpenCode's S16 replay case assumed an
   approval already existed. Member B pointed out that the first Continue had
   no approval to reuse. The revised plan binds approval on that click and
   checks it again before submission. A repeated click cannot submit twice, and a
   restart while waiting for the user leaves the operation pending
   (messages 47–48).
2. **Counting a call that never left the device.** OpenCode counted one
   model call for a permitted Retry. Member B pointed out that a retry could
   still fail the budget check before dispatch. The revised plan counts the
   attempt, records zero provider calls in that case, and allows at most one
   retry per operation (messages 43–46,
   [rev.6 plan](docs/plans/2026-11-16-pr5-rev6-revision.md)).

**[TO FILL: C, D, E: what you caught in your own sessions.]**

---

## 7. Known gaps and technical debt

| ID | Category | Gap | Status |
|---|---|---|---|
| DEBT-001 | Guardrail | Shell redirects not denied for `build` and `verify` | Open |
| DEBT-002 | Verification | Test-plan cases P/C/A not all covered against production classes | Open |
| DEBT-003 | Evaluation | Approval persistence in memory only; REL-002/003/004 Partial | Open |
| DEBT-004 | Guardrail | API key stored unencrypted in app-private storage | Open |
| DEBT-005 | Evaluation | Retry uses pre-uninstall evidence; AutoNotification not evaluated; live margin at threshold | Open |
| DEBT-006 | Evaluation | No automated on-device end-to-end test | Open |

Other gaps to state honestly:

- `docs/review-memo.md` was not kept per PR during the project. It was
  reconstructed on 2026-11-23 for every PR (#1–#39) from GitHub reviews,
  agent sessions, and the AI-use log, and it says so at the top.
- 19 of 41 requirements are not fully met (Section 4.1). Most depend on the
  deferred persistence and audit work (DEBT-003).
- The trace matrix lags behind PRs #30 to #33 (Section 5).
- PR #33 recorded that its tests were only partly approved before
  implementation, because the on-device fixes were made under time pressure.
- The CI semantic-evaluation gate's live step was switched from the retired
  `GROQ_API_KEY` to the course service's `AGENT_*` variables on 2026-11-23. It
  runs only once the `AGENT_API_KEY` secret is configured.

---

## 8. Self-reflections

Each member writes one paragraph of 300–500 words in their own words. The
course asks for a personal reflection, so these cannot be drafted by an agent.

### 8.1 Member A (`wxxsfxyzm`) — project lead

As project lead I set up the governance before any agent ran, and later
carried most of the implementation myself. I divided the work at the start,
but Android development needs specific experience, and with little time left
I took on the main coding tasks. I own and maintain this project, so I knew
the codebase well enough to do so confidently, and I believe the choice was
right: it put our limited time into building rather than into coordination
and learning a large unfamiliar codebase. I deliberately left the
requirements, the architecture and the verification process to my teammates,
so that each of them had room to think through a part of the design
themselves. Working through the approval gates felt slow at first. Every slice
began with a plan, and the agent had to stop until I approved it and its
tests. Once implementation started, the work went smoothly, because the hard
questions had already been answered on paper. The most important lesson was
that green tests do not mean a working product. All 463 unit tests passed on
my machine, yet when I installed the app on a phone, the AI advisor had never
once made a successful model call, and five defects appeared in a single
evening (BUG-005 to BUG-009). They shared one cause: the tests supplied what
the real device lacked, such as objects built directly instead of through
dependency injection, a preset APK hash, and a consent gate installed by the
test itself. No agent noticed this, because every signal it could see was
green. Only running the real app showed it, which is why human oversight
cannot be replaced by the agent's self-verification. The second lesson was
that agents give confident but wrong diagnoses. The coding agent once reported
that the project configuration had not loaded, when the real cause was that
the wrong agent had been selected. Later the reviewing agent blamed a VPN for
what was actually a bug in reading the model's response, and I adjusted the
phone's network settings for nothing until the stack trace showed the real
cause. I learned to ask for evidence before accepting any conclusion. Using a
second AI to review the first one's work is common practice in open-source
communities, and it saved me a great deal of effort. It also meant
supervising two agents at once. That only works if I understand what each one
is doing, and I cannot trust a conclusion just because two agents agree on
it. When a calculator was rated medium risk and auto-approval stopped being
useful, I kept the deterministic rule unchanged, because design rules must
not be changed casually, and improved the evidence the model receives
instead. Next time, I would add an automated on-device smoke test from the
first integrated slice, so that the gap between unit tests and the real
device is caught on the first day rather than at the end.

### 8.2 Member B (`GArbage823`) — Requirements Engineer

I started on requirements, then built the install-risk policy engine, the
AutoDialog evidence mapper, and the default-deny model request gate. I set
the scope, approved the plans and tests, and checked the verification output
before merge. For the later batches, I authorised OpenCode to move into Build
mode once the plan and tests were ready. D1 went from rev.3 to rev.6 between
11-14 and 11-16. PR #5 came back with seven required changes. One separated
install success likelihood from security risk; another distinguished a
preflight rejection with zero model calls from a response that exceeded the
budget. We retained all 41 IDs by folding the proposed COMP-002 criteria into
FR-002 and FR-005. For rev.6 we updated the YAML registry and JSON Schema
together. All 14 cases matched the expected rule, decision and disposition.
We recorded the results in the review memo. OpenCode described notification
replay as reusing an existing approval. A user who has never approved
anything has nothing to reuse. That first-use state was not in the prompt I
wrote. The plan filled the gap with an assumption that does not hold for a
first-time user. I asked for the first Continue to create an approval bound
to the installation object, revalidate the binding before submission, and
make zero additional model calls. Repeated clicks must yield at most one
submission. In the mapper review, I also required affirmative confirmation
before an empty installed-app lookup could be classified as `NEW_INSTALL` for
the target user. On their own branches the three slices passed: six policy
tests inside 259, 17 mapper tests inside 276, and seven egress tests inside
266. The branch tests covered the inputs supplied by our fixtures. The lead’s
device run exposed an assumption those fixtures had hidden. Every APK came
back unreadable. The production parser never populated `fileHash`, and our
fixtures had always supplied one. The model request gate was never installed
on the production path, so no model call went out. After that fix, every
reply failed with an `EOFException` on short bodies. None of the three
batches ran on a device or against the live model. Next time I would pair the
offline cases with a smoke test through the actual Android install flow as
soon as the first slice is wired in.

### 8.3 Member C (`yipu02`) — Software Architect

As Software Architect, I treated my role as supervising an AI agent rather
than delegating authority to it, and our session structure reflected this.
Work began in Plan mode, where the agent ran a gap analysis of the empty D2
skeletons against the approved D1 requirements. After resolving six explicit
open questions, I approved the plan
`docs/plans/2026-11-17-d2-architecture-population.md` (Gate A). Build mode
then proceeded in four controlled batches, pausing after each for my review,
with every decision logged in `docs/ai-use-log.yaml` and cross-checked against
`trace-matrix.md`. This discipline was essential: the agent was fast and
fluent, but it lacked self-governance and would readily have promoted
unverified proposals into baseline architecture. We refined this workflow to
enforce clear ownership boundaries. Every Build-mode prompt carried an
explicit constraint, "do not touch `app/`", which confined architectural edits
to `docs/` and `architecture-blueprint.md` while implementation owners
controlled production code. I also consolidated redundant prompts and, once
PRs #16, #18 and #19 had merged, required an incremental documentation sync
rather than a speculative end-state rewrite, so that the records tracked
verified code rather than predictions. Three recurring defects emerged. The
first was documentation drift: even after the policy engine and adapter had
merged, ADR-002 still marked those decisions as "not started". The second was
traceability rot: an AI-generated entry in `docs/ai-use-log.yaml` cited a plan
file, `docs/plans/2026-11-21-d2-sync-post-merge.md`, that did not exist. The
third was status overstatement: ADR-002.5 was recorded as implemented when
only the underlying gates existed, with no production call site and no
consent UI. The architecture also evolved under review. ADR-001 originally
recommended a separate Gradle module (Option A); after the team lead's review,
the team selected Option B, in-module packages enforced through interfaces,
reviews and tests, given our limited Android build experience and the
schedule, and I propagated that decision across the D2 documents. ADR-002.5
moved from a two-layer gate to the consent-scoped `ModelRequestGate` from
PR #19. During the post-merge sync, code inspection showed that this gate was
wired only in tests, so I corrected its status to "partially implemented" and
removed the dangling plan reference. When the gate was later wired into the
app, the lead updated the ADR and kept my earlier wording as an audit trail.
The critical catches came from human oversight rather than from the agent:
an AI agent accelerates architectural work, but it cannot replace the
architect's duty to verify every claim against ground truth.

### 8.4 Member D (`Eugene12321`) **[TO FILL]**

### 8.5 Member E (`yuki11111111111`) **[TO FILL]**

---

## 9. References

- Course specification: CS5351 Agentic Software Engineering Project, Project
  Requirement Sheet.
- Plans: `docs/plans/` (10 files).
- Records: `docs/ai-use-log.yaml`, `docs/bug-diary.md`,
  `docs/debt-register.md`, `docs/review-memo.md`.
- Transcripts: `transcripts/` (one per member, combined per the team
  agreement).
- Repository: https://github.com/wxxsfxyzm/CS5351-Project
