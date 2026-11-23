# Transcript — wxxsfxyzm (project lead): guiding and correcting the coding agents

| Field | Value |
|---|---|
| Operator | wxxsfxyzm (project lead) |
| Coding agent | OpenCode 1.19.5 with the course model `CS5351/DeepSeek-V4-Flash`, roles `project-plan`, `build`, `verify` (`.opencode/permissions.jsonc`) |
| Review assistant | Claude Code (`claude-opus-5-5`), used by the lead to read OpenCode output against the course spec, draft instructions, and debug on a device |
| Period | 2026-11-16 → 2026-11-23 |
| Result | PRs #4, #24, #26–#32 and `fix/ai-advisor-on-device`; records in `docs/ai-use-log.yaml`, `docs/bug-diary.md`, `docs/debt-register.md`, `docs/plans/` |

## What this document is

An **edited excerpt**, not a raw log. Following the course guidance, it keeps
the parts that show how the lead directed and corrected the agents across the
whole development: planning, approval before code, build, verification, and
architecture revision. Generated code, tool output, and routine progress are
left out.

Each entry gives:

- **Context.** What the agent had just done.
- **Lead.** An excerpt of what the lead said. Messages written in Chinese are
  quoted, then translated in italics.
- **Agent.** A short excerpt of the agent's response, with no code.
- **Type.** One of: *launch*, *redirect*, *reject*, *correct*, *decide*,
  *approve*, *stop*.
- **Why.** The reason the lead gave. Where the lead gave none, the entry says so.
- **Outcome.** What changed, with the PR and record IDs.

**Sources:**

- OpenCode sessions, exported with `opencode export`, cited by session ID and
  message index.
- The Claude Code session `cb98cbca-81bf-454c-b679-df02a7e48a9e`, cited by date.
- Transcript A (`transcriptA-wxxsfxyzm.md`), which has the governance-setup
  part in full.

**How supervision worked.** The lead ran OpenCode for every planned change.
Claude Code was used alongside it to:

- read each OpenCode plan or build report against the course PDF and the
  repository rules;
- point out defects;
- draft the next instruction.

The lead decided what to send. Late in the project, on-device defects were
fixed in Claude Code directly at the lead's direction because of time (stage 9),
and this is recorded as such in AI-wxxsfxyzm-0019.

**Omitted.** Exchanges the lead asked not to record are left out: a CI
signing fix, local OpenCode setup troubleshooting, an AI-log format question,
and the network-settings layout fix.

---

## Stage 1 — Governance before any agent work (2026-11-16, Claude Code)

The full exchange is in Transcript A. The key interventions were these.

### 1.1 Restructure the agent entry point
- **Context:** `AGENTS.md` had grown to about 18 KB, with course rules mixed
  into the upstream InstallerX rules.
- **Lead:** proposed a three-layer structure: `AGENTS.md` holds the global
  rules and is always loaded; `docs/code-governance/` is loaded per task; the
  session prompt carries the current task and approval state.
- **Agent:** inspected the repository, produced a plan to slim `AGENTS.md`,
  and stopped for approval.
- **Type:** launch → approve.
- **Why (stated):** avoid context pollution, token overhead, and instruction
  dilution, so that human approval and safety boundaries are not lost in detail.

### 1.2 Check against the course specification
- **Lead:** "检查一下 …CS5351-2026-2027-project.pdf，目前对约束的修改是否完善了，我是组长需要做好架构准备"
  *"Check the project PDF: are the constraint changes complete? As the lead I
  need the architecture groundwork ready."*
- **Agent:** "上一轮的精简…`AGENTS.md` 现在缺了几项必填内容"
  *"The slimming made AGENTS.md miss several D3 required items"*: build target,
  reading order, baseline, readable/frozen scope, integration contracts, and
  verification requirements.
- **Type:** correct.
- **Why:** the agent had optimised for size and dropped required content. The
  lead's check against the PDF exposed it.
- **Outcome:** `AGENTS.md` rewritten with the seven D3 sections (PR #4).

### 1.3 Fix the tool and version
- **Context:** the agent planned the permission file in OpenCode v2 format.
- **Lead:** "老师要求的文件结构应该是v1的…我们是不是应该固定使用v1而非v2？"
  *"The course structure looks like v1; shouldn't we use v1, not v2?"* Later:
  "不用固定版本，我们口头约定v1即可" *"No pinned version; we agree on v1."*
- **Agent:** confirmed that v2 silently ignores v1 permission fields, a risk
  for a project whose permissions are a deliverable.
- **Type:** correct → decide.
- **Why (stated):** the course folder layout follows v1.
- **Outcome:** `.opencode/permissions.jsonc` written for v1; three roles
  (plan, build, verify) accepted by the lead.

### 1.4 Keep the record honest
- **Lead:** "我纠正的过程你帮我写进去吧" *"Write my corrections into the transcript."*
- **Agent:** annotated each lead message. Reasons were given only where the
  lead stated one.
- **Type:** decide.
- **Outcome:** Transcript A; AI-wxxsfxyzm-0001 to 0005.

---

## Stage 2 — CI tidy (2026-11-20, OpenCode `ses_e06534daeffe`)

### 2.1 Scope the task and stop before editing
- **Lead (msg 0):** "这次只整理 CI…保留格式检查、单元测试和 Debug APK 构建…#13 和 #16 的业务代码先别动…写完就停，等我明确批准计划和测试后再实施"
  *"Only tidy CI; keep format, unit tests, and the Debug APK build; do not
  touch #13/#16 code; stop after the plan until I approve the plan and tests."*
- **Agent:** produced a plan. It could not save the plan, because the session
  was running the build role, so it presented the plan in the chat.
- **Type:** launch.

### 2.2 Pause on a stale baseline
- **Lead (msg 20):** "先暂停上一版计划。我刚发现本地 main 少了远端两个重要提交…以最新的 origin/main 为准重新检查…先别 pull、merge 或 rebase"
  *"Pause the plan. Local main was missing two remote commits; recheck against
  the latest origin/main; do not pull, merge, or rebase."*
- **Agent:** "Your two 'missing commits' are not visible in this local clone"
  (it had already been looking at them).
- **Lead (msg 25):** "你看到的 eaf9fe78 就是我同步后的 main。两个提交是 0f718739（#5）和 eaf9fe78（#6），请看 fcdd2348..eaf9fe78"
  *"eaf9fe78 is the synced main; review fcdd2348..eaf9fe78."*
- **Agent:** reviewed the two commits (both docs only) and confirmed there was
  no impact on the CI logic or on who may edit workflows.
- **Type:** stop → correct.
- **Why (stated):** the plan must rest on the current requirements, the
  architecture, and the permission model.

### 2.3 Approve with an execution order
- **Lead (msg 52):** "我批准 pr-check.yml 的 diff 和验证计划，执行时按这几个顺序来：先确认 PR Target 是否是 required check，并在 CI PR 合入 main 前停用三个发布 workflow。git apply --check 放在应用补丁前…补丁由我应用"
  *"I approve the diff and the verification plan, in this order: confirm
  whether PR Target is a required check; disable the three release workflows
  before the CI PR merges; run git apply --check first. I apply the patch
  myself."*
- **Agent:** recorded the approval and the order; did not apply the patch.
- **Type:** approve.
- **Why (stated):** release workflows are frozen and must not run; the human
  applies the patch.
- **Outcome:** plan `2026-11-21-ci-workflow-tidy.md`; AI-wxxsfxyzm-0006.

---

## Stage 3 — Proving the permission model (2026-11-21, OpenCode `ses_e017895b4ffe`)

### 3.1 Permission probe
- **Lead:** asked the `project-plan` agent to attempt seven actions once each
  and report which were allowed: write a plan, write source, write
  `.opencode/`, read a credentials path, `git status`, `git push`, and a shell
  redirect.
- **Agent:** reported the plan write and `git status` as allowed and the
  other five as denied.
- **Type:** launch (verification of governance).
- **Why:** the permission file is a deliverable; the lead tested enforcement
  instead of assuming it.
- **Outcome:** a gap was found in the build and verify roles (a shell redirect
  is not denied there). Recorded as BUG-002 and DEBT-001; the dedicated
  `project-plan` agent was added (PR #24).

---

## Stage 4 — ApprovalCoordinator, binding, and gateway (2026-11-21, OpenCode `ses_e01b23c74ffe`)

### 4.1 Plan with explicit limits
- **Lead (msg 0):** gave the reading list and the scope: coordinator, binding,
  and gateway; a fake gateway first; DR-01 to DR-10; no new module. "不允许仅靠内存锁宣称已经满足 REL-002/003/004…现在只允许规划"
  *"Do not claim REL-002/003/004 from an in-memory lock; planning only."*
- **Type:** launch.

### 4.2 Correct the agent's diagnosis
- **Agent:** "This session was not launched with the project config… Session
  blocker."
- **Lead (msg 22):** "上一轮写入失败的原因是本会话当时使用的是 build agent…并不是未加载项目配置…改为如实说明"
  *"The write failed because the session used the build agent, not because
  the config was missing. State this truthfully in the plan."*
- **Type:** correct.
- **Why (stated):** the agent's explanation was wrong and would have gone into
  a project record.

### 4.3 Decide the open questions and require a safer design
- **Lead (msg 26):** answered Q1 to Q6: reuse `Authorizer` but make it
  non-null; caller-supplied config hash; builder returns null on a missing
  field; steps 1 to 8 only; no `docs/architecture` edits by build; TTL
  deferred and listed as a gap. Required changes:
  - "复验不能复用同一份 freshFacts…在 ledger.tryAcquire 和 gateway.submit 之前重新调用 provider 读取当前事实"
    *"Revalidation must not reuse the same facts; re-read them through a
    provider before the permit and the submit."*
  - test fakes live in `app/src/test` only.
- **Agent:** updated the plan: `BindingFactsProvider`, a double read, and a
  coordinator-level TOCTOU test.
- **Type:** decide + reject (design).
- **Why (stated):** reusing the first read would miss an APK replacement or a
  config change (INV-003, ADR-002.7).

### 4.4 Review finding after the build
- **Lead (msg 94):** "autoSubmit…信任调用方传入的 PolicyResult…调用方可以伪造 EXECUTE_ONCE 绕过 DeterministicPolicyEngine"
  *"autoSubmit trusts a caller-supplied PolicyResult; a caller could forge
  EXECUTE_ONCE and bypass the engine."* Required:
  - load the persisted decision;
  - persist before submitting;
  - make a failed submit terminal;
  - five negative tests.
- **Agent:** "伪造的 PolicyResult 无法再绕过引擎" *"A forged PolicyResult can no longer bypass the engine"*; added the tests.
- **Type:** reject → correct.
- **Why (stated):** the coordinator must be the only decider (ARCH-001), and
  REL-002 requires persisting before submitting.
- **Outcome:** PR #27; AI-wxxsfxyzm-0008 and 0010.

---

## Stage 5 — A teammate's AI-generated tests (2026-11-21, Claude Code)

### 5.1 Review PR #25 before adopting it
- **Lead:** "突然有个组员提交了pr，你看看是否采纳" *"A teammate opened a PR; should we adopt it?"*
- **Review finding:** 6 of 8 suites tested a 691-line copy of the engine and
  coordinator that lived in the test folder, so they stayed green whatever the
  production code did.
- **Decision:** keep the two suites that test production code; remove the
  copies; record DEBT-002. Then the lead redirected: "先别管他的，我们先写我的任务，继续指挥opencode"
  *"Leave his for now; continue my tasks with OpenCode."*
- **Type:** reject (in part) → redirect.
- **Outcome:** PR #25 merged later with only the production-wired tests; DEBT-002.

---

## Stage 6 — Persistence slice: plan, block, deferral (2026-11-22, OpenCode `ses_dfe6fab3bffe`, `ses_dfe56b580ffe`, `ses_dfdc45682ffe`)

### 6.1 Plan review
- **Lead (`ses_dfe6…` msg 16):** "Not approved yet." Answered Q1 to Q10. Q1:
  Robolectric and room3-testing, with a timebox — "if Robolectric cannot run
  Room… stop and report; fallback is fake-DAO JVM tests plus a recorded debt
  entry, not androidTest." Required change A: "SEC-003 test is mis-specified.
  onContinue revalidates fresh facts against the in-memory `bound`, never
  against the persisted binding", so the test was rewritten to check that a
  tampered stored snapshot cannot cause submission.
- **Type:** reject → decide.
- **Why (stated):** androidTest cannot run in CI; the original SEC-003 test
  would pass without testing real behaviour.

### 6.2 Approve with conditions
- **Lead (msg 21):** "Plan rev.2 approved, with these conditions… then stop.
  Do not start building." `onRecovered` must persist the mapped state;
  explicit mapping when the permit is consumed.
- **Type:** approve.
- **Why (stated):** "Without this, a recovered AWAIT_HUMAN is unusable because
  onContinue reads the stored state… and rejects."

### 6.3 Enforce the stop rule, then defer
- **Agent (`ses_dfe5…`):** "Step 2 result: BLOCKED — stopped per Q1 timebox."
  Robolectric aborted with exit 134 under JDK 25 on macOS arm64; all step 2
  edits were reverted.
- **Lead (Claude Code):** "这个东西一定要跑吗，赶时间啊" *"Does this have to run? We're short on time."*
  The review assistant recommended deferring, since the remaining checks needed
  real SQLite on the JVM. Then: "我们得让opencode这块解决掉然后做好记录"
  *"Let OpenCode close this out and record it."*
- **Lead (`ses_dfe5…` msg 99):** "Lead decision: the ADR-002.3 persistence
  slice is deferred. Do not implement steps 3-9 and do not apply the fake-DAO
  fallback. Reason: the Robolectric block removes the real-SQLite evidence the
  slice needs…"
- **Type:** stop → decide.
- **Outcome:** the plan gets §0.2 "Execution outcome"; BUG-003 and DEBT-003
  are recorded by the verify agent (`ses_dfdc…`); PR #28.

---

## Stage 7 — AI advisor end to end (2026-11-22, OpenCode `ses_dfda7c511ffe` plan, `ses_dfd943efdffe` build)

### 7.1 Define the product before planning
- **Lead (Claude Code):** "当然要把成品做出来，需要演示的，两个主题的对话框都做…设置也在网络里面单开一组卡片加用户可调开关…先不要豁免"
  *"Build the finished product for a demo: dialogs in both themes, a separate
  card group with toggles in network settings, no exemptions."* Then: "第一次打开这个功能的时候就要求配置端点和key，然后存储在私有目录，这样不会被质疑打包在apk内被盗用"
  *"Require the endpoint and key on first enable and store them in private
  storage, so the key is never packaged in the APK."* And: "我们要接成课程用的ds，和opencode一样"
  *"Use the course DeepSeek service, like OpenCode does."*
- **Type:** decide.
- **Why (stated):** a demo needs a working product; a key packaged in the APK
  could be stolen.

### 7.2 Inject measured facts into the plan
- **Lead (`ses_dfda…` msg 65):** live-tested the course service:
  chat/completions with JSON mode works; "/v1/responses also works but returns
  a reasoning item and defaults to temperature 1.0 and server-side storage; do
  not use it."
- **Type:** redirect.

### 7.3 Reject plan rev.1
- **Lead (msg 87):** "Not approved yet." Required changes:
  - "The plan's 'advisor off => ViewModel calls session.install(true)
    directly' is a bypass… If the engine cannot express that without a DR
    change, list it as a requirement conflict for me instead of bypassing the
    choke point. Do not change DR rules."
  - The gateway, facts provider, and coordinator are per session: "two
    concurrent sessions would overwrite each other's facts (INV-003)."
  - One assessment trigger: "recomposition or re-entering the stage must not
    dispatch again (COST-001)."
- **Agent:** rev.2 documented the advisor-off path as requirement conflict Q9
  instead of choosing a bypass.
- **Type:** reject.

### 7.4 Revise the architecture, then approve
- **Lead (msg 146):** "Q9: Option (b). I authorize narrowing REQ-IX-ARCH-001
  for v1 to AI-enabled in-scope requests… The requirement and ADR edits are a
  human task done outside this session." Also: "InstallWaitingUnknownSource
  is not terminal… Mapping the waiting state to Failed would consume the
  permit." Then: "the plan rev.3 and its tests (sections 4.1-4.4) are approved
  by wxxsfxyzm on 2026-11-22… Do not start building."
- **Type:** decide (architecture revision) → approve.
- **Outcome:** REQ-IX-ARCH-001 and ADR-002.6 narrowed (PR #31); plan §13.

### 7.5 Reject a vacuous test
- **Agent:** checkpoint 0 done; the live test passed.
- **Lead (`ses_dfd9…` msg 59):** "Gradle reports PASS, but 10/10 scenarios
  returned NETWORK_ERROR, so the test is vacuous." Diagnosed the cause (OkHttp
  calls the unmocked `Log.isLoggable` in JVM tests). Directed a test-only
  `java.net.http` transport and "require at least 8 of the 10 fixtures to
  return a usable assessment… Do not change OkHttpAiTransport or any
  production code for this."
- **Lead (msg 67):** "Checkpoint 0 is GO. Keep MIN_USABLE = 8; do not lower it."
- **Type:** reject → approve.
- **Outcome:** BUG-004.

### 7.6 Reject the build on safety grounds
- **Agent:** all checkpoints were done, "each ending green".
- **Lead (msg 371):** "Review of the build: not accepted. Tests are green, but
  the advisor-on path still bypasses the choke point." Seven required fixes,
  including:
  - "Fail closed. With the advisor on, no path in InstallerViewModel may call
    session.install except the… Baseline branch."
  - Gate-withheld cases go through the engine.
  - `EvaluatedPolicy` is derived from facts using the lead's policy-v1 rules,
    not constants.
  - No AutoDialog auto-install in `ProgressHandler`.
  - `ChokePointTest` must not allowlist whole files.
- **Type:** reject.
- **Why (stated):** green tests did not prove the safety boundary.

### 7.7 Two more bypasses found in re-review
- **Lead (msg 460):** "One remaining bypass: uninstallAndRetryInstall… calls
  baselineInstall… With the advisor active, this second submission skips the
  coordinator… although the facts have changed."
- **Lead (msg 491):** "not ready to commit yet": the retry reused the
  operationId, "so… the stage hangs, and the SubmissionLedger permit… was
  already consumed", and "replace `else -> baselineInstall(...)` with a branch
  that does nothing (fail closed)."
- **Type:** reject (twice).
- **Outcome:** a new operation for the retry; a fail-closed default;
  coordinator-level test.

### 7.8 Approve the commit, keep push with the human
- **Lead (msg 514):** "Final review passed… Commit now… Stage only the slice…
  Do not push and do not open a PR."
- **Type:** approve.
- **Outcome:** PR #30; AI-wxxsfxyzm-0013.

---

## Stage 8 — Integration and records (2026-11-22, Claude Code and OpenCode `ses_dfc3549a6ffe`)

### 8.1 Teammate PRs fixed on their branches
- **Lead:** "他们的如果有什么问题，你在他们的基础上修改以后推上去"
  *"If their PRs have problems, fix them on their branches and push."*
- **Finding:** PR #29 changed the user prompt without a new prompt version, so
  the lead required a v1.2 bump. PR #25 had a duplicated AI-log ID.
- **Type:** correct.
- **Outcome:** PRs #29 and #25 merged; AI-wxxsfxyzm-0015 and 0017.

### 8.2 Verify-role records, with one correction
- **Lead → verify agent:** recorded BUG-004, DEBT-004 (plaintext key), and
  DEBT-005.
- **Review:** DEBT-004 claimed the key leaks "through an app-data backup".
  The manifest sets `allowBackup="false"` and excludes every domain, so the
  claim was false. The error came from the instruction, not from the agent;
  it was corrected before the PR.
- **Type:** correct.
- **Outcome:** PR #32.

---

## Stage 9 — On-device validation (2026-11-22 → 23, Claude Code)

The lead installed the app on a phone and tested the real flow. Every defect
below passed unit tests but failed on the device. Because of the time left,
the lead had them fixed directly ("你直接改吧" *"just fix it"*). Each fix came
with a test that fails on the old code.

| # | Lead's report or decision | What it exposed | Outcome |
|---|---|---|---|
| 9.1 | "点击开关打开，或者先点击configure配置，输入了apikey以后，enable打不开" *"Enable never turns on, even after configuring the key."* | The enable flow sent the user back to the config dialog instead of the consent dialog. | Sequential enable flow |
| 9.2 | "第二个开关应该不用留的，每次打开第一个开关的时候先弹那个通知就行了" *"Drop the second switch; show the consent notice on every enable."* | Design decision (FR-011 still met: consent on every enable, withdrawn on disable). | One switch |
| 9.3 | "还有问题，在等待ai返回结果的时候，下方的操作按钮应该都不可用才对" *"While waiting for the AI, the buttons must be disabled."* Then "保留取消吧" *"Keep Cancel."* | Actions were live before a decision existed. | Only Cancel enabled |
| 9.4 | "看下log，启动安装闪退" *"Check the log: it crashes on install."* | Koin resolved defaulted parameters. | BUG-005 |
| 9.5 | "自动安装过程中出了什么问题" *"What went wrong during auto install?"* | The APK hash was never computed, so evidence was always UNREADABLE. | BUG-006 |
| 9.6 | "这些值分析的时候应该是都分析到的，只是没往下传" *"These values are analysed already, just not passed down."* | Facts were not propagated, so DR-05 was unreachable. | BUG-007 |
| 9.7 | "怎么还是need review，看看日志" *"Why still needs review?"* | `ModelRequestGate` was never installed, so the model was never called. | BUG-008 |
| 9.8 | "不开vpn都是正常" *"Without VPN curl works fine"* (the lead ran curl in Termux) | Ruled out the network. The agent had blamed the VPN or the WAF; the stack trace showed `readByteArray(n)` throwing EOF. | BUG-009 |
| 9.9 | "如果是更新且权限没有新增，不应该定为high，另外系统软件更新也不应该定为high" *"An update that adds no permissions, or a system-app update, should not be HIGH."* | The model lacked update-relative evidence. | Two allowlist fields and prompt v1.3 (lead approved) |
| 9.10 | "我刚才装个计算器他都给medium，那这个自动审批几乎没用了" *"Even a calculator gets MEDIUM; auto-approval is nearly useless."* | The v1.3 rule was too weak. The lead kept DR-05 unchanged and tightened the prompt instead. | Calculator reinstall reached DR-05 on the device |
| 9.11 | "现在网络请求超时是不是没有兜底" *"Is there no timeout fallback?"* | REQ-IX-PER-001 gap: no whole-call timeout, no attempt budget, Cancel did not abort. | BUG-010 |
| 9.12 | "blocked by policy hard block，这个没有显示原因" *"The block shows no reason."* | The UI showed raw reason codes. | Readable causes for block and failures |

- **Type:** correct (each row); decide (rows 9.2, 9.9, 9.10).
- **Why:** the lead tested the real app instead of trusting green tests, which
  is what exposed BUG-005 to BUG-009.
- **Outcome:** `fix/ai-advisor-on-device`; BUG-005 to BUG-010; DEBT-006 (no
  automated on-device test); AI-wxxsfxyzm-0019.

---

## What the lead caught that the agents did not

1. **Optimising the wrong goal.** The agent slimmed `AGENTS.md` and dropped
   required content (1.2).
2. **Wrong self-diagnosis.** The agent blamed the configuration when the wrong
   agent was selected (4.2). The review assistant blamed the network for a
   transport bug (9.8).
3. **Safety bypasses behind green tests.** A forged `PolicyResult` (4.4), an
   advisor-off bypass (7.3), fail-open fallbacks (7.6), and an uninstall-retry
   bypass (7.7).
4. **Tests that prove nothing.** A vacuous live test (7.5), a mis-specified
   SEC-003 test (6.1), and suites that tested copies of production code (5.1).
5. **Gaps only a real device shows.** BUG-005 to BUG-009, where the tests
   supplied what the device lacks.
6. **Product judgement.** Consent UX (9.2), the risk rating of routine updates
   (9.9, 9.10), and deferring the persistence slice (6.3).
