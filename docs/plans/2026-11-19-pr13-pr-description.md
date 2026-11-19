# Pull Request

## 1. Summary

Addresses the four blocking items and one note from the architecture review of PR #13 (AI advisor interfaces).

- **E-1 (High, privacy).** `device_api_level` is a `local_only` allowlist entry (`requirements.yaml`), yet it was transmitted. It is now removed from the request builder, the allowlist, and `InstallEvidence`; only the derived `platform_compatibility` reaches the model. Five tests that previously encoded the violation now assert the field is absent, plus a new regression guard (NFC-OUT-006).
- **E-2 (High, isolation).** `AdvisorIsolationTest` now also scans `data/ai`: no file may reference `domain.engine.repository`, `data.engine.executor`, `domain.privileged`, `framework.privileged`, `domain.session`, or `ActionHandler` (imports and fully-qualified uses), and `okhttp3`/`kotlinx.serialization` are allowed only under `data/ai/provider`. Verified by injecting a violating import: the check fails, and passes once reverted.
- **E-3 (Medium, credentials).** The `GROQ_API_KEY` `buildConfigField` is removed from `app/build.gradle.kts`. The key is read at runtime from the process environment only (`di/AiModule.kt`), so no secret is compiled into any APK or CI artifact. A new guard fails if `buildConfigField(...GROQ_API_KEY...)` or a Groq key literal reappears. Verified by injecting the field: the guard fails, and passes once reverted.
- **E-4 (Low, coverage).** The five D1 context fields are implemented (option (a)): `install_mode`, `target_user_scope`, `package_source`, `source_confidence`, `installer_capability`, with enum spellings exactly as in `requirements.yaml` rev.6 and the documented `unknown_encoding` values.
- **Unrelated build fix (please confirm).** `PendingApkSignatureAnalyzer` called `isVerifiedUsingV32Scheme`, which does not exist in the `apksig` bundled with Android SDK Build Tools 37.0.0 (the jar the app actually compiles against). The reference broke compilation on the branch; it is removed because the flag only fed a display label. See §7.

No change to the deterministic policy layer, the installer backends, or the UI.

## 2. Change Type

- [ ] Requirements
- [ ] Architecture / ADR
- [x] Implementation
- [x] Tests / Verification
- [ ] AI Prompt / Model
- [ ] Agent Governance
- [ ] Documentation only

## 3. Traceability

- Related Requirements: REQ-IX-SEC-004, REQ-IX-SEC-001, REQ-IX-FR-002, REQ-IX-FR-003, REQ-IX-FR-009, REQ-IX-INV-001, REQ-IX-INV-006, REQ-IX-MOD-001
- Related ADR(s): ADR-001 (Option B, accepted) — §6 check-based enforcement; ADR-002.1 (completeness); ADR-002.2 (adapter/transport)
- Related Invariants: REQ-IX-INV-001, REQ-IX-INV-004 (unchanged behaviour, re-verified), REQ-IX-INV-006
- Related Negative Fit Criteria: NFC-OUT-001, NFC-OUT-002, NFC-OUT-006
- Related Issue / Approved Plan: PR #13 architecture review; approved plan `docs/plans/2026-11-19-pr13-architecture-review-fixes.md`

## 4. Human Approval

- Was the implementation plan approved before coding? Yes.
- Were the relevant tests approved before implementation? Yes — the plan and its test design were approved together before any code was written (E-4 resolved to option (a) *implement the five fields*).
- Approval evidence (PR/Issue/Transcript link): recorded in `docs/plans/2026-11-19-pr13-architecture-review-fixes.md` §0 (`Status: Approved 2026-11-19 … E-4 resolved as option (a)`).

## 5. Verification

