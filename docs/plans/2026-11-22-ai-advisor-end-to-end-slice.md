# Execution Plan — AI install-risk advisor, end-to-end in the running app

**Role:** plan (agent `project-plan`)
**Date:** 2026-11-22
**Baseline:** `main` at `e8420f8c` (PRs #26, #27, #28 merged).
**Requirements registry:** `docs/req/requirements.yaml` rev.6 (`draft_for_group_review`).
**Related ADRs:** ADR-001 (accepted, Option B), ADR-002.1 (implemented), ADR-002.2 (implemented, provider decided), ADR-002.3 (not started — persistence stays deferred), ADR-002.4 (not started — dual-UI review/consent), ADR-002.5 (gates merged, consent UI and production wiring not complete), ADR-002.6 (specified), ADR-002.7 (specified).
**Approved inputs:** `docs/plans/2026-11-21-approval-coordinator-binding-gateway.md` §9 items 3–5 (approved, merged #27); `docs/plans/2026-11-20-autodialog-install-evidence.md`; `docs/plans/2026-11-21-autodialog-model-egress.md`.
**Status:** **APPROVED (rev.3)** by wxxsfxyzm on 2026-11-22 — see §13 approval record. Approved scope: this plan and its tests (§4.1–4.4). Build may begin but does **not** start in this session (the human starts the build role). No docs/architecture edits, no commit/push/PR in this session.
**Rev.3 changes (from review):** (1) **Q9 answered = Option (b):** REQ-IX-ARCH-001 is narrowed for v1 to **AI-enabled in-scope requests**; advisor-off keeps the baseline `session.install(true)` path (FR-012), advisor-on goes through `ApprovalCoordinator -> InstallationGateway` with no exception. Section 1.1 now claims ARCH-001 only for advisor-on; §9 records the human requirement/ADR edit. Checkpoint C is **unblocked**. Added a test that the advisor-off path makes **0 model calls** and **does not construct the coordinator** (§4.1). (2) **`InstallWaitingUnknownSource` is not terminal:** the gateway keeps waiting through the permission prompt and classifies only on a terminal stage (success ⇒ `Submitted`, failure/cancel ⇒ `Failed`, unreconcilable ⇒ `ResultUnknown`); added a waiting→granted→success test (§2.9, §4.1).
**Rev.2 changes (from prior review, retained):** ARCH-001 not claimed "Yes" until resolved; gateway wraps `session.install(true)` with per-session coordinator/gateway/facts and only store+ledger shared (§2.9.1); one assessment trigger with a no-second-dispatch guard (§2.7); key log-scan test (§4.1); `--offline` removed (§4.3); answers Q1–Q8.
**Checkpoint 0 evidence (lead, 2026-11-22):** the course endpoint `https://llm.cs.cityu.edu.hk/v1`, model `CS5351/DeepSeek-V4-Flash` (LiteLLM 1.89.2), passed an exploratory live smoke — `chat/completions` + `response_format: json_object` accepted (HTTP 200, ~1.1 s), no reasoning in `message.content`, 3/3 `finish_reason=stop`, 527→236 tokens under 512, output inside `StrictAiOutputValidator`. Build starts from these confirmed facts (see §2.4).

---

## 0. Facts verified in the repository (evidence for every claim below)

| Claim | Evidence (file:line) |
|---|---|
| No production caller of the advisory chain; installs go through the original path | `grep` finds no reference to `RequestInstallRiskAssessmentUseCase`, `DeterministicPolicyEngine`, or `ApprovalCoordinator` under `app/src/main` outside their own definitions. `ProgressHandler.onAnalysedSuccess()` (`ProgressHandler.kt:51-69`) calls `session.install(true)`; `InstallerViewModel.install()` (`InstallerViewModel.kt:817-821`) and the AutoDialog auto-path (`InstallerViewModel.kt:597-603`) both forward to `session.install(true)`. |
| Provider is the Groq test config | `GroqAiModelClient.kt:72-81` (`BASE_URL` groq, `DEFAULT_MODEL = qwen/qwen3-32b`, `ENV_API_KEY = "GROQ_API_KEY"`); `AiModule.kt:50-60` wires the key from `System.getenv`. |
| Transport is already OpenAI-compatible, `response_format json_object`, `max_tokens` | `OpenAiCompatibleTransport.kt:17-50` (`OpenAiCompatiblePayload.build`), `GroqAiModelClient.kt:36-44` (`jsonObjectMode = true`). |
| Two egress/consent checkpoints | `OkHttpAiTransport.kt:70-83` (`egressAllowed()` then `ModelRequestGate.hasCurrentConsent()` right before dispatch); `RequestInstallRiskAssessmentUseCase.kt:74-84` (entry gate). |
| Consent source is a coroutine-context element | `ModelRequestGate.kt:20-38`. |
| Evidence mapper exists | `AutoDialogInstallEvidenceMapper.kt:31-123` (`Result.Mapped/Incomplete/OutOfScope`; `installMode = AutoDialog` hard-coded). |
| Readiness for the new client | `http` (RFC text) and `java.net.URI` are available on all supported API levels (min 26, `build-plugins/src/main/kotlin/BuildConfig.kt:9`); no new dependency is needed for validation. |
| `androidx.security:security-crypto` is **not** a dependency | Absent from `gradle/libs.versions.toml`; adding it is a dependency change requiring approval. |
| DataStore is the settings store; backup/export is key-driven | `SettingsModule.kt:74-99`; `AppDataStore.kt:30-198` (`supportedKeys` registry); `BackupRepositoryImpl.kt:296-314` (`toBackupSettings` only exports registered keys) and `:278-294` (`writeSettings` only restores registered keys). |
| App backup already disabled | `AndroidManifest.xml:59` `allowBackup="false"`; `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` exclude everything. |
| Prompt sync contract | `.github/scripts/course_gates.py:262-294` reads `PROMPT_VERSION`, `DEFAULT_MODEL_ID`, `MAX_OUTPUT_TOKENS`, and `SYSTEM_PROMPT` from `AiRequestBuilder.kt`, then requires exactly one `prompts/*.yaml` with that `prompt_version` and matching `system_prompt`, `model_id`, `max_output_tokens`. |
| Model file today | `prompts/prompt_v1.0.yaml` (`prompt_version: ai-risk-prompt-v1`, `model_id: qwen/qwen3-32b`, `status: active`). |
| Live test + CI | `app/src/test/java/com/rosan/installer/live/GroqLiveAdvisorTest.kt` (reads `GROQ_API_KEY`, skips if unset); `ci.yml:173-218` (`semantic_eval` job passes `GROQ_API_KEY` and runs `com.rosan.installer.live.*`). The lead live-tested the course service on 2026-11-22 using `AGENT_BASE_URL`/`AGENT_API_KEY`/`AGENT_DEPLOYMENT_MODEL`; the plan reuses those names (see §2.4). |
| Coordinator/engine contracts (consumed as-is) | `ApprovalCoordinator.kt:71-248` (`decide/autoSubmit/onContinue/onCancel/onModelFailure`), `DeterministicPolicyEngine.kt:18-63`, `PolicyDecisionModels.kt:17-87`, `BindingFactsProvider.kt:16-44`, `InstallationGateway.kt:20-42`. |
| Real install seam | `ActionHandler.performInstallLogic()` `:575-601` → `ProcessInstallationUseCase` `:84-153` → `installApp` `:280-325` → `submitInstall` `:399-419` → `AppInstallerRepositoryImpl.doInstallWork` `:50-101` → `NoneAppInstallerRepoImpl.doInstallWork` `:42-140` (`PackageInstaller` session, `LocalIntentReceiver`, `installResultVerify`). |
| v1 backend is `Authorizer.None` only when the app is the system package | `ConfigModelExt.kt:7` (`Authorizer.None -> deviceCapabilityProvider.isSystemApp`). |
| Dual UI entry points | M3 `InstallPrepareDialog.kt:116-526` (buttons at `:441-526`); Miuix `InstallPrepareContent.kt:86-701` (buttons at `:638-700`). Settings: `NetworkPage.kt:79-271`, `MiuixNetworkPage.kt:69-268`. |
| No Robolectric; UI is Compose | `docs/bug-diary.md` BUG-003; UI state mapping is testable only as pure functions. |

---

## 1. Traceability

### 1.1 Requirements advanced by this slice

| ID | Priority | How this slice advances it | Fully satisfied here? |
|---|---|---|---|
| REQ-IX-FR-001 | Must | operation ID per request + attempt ID per analysis surface in the ViewModel-wiring checkpoint; backend invocations stay 0 until decision | Partial — IDs are per-process (no persistence, DEBT-003) |
| REQ-IX-FR-002 | Must | reuse `AutoDialogInstallEvidenceMapper`; compatibility facts come from the existing structured analysis outputs | Yes for the mapped inputs; completeness honesty unchanged |
| REQ-IX-FR-003 | Must | a real model call against `https://llm.cs.cityu.edu.hk/v1` with the new model and prompt; one dispatch, no auto-retry; AutoDialog only (AutoNotification deferred, 0 AI calls). Explore-confirmed live 2026-11-22 (§2.4). | Yes for AutoDialog only |
| REQ-IX-FR-005 | Must | the dialog path runs `DeterministicPolicyEngine` on collected facts; every off/unconfigured/failed case still decides | Yes (engine unchanged) |
| REQ-IX-FR-006 | Must | advisory card + policy decision in both families; BLOCK/REQUIRE_REVIEW/EXECUTE_ONCE mapped; the Install button acts as Continue for REQUIRE_REVIEW, Cancel is Cancel | Partial — agreement/disagreement text and full evidence panel are included; the "important permissions" sublist reuses the existing permission list |
| REQ-IX-FR-007 | Must | real `InstallationGateway` wrapping `session.install(true)` (the S3 seam), created per session; coordinator is the only caller | Partial — single AutoDialog submission proven; AutoNotification deferred |
| REQ-IX-FR-008 | Must | the gateway wraps `session.install(true)` so the existing progress/notification/post-install/cache lifecycle is unchanged; `RESULT_UNKNOWN` shown as unknown | Partial — delivered through existing session lifecycle, not new stages |
| REQ-IX-FR-011 | Must | consent switch in the settings card group is the `ModelRequestGate` source; turning it on shows the enumerated allowlist (≥ dialog) before it persists; consent off ⇒ 0 calls, REQUIRE_REVIEW | Partial — consent is a stored preference read per assessment; the enumerated list is shown at first enable; full first-transmission **screen** is a dialog, not a dedicated page |
| REQ-IX-FR-012 | Must | **advisor off = baseline InstallerX**: no model call, no coordinator, the existing `session.install(true)` path unchanged; the advisor switch never weakens existing checks | Yes for behaviour (0 model calls, baseline path preserved). The `AI_DISABLED` **audit record** is not written (no recorder; FR-009 deferred) — recorded as a gap |
| REQ-IX-ARCH-001 | Must | **advisor-on:** every in-scope single-APK submission goes through `ApprovalCoordinator -> InstallationGateway`, no `session.install` call outside the gateway; **advisor-off:** baseline `session.install(true)` unchanged (per Q9/Option (b) narrowing) | **Yes for advisor-on requests**; advisor-off is baseline by the authorized v1 narrowing (§2.7.1, Q9) |
| REQ-IX-ARCH-002 | Should | both families get the settings card group and the advisory card | Yes (build + manual demo per family) |
| REQ-IX-INV-001 | Must | gateway/advisory isolation unchanged; `AdvisorIsolationTest` re-run | Yes (regression) |
| REQ-IX-INV-002 | Must | engine unchanged; dialog never bypasses BLOCK | Yes (regression + new UI mapping test) |
| REQ-IX-INV-004 | Must | model failure, unconfigured advisor, consent off all route to REQUIRE_REVIEW with 0 auto-approvals | Yes (proven at the coordinator; UI maps it) |
| REQ-IX-INV-006 | Must | `DeterministicPolicyEngine` and the decision path import no SDK/network; new key store lives in `data/`, not `domain/engine` | Yes (static boundary test extended) |
| REQ-IX-INV-007 | Must | engine unchanged; the UI mapping test asserts no UI state can exceed the engine decision | Yes |
| REQ-IX-SEC-004 | Must | only the configured host is reachable; requests still pass `AiNetworkGate`; key never logged/exported | Yes for egress; log scan test added |
| REQ-IX-SEC-005 | Must | sanitization path unchanged | Yes (regression) |
| REQ-IX-MOD-001 | Should | Groq-specific client generalized to a provider-neutral client behind `AiModelClient` | Yes (adapter contract test) |

### 1.2 Explicitly NOT satisfied by this slice (honest gaps — do not claim)
- **REQ-IX-REL-002 / REL-003 / REL-004, INV-005 across restart, SEC-003 durable tamper, FR-009, DATA-001, FR-010:** persistence and the audit recorder stay deferred (DEBT-003, ADR-002.3). Approval state and the submission ledger remain in-memory.
- **REQ-IX-FR-012:** behaviour is preserved (advisor off ⇒ 0 model calls, baseline `session.install(true)`, no coordinator constructed), but there is no audit recorder to write `AI_DISABLED` (FR-009/DATA-001 deferred). Recorded as a gap; **behaviour claimed, audit not claimed**.
- **REQ-IX-SCOPE-001 AutoNotification (Should): DEFERRED and not claimed** (answered Q8). Its install path makes **0 AI calls**; the mapper only produces evidence for `SessionMode.Single` + `InstallMode.AutoDialog`. Notification mode, the ActionHandler session bypass, and multi-split installs are likewise 0-AI and not claimed.
- **ADR-002.4 / ADR-002.5 consent UI (answered Q4):** turning the consent switch on shows a **dialog listing every field in `ALLOWLISTED_LEAF_PATHS`** (FR-011 enumerated list), with Cancel leaving consent off. This is the first-transmission consent surface; a dedicated page is not built.

---

## 2. Interface contract and module boundaries

### 2.1 Provider generalization (checkpoint 0) — stays inside `data/ai`

Replace `GroqAiModelClient` with a provider-neutral `OpenAiCompatibleModelClient` behind the existing `AiModelClient` interface (`AiAdvisorContracts.kt:46-55`). The advisor, validator, policy, and UI never see the provider.

```
data/ai/provider/OpenAiCompatibleModelClient.kt   # was GroqAiModelClient.kt; same complete() contract
```

- Constructor changes: `baseUrl`, `modelId`, `json` become **values passed per call**, not compile-time constants. The endpoint and key are resolved for each call from the injected `AiEndpointConfig` (see 2.2). The client keeps the current `guardedContent`/truncation behaviour (`GroqAiModelClient.kt:54-70`).
- `OpenAiCompatibleTransport.post(path, body)` (`OpenAiCompatibleTransport.kt:61-64`) gains an explicit target instead of reading a fixed field: `post(baseUrl, path, body)`. `OkHttpAiTransport` drops its `baseUrl`/`modelId` constructor fields and takes them per call; it still reads the key via `apiKeyProvider()` and still checks `egressAllowed()` and `ModelRequestGate.hasCurrentConsent()` immediately before dispatch (`OkHttpAiTransport.kt:70-83`).
- **Host-only egress.** `OkHttpAiTransport` builds the request only when the configured URL passes `AiEndpointValidator` (HTTPS, well-formed, no userinfo, host non-empty). Because the transport constructs the URL from the validated config and **does not accept a caller-supplied URL**, only the configured host is reachable. This is belt-and-braces on top of `AiNetworkGate` (`AppSettingsNetworkGate`); both remain.
- **HttpProfile interaction.** `HttpProfile` (`HttpProfile.kt`) and its `validateSecurity` (`OkHttpNetworkResolver.kt:722-747`) apply only to the *download* resolver (`OkHttpNetworkResolver`, `SourceResolver`), not to the AI transport. The AI transport is HTTPS-only by validation, so it never depends on `HttpProfile`; it keeps the app's `OkHttpClient` via `newBuilder()` for timeouts (`OkHttpAiTransport.kt:61-67`) and does not widen the baseline network boundary. This is stated explicitly because the task asks how `HttpProfile` changes: **it does not change**; the AI path is stricter (HTTPS-only) than any `HttpProfile`.
- The client `modelId` check inside `extractContent` (`OkHttpAiTransport.kt:165-179`) compares against the per-call model id.

### 2.2 Endpoint/key configuration and storage (checkpoint A)

New seam in `data/ai/provider/` (data layer, not `domain/engine`), so the policy/invariant boundary is untouched:

```kotlin
// Implements: ADR-002.2, ADR-002.5. Enforces: REQ-IX-SEC-004, REQ-IX-FR-009, REQ-IX-MOD-001
data class AiEndpointConfig(val endpoint: String?, val apiKey: String?, val modelId: String, val promptVersion: String) {
    val isComplete: Boolean get() = !endpoint.isNullOrBlank() && !apiKey.isNullOrBlank()
}

fun interface AiEndpointConfigSource { suspend fun current(): AiEndpointConfig }
```

`AiEndpointConfigSource` reads the stored values on **each** call, so a change or a missing key disables the live call without a restart and never weakens a deterministic check.

**Storage.** Two keys: endpoint (String) and key (String). They are **not** added to `AppDataStore` (`AppDataStore.kt:30-198`). Adding them there would make the backup/export registry pick them up (`BackupRepositoryImpl.kt:296-314, 278-294`) — a privacy regression. Instead:

```
data/ai/provider/AiCredentialStore.kt          # app-private storage seam, testable
data/ai/provider/DataStoreAiCredentialStore.kt # impl over a separate Preferences DataStore file
```

- A **separate** `preferencesDataStoreFile("ai_credentials")` in app-private storage, with its own `Preferences` keys, registered in `SettingsModule.kt`. Because it is not in `AppDataStore.supportedKeys`, `BackupRepositoryImpl.toBackupSettings()` never sees it, so it is excluded from the app's settings export/import by construction (regression test asserts the exported envelope has no credential key). `allowBackup=false` and the data-extraction rules already exclude the file from Android backup.
- **No `BuildConfig`, resource, asset, or APK-embedded key.** The key is only ever read through `AiCredentialStore` at call time and placed only in the `Authorization` header (`OkHttpAiTransport.kt:78`).
- **Encryption choice (Q2 answered): plaintext in the private DataStore file this slice, no new dependency.** The threat model is non-rooted, non-backup devices; `androidx.security:security-crypto` is **not** added. This is recorded as guardrail debt (verify role, later). Adding any dependency without approval remains forbidden by `.agent/rules.md` §2.
- **Masking and no logging.** `AiCredentialStore` exposes a `maskedKey()` (`••••` + last 4) for the UI only. The key value never enters a `Timber` call, a `Failure.message`, an audit detail, or a backup. A log-scan test asserts no key substring appears in any string the credential path produces.

**Validation.** `AiEndpointValidator` (pure, testable):

```
data/ai/provider/AiEndpointValidator.kt
// HTTPS-only, absolute, host present, no userinfo, no fragment, path normalized.
// Default endpoint constant: "https://llm.cs.cityu.edu.hk/v1"
```

Reject: non-`https` scheme, relative URL, blank host, embedded credentials (`user:pass@`), control characters, whitespace. Accept: trailing-slash-insensitive, custom port allowed (still HTTPS). A rejected endpoint disables the live call and the switch cannot be turned on.

### 2.3 Model + prompt v1.1 (checkpoint 0)

- `AiRequestBuilder.kt:135-138` constants: `PROMPT_VERSION = "ai-risk-prompt-v1.1"`, `DEFAULT_MODEL_ID = "CS5351/DeepSeek-V4-Flash"`, `MAX_OUTPUT_TOKENS = 512` (**unchanged** — checkpoint 0 measured 236 completion tokens).
- **New** `prompts/prompt_v1.1.yaml` with `prompt_version: ai-risk-prompt-v1.1`, `model_id: CS5351/DeepSeek-V4-Flash`, `max_output_tokens: 512`, `status: active`, `file_version: "1.1"`, and the **same** `system_prompt`/`user_prompt_template` as v1.0 (checkpoint 0 confirmed the v1.0 prompts already produce a clean, complete reply on this model — nothing in the prompt text changes). `prompt_v1.0.yaml` is edited **only** to set `status: superseded` (its text untouched). The prompt-sync gate then finds exactly one file for the active version (verified by running `course_gates.py prompt-sync` locally).
- Because the system prompt is unchanged, `AiRequestBuilder.SYSTEM_PROMPT` does not change; only `PROMPT_VERSION` and `DEFAULT_MODEL_ID` move. The prompt-sync gate compares `system_prompt`/`model_id`/`max_output_tokens`, so it passes with the new file.
- **Out of scope for build role:** editing `docs/architecture/*` (human task, §9).

### 2.4 Compatibility with the course gateway — RESOLVED by checkpoint 0 (2026-11-22)

The lead live-tested the course service (LiteLLM 1.89.2, model `CS5351/DeepSeek-V4-Flash`, `https://llm.cs.cityu.edu.hk/v1`) on 2026-11-22. The previously open compatibility branches are now **closed**:

1. **`response_format: json_object` — accepted.** HTTP 200, ~1.1 s. Keep `jsonObjectMode = true`; **no** `jsonObjectMode` config flag and **no** retry are needed. This is the confirmed behaviour, not a proposed branch.
2. **No reasoning text in the completion.** `choices[0].message.content` contains the four keys with no `reasoning_content` and no `<think>` block. The existing extractor (`OkHttpAiTransport.kt:165-179`) reads only `message.content`, so it is unchanged.
3. **Token budget — keep 512.** With the real v1.0 system + user prompts: 3/3 runs `finish_reason=stop`, 527 prompt → 236 completion tokens, well under `max_tokens=512`. Output had exactly the required 4 keys, valid enums, 5 reasons (68–135 chars), 11 `evidence.*` refs — all inside `StrictAiOutputValidator` limits. `MAX_OUTPUT_TOKENS = 512` stays; the prompt-sync value in `prompt_v1.1.yaml` is 512. **§11 Q3 is answered: keep 512.**
4. **Endpoint choice — `chat/completions` only.** `/v1/responses` also works but returns a reasoning item, defaults to temperature 1.0, and stores completions server-side; it is **not** used. The transport path remains `chat/completions`. (Noted so a later refactor does not "upgrade" to `/responses`.)
5. **Prompt text unchanged.** Because v1.0's prompt already yields a clean, complete, validator-conforming reply on this model, `prompt_v1.1.yaml` keeps the **same** `system_prompt` and `user_prompt_template`; only `prompt_version`, `model_id`, `file_version`, and `status` change. The strict validator is unchanged; no lenient prose extraction is added. A prose-wrapped or truncated reply is still INVALID / `TokenLimitExceeded` → REQUIRE_REVIEW.

**Re-run at checkpoint 0 (regression, not discovery):** the live test replays the same evidence fixtures against the course endpoint to confirm these results still hold from the assembled client (env `AGENT_BASE_URL`, `AGENT_API_KEY`, `AGENT_DEPLOYMENT_MODEL`; see §4.3). If they regress, checkpoint 0 stops the slice.

### 2.5 Settings card group (checkpoint A)

`NetworkSettingsState.kt` gains:

```kotlin
val aiAdvisorEnabled: Boolean = false,
val aiConsentGranted: Boolean = false,
val aiEndpointConfigured: Boolean = false, // derived: validated endpoint + key stored
val aiEndpointHostSummary: String = "",    // host only for display; never the key
val aiKeyMasked: String = "",              // "••••" + last4, or empty
val aiModelPromptSummary: String = "",     // read-only e.g. "CS5351/DeepSeek-V4-Flash · v1.1"
```

`NetworkSettingsAction.kt` gains: `SetAiAdvisorEnabled(enabled)`, `SetAiConsent(granted)`, `SaveAiEndpoint(endpoint)`, `SaveAiApiKey(key)`, `ClearAiConfiguration`.

`NetworkSettingsViewModel.kt`:
- New `Setting`/store dependency for the two advisor booleans (`BooleanSetting.AiInstallRiskAdvisorEnabled`, `BooleanSetting.AiInstallRiskAdvisorConsent` — these two are safe to add to the normal settings registry; they contain no secret, so backup/export of the *switch states* is acceptable and desirable). The endpoint/key live in `AiCredentialStore` (not exported).
- `SetAiAdvisorEnabled(true)` while configuration is incomplete ⇒ emit a one-shot "configure first" event and **do not** persist `true`. The UI opens the configuration dialog on that event. Cancel leaves the switch off.
- `SaveAiEndpoint`/`SaveAiApiKey` validate before writing; an invalid endpoint is not stored and the dialog shows an error.
- The key is never placed in `NetworkSettingsState`.

Both pages render the same group with the same string resources:
- `NetworkPage.kt` (M3): a `SegmentedColumn` titled `ai_advisor_settings_title` after the existing network items, with `Switch`-style rows (reuse `BaseWidget`) for advisor on/off and consent, a `BaseWidget` "Configure" row, and a read-only `BaseWidget` for the model/prompt line. When `allowInternetAccess` is false, the group is rendered but disabled with the explanation string `ai_advisor_requires_network`.
- `MiuixNetworkPage.kt`: the same rows with `SwitchPreference`/`BasicComponent`/`SmallTitle`; same disable rule.
- Configuration dialog in both families (`CustomGithubProxyUrlDialog` is the M3 pattern at `NetworkPage.kt:133-158`; `MiuixCustomGithubProxyUrlDialog` for Miuix): endpoint field prefilled with `https://llm.cs.cityu.edu.hk/v1`, key field with `PasswordVisualTransformation`, helper text that the key is stored only on this device and never leaves it except in the request header. **No "Test connection" button** (Q1 answered).

### 2.6 Source of consent for `ModelRequestGate`

The dialog flow installs `ModelRequestGate { prefs.aiInstallRiskAdvisorConsent }` around the assessment (`withContext`) — a **reader of current state**, never a cached boolean (`ModelRequestGate.kt:20-38`). Consent off, network off, or unconfigured endpoint/key each yield 0 dispatches and REQUIRE_REVIEW (DR-02 / DR-08 / unconfigured gate).

**Turning consent on shows the enumerated allowlist (FR-011, answered Q4).** The consent switch defaults off. Flipping it on first shows a dialog that lists every field in the `EvidenceSanitizer`/`AiRequestBuilder` allowlist (`AiRequestBuilder.ALLOWLISTED_LEAF_PATHS`, 19 entries today) plus the purpose and the recipient host, and explains what stays local (`outbound_metadata_allowlist.local_only`: package names, hashes, labels, paths). **Cancel leaves consent off**; Confirm persists `aiInstallRiskAdvisorConsent = true`. The list is generated from `ALLOWLISTED_LEAF_PATHS` (single source of truth) so the dialog cannot drift from what is actually transmitted; a unit test asserts dialog entries == allowlist.

### 2.7 Dialog-flow wiring (checkpoint C) — one trigger, coordinator on every in-scope submission

**The assessment trigger (answered Q3).** There is exactly **one** trigger: when the prepare dialog is entered with the advisor on, `PrepareApprovalUseCase` runs once for that operation. The card shows a loading state, then the result. Recomposition or re-entering `InstallPrepare` **must not** dispatch again. This is enforced by a per-operation `started` guard keyed on the operation ID inside the ViewModel (not by `LaunchedEffect` re-entry), plus a test that asserts exactly one provider dispatch across repeated recompositions/stage re-entries (COST-001).

**Sequencing — `PrepareApprovalUseCase` (answered Q5).** Takes the chosen `PackageAnalysisResult` + selected `BaseEntity` and runs:

1. **Advisor off** (checked first) ⇒ **baseline InstallerX (FR-012, Q9 Option (b))**: no evidence mapping, no model call, **the coordinator is not constructed**; the ViewModel calls `session.install(true)` exactly as today (§2.7.1). Return before touching any advisory/approval element.
2. `AutoDialogInstallEvidenceMapper` → evidence or `Incomplete/OutOfScope`. Out-of-scope (splits/modules/multi-APK) ⇒ baseline behaviour, 0 AI calls (SCOPE-001).
3. **Advisor on, configured, consent present** ⇒ `withContext(ModelRequestGate{...}) { RequestInstallRiskAssessmentUseCase(evidence) }`; if unconfigured/consent off/network off, no call (card shows the unavailability reason).
4. Builds `PolicyInput` (`PolicyDecisionModels.kt:61-67`) from the `Status` + `EvaluatedPolicy` + `LocalCompatibility` (derived from the evidence's derived compatibility signals) + `HumanApprovalState.NOT_OBTAINED`.
5. `ApprovalCoordinator.decide(...)` → `Outcome.Decided(state, result)`.

**The ViewModel** (`InstallerViewModel.kt`) owns the new actions: `InstallerViewAction.RunAdvisor`, `InstallerViewAction.AdvisorCancel`. The **Install button is the Continue** for `REQUIRE_REVIEW` (it maps to `ApprovalCoordinator.autoSubmit`/`onContinue` per the disposition, §2.8), is **disabled** for `BLOCK`/`RECOLLECT`/`INVALIDATED`, and the **Cancel** button is Cancel. This applies with the advisor **on**; the advisor-off path is §2.7.1 (baseline, no coordinator).

**AutoNotification is deferred and not claimed (answered Q8).** Its install path must make **0 AI calls**: `ProgressHandler`'s AutoNotification branch and `ActionHandler` are untouched, and the mapper only produces evidence for `SessionMode.Single` + `InstallMode.AutoDialog`. A test asserts an AutoNotification-shaped session makes 0 advisory calls. Notification mode (`InstallMode.Notification`), the ActionHandler session bypass, and multi-split installs are likewise 0-AI and keep baseline behaviour; none is claimed as advisor-protected.

#### 2.7.1 Advisor-off path and REQ-IX-ARCH-001 — RESOLVED: Option (b), v1 narrowing (Q9)

**Decision (lead, 2026-11-22): Option (b).** REQ-IX-ARCH-001 is narrowed **for v1** to **AI-enabled in-scope requests**:

- **Advisor switch OFF** ⇒ the app is baseline InstallerX (FR-012): no model call, **the coordinator is not constructed**, and the existing `session.install(true)` path stays unchanged. (The requirement text says ARCH-001 has "no exception"; this narrowing is an authorized v1 scope decision recorded by the lead, not an agent assumption.)
- **Advisor switch ON** ⇒ **every** in-scope single-APK submission goes through `ApprovalCoordinator -> InstallationGateway`, with **no exception**.

Consequences for the build:
- The advisor-off branch in `PrepareApprovalUseCase` / `InstallerViewModel` simply does what the baseline does (`session.install(true)`); it **must not** instantiate or call the coordinator. A test asserts the advisor-off path makes **0 model calls** and does **not** construct the coordinator (§4.1, `PrepareApprovalUseCaseTest.advisorOffIsBaselineNoCoordinator`).
- No `DeterministicPolicyEngine`/`PolicyAdvice`/DR change is needed (the reason the earlier analysis found a conflict is now moot: advisor-off does not go through the engine).
- **Human task (outside this session):** update `docs/req/requirements.yaml` REQ-IX-ARCH-001 and the ADR (ADR-002.6 / relevant ADR) to state the v1 narrowing. The agent does **not** edit `docs/req/` or `docs/architecture/`; this is listed in §9.
- **Unblocked:** checkpoint C may start once this plan is approved (it is — §13).

The advisor-**on** path remains fully through the choke point (`ApprovalCoordinator -> gateway -> session.install`), which is the path ARCH-001 now claims for v1.

#### 2.7.2 BaseEntity, modes, and out-of-scope

- The chosen `BaseEntity`: for single-APK (the mapper's scope) it is the selected `BaseEntity` (`AutoDialogInstallEvidenceMapper.kt:79-80`). Splits/modules/multi-package are `OutOfScope` ⇒ baseline path (SCOPE-001: 0 AI calls).

### 2.8 Decision-to-stage mapping (pure, testable)

New state on `InstallerStage` (`InstallerStage.kt`) — **an addition, not a replacement**:

```kotlin
data class AiReview(
    val loadState: AiLoadState,      // Loading (shown on entry) | Ready
    val assessment: AiReviewModel?,  // likelihood, risk, reasons, agreement, modelId, promptVersion
    val policy: PolicyResult?,       // decision/disposition/ruleId/reasonCode; null while Loading
    val unavailable: AiUnavailable?, // failure/unconfigured/consent-off reason for the card
    val continueEnabled: Boolean,    // the Install button acts as Continue: true only for AWAIT_HUMAN
) : InstallerStage
```

A pure mapper `ui/page/main/installer/mapper/AiDecisionUiMapper.kt` turns `(Status, PolicyResult)` into a **sealed UI decision state**. **The Install button IS the Continue**: its `enabled` is the single source of truth, so there is no separate Continue button:

| Engine result | UI state | Install button (Continue) | Cancel |
|---|---|---|---|
| `BLOCK / BLOCKED` (DR-01) | `AiUiState.Blocked(reason)` | **disabled**, reason shown | enabled |
| `REQUIRE_REVIEW / AWAIT_HUMAN` (DR-02/04/06/07/08) | `AiUiState.Review(assessment?, failure?)` | **enabled** (acts as Continue) | enabled |
| `REQUIRE_REVIEW / RECOLLECT` (DR-03) | `AiUiState.Recollect(reason)` | **disabled** | enabled |
| `ALLOW_AUTO / EXECUTE_ONCE` (DR-05) | `AiUiState.AutoExecute` | not shown — engine auto-executes; card shown transiently, then the existing Installing stage takes over | (n/a) |
| `REQUIRE_REVIEW / EXECUTE_ONCE` (DR-09) | `AiUiState.Executing` | none (already submitting) | (n/a) |
| `REQUIRE_REVIEW / INVALIDATED` (DR-10) | `AiUiState.Invalidated(reason)` | **disabled** | enabled |
| `Outcome.PersistenceFailed` | `AiUiState.Unavailable(reason)` | **disabled** | enabled |
| *(advisor on, assessment in flight)* | `AiUiState.Loading` (card shows spinner) | **disabled** until the result arrives | enabled |

**Unavailable sub-states** (all map to `Review`/`Unavailable` ⇒ REQUIRE_REVIEW, never auto): `ConsentOff`, `Unconfigured`, `NetworkOff`, `ModelFailure(code)`, `InvalidOutput`. Each renders a distinct short reason; the Install button (Continue) is **enabled** for `ModelFailure`/`InvalidOutput` (the human may still choose to install after seeing "advice unavailable") but **disabled** for `RECOLLECT`. This mapping is a pure function with a unit test, so BUG-003 (no Robolectric) is not a blocker.

### 2.9 Real `InstallationGateway` (checkpoint B)

`data/engine/install/SessionInstallationGateway.kt` implements `InstallationGateway.submit(binding)`:

- **Only caller** is `ApprovalCoordinator` (ARCH-001). The gateway is **constructed per session** and holds that session's `InstallerSessionRepository`.
- **The seam is `session.install(true)`** (the S3 seam named in `2026-11-21-approval-coordinator-binding-gateway.md` §9 item 3), **not** `ProcessInstallationUseCase` directly. Calling `session.install(true)` keeps the whole existing session/ActionHandler lifecycle intact: `Action.Install(triggerAuth=true)` → biometric check → `performInstallLogic()` → `runWithSelfUpdateRecovery` → `ProcessInstallationUseCase` → post-install actions, notifications, cache cleanup, and progress emission (`ActionHandler.kt:257, 257→473-479, 575-601`). This is why FR-008 needs no new stage: the Installing/Success/Failed stages come from the session exactly as today.
- **How a submission result is observed.** `session.install(true)` is fire-and-forget (it enqueues an action and returns), so the gateway **collects the session's progress and classifies only on a terminal stage**:
  - `InstallSuccess` / `InstallCompleted` ⇒ `Submitted`.
  - `InstallFailed` (and `InstallAnalysedFailed` after an install action) ⇒ `Failed(reason)`.
  - **`InstallWaitingUnknownSource` is NOT terminal** — the Activity launches the "install unknown apps" settings and, on return with the permission granted, re-issues `session.install(false)` (`InstallerActivity.kt:99-117`). The gateway **keeps waiting** across the prompt and classifies the continuing progress (a reload/retry is the same operation, one permit already consumed). Only if the user leaves the flow without success (cancel / dismiss) does the gateway observe a terminal failure/cancel stage ⇒ `Failed`. If the gateway cannot reconcile a completion at all (e.g. process death mid-install) ⇒ `ResultUnknown`.
  - `MISSING_INSTALL_PERMISSION` (`NoneAppInstallerRepoImpl.kt:62-66`) is exactly the `InstallWaitingUnknownSource` stage above — it is a wait, not a failure; mapping it to `Failed` would consume the permit and leave `FAILED` after the user grants permission. **Do not do that.**
  The gateway does not re-implement progress; it only waits for and classifies what the session already emits.
- **INV-003 second belt at the seam.** Before calling `session.install(true)`, the gateway re-checks that the bound `targetPackageName` equals the session's current selected package (`session.analysisResults` selected `BaseEntity`). A mismatch ⇒ `Failed`, 0 `session.install` calls. This is defense in depth; the coordinator already revalidated.
- **No Root/Shizuku/Dhizuku widening:** the backend is whatever the session config already chose; the gateway does not change `Authorizer` or install flags. v1 demonstration is `Authorizer.None` (system app) per SCOPE-001; other backends remain baseline behaviour and are **not** claimed as advisor-protected.

#### 2.9.1 DI scoping — per-session coordinator, gateway, and facts (approved revision)

Sessions are per `InstallerSessionManagerImpl` instance (`InstallerSessionManagerImpl.kt:31-64`: `ConcurrentHashMap` keyed by id), and **two sessions can be alive at once** (e.g. two install intents). Therefore:

- **Per session (factory / session-scoped), not singleton:** `ApprovalCoordinator`, `InstallationGateway` (`SessionInstallationGateway`), and `BindingFactsProvider`.
- **Singleton (shared across sessions):** only `ApprovalStateStore` (in-memory) and `SubmissionLedger`. This is required because `SubmissionLedger` dedups per operation ID across the whole app (INV-005), and `ApprovalStateStore` is the operation registry — but note the two-session test below proves session-scoped facts do not collide.
- **No global `SessionBindingFactsSource` updated by the ViewModel.** A single mutable facts source written by whichever ViewModel is active would let two concurrent sessions overwrite each other's facts and break INV-003 (a binding built from the *other* session's package). Instead, each `SessionInstallationGateway`/`BindingFactsProvider` is bound to its **own** `InstallerSessionRepository`, and reads that session's immutable-per-operation facts (`session.analysisResults`, `session.config`) directly. The ViewModel only creates/keys the per-session objects.
- **Concrete wiring:** add a Koin `factory` (or `scope`) keyed by session id / the session repository, e.g. `factory<InstallationGateway> { (session: InstallerSessionRepository) -> SessionInstallationGateway(session) }` and a matching `BindingFactsProvider`; the ViewModel obtains them for its `session`. Singletons are registered once in `ApprovalModule.kt`; the per-session ones are `factory` (new instance per resolution), not `single`.
- The per-session coordinator/gateway must not be injectable into UI/AI/policy (reviewed at checkpoint B; isolation test extended).

**Two-session test (required):** `GatewaySessionIsolationTest` constructs two `FakeInstallationGateway`-backed coordinators, each with its own session repository and facts provider, for **different** packages (`com.example.a`, `com.example.b`), drives both through `decide` + Continue, and asserts that (i) each gateway submitted the package bound to its own session, (ii) neither saw the other's package, (iii) with a differing `apkSha256` per session, revalidation for each uses its own facts (no cross-contamination), and (iv) the shared `SubmissionLedger` still gives one permit per operation ID. This test fails if a global facts source is used.

**DI registrations (checkpoint B)** in `AiModule.kt` (provider/config) plus a new `ApprovalModule.kt` added to `appModules` (`di/init/app_modules.kt:15-33`):
  - `ApprovalStateStore` → in-memory implementation **in `data/`** (production needs one; DEBT-003 still says it is not durable — this does not claim REL-002) — **single**.
  - `SubmissionLedger` — **single**.
  - `BindingFactsProvider` — **factory, per session** (reads that session's facts; no global mutable source).
  - `InstallationGateway` → `SessionInstallationGateway` — **factory, per session**.
  - `ApprovalCoordinator` — **factory, per session**.
  - `AiEndpointConfigSource` → `DataStoreAiCredentialStore` — single.
  - `AiModelClient` → `OpenAiCompatibleModelClient` using per-call config — single (stateless).
  - The per-session coordinator/gateway must not be injectable into UI/AI/policy (reviewed in the build checkpoint; isolation test extended).

**Important DI constraint from the agent rules:** adding `ApprovalModule.kt` is a new DI file; it follows the existing one-file-per-module pattern (`structural-constraints.md`) and is **not** a generic framework. It is within the approved plan once this plan is approved.

### 2.10 Dialog UI, both families (checkpoints D/E)

A shared, resource-driven advisory card composable per family, fed **only** by the pure `AiUiState` (so no business logic in Compose):

- **M3** `ui/page/main/installer/dialog/inner/AiAdvisoryCard.kt`, embedded in `InstallPrepareDialog.kt` before the buttons (`:441`); the existing **Install button is reused as Continue** (`DialogButton(enabled = continueEnabled)`), enabled only for `REVIEW`/`ModelFailure`/`InvalidOutput`, disabled for `Blocked`/`Recollect`/`Invalidated`/`Loading`. The **Cancel** button stays Cancel.
- **Miuix** `ui/page/miuix/installer/sheetcontent/AiAdvisoryCardContent.kt`, embedded in `InstallPrepareContent.kt` before the button row (`:638`); the existing install `TextButton(enabled = continueEnabled)` acts as Continue; Cancel stays Cancel.
- Card content: success-likelihood chip, security-risk chip, up to 5 reasons, an **"Advisory only — the decision below is made by deterministic policy"** label, the policy decision + reason, a loading spinner while `Loading`, and the unavailable reason when applicable. Strings use the existing `values/strings.xml` (989 strings today) with `<string>` names under `ai_advisor_*`; add English only and let the translation pattern (many `values-*` locales) fall back to English, matching how newer strings are introduced. No strings are added to other locales by the agent unless the maintainer asks.

**One trigger, no re-dispatch (answered Q3).** Assessment starts once when `InstallPrepare` is entered with the advisor on (per-operation `started` guard). The card shows `Loading` then the result. Recomposition or re-entering the stage must not dispatch again; `AiDecisionUiMapperTest`/`PrepareApprovalUseCaseTest` assert exactly one `providerDispatches`/model call across repeated UI emissions.

**AutoDialog timing (answered Q6).** With the advisor **on**, the 500 ms auto-install (`InstallerViewModel.kt:597-603`) must **not** fire: `install()`/the auto-path advertises `RunAdvisor` (assessment), not `session.install`. DR-05 (`ALLOW_AUTO`) still auto-executes through the coordinator+gateway (engine semantics unchanged) — the card is shown transiently and then the existing Installing stage takes over. With the advisor **off**, the 500 ms baseline auto-install stays unchanged (Q9 Option (b): baseline, no coordinator).

---

## 3. Scope boundaries

### 3.1 Files to create

| File | Checkpoint | Purpose |
|---|---|---|
| `data/ai/provider/OpenAiCompatibleModelClient.kt` | 0 | provider-neutral client (replaces Groq client) |
| `data/ai/provider/AiEndpointValidator.kt` | 0/A | HTTPS/URL validation |
| `data/ai/provider/AiEndpointConfigSource.kt` (+ `AiEndpointConfig`) | A | per-call config seam |
| `data/ai/provider/AiCredentialStore.kt`, `DataStoreAiCredentialStore.kt` | A | app-private credential storage |
| `prompts/prompt_v1.1.yaml` | 0 | new prompt/model version |
| `data/engine/install/SessionInstallationGateway.kt` | B | real gateway; per-session; wraps `session.install(true)` |
| `data/engine/approval/InMemoryApprovalStateStore.kt` | B | production in-memory store (not durable) — singleton |
| `data/engine/approval/SessionBindingFactsProvider.kt` | B | **per-session** facts provider; reads that session's repository (no global mutable source) |
| `di/ApprovalModule.kt` | B | Koin: shared singletons + per-session factories |
| `domain/engine/approval/PrepareApprovalUseCase.kt` | C | mapper→advisor→policy→coordinator sequencing (one trigger) |
| `ui/page/main/installer/mapper/AiDecisionUiMapper.kt` | C/D | pure decision→UI state (Install button = Continue) |
| `ui/page/main/installer/dialog/inner/AiAdvisoryCard.kt` | D | M3 card |
| `ui/page/miuix/installer/sheetcontent/AiAdvisoryCardContent.kt` | E | Miuix card |

### 3.2 Files to change

| File | Checkpoint | Change |
|---|---|---|
| `data/ai/provider/GroqAiModelClient.kt` | 0 | deleted or reduced to a thin alias; references updated |
| `data/ai/provider/OpenAiCompatibleTransport.kt` | 0 | `post(baseUrl, path, body)` |
| `data/ai/provider/OkHttpAiTransport.kt` | 0 | per-call baseUrl/modelId; host-only |
| `data/ai/advisory/DefaultAiRiskAdvisor.kt` | 0 | contract-compatible; no provider coupling added |
| `domain/ai/usecase/AiRequestBuilder.kt` | 0 | new `PROMPT_VERSION`, `DEFAULT_MODEL_ID` |
| `prompts/prompt_v1.0.yaml` | 0 | `status: superseded` only |
| `di/AiModule.kt` | 0/A | new client + config source wiring |
| `app/src/test/java/com/rosan/installer/live/GroqLiveAdvisorTest.kt` → `CourseServiceLiveAdvisorTest.kt` | 0 | env vars `AGENT_BASE_URL`, `AGENT_API_KEY`, `AGENT_DEPLOYMENT_MODEL` (reuse existing names, no new key name); skip if any is unset |
| `.github/workflows/ci.yml` (`semantic_eval` secret name) | 0 | **separate item, human-approved** (see §10) |
| `domain/settings/repository/AppSettingsRepository.kt` (+ impl, `AppPreferences.kt`, `AppDataStore.kt`) | A | two advisor booleans only (no secrets) |
| `ui/page/main/settings/preferred/network/{NetworkSettingsState,Action,ViewModel}.kt` | A | new state/actions/validation |
| `ui/page/main/settings/preferred/network/NetworkPage.kt` | A | M3 card group |
| `ui/page/miuix/settings/preferred/network/MiuixNetworkPage.kt` | A | Miuix card group |
| `ui/page/main/installer/InstallerStage.kt` | C | add `AiReview` |
| `ui/page/main/installer/InstallerState.kt` | C | carry `aiUiState` |
| `ui/page/main/installer/InstallerViewAction.kt` / `InstallerViewModel.kt` | C | new actions; run assessment on advisor-on path |
| `ui/page/main/installer/dialog/inner/InstallPrepareDialog.kt` | D | embed card + buttons |
| `ui/page/miuix/installer/sheetcontent/InstallPrepareContent.kt` | E | embed card + buttons |
| `res/values/strings.xml` | A/D/E | `ai_advisor_*` strings (English) |
| `di/ViewModelModule.kt` | C | pass new use case/deps to `InstallerViewModel` |

### 3.3 Explicitly out of scope

- `docs/architecture/*` — human task (§9). Agents do not edit.
- `.opencode/*`, `.agent/*`, `CODEOWNERS`, release workflows.
- `ActionHandler` out-of-scope paths (session bypass, `InstallMode.Notification`, Root/Shizuku/Dhizuku widening).
- Any new Gradle dependency (including `security-crypto`) without separate approval.
- Persistence / audit recorder / FR-009 / FR-010 / DATA-001 (DEBT-003).
- Changing `DeterministicPolicyEngine`, `PolicyDecisionModels`, the `PolicyInput` fields, or any DR rule.
- A "Test connection" network button (Q1 answered: not built).

---

## 4. Testing strategy (tests listed first, with IDs)

**Style:** `kotlin.test` + `assertEquals`, no MockK, no network, no Robolectric (BUG-003). Each test names its requirement in KDoc per `verification-constraints.md` §1. Every feature has a negative test; every invariant has a test that fails if the protection is removed.

### 4.1 New JVM unit tests

| Test (ID) | Req/inv | Type | Negative case that must fail if the protection is removed |
|---|---|---|---|
| `ProviderRequestShapeTest.recordsCourseStyleRequest` | MOD-001, SEC-004 | adapter contract | asserts the outbound body has `model=CS5351/DeepSeek-V4-Flash`, `response_format` per config, `max_tokens`, and only allowed keys; a test that adds a forbidden field must fail |
| `ProviderResponseHandlingTest.mapsRecordedCourseReplies` | FR-003, MOD-001, INV-004 | fixed-response | recorded DeepSeek-style replies: bare JSON ⇒ `Valid`; fenced JSON ⇒ `Valid`; JSON+prose ⇒ `Invalid`; truncated (`finish_reason=length`) ⇒ `TokenLimitExceeded`; `reasoning_content`-only ⇒ `Invalid`/`Missing`. Removing strictness must fail a case |
| `ProviderResponseHandlingTest.jsonObjectRejected_isSingleDispatchAndReview` | FR-003, INV-004, COST-001 | negative | **defensive regression**: a `400` for `response_format` (should not occur on the course gateway per checkpoint 0) ⇒ 1 dispatch, failure code, no retry, ⇒ REQUIRE_REVIEW |
| `AiEndpointValidatorTest.httpsOnlyWellFormed` | SEC-004, FR-009 | unit (positive+negative) | rejects `http://`, relative, `user:pass@`, blank host, control chars; accepts the course URL + custom port |
| `AiCredentialStoreTest.notInExportAndMasked` | SEC-004, FR-009 | negative | after storing a key, `BackupEnvelope.settings` (built via the real `toBackupSettings` path) contains no credential key; `state.aiKeyMasked` never contains the raw key; `maskedKey` shows last 4 only |
| `AiCredentialStoreTest.keyNeverAppearsInLogsOrErrors` | SEC-004, FR-009 | negative (log scan) | store a sentinel key, drive the credential path (masking, config read, a forced failure), capture `Timber` trees + every `Throwable.message`/`toString` produced by the path, assert **no** occurrence of the sentinel key (or its ≤4-char-unmasked prefix beyond the last 4) — must fail if the key is logged or echoed in an error |
| `AiCredentialStoreTest.missingKeyDisablesLiveCall` | SEC-004 | negative | missing endpoint/key ⇒ the live call is not attempted; deterministic decision still produced |
| `AiConsentDialogTest.listsWholeAllowlist` | FR-011 | unit | the consent dialog's enumerated entries == `AiRequestBuilder.ALLOWLISTED_LEAF_PATHS` (cannot drift); Cancel leaves `aiInstallRiskAdvisorConsent = false`; Confirm persists true |
| `AiSettingsToGateTest.consentAndSwitchMatrix` | FR-011, FR-012, SEC-004 | unit matrix | switch off ⇒ 0 calls + baseline; consent off ⇒ 0 calls + REQUIRE_REVIEW; network off ⇒ 0 calls; on+consented+configured ⇒ 1 call |
| `PrepareApprovalUseCaseTest.decisionRouting` | FR-001/005/007, INV-004, INV-007 | unit | maps `Status`+policy through the coordinator: BLOCK ⇒ BLOCKED; model failure ⇒ REQUIRE_REVIEW; OutOfScope ⇒ baseline, 0 AI calls |
| `PrepareApprovalUseCaseTest.advisorOffIsBaselineNoCoordinator` | FR-012, Q9(b), ARCH-001 | negative | with the advisor switch off, the path makes **0 model calls**, **does not construct/call the coordinator**, and calls `session.install(true)` exactly as baseline; must fail if the advisor-off branch enters the coordinator |
| `PrepareApprovalUseCaseTest.singleDispatchAcrossRecomposition` | FR-003, COST-001 | negative | entering `InstallPrepare` with the advisor on dispatches exactly **one** model call; repeated recomposition / re-entering the stage / re-emitting the same operation does **not** dispatch again (the per-operation `started` guard); must fail if the guard is removed |
| `SessionInstallationGatewayTest.packageMismatchBlocks` | INV-003, ARCH-001 | negative | if the bound package ≠ the session's current selected package ⇒ `Failed`, 0 `session.install` calls |
| `SessionInstallationGatewayTest.callsSessionInstallNotProcessInstallation` | FR-007, ARCH-001 | unit (spy) | a valid submission calls `session.install(true)` exactly once and never reaches a backend by another route; `InstallSuccess` ⇒ `Submitted`, `InstallFailed` ⇒ `Failed` |
| `SessionInstallationGatewayTest.waitingUnknownSourceIsNotTerminal` | FR-007, INV-005 | negative (state machine) | progress `InstallWaitingUnknownSource` → (user grants permission) → `InstallSuccess` yields **one** submission classified `Submitted`, permit consumed once, state `SUBMITTED` (not `FAILED`); a wait followed by cancel ⇒ `Failed`, 0 extra submissions. Must fail if the gateway treats `InstallWaitingUnknownSource` as terminal |
| `AiDecisionUiMapperTest.decisionToUiState` | FR-006, INV-002, INV-007 | unit (pure) | each DR result → the table in §2.8; `continueEnabled` is true only for `AWAIT_HUMAN`/`ModelFailure`/`InvalidOutput`; a UI state can never be more permissive than the engine (INV-007) |
| `AiDecisionUiMapperTest.unavailableIsReviewNotAuto` | INV-004, FR-011 | negative | every `Unavailable` subtype ⇒ REQUIRE_REVIEW, `continueEnabled = true` (except RECOLLECT), but never auto-execute |
| `GatewaySessionIsolationTest.twoSessionsDoNotCrossContaminate` | INV-003, INV-005 | negative (two sessions) | two sessions for **different** packages/hashes: each gateway submits its own package, neither sees the other's, per-session facts are independent, and the shared `SubmissionLedger` still yields one permit per operation ID. Must fail if a global facts source is used |
| `ChokePointTest` (extend existing) | ARCH-001 (advisor-on) | structural + spy | for advisor-on, the production gateway is the only path to `session.install`; a direct `session.install` call from the advisor-on UI path is not reachable; 0 bypasses. Advisor-off is baseline by the v1 narrowing (§2.7.1) |
| `AiIsolationTest` (extend existing `AdvisorIsolationTest` + policy boundary) | INV-001, INV-006 | static | `data/ai/provider` + `domain/engine/approval` import no policy-SDK/network from `domain/engine/policy`; `DeterministicPolicyEngine` imports nothing new |
| `PromptSyncTest` (or run the CI script locally) | MOD-001 | static | `course_gates.py prompt-sync` passes with `prompt_v1.1.yaml`; a mismatched system prompt fails |

### 4.2 Existing suites that must still pass (regression)

`AiRequestBuilderTest`, `StrictAiOutputValidatorTest`, `AdvisorScenarioTest`, `AdvisorFailureReportingTest`, `EvidenceSanitizerTest`, `ModelRequestGateTest`, `AutoDialogInstallEvidenceMapperTest`, `DefaultAiRiskAdvisorTest`, all `domain/engine/approval/*` (`ApprovalCoordinatorTest`, `ApprovalBindingTest`, `IdempotencyTest`, `RecoveryTest`, `StatePersistenceTest`, `ChokePointTest`, `ApprovalIsolationTest`), `domain/engine/policy/*` (`PolicyDecisionTableTest`, `PolicyDependencyBoundaryTest`), plus the broader `app/src/test` suite.

### 4.3 Commands (report each result)

```bash
python .github/scripts/course_gates.py prompt-sync
python .github/scripts/course_gates.py schema
python .github/scripts/course_gates.py permissions
python .github/scripts/course_gates.py dependencies
./gradlew spotlessCheck :app:testUnstableDebugUnitTest assembleUnstableDebug assemblePreviewDebug
# live (human-run, checkpoint 0 + re-verification):
AGENT_BASE_URL="https://llm.cs.cityu.edu.hk/v1" AGENT_API_KEY=<key> AGENT_DEPLOYMENT_MODEL="CS5351/DeepSeek-V4-Flash" \
  ./gradlew :app:testUnstableDebugUnitTest --tests "com.rosan.installer.live.*"
```

> `--offline` is intentionally omitted: `spotlessCheck` needs the ktlint jars resolved from the repository, which an offline build cannot fetch. Tests and the smoke build still run without network access.

### 4.4 Manual device demo checklist (UI evidence, both families)

For **each** of Material 3 and Miuix, capture a screenshot and a one-line result:

1. First-use: advisor off → toggle on → configuration dialog appears → **Cancel** → switch stays off.
2. First-use: toggle on → endpoint prefilled `https://llm.cs.cityu.edu.hk/v1` → enter key → Save → switch on; model/prompt line reads `CS5351/DeepSeek-V4-Flash · v1.1`.
3. Turning the **consent** switch on shows the enumerated allowlist dialog (all `ALLOWLISTED_LEAF_PATHS`); **Cancel** leaves consent off; Confirm turns it on.
4. Invalid endpoint (`http://…`) rejected; key remains masked in the UI after reopen.
5. Advisor **off** → install → baseline InstallerX behaviour unchanged; no network call; the coordinator is not involved.
6. Advisor **on**, **consent off** → install → card shows "advice unavailable (consent off)" and REQUIRE_REVIEW; no call.
7. Advisor on, consent on, **internet off** → card shows "network off"; REQUIRE_REVIEW; no call.
8. **LOW** likelihood / HIGH risk advice ⇒ REQUIRE_REVIEW; the **Install button acts as Continue** ⇒ submit once; Cancel ⇒ no submission.
9. **HIGH** likelihood / LOW risk with AUTO_RULES_NOT_MET ⇒ REQUIRE_REVIEW (not auto); Install button acts as Continue.
10. **BLOCK** (hard prohibition) ⇒ Install button **disabled**; reason shown; AI cannot override.
11. **Model failure** (key wrong or endpoint unreachable) ⇒ "advice unavailable (failure code)"; REQUIRE_REVIEW; Install button (Continue) enabled; no auto.
12. After submission, the existing progress/success/failure stages render; a `RESULT_UNKNOWN` is shown as unknown, never "not installed".
13. AutoDialog: with advisor on, confirm the 500 ms auto-install does **not** fire (the Install button waits for the human for REQUIRE_REVIEW; DR-05 still auto-executes). With advisor off, confirm the 500 ms baseline behaviour is unchanged and the coordinator is not involved.
14. Recomposition / leaving and re-entering `InstallPrepare`: the card does not trigger a second model call (watch the live test log or a counter).

Record each as a row in the verify-role memo (screenshots are human-captured; the agent does not fabricate device results).

---

## 5. Build checkpoints (each compiles and passes tests)

> Each checkpoint ends with `spotlessCheck` + `:app:testUnstableDebugUnitTest` green and the builds compiling. If a checkpoint reveals a contract that cannot be met without changing the engine or a frozen rule, **stop and return to plan**.

**Checkpoint 0 — provider generalization, prompt v1.1, live re-verification (GO/NO-GO).**
**Status: exploratory live smoke already PASSED by the lead on 2026-11-22 (see §2.4); this checkpoint builds the code to match and re-confirms from the assembled client.** Provider-neutral client, `AiEndpointValidator`, transport `post(baseUrl, path, body)`, `AiRequestBuilder` constants (`PROMPT_VERSION`, `DEFAULT_MODEL_ID`; `MAX_OUTPUT_TOKENS` stays 512), `prompts/prompt_v1.1.yaml`, `prompt_v1.0.yaml` superseded, `CourseServiceLiveAdvisorTest` (env `AGENT_BASE_URL`, `AGENT_API_KEY`, `AGENT_DEPLOYMENT_MODEL`; skip if unset). The lead re-runs the live test with their key against `https://llm.cs.cityu.edu.hk/v1`; it must still produce a valid assessment (the exploratory run already established: `json_object` accepted, no reasoning, `finish_reason=stop`, 236/512 completion tokens, 4 keys). If the assembled client regresses any of these, **stop** — no settings/UI work proceeds on an unverifiable model path. CI secret change is a separate human item (§10) and is not part of checkpoint 0's code.

**Checkpoint A — settings, first-use setup, credential storage.**
Two advisor booleans in settings; `AiCredentialStore` + DataStore file; `AiEndpointValidator`; `NetworkSettings*` state/action/VM; both network pages; configuration dialog in both families; strings (English). Tests: `AiEndpointValidatorTest`, `AiCredentialStoreTest`, `ProviderRequestShapeTest` (request building from config), settings-to-gate matrix (the gate logic is testable without UI). No dialog-flow change yet.

**Checkpoint B — gateway and DI.**
`SessionInstallationGateway` (per session, wraps `session.install(true)`), in-memory production `ApprovalStateStore` (single), `SubmissionLedger` (single), per-session `SessionBindingFactsProvider`, `ApprovalModule.kt` (singletons + per-session factories), `AiEndpointConfigSource` wiring, `AiModelClient` wiring. Tests: `SessionInstallationGatewayTest.*` (including `waitingUnknownSourceIsNotTerminal`), `GatewaySessionIsolationTest.twoSessionsDoNotCrossContaminate`, extended `ChokePointTest`, `AiIsolationTest`, prompt-sync. Coordinator/engine tests still green. **No** ViewModel change yet (so a compile green here means the new backend is wired but unused).

**Checkpoint C — ViewModel wiring (unblocked; Q9 answered Option (b)).**
`PrepareApprovalUseCase` (one trigger; advisor-off = baseline, no coordinator; advisor-on = assessment), new stage `AiReview`, `InstallerState.aiUiState`, new actions, `install()`/auto-path branch, `AiDecisionUiMapper`, `di/ViewModelModule.kt` update. Tests: `PrepareApprovalUseCaseTest.decisionRouting`, `PrepareApprovalUseCaseTest.advisorOffIsBaselineNoCoordinator`, `PrepareApprovalUseCaseTest.singleDispatchAcrossRecomposition`, `AiDecisionUiMapperTest`, settings-to-gate matrix at the use-case level.

**Checkpoint D — Material 3 UI.**
`AiAdvisoryCard.kt`, `InstallPrepareDialog.kt` embedding (Install button = Continue), strings for the card. Build + existing tests; manual demo rows for M3 §4.4.

**Checkpoint E — Miuix UI.**
`AiAdvisoryCardContent.kt`, `InstallPrepareContent.kt` embedding (install button = Continue). Build + existing tests; manual demo rows for Miuix §4.4. *(AutoNotification is deferred and not claimed; its path makes 0 AI calls — no checkpoint work for it.)*

---

## 6. Coordinator/engine handling (no engine change)

The engine is consumed as-is (`DeterministicPolicyEngine.kt:18-63`); **no DR rule and no `PolicyAdvice` arm is added**. The coordinator's existing ordering (`onContinue` reads facts, builds binding, re-reads, revalidates, submits once: `ApprovalCoordinator.kt:135-178`) is the INV-003 boundary and is **not** modified. This slice only:
- injects real per-session `factsProvider`/`gateway` and shared `store`/`ledger` (checkpoint B),
- calls `decide` from the assessment path and `autoSubmit` for DR-05 (checkpoint C), and
- wires the Install button (Continue) → `onContinue(baseInput)` / `autoSubmit` by disposition and Cancel → `onCancel` (checkpoints D/E).

`autoSubmit` for DR-05 still runs the binding + single permit; because there is no human Continue, the card is shown transiently and the install proceeds. This is the only path that begins a submission without a tap, and it is reachable **only** when the engine returns `ALLOW_AUTO/EXECUTE_ONCE` (COMPLETE + COMPATIBLE + HIGH + LOW + AUTO_RULES_MET). Keeping engine semantics was confirmed (answered Q6).

---

## 7. Preventing duplicate submission / stale approval (this slice keeps the existing mechanisms)

| Threat | Mechanism (already merged, re-exercised) | Test |
|---|---|---|
| Double Continue | `SubmissionLedger.tryAcquire` | `IdempotencyTest` (regression) |
| APK replaced after evidence | re-read via the per-session `BindingFactsProvider` + binding compare | `ApprovalBindingTest` (regression) + gateway package check |
| Config changed | `configSnapshotHash` mismatch ⇒ DR-10 | `ApprovalCoordinatorTest` (regression) |
| Subscribe twice (auto + human) | state guard: auto path only from `EXECUTE_ONCE`; Continue only from `AWAIT_HUMAN` | `PrepareApprovalUseCaseTest` |

No TTL/expiry is added (REL-002 deferral unchanged, DEBT-003).

---

## 8. Retry / second-attempt (COST-001)

This slice makes **one** attempt per operation (attempt 1, system-initiated). A user-initiated retry that creates attempt 2 is **not** built here: the Continue button submits from the existing decision and makes **zero** additional model calls (FR-006), and a model failure yields REQUIRE_REVIEW, not a re-query. The "retry assessment" UI (attempt 2) is a later, separately-approved item. This is stated so no one reads the slice as satisfying COST-001's second-attempt path end-to-end; the engine-side accounting exists but the UI trigger does not.

---

## 9. Documentation changes (HUMAN task — the agent will not edit)

These must be updated by a human after review; the plan lists them so they are not forgotten:

- **REQ-IX-ARCH-001 (v1 narrowing, Q9 Option (b))** — edit `docs/req/requirements.yaml` REQ-IX-ARCH-001 to state that the v1 choke-point guarantee applies to **AI-enabled in-scope requests**; advisor-off keeps the baseline `session.install(true)` path (FR-012). **No agent edit** (approval-required). The matching **ADR** (ADR-002.6, authority separation) records this narrowing.
- **ADR-002.2** — provider changed from Groq to the course service; the adapter is now provider-neutral; key origin changed from `GROQ_API_KEY` env to user-entered stored config. Requires re-deciding "E-3: key is read at runtime from the process environment" — the new decision is "user-entered, app-private, never shipped".
- **ADR-002.4** — dual-UI review now implemented (first time), decision on the new `InstallerStage.AiReview` and the advisory card; consent is an inline **enumerated-allowlist dialog** (Q4), not a separate page.
- **ADR-002.5** — consent UI + production wiring now complete for AutoDialog; state what "consent" means (a persisted boolean read per assessment) and that the field list is shown by an enumerated dialog.
- **ADR-002.3** — explicitly still "not started"; add a note that production now uses an in-memory store, so REL-002 remains unmet.
- **`docs/architecture/trace-matrix.md`** — mark FR-003/005/006/007/011/012, ARCH-001 (advisor-on only), ARCH-002, and MOD-001 runtime monitors updated; regenerate from the registry rather than hand-editing.
- **`docs/debt-register.md` / `docs/bug-diary.md`** — verify-role additions (in-memory store debt remains DEBT-003; plaintext key storage recorded as guardrail debt per Q2).

**Prompt/model version traceability:** the live test records environment, model, and prompt version per VER-001; the new file `prompt_v1.1.yaml` + `CourseServiceLiveAdvisorTest` provide that.

---

## 10. Separate approval items (listed, not performed)

1. **`.github/workflows/ci.yml`** — change the `semantic_eval` job (`ci.yml:173-218`): replace the `GROQ_API_KEY` secret with the course credentials using the **same names the live test reads** — `AGENT_BASE_URL`, `AGENT_API_KEY`, and `AGENT_DEPLOYMENT_MODEL` — and the live-suite gating condition. The agent will **not** edit `.github/`; the human makes this change and decides whether the course key is a repository secret at all. The live test skips when any of the three is absent, so CI stays green without the secret.
2. **New Gradle dependency** — **none.** Q2 is answered "plaintext, no `security-crypto`"; the entry remains only as a reminder that adding one would need separate approval.
3. **`docs/architecture/*` edits** — §9.

---

## 11. Risks and open questions

### Risks

- **R1 — live call reliability (reduced).** The lead's 2026-11-22 exploratory run passed (`json_object` accepted, no reasoning, `finish_reason=stop`, 236/512 tokens, valid 4-key reply). Residual risk is that the assembled client behaves differently, or the service changes behaviour later. Mitigation: checkpoint 0 re-confirms from the real client; the strict validator is unchanged so any drift ⇒ REQUIRE_REVIEW, never auto-approval.
- **R2 — AutoDialog auto-install vs review.** Today `InstallPrepare` auto-installs after 500 ms (`InstallerViewModel.kt:597-603`). Advisor-on must not auto-fire, or the human never sees the card. Mitigation: the branch in §2.10 + state-mapping test + manual demo row 13. This is the most likely functional regression and is called out for reviewer attention.
- **R3 — in-memory approval/ledger.** A process death loses state; REL-002/003/004 stay unmet. Mitigation: unchanged DEBT-003; do not claim durability.
- **R4 — plaintext key at rest (accepted, Q2).** The key sits in a separate app-private DataStore file, excluded from export by construction. Accepted under the stated threat model; recorded as guardrail debt for the verify role (not fixed in this slice).
- **R5 — key leaking into logs/errors.** The transport never puts the key in messages (`OkHttpAiTransport.kt:131-142`), but new code (config source, UI, error toString) could. Mitigation: the `AiCredentialStoreTest.keyNeverAppearsInLogsOrErrors` log-scan test (§4.1) + masking-only state.
- **R6 — gateway reachability / session leakage.** A per-session gateway/coordinator must not be injectable into UI/AI/policy, and must not share facts across sessions. Mitigation: `GatewaySessionIsolationTest` + isolation test extension + review at checkpoint B.
- **R7 — ARCH-001 advisor-off routing (Q9): RESOLVED via Option (b).** v1 narrows ARCH-001 to AI-enabled in-scope requests; advisor-off is baseline. Residual risk: the narrowing must be recorded in the requirements/ADR (human task, §9) so the claim is honest; the advisor-off path is covered by `PrepareApprovalUseCaseTest.advisorOffIsBaselineNoCoordinator`.
- **R8 — egress widening.** Generalizing the client must not let an arbitrary URL be reached. Mitigation: transport builds URL only from a validated config; `AiNetworkGate` still checked; endpoint validator rejects non-HTTPS/odd hosts.
- **R9 — timebox.** This is a large slice (6 checkpoints). Mitigation: each checkpoint is independently shippable and honest; checkpoint 0 gates everything, and checkpoint C is now unblocked (Q9 answered).

### Answers incorporated (rev.2)

- **Q1 (test-connection button):** **No.** Not built.
- **Q2 (key at rest):** **Plaintext** in the separate app-private DataStore file; **no** new dependency; recorded as guardrail debt (verify role, later).
- **Q3 (token budget):** **Keep 512** (checkpoint 0: 236 completion tokens).
- **Q4 (consent display):** Inline consent is acceptable, but turning the consent switch **on shows a dialog listing every field in the `EvidenceSanitizer`/`AiRequestBuilder` allowlist**; **Cancel leaves consent off** (§2.6).
- **Q5 (sequencing seam):** **Yes, `PrepareApprovalUseCase`.**
- **Q6 (DR-05):** **Keep engine semantics** (DR-05 auto-executes; card shown transiently).
- **Q7 (endpoint/model):** Confirmed `https://llm.cs.cityu.edu.hk/v1`, `CS5351/DeepSeek-V4-Flash`, `chat/completions` only.
- **Q8 (AutoNotification):** **Deferred and not claimed**; its path makes **0 AI calls**.
- **Q9 (ARCH-001 v1 scope):** **Answered — Option (b).** REQ-IX-ARCH-001 is narrowed for v1 to AI-enabled in-scope requests; advisor-off is baseline (FR-012), advisor-on goes through the coordinator+gateway. Checkpoint C is unblocked. The requirement/ADR edits are a human task (§9, §13).

### Remaining open questions

None. All decisions Q1–Q9 are answered (§13 records Q9; the rest are in "Answers incorporated" above).

---

## 12. Approval checklist (completed at rev.3 approval — see §13)

- [x] Checkpoint split (0 → A → B → C → D → E) approved; checkpoint 0 is the go/no-go gate
- [x] Provider-generalization + prompt v1.1 approach approved (§2.1, §2.3)
- [x] Compatibility facts confirmed (checkpoint 0: `json_object` accepted, no reasoning, truncation at 512 not hit) and kept as regressions (§2.4)
- [x] Credential storage approved: plaintext in separate app-private DataStore file, no dependency, excluded from export (§2.2); log-scan test accepted (§4.1)
- [x] Consent source = stored boolean read per assessment; enumerated allowlist dialog on enable, Cancel leaves it off (§2.6, Q4)
- [x] **Q9 resolved — Option (b):** ARCH-001 narrowed for v1 to AI-enabled in-scope requests; advisor-off is baseline, no coordinator; checkpoint C unblocked (§2.7.1)
- [x] Real gateway wraps `session.install(true)`, **per session** (coordinator/gateway/facts per session; store+ledger singletons); two-session isolation test accepted (§2.9.1)
- [x] `InstallWaitingUnknownSource` is non-terminal; gateway waits through permission and classifies on terminal stage; waiting→granted→success test accepted (§2.9, §4.1)
- [x] One trigger, no re-dispatch; Install button = Continue (§2.7, §2.8, §2.10); no-second-dispatch test accepted
- [x] Dialog wiring + AutoDialog no-auto-install change approved (advisor on) (§2.7, §2.10, R2)
- [x] Test list approved (§4.1–4.4), including negatives, two-session, waiting-permission, advisor-off-no-coordinator, no-second-dispatch, key log-scan, and regressions
- [x] Manual demo checklist approved as the UI evidence (§4.4)
- [x] Doc edits listed as human tasks (§9); CI secret is a separate item (§10)
- [x] AutoNotification deferred and explicitly not claimed; 0 AI calls (Q8)
- [x] Answers Q1–Q8 recorded; Q9 answered

---

## 13. Approval record

| Field | Value |
|---|---|
| Plan version approved | **rev.3** |
| Approved scope | This plan **and** its tests (§4.1–4.4), including the rev.3 changes |
| Approved by | **wxxsfxyzm** (project lead) |
| Approval date | **2026-11-22** |
| Decisions recorded at approval | Q9 = **Option (b)** (REQ-IX-ARCH-001 narrowed for v1 to AI-enabled in-scope requests; advisor-off = baseline `session.install(true)`, no coordinator; advisor-on = coordinator→gateway with no exception). `InstallWaitingUnknownSource` is non-terminal; the gateway keeps waiting through the permission prompt and classifies only on a terminal stage. Added tests: `PrepareApprovalUseCaseTest.advisorOffIsBaselineNoCoordinator` and `SessionInstallationGatewayTest.waitingUnknownSourceIsNotTerminal`. |
| Human follow-ups (outside this session) | Edit `docs/req/requirements.yaml` REQ-IX-ARCH-001 and the ADR for the v1 narrowing (§9); CI secret change (§10). |
| Build authorization | **Yes** — the build role may implement once the human starts it. This session does **not** begin building. |
| Constraints still in force | Changes to `docs/req/` and `docs/architecture/` are human-only; no commit/push/PR without explicit request; every prior test suite re-run with zero regressions; tests reference their requirement/invariant. |

**Approved. No code is written in this planning session; the build role starts separately.**

**No code or tests will be written until this plan and its tests are explicitly approved. Silence, partial replies, or approval of a different plan do not count.**
