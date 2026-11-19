# PR #13 Architecture Review Fixes — Execution Plan

**Status:** Approved 2026-11-19 for plan and tests together; E-4 resolved as
option (a) *implement the five fields* (human decision, 2026-11-19).
**Role:** plan → build
**Date:** 2026-11-19
**Branch:** `feat/ai-risk-advisor` (PR #13)
**Reviewer:** team lead, Architecture Review — AI Advisor Interfaces (PR #13)
**Requirements source:** `docs/req/requirements.yaml` rev.6; `adr/001-architecture-choice.md` (Option B, accepted); `permission-model.md` §9; `architecture-blueprint.md`.

---

## 0. Approval Gate

`Prompt.md` §2 requires explicit human approval of **both** this plan and the
test changes before any code changes. Silence, partial replies, or approval of
a different plan do not count.

Nothing below authorizes a commit, push, or PR edit (`AGENTS.md` Repository
Operations).

**Open decision (needs your answer): E-4.** See §3.3.

---

## 1. Traceability

| Review item | Requirement / ADR / criterion | Artifacts touched |
|---|---|---|
| E-1 `device_api_level` sent | REQ-IX-SEC-004, NFC-OUT-006, `outbound_metadata_allowlist.local_only[device_api_level]` | `InstallEvidence.kt`, `AiRequestBuilder.kt`, 5 test files |
| E-2 `data/ai` not scanned | REQ-IX-INV-001, REQ-IX-INV-006, `permission-model.md` §9.2–9.3, ADR-001 §6 | `AdvisorIsolationTest.kt` |
| E-3 `GROQ_API_KEY` in `BuildConfig` | REQ-IX-FR-009 | `app/build.gradle.kts`, `di/AiModule.kt`, new isolation test |
| E-4 five allowlisted fields missing | REQ-IX-FR-002, REQ-IX-FR-003, NFC-OUT-002 | either `InstallEvidence.kt` + `AiRequestBuilder.kt`, or PR text only |
| (non-blocking) `domain/ai` uses `kotlinx.serialization` | — | none; recorded only |

---

## 2. Scope boundaries

**In scope:** the files named in §3.

**Out of scope:** `docs/req/`, `docs/architecture/`, `docs/code-governance/`,
`AGENTS.md`, `Prompt.md`, `architecture-blueprint.md`, `.github/workflows/`,
`.opencode/*`, `.agent/rules.md`, UI code, the deterministic policy layer.
`trace-matrix.md` (E-4 wording) needs human approval per `AGENTS.md`; I will
not edit it.

---

## 3. Steps

### 3.1 E-1 — stop transmitting `device_api_level` (High)

1. `domain/ai/usecase/AiRequestBuilder.kt:59` — delete
   `evidence.deviceApiLevel?.let { put("device_api_level", it) }`.
2. `AiRequestBuilder.kt:142` — delete `"evidence.device_api_level"` from
   `ALLOWLISTED_LEAF_PATHS`; note in the KDoc that `device_api_level` is a
   `local_only` entry (NFC-OUT-006).
3. `domain/ai/model/InstallEvidence.kt:35` — drop `deviceApiLevel`. It is not
   read by `completeness()` and has no local consumer.

**Tests (written first):**

- `AiRequestBuilderTest.request_containsExactlyAllowlistedLeafPaths` — remove
  `evidence.device_api_level` from the expected set; drop
  `deviceApiLevel = 35` from `sample()`.
- **New** `AiRequestBuilderTest.payload_neverContainsDeviceApiLevel` —
  a regression guard that asserts `evidencePayloadJson` and the full
  user prompt contain no `device_api_level` key for a fully populated
  evidence set (NFC-OUT-006 negative test).
- `AdvisorScenarioTest.incompatible_sendsBothIncompatibilitySignals:150` —
  delete the `device_api_level == 29` assertion; **add**
  `assertFalse(payload.containsKey("device_api_level"))`.
- `AdvisorScenarioTest.everyScenario_sendsOnlyAllowlistedFields` — unchanged
  (it now also catches a reintroduction).
- `EvidenceFixtures.kt:48,71`, `AdvisorFailureReportingTest.kt:49` — remove
  the `deviceApiLevel = …` arguments.

### 3.2 E-2 — extend the isolation check to `data/ai` (High)

`AdvisorIsolationTest` (ADR-001 §6 check-based enforcement):

1. Add `data/ai` as a second scanned root (`advisorySources("data/ai")`;
   keep the existing `domain/ai` scan untouched).
2. New test `dataAiPackage_importsNoBackendOrPrivilegedType` — no file under
   `data/ai` may import `domain.engine.repository`, `data.engine.executor`,
   `domain.privileged`, `framework.privileged`, `domain.session`, or
   `ActionHandler` (scan for both `import` statements and fully-qualified
   usages, so a same-package reference cannot slip through).
3. New test `dataAiPackage_networkAndJsonLibrariesOnlyUnderProvider` —
   `okhttp3.*`, `kotlinx.serialization.*`, `java.net.*`, `retrofit2.*` are
   permitted only under `data/ai/provider/**`.
4. New sanity test `dataAiPackage_isScanned` (≥ 5 files, imports > 0).

Current state is expected to pass: `data/ai/gate` and `data/ai/advisory`
import only `domain.ai.*`, `domain.settings.repository`, and kotlinx
coroutines; `OkHttpAiTransport` and `GroqAiModelClient` live under
`provider/`.

### 3.3 E-3 — keep the key out of `BuildConfig` (Medium)

1. `app/build.gradle.kts:45-51` — remove the `GROQ_API_KEY`
   `buildConfigField` entirely and the `java.util.Properties` import if it
   becomes unused. No secret is compiled into any variant.
2. `di/AiModule.kt:50-53` — the `apiKeyProvider` reads only the process
   environment `GROQ_API_KEY` (the documented live-test path); update the
   KDoc and drop the `BuildConfig` import.
3. New test `AdvisorIsolationTest.boundingSecrets_areNotCompiledIntoBuildConfig`
   — scans every source file for `buildConfigField(… "GROQ_API_KEY"` and for
   the `gsk_` key prefix, so the pattern cannot return unnoticed.

**Not changing:** `GroqLiveAdvisorTest` already reads the environment
variable; the runtime-settings injection option is deferred (needs a new
preference, a settings UI row, and a consent/privacy review — larger than this
review item).

**PR-description facts I verified locally and will report (not clickable to
you unless confirmed):**

- No workflow under `.github/workflows/` references `GROQ_API_KEY`; only
  `secrets.GITHUB_TOKEN` is used (`pr-check.yml:103,143`).
- The key is only ever placed in the `Authorization` header
  (`OkHttpAiTransport.kt:70-75`) and is never logged or put into failure text.
- History check (`git log -S GROQ_API_KEY`) to confirm only the empty-default
  plumbing was ever committed — to be run and reported in §4.

### 3.4 E-4 — the five allowlisted fields (Low) — **DECISION NEEDED**

The reviewer allows either option. My recommendation is **(a) implement**, for
three reasons: (i) `completeness.required_fields_always_present: true` and
NFC-OUT-002 say the allowlisted paths must be present, so a reduced subset
silently changes the contract; (ii) all five have ready local sources; (iii)
it is a contained change with no privacy cost (every value is already a
derived enum).

**(a) Implement (recommended).** Add to `InstallEvidence` + the builder,
mirroring the rev.6 enum values exactly:

| Field | Enum values | Builder unknown encoding |
|---|---|---|
| `install_mode` | `AutoDialog`, `AutoNotification` | omitted when null |
| `target_user_scope` | `CURRENT_USER`, `OTHER_USER`, `ALL_USERS`, `UNKNOWN` | `UNKNOWN` |
| `package_source` | `UNSPECIFIED`, `OTHER`, `STORE`, `LOCAL_FILE`, `DOWNLOADED_FILE` | `UNSPECIFIED` |
| `source_confidence` | `EXACT_CALLER`, `LAUNCHED_FROM_UID`, `TRUSTED_PROXY_ORIGINATING_UID`, `PROVIDER_OWNER`, `REFERRER_HEURISTIC`, `UNKNOWN` | `UNKNOWN` |
| `installer_capability` | `SYSTEM_PACKAGE_INSTALLER`, `UNKNOWN` | `UNKNOWN` |

New advice enums are declared in `domain/ai/model/AdvisoryEnums.kt` (mirroring
the existing `LocalCompatibility` pattern) so `InstallEvidence` does not
depend on `domain/settings/model` or `domain/session`. New allowlist entries
go in `ALLOWLISTED_LEAF_PATHS` with the REQ-IX-SEC-004 note. **No collector
change in this PR** — collection is `REQ-IX-FR-002` `InstallEvidenceCollector`
work with its own test; flagging that as a follow-up.

Tests: builder allows/encodes each of the five; `AiRequestBuilderTest`
expected-path set updated; `AdvisorScenarioTest` gains one scenario-shape
assertion for the new fields.

**(b) Declare the reduced subset instead.** No code change; add a PR note and
ask the human to approve an accompanying `trace-matrix.md` edit.

### 3.5 Non-blocking note

`domain/ai` + `kotlinx.serialization` coupling (reviewer: "no change
required now"). Recorded; no change.

---

## 4. Verification plan

| Check | Command |
|---|---|
| Formatting | `./gradlew spotlessCheck` (and `spotlessApply` before it) |
| Focused tests | `./gradlew :app:testUnstableDebugUnitTest --tests "com.rosan.installer.domain.ai.*"` |
| Full unit suite (regression) | `./gradlew testUnstableDebugUnitTest` |
| Smoke build | `./gradlew assembleUnstableDebug` |
| Secret history | `git log --all -S "GROQ_API_KEY" --oneline` |
| Secret scan | repo-wide search for `gsk_` |

Every test above references its requirement/invariant in a KDoc header, per
`verification-constraints.md`. I will report each command's actual result and
anything not run.

---

## 5. Risks and open questions

1. **E-4 option (a) vs (b)** — needs your decision.
2. **Android build environment** — the unit/build commands need an Android SDK
   and a JDK the project supports; if the local machine cannot run them, I
   will say so instead of claiming a pass.
3. **`install_mode` required-but-nullable** — rev.6 lists no `UNKNOWN`
   encoding for it; I omit it when unknown and report that in the PR, so the
   decision layer must not read a missing key as a value.
4. **`Agents must not self-approve`** — I will not push or open/edit the PR.
5. **E-3 fully closing the item** requires a PR-description statement, which
   only a human can paste.