- Tests executed: `./gradlew spotlessCheck`; `./gradlew testUnstableDebugUnitTest --rerun-tasks`; `./gradlew assembleUnstableDebug`; `./gradlew assemblePreviewDebug -PAPP_ID="com.rosan.installer.x.revived.test"` (local Windows, JDK Temurin 25, Android SDK platform 37 / Build Tools 37.0.0).
- Tests passed / failed: 253 unit tests in 40 classes, 0 failures, 0 errors. AI suites: `AdvisorIsolationTest` 7, `AdvisorScenarioTest` 15, `AiRequestBuilderTest` 16, `AdvisorFailureReportingTest` 13, `DefaultAiRiskAdvisorTest` 15, `EvidenceSanitizerTest` 7, `StrictAiOutputValidatorTest` 23.
- Regression impact: none. The full unit suite and both builds pass; `LightweightApkSignatureReaderTest` and `PendingApkSignatureAnalyzerTest` still pass after the build fix.
- Security / architecture checks:
  - E-1 — no `device_api_level` in the payload or prompt for any fixture; the key is absent from `ALLOWLISTED_LEAF_PATHS`.
  - E-2 — `data/ai` scan added; injection test confirmed the check is load-bearing.
  - E-3 — no `GROQ_API_KEY` field in the generated `BuildConfig.java` or the built APK; no workflow under `.github/workflows/` sets `GROQ_API_KEY`; `git log --all` contains no key-shaped literal; the key is only placed in the `Authorization` header and is never logged or included in failure text.
- Evidence or CI links: local run only; CI will re-run `pr-check.yml`. Screenshots/logs available on request.
- Device(s) tested: none — unit tests and builds only; no on-device test was performed.

## 6. AI Coding Agent Usage

- Was an AI coding agent used? Yes.
- Agent and model: OpenCode v1 (coding agent), `cityu/CS5351/DeepSeek-V4-Flash`.
- Related transcript: agent session transcript for the PR #13 review fix, to be attached with the AI-use log per `AGENTS.md`.
- AI-use log updated: No — `docs/ai-use-log.yaml` is outside the approved task scope; the session entry is drafted separately for human commit.
- Significant human corrections: (1) the human rejected the draft-and-wait shortcut and required the files to be persisted in the repository; (2) the human decided E-4 as *implement* rather than *declare a reduced subset*; (3) the human authorised push + PR creation. Also noted: the agent's first version of the E-3 guard derived the wrong `app/` directory (the test task runs with `user.dir` inside `app/`) and a probe run exposed it; the guard was fixed and re-verified by injection.

## 7. Risk and Scope

- Affected modules: `domain/ai` (model, usecase), `data/ai` (no code change; covered by new test), `di/AiModule.kt`, `app/build.gradle.kts`, `data/engine/signature/PendingApkSignatureAnalyzer.kt`, AI unit tests, plan document.
- Safety-critical changes: none in the deterministic path. The AI component stays advisory; no installer, privileged, session, or network capability is added.
- Known limitations:
  - E-4: no `InstallEvidenceCollector` yet populates the five context fields — that is `REQ-IX-FR-002` collection work with its own tests (follow-up).
  - `install_mode` has no `unknown_encoding` in rev.6, so an unknown mode is omitted and completeness degrades to `KEY_UNKNOWN`; flagged for Member A.
  - The `domain/ai` → `kotlinx.serialization` coupling noted in the review is unchanged, as the reviewer indicated no change is required now.
  - E-3 injects the key only through the process environment; runtime injection through the app settings layer is deferred (needs a preference, a UI row, and a consent review).
- Follow-up tasks: implement `InstallEvidenceCollector` for the five context fields; decide the `install_mode` unknown encoding; attach the transcript and update `docs/ai-use-log.yaml`; confirm the `apksig`/`isVerifiedUsingV32Scheme` build fix.

## 8. Reviewer Decision

(To be recorded by the reviewer)

- Verdict: APPROVE / CHANGES REQUIRED / REJECT
- Reviewer:
- Date:
- Reasoning:
- Required follow-up actions:
