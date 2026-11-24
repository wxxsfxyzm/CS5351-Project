# Transcript — Member D (`Eugene12321`): guiding and supervising verification of the AI pre-install assessment path

| Field | Value |
|---|---|
| Operator | Member D (`Eugene12321`) |
| Coding agent | Antigravity AI Coding Agent (`claude-opus-5-5`, `gemini-4-argon`) |
| Session ID | `204002de-922b-49e2-8aa1-202792d453c4` |
| Date | 2026-11-21 |
| Source raw transcript | `transcripts/60747744-YangXinyu.md` (retained unchanged) |
| Result | PR #25 (merged): `docs/verification/test-plan.md`, `PolicyWithoutModelTest.kt`, `AiPolicyIntegrationTest.kt`; `DEBT-002` recorded for rebuilding shadow suites against production classes |
| AI-use log | `AI-60747744-0001`, `AI-60747744-0002` (and lead review `AI-wxxsfxyzm-0016`) |

## What this document is

An **edited, annotated excerpt** of Member D's verification session, derived
from the full session record in `transcripts/60747744-YangXinyu.md` without
overwriting it. Following the course guidance (D5), this file keeps the
end-to-end supervision arc — how Member D scoped, directed, reviewed,
approved, and corrected the coding agent across planning, offline suite
construction, and production-wired integration — while **removing all
agent-generated code blocks and raw tool/build logs**.

Each entry cites the corresponding turns in `transcripts/60747744-YangXinyu.md`
and records:

- **Operator.** Member D's prompt or instruction. Messages written in Chinese
  are quoted, followed by an italicized English translation.
- **Agent.** A concise summary of the agent's response, with no code.
- **Type.** One of: *launch*, *redirect*, *approve*, *correct*, *stop*.
- **Why.** The reason, stated where the operator gave one or where a course
  governance rule required it.
- **Outcome.** What artifacts or decisions changed.

---

## End-to-End Workflow Summary

| Stage | Turns in raw transcript | Operator focus & supervision | Key artifacts / outcome |
|---|---|---|---|
| 1. Baseline & Test Plan | Turns 1–28 | Scope Stage 1 before coding; enforce `DR-01..DR-10` precedence and AI `HIGH` risk → `REQUIRE_REVIEW` rule; specify 9 `INV-003` binding fields and open questions | `docs/verification/test-plan.md`, initial `PolicyWithoutModelTest.kt` |
| 2. Contracts & Policy Suites | Turns 29–47 | Approve offline contract mirrors while upstream packages were unmerged; require `REQ-IX-*` KDoc tags and dedicated negative invariant tests (`INV-002`, `INV-007`) | `VerificationContracts.kt`, `VerificationFakesAndEngines.kt`, `PolicyDecisionTableTest.kt`, `HardBlockAndNoDowngradeTest.kt` |
| 3. Binding & Idempotency | Turns 48–50 | Direct Stage 3 tests for pre-approval zero submissions, TOCTOU SHA-256 tamper, `NFC-OUT-004`, 9-field mutations, 20-way concurrency (`INV-005`), and Retry semantics | `ApprovalCoordinatorAndInvariantsTest.kt` |
| 4. AI Validation & Fallback | Turns 51–61 | Direct Stage 4 tests for `F-01..F-09`, action-field and guarantee-language rejection, 0 automatic retries, static isolation, and offline test/lint verification | `AdvisorAndIntegrationTest.kt`; 10 suites / 54 tests (51 passed, 3 main-source scans skipped via `assumeTrue`) |
| 5. Production Integration & PR #25 | Turns 73–84 | Redirect to merged upstream production classes (`DeterministicPolicyEngine`, `DefaultAiRiskAdvisor`, `StrictAiOutputValidator`, `ModelRequestGate`); verify 0 skips | `AiPolicyIntegrationTest.kt`, updated `PolicyWithoutModelTest.kt`; 11 suites / 63 tests (63 passed, 0 skipped); merged via PR #25 with `DEBT-002` |

---

## Stage 1 — Baseline inspection and D1 rev.6 test plan (Turns 1–28)

### 1.1 Launch: map Member D's roadmap without prematurely writing code (Turns 1–16)
- **Operator (Turn 1):** "这个文件夹中是我和别人合作要做的项目，我担任的是d的部分，目前我需要做的任务如图片和文档所示，请为我讲解我具体应该怎么一步步完成"
  *"This folder contains the team project; I am responsible for Part D. My current tasks are shown in the image and roadmap PDF — please explain step by step how I should complete them."*
- **Agent (Turns 2–16):** Audited the local repository baseline against `requirements.yaml` rev.6, `runtime-policy-catalog.md`, `invariants.md`, `trace-matrix.md`, `adr/002-later-important-arch-decisions.md`, and `verification-constraints.md`. Reported that no AI, Policy, or Coordinator implementation existed yet in the snapshot (`ADR-002.1` was still `not started`), drafted a 5-stage verification guide, and highlighted that `verification-constraints.md` §2 requires human (Member A) approval of the test plan before implementing tests.
- **Type:** launch.
- **Why (stated):** Understand dependencies across Members A, C, and E and identify what Member D can start immediately versus what must wait for frozen interfaces.
- **Outcome:** Established the 5-stage verification sequence and identified four ambiguities (`DR-09` decision vs. disposition, `DR-03` vs. `DR-10` precedence, `FakeInstallationGateway` ownership, and offline/live test directories) to escalate to Members A and C.

### 1.2 Scope Stage 1 deliverables: test plan and model-free static check (Turns 17–21)
- **Operator (Turn 17):** "请帮我在项目里生成 `docs/verification/test-plan.md` 和 `PolicyWithoutModelTest` 的初版"
  *"Please generate `docs/verification/test-plan.md` and the initial version of `PolicyWithoutModelTest` in the project."*
- **Agent (Turns 18–21):** Read `permission-model.md` §9 and Spotless rules, created the initial structural check `PolicyWithoutModelTest.kt` (scanning candidate policy directories while keeping unimplemented main-source checks honestly marked `SKIPPED` via `assumeTrue`, paired with detector self-tests), and drafted §1–§2 of `docs/verification/test-plan.md` (purpose, scope, and 5-stage strategy).
- **Type:** approve (Stage 1).
- **Why (governance rule):** Keep Stage 1 strictly within unblocked deliverables (`docs/verification/test-plan.md` and an interface-independent static check) prior to test-plan approval.
- **Outcome:** Created `PolicyWithoutModelTest.kt` and opened `docs/verification/test-plan.md`.

### 1.3 Correct & refine decision table precedence: AI HIGH risk is never BLOCK (Turns 22–24)
- **Operator (Turn 22):** "我看了前两节的测试策略。接着写 §3 结构检查和 §4 Policy 决策表时，注意把 DR-01 到 DR-10 的优先级全部覆盖到；尤其是图片里特别强调的：AI 判断安全风险 HIGH 只能转 `REQUIRE_REVIEW`，绝不能直接 `BLOCK`，只有确定性硬限制才能 `BLOCK`，这组对照用例要写清楚。"
  *"I reviewed the first two sections of the test strategy. When writing §3 structural checks and §4 Policy decision table, ensure full coverage of DR-01 to DR-10 precedence — especially the critical rule highlighted in the spec that AI `HIGH` security risk must only route to `REQUIRE_REVIEW`, never directly to `BLOCK`; only deterministic hard blocks may produce `BLOCK`. Make that contrast pair explicit."*
- **Agent (Turns 23–24):** Added §3 (`S-01`–`S-04`) and §4 (`P-01`–`P-22`) to `docs/verification/test-plan.md`, including the explicit contrast between `P-05` (`HIGH` likelihood + `HIGH` security risk → `DR-07 REQUIRE_REVIEW / AWAIT_HUMAN`, never `BLOCK`) and `P-15`/`P-20` (`HARD_BLOCK` + favorable AI + human Continue → `DR-01 BLOCK / BLOCKED`), plus exhaustive `P-19` and anti-downgrade `P-21`.
- **Type:** redirect → correct.
- **Why (stated):** Guarantee that the verification matrix directly proves the authority boundary between AI advice (`DR-07`) and deterministic hard prohibitions (`DR-01`).
- **Outcome:** §3–§4 of `docs/verification/test-plan.md` completed with all `DR-01`..`DR-10` and anti-downgrade cases.

### 1.4 Verify INV-003 binding fields and record open questions (Turns 25–28)
- **Operator (Turn 25):** "好的，§4 很清楚。接下来补全 §5 Coordinator 与审批绑定测试、§6 AI 与 Policy 集成测试，以及向 A/C 确认的待决问题（比如 DR-09 的 disposition 判断依据、DR-03 与 DR-10 的先后顺序、以及需要 A 提供的 `FakeInstallationGateway`）。记得把 INV-003 的 9 个绑定字段和 NFC-OUT-004 区分目标包名与来源包名的用例写进去。"
  *"Good, §4 is clear. Next complete §5 Coordinator & approval-binding tests, §6 AI & Policy integration tests, and open questions for A/C (such as asserting `DR-09` on `disposition`, `DR-03` vs. `DR-10` ordering, and requesting `FakeInstallationGateway` from A). Make sure to include all 9 `INV-003` binding fields and the `NFC-OUT-004` case distinguishing target package name from install-source package name."*
- **Agent (Turns 26–28):** Completed §5 (`C-01`–`C-16`), §6 (`A-01`–`A-10` + separate live evaluation policy), §7 required inputs from C/A/E, §8 open questions, §9 exit criteria, and §10 approval sign-off table in `docs/verification/test-plan.md`, and verified LF line endings.
- **Type:** redirect.
- **Why (stated):** Ensure every `INV-003` binding field and `NFC-OUT-004` negative fit criterion is specified alongside unresolved spec ambiguities before test coding begins.
- **Outcome:** Completed `docs/verification/test-plan.md` (Draft v0.1).

---

## Stage 2 — Contract mirrors and deterministic policy verification (Turns 29–47)

### 2.1 Approve Stage 2–4 offline suite construction and scope contract mirrors (Turns 29–38)
- **Operator (Turn 29 → Turn 36):** "请帮我完成测试模块的代码……测试计划确认没问题。由于 C/A/E 还没把正式的 `domain/ai` 和 `ApprovalCoordinator` 合入当前目录，我们先在 `app/src/test/.../verification/` 下严格按照 D1 rev.6 和 ADR-002 建立测试契约镜像（`VerificationContracts.kt`），等正式包合入后再切换 import，先保证所有用例能跑起来。"
  *"Please complete the test module code ... The test plan is confirmed. Since C/A/E have not merged `domain/ai` and `ApprovalCoordinator` into this directory yet, let us first define test-scoped contract mirrors (`VerificationContracts.kt`) under `app/src/test/.../verification/` strictly aligned with D1 rev.6 and ADR-002 so all test cases can run offline, and switch imports once production packages merge."*
- **Agent (Turns 30–38):** Checked the project files and defined `VerificationContracts.kt` containing `AssessmentEvidence` (all 9 binding fields plus derived compatibility signals), `AssessmentResult`, `PolicyEvaluationOutcome`, and the D1 rev.6 enums.
- **Type:** approve → redirect.
- **Why (stated):** Enable offline execution of the full verification suite against D1 rev.6 contracts without waiting for upstream production merges, while keeping `app/src/main` untouched.
- **Outcome:** Created `VerificationContracts.kt` under `app/src/test`.

### 2.2 Direct reference engines and observable fakes (Turns 39–41)
- **Operator (Turn 39):** "契约字段没问题，已经覆盖了 INV-003 的 9 个绑定字段。接下来请写出 `DeterministicPolicyEngine`、`AIOutputValidator`、`ApprovalCoordinator` 的参考实现，以及用来统计后端提交次数和模拟 TOCTOU 哈希变化的 `FakeInstallationGateway`、`FakeApkHashProvider`。"
  *"The contract fields look good and cover all 9 `INV-003` binding fields. Next implement the reference `DeterministicPolicyEngine`, `AIOutputValidator`, and `ApprovalCoordinator`, together with `FakeInstallationGateway` and `FakeApkHashProvider` to count backend submissions and simulate TOCTOU hash mutations."*
- **Agent (Turns 40–41):** Created `VerificationFakesAndEngines.kt` implementing `DR-01`..`DR-10` first-match order, mutex-guarded `ApprovalCoordinator` with `onContinue` revalidation and `onRetry`/`recoverAfterRestart`, `AIOutputValidator`, `FakeInstallationGateway`, `FakeApkHashProvider`, `InMemoryApprovalStateStore`, and `FixedResponseAdvisor`.
- **Type:** redirect.
- **Why (stated):** Provide observable test doubles for submission counts and disk-hash changes required by `ApprovalBindingTest` and `IdempotencyTest`.
- **Outcome:** Created `VerificationFakesAndEngines.kt`.

### 2.3 Direct `PolicyDecisionTableTest` with requirement traceability (Turns 42–44)
- **Operator (Turn 42):** "好，现在开始写阶段 2 的 `PolicyDecisionTableTest.kt`，把测试计划里的 P-01 到 P-19 以及 P-22 全部写成独立单测，注意每条测试的 KDoc 都要标明验证的 `REQ-IX-*` 和 `DR-*` 编号。"
  *"Good, now write Stage 2's `PolicyDecisionTableTest.kt`, turning `P-01` through `P-19` and `P-22` from the test plan into individual unit tests. Ensure every test KDoc cites the `REQ-IX-*` and `DR-*` IDs it verifies."*
- **Agent (Turns 43–44):** Created `PolicyDecisionTableTest.kt` (20 unit tests) covering all `DR-01`..`DR-10` branches, `P-05` (`HIGH` security risk → `REQUIRE_REVIEW`), `P-19` exhaustive non-favorable check, `P-18` determinism, and `P-22` model-free execution, each annotated with traceability KDoc.
- **Type:** redirect.
- **Why (stated):** Enforce `verification-constraints.md` §1 rule that every test references its requirement and rule ID.
- **Outcome:** Created `PolicyDecisionTableTest.kt`.

### 2.4 Enforce dedicated negative invariant tests for INV-002 and INV-007 (Turns 45–47)
- **Operator (Turn 45):** "决策表测完后，按 `verification-constraints.md` §1 的要求，每条不变量都要有独立的负向测试。请单独写出 `HardBlockPrecedenceTest`（INV-002）和 `NoDowngradeTest`（INV-007），确保无论 AI 返回多乐观、甚至人工点了 Continue，硬限制都必须拦截且后端提交为 0。"
  *"Once the decision table is covered, `verification-constraints.md` §1 requires an independent negative test for every invariant. Write `HardBlockPrecedenceTest` (`INV-002`) and `NoDowngradeTest` (`INV-007`) separately, ensuring that no matter how favorable the AI output is — even with human Continue — a hard block holds and produces 0 backend submissions."*
- **Agent (Turns 46–47):** Created `HardBlockAndNoDowngradeTest.kt` containing `HardBlockPrecedenceTest` (exhaustive `HARD_BLOCK` dominance across completeness × compatibility × human approval states in `P-20`, plus end-to-end `onContinue()` producing `BLOCK` and 0 submissions in `C-11`/`A-08`) and `NoDowngradeTest` (`P-21` plus exhaustive `NoDowngrade` matrix).
- **Type:** approve → redirect.
- **Why (stated):** Satisfy `verification-constraints.md` §1 mandatory negative invariant coverage for `REQ-IX-INV-002` and `REQ-IX-INV-007`.
- **Outcome:** Created `HardBlockAndNoDowngradeTest.kt`.

---

## Stage 3 — Approval binding, TOCTOU, and idempotency verification (Turns 48–50)

### 3.1 Direct `ApprovalCoordinatorTest`, `ApprovalBindingTest`, and `IdempotencyTest` (Turns 48–50)
- **Operator (Turn 48):** "阶段 2 逻辑很完整。现在进入最关键的阶段 3：请编写 `ApprovalCoordinatorAndInvariantsTest.kt`，重点覆盖：1. 人工确认前 0 提交、Cancel 后不可复用、首次 Continue 0 额外模型调用；2. TOCTOU 场景（评估后、Continue 前 APK SHA-256 被替换）；3. NFC-OUT-004（来源包名与目标包名不同时，只修改绑定目标包名必须失效）；4. 9 个绑定字段逐个篡改或置空；5. 同一个 `operationId` 并发 20 次 Continue 至多提交 1 次（INV-005），以及 Retry 只增 attempt ID 不增提交权。"
  *"Stage 2 logic is thorough. Now move to the critical Stage 3: write `ApprovalCoordinatorAndInvariantsTest.kt`, focusing on: (1) 0 submissions before human action, Cancel non-reuse, 0 extra model calls on first Continue; (2) TOCTOU scenario where APK SHA-256 changes after assessment before Continue; (3) `NFC-OUT-004` with distinct target and source package names where mutating only bound `targetPackageName` invalidates approval; (4) mutating/blanking each of the 9 binding fields one by one; and (5) 20 concurrent Continue events for one `operationId` yielding at most 1 submission (`INV-005`), plus Retry adding only an attempt ID."*
- **Agent (Turns 49–50):** Created `ApprovalCoordinatorAndInvariantsTest.kt` with `ApprovalCoordinatorTest` (8 cases: `C-01`–`C-03`, `C-12`–`C-16`), `ApprovalBindingTest` (4 cases: `C-04`–`C-07`, including 11 single-field mutations covering all 9 `INV-003` fields), and `IdempotencyTest` (3 cases: `C-08`–`C-10`, including 20 concurrent coroutines on `Dispatchers.Default`).
- **Type:** redirect.
- **Why (stated):** Verify `REQ-IX-INV-003`, `REQ-IX-INV-005`, `NFC-OUT-004`, and crash-recovery semantics (`REQ-IX-REL-002` / `003`).
- **Outcome:** Created `ApprovalCoordinatorAndInvariantsTest.kt`.

---

## Stage 4 — AI output validation, failure fallback, and isolation (Turns 51–61)

### 4.1 Direct `OutputValidationTest`, `FailureFallbackTest`, and `AdvisorIsolationTest` (Turns 51–53)
- **Operator (Turn 51):** "最后是阶段 4：请编写 `AdvisorAndIntegrationTest.kt`，覆盖 `OutputValidationTest`（拒绝指令字段、保证性语言、伪造引用、缺失维度）、`FailureFallbackTest`（超时/断网/限流 0 自动重试、预检 token 拒发 `F-07`、调用后超额 `F-08`、拒绝同意 `F-06`），以及扫描 AI 包不能引用安装后端与特权服务的 `AdvisorIsolationTest`。"
  *"Finally Stage 4: write `AdvisorAndIntegrationTest.kt`, covering `OutputValidationTest` (reject action fields, guarantee language, fabricated refs, missing dimensions), `FailureFallbackTest` (timeout/network-loss/rate-limit with 0 automatic retries, preflight token rejection `F-07`, post-call overage `F-08`, consent declined `F-06`), and `AdvisorIsolationTest` scanning that the AI package never references installer backends or privileged services."*
- **Agent (Turns 52–53):** Created `AdvisorAndIntegrationTest.kt` covering `OutputValidationTest` (3 tests), `FailureFallbackTest` (6 tests), and `AdvisorIsolationTest` (2 tests).
- **Type:** redirect.
- **Why (stated):** Verify `REQ-IX-FR-004`, `REQ-IX-INV-001`, `REQ-IX-INV-004`, `REQ-IX-COST-001`, and `permission-model.md` §9.2 isolation rules.
- **Outcome:** Created `AdvisorAndIntegrationTest.kt`.

### 4.2 First offline test and formatting verification gate (Turns 54–61)
- **Agent (Turns 54–61):** Executed `./gradlew testUnstableDebugUnitTest --tests "com.rosan.installer.verification.*"` and `spotlessApply spotlessCheck`, removed an unnecessary `!!` warning in `VerificationFakesAndEngines.kt`, and reported exact counts: 10 suites / 54 tests total (51 passed, 3 main-source static scans skipped via `assumeTrue` because `src/main` packages were not yet merged in that snapshot, 0 failures).
- **Type:** stop (verification checkpoint).
- **Outcome:** Verified all offline suites compiled, passed `spotlessCheck`, and followed `verification-constraints.md` §7 honest reporting.

---

## Stage 5 — Integration with merged production classes and PR #25 outcome (Turns 73–84)

### 5.1 Redirect to merged upstream production classes (Turns 73–75)
- **Operator (Turn 73):** "我看到团队主分支已经合入了真实的 `DeterministicPolicyEngine`、`DefaultAiRiskAdvisor`、`StrictAiOutputValidator`、`ModelRequestGate` 和 `AiRequestBuilder`。请专门写一个 `AiPolicyIntegrationTest.kt`，把这些 `src/main` 里的真实生产类串联起来做端到端集成测试，并更新 `PolicyWithoutModelTest` 的扫描路径。"
  *"I saw that the team's `main` branch has merged the real `DeterministicPolicyEngine`, `DefaultAiRiskAdvisor`, `StrictAiOutputValidator`, `ModelRequestGate`, and `AiRequestBuilder`. Please write a dedicated `AiPolicyIntegrationTest.kt` wiring these real `src/main` production classes end-to-end, and update `PolicyWithoutModelTest`'s scan path."*
- **Agent (Turns 74–75):** Inspected the merged production classes (`DeterministicPolicyEngine.kt`, `PolicyDecisionModels.kt`, `RequestInstallRiskAssessmentUseCase.kt`, `StrictAiOutputValidator.kt`, `AiRequestBuilder.kt`, `ModelRequestGate.kt`, `EvidenceFixtures.kt`), authored `AiPolicyIntegrationTest.kt` (9 end-to-end tests bridging `RequestInstallRiskAssessmentUseCase.Status` to `DeterministicPolicyEngine.evaluate(PolicyInput)`), and added `com/rosan/installer/domain/engine/policy` to `PolicyWithoutModelTest.kt`.
- **Type:** redirect → approve.
- **Why (stated):** Ensure the verification suite directly exercises the real merged production code rather than remaining solely against test-scoped doubles.
- **Outcome:** Created `AiPolicyIntegrationTest.kt` and activated real main-source scanning in `PolicyWithoutModelTest.kt`.

### 5.2 Resolve compile & Spotless build issues and pass 100% of verification checks (Turns 76–84)
- **Agent (Turns 76–84):** Fixed a named-parameter mismatch on `DefaultAiRiskAdvisor` in `AiPolicyIntegrationTest.kt`, resolved a concurrent `spotlessKotlin` / `build/` race by running `spotlessApply spotlessCheck` sequentially before `testUnstableDebugUnitTest`, and verified all **11 verification classes / 63 tests passed with 0 skipped, 0 failures, and 0 errors** alongside passing `spotlessCheck`.
- **Type:** stop (final verification gate).
- **Outcome:** Branch `feat/d-verification-suite` verified green locally.

### 5.3 PR #25 review and adoption outcome (post-submission)
- **Context:** During PR #25 review (`AI-wxxsfxyzm-0016`), the project lead verified that `docs/verification/test-plan.md`, `transcripts/60747744-YangXinyu.md`, `PolicyWithoutModelTest.kt`, and `AiPolicyIntegrationTest.kt` directly verify production code and merged PR #25 into `main`. The 6 shadow-implementation suites (`VerificationContracts.kt`, `VerificationFakesAndEngines.kt`, and the 4 test files wired to them before PR #16/#27 merged) were removed at review and recorded in `docs/debt-register.md` as **`DEBT-002`** for rebuilding against the merged `domain.engine.policy` and `domain.engine.approval` production packages.
- **Outcome:** Member D's test plan, supervision transcript, static policy check, and production-wired AI+Policy integration suite landed cleanly on `main`.

---

## What Member D's supervision caught and enforced

1. **Plan-before-code discipline (Stage 1).** Member D restricted initial generation to `docs/verification/test-plan.md` and `PolicyWithoutModelTest.kt` while upstream interfaces were unfrozen, preventing premature coupling.
2. **AI `HIGH` risk never produces `BLOCK` (1.3).** Member D explicitly required contrasting `P-05` (`DR-07 REQUIRE_REVIEW`) against `P-15`/`P-20` (`DR-01 BLOCK`), ensuring AI risk cannot impersonate deterministic hard blocks.
3. **Full `INV-003` binding & `NFC-OUT-004` fidelity (1.4, 3.1).** Member D enforced explicit checks across all 9 bound fields, TOCTOU APK SHA-256 re-reads, distinct target vs. source package checks, and 20-way concurrent idempotency.
4. **Separate negative invariant suites (2.4).** Member D required standalone `HardBlockPrecedenceTest` and `NoDowngradeTest` suites per `verification-constraints.md` §1 rather than burying negative invariants inside happy-path tables.
5. **Production-wired integration once upstream merged (5.1–5.2).** As soon as `domain/engine/policy` and `domain/ai` landed on `main`, Member D directed building `AiPolicyIntegrationTest` directly on top of the merged production classes and updating `PolicyWithoutModelTest` so static scans executed with 0 skips.
