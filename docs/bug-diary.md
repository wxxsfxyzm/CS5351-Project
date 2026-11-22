# Bug Diary

One entry per significant bug: symptom, reproduction, classification,
root cause, fix, and the invariant or guard gap it exposed.

## BUG-001 — OpenCode `plan` agent cannot write plan files

- **Date / reporter:** 2026-11-21, wxxsfxyzm (project lead).
- **Status:** Fixed (uncommitted at time of writing).
- **Symptom:** In the plan stage, the agent did not write plan files to
  `docs/plans/`, although `.opencode/permissions.jsonc` allowed
  `docs/plans/*` for the `plan` agent.
- **Reproduction:** OpenCode 1.19.5, started with
  `OPENCODE_CONFIG=.opencode/permissions.jsonc opencode`; select the
  built-in `plan` agent and ask it to write `docs/plans/<file>.md`.
- **Classification:** Agent tooling defect; hidden dependency on
  undocumented tool behavior.
- **Root cause:** Not a permission problem. The resolved ruleset ended
  in `edit docs/plans/* allow`. OpenCode 1.19.5 (`SessionReminders.apply`)
  appends a hard-coded reminder to every user turn when the agent name
  is exactly `plan`: "Plan mode ACTIVE … STRICTLY FORBIDDEN: ANY file
  edits … overrides ALL other instructions". The model obeys the
  reminder over the permission rules. Switching from `plan` to `build`
  in one session also injects a reminder that file changes are now
  permitted.
- **Fix:** Added a custom primary agent `project-plan` (edit
  `docs/plans/*` only) for the project plan role; made the built-in
  `plan` agent read-only; updated `Prompt.md`, `permission-model.md`,
  and `mcp-tool-manifest.md`. Verified with a live
  `opencode run --agent project-plan` probe: the write to `docs/plans/`
  succeeded; writes to `app/src/` and `.opencode/` were denied.
- **Gap exposed:** The plan stage in `Prompt.md` depended on tool
  behavior that the permission model did not describe. The session smoke
  test checked only that forbidden actions fail, not that required
  actions succeed.

## BUG-002 — Shell redirects bypass read and edit rules

- **Date / reporter:** 2026-11-21, found by the agent while
  investigating BUG-001.
- **Status:** Fixed for `project-plan` and the built-in `plan`; open
  for `build` and `verify` (see DEBT-001).
- **Symptom:** Allowed read-only commands could write or read files
  outside the whitelist, for example `git status > .opencode/x` or
  `git diff --no-index <file> /dev/null`.
- **Reproduction:** Evaluated against the resolved rulesets from
  `opencode debug agent`: before the fix, `git status > x` resolved to
  `allow` for the `plan` agent through the `git status*` rule.
- **Classification:** Security; guardrail bypass.
- **Root cause:** OpenCode checks each command in a chain separately,
  but matches a command together with its redirections, so allow
  patterns ending in `*` also cover `> file`. `git` is not in the set of
  commands whose path arguments OpenCode checks, so `--no-index` and
  `--output` reach paths that the `read` and `edit` rules deny.
- **Fix:** Deny `*>*`, `*--output*`, and `*--no-index*` after the allow
  rules for `project-plan` and the built-in `plan`. A live probe
  confirmed `git status > docs/plans/zz-redirect.txt` was denied.
- **Gap exposed:** Guard rules G1–G3 were enforced only through `read`
  and `edit` rules; shell allow rules were not checked for file writes
  or reads.

## BUG-003 — Robolectric native runtime aborts under JDK 25 on macOS arm64

- **Date / reporter:** 2026-11-22, detected by the build agent at plan
  step 2 of the ADR-002.3 persistence slice.
- **Status:** Open (environment).
- **Symptom:** Robolectric 4.18.1 aborts the JVM with exit 134 (SIGABRT)
  while loading its native runtime (`libandroid_runtime.dylib`), before
  any test logic runs.
- **Reproduction:** Add `org.robolectric:robolectric:4.18.1` and
  `androidx.room3:room3-testing:3.0.3` as `testImplementation`, then run
  a Robolectric Room smoke test under `:app:testUnstableDebugUnitTest`
  with the project toolchain (JDK 25, macOS arm64). The test is reported
  SKIPPED before the JVM aborts.
- **Classification:** Environment / test-runtime defect (JDK 25 + macOS
  arm64 incompatibility with the Robolectric native runtime).
- **Root cause:** A minimal Robolectric-only probe with no Room also
  aborts, so the failure is the Robolectric native runtime itself, not
  Room or the new code. A direct JVM run prints "Loading android native
  library from ... libandroid_runtime.dylib", then "could not find method
  serverCertificateRequested", then aborts with no `hs_err` written
  (C-level `abort()` / `JNI_OnLoad` failure).
- **Flags tried (none resolved the abort):** `--enable-native-access=ALL-UNNAMED`,
  `-Xmx2g`, `sdk=35`, `sdk=37`, `-Drobolectric.nativeruntime=false`.
- **Fix:** None yet. The plan's step 2 stop rule worked as designed: the
  build stopped and reported instead of proceeding on unverified Room
  behavior, and the slice was deferred (see DEBT-003).
- **Gap exposed:** The approved test strategy assumed Robolectric could
  run real SQLite on the JVM under the project JDK 25 toolchain; that
  assumption was not proven before the slice depended on it. A test
  runtime compatible with the toolchain was not confirmed up front.

## BUG-004 — Live course-service test passed while proving nothing

- **Date / reporter:** 2026-11-22, found by the project lead while
  running the AI advisor end-to-end slice (PR #30) live suite.
- **Status:** Fixed (PR #30).
- **Symptom:** `CourseServiceLiveAdvisorTest` reported PASS with 10/10
  `NETWORK_ERROR` results and 0 usable replies, so the run validated
  no model behavior while still showing green.
- **Reproduction:** Run the live course-service suite in JVM unit tests
  on the project JDK 25 toolchain; every request returned
  `NETWORK_ERROR` before any HTTP dispatch.
- **Classification:** Verification defect; a test that asserts only
  "no crash" (vacuous pass).
- **Root cause:** OkHttp 5 detects the Android platform in JVM unit
  tests and calls `android.util.Log.isLoggable`, which is not mocked in
  a plain JVM test, so every request failed before dispatch. TLS, the
  proxy, and the JDK 25 network stack were all fine; the failure was
  inside OkHttp's platform detection, not the network.
- **Fix:** In PR #30 the live test uses a test-only `java.net.http`
  transport instead of OkHttp and asserts at least `MIN_USABLE = 8` of
  10 usable replies, so an all-failure run now fails instead of passing.
- **Residual:** The production `OkHttpAiTransport` is verified only on
  device, not in JVM tests, so a JVM-only run cannot exercise the real
  production transport.
- **Gap exposed:** A live or integration test must assert a positive
  outcome threshold, not merely that the run did not crash.

## BUG-005 — Installer crashed when the AI advisor was on

- **Date / reporter:** 2026-11-23, found by the project lead on a device
  after merging PR #30.
- **Status:** Fixed (`fix/ai-advisor-on-device`).
- **Symptom:** With the advisor on, opening any APK crashed the installer:
  `NoDefinitionFoundException: AutoDialogInstallEvidenceMapper`.
- **Classification:** Wiring defect (dependency injection).
- **Root cause:** `singleOf(::PrepareApprovalUseCase)` and
  `singleOf(::AiRequestBuilder)` make Koin resolve every constructor
  parameter, including ones with default values
  (`AutoDialogInstallEvidenceMapper`, `Json`), which have no definition.
  Unit tests constructed these classes directly, so the Koin graph was
  never resolved.
- **Fix:** Explicit constructors in `AiModule`;
  `AiModuleResolutionTest` resolves the real module and fails on the old
  definitions.
- **Gap exposed:** No test resolved the production DI graph.

## BUG-006 — Evidence was always UNREADABLE on a device

- **Date / reporter:** 2026-11-23, project lead, on-device demo run.
- **Status:** Fixed (`fix/ai-advisor-on-device`).
- **Symptom:** Every APK stopped at "Evidence incomplete; collect
  again" (DR-03 RECOLLECT) with Continue disabled.
- **Classification:** Data defect (missing evidence).
- **Root cause:** The evidence and the approval binding read the APK
  SHA-256 from `AppEntity.BaseEntity.fileHash`, which the parser never
  sets (also upstream). A null hash makes `completeness()` UNREADABLE,
  and the binding could not be built, so Continue could not submit
  either. Test fixtures always set `fileHash`.
- **Fix:** `ApkContentHasher` hashes the selected APK bytes on the AI
  path only. `SessionBindingFactsProvider` re-hashes on every read, so a
  file replaced after approval now fails revalidation (INV-003).
  `ApkHashEvidenceTest` uses device-shaped entities (`fileHash = null`,
  real file) with a negative control.
- **Gap exposed:** Fixtures supplied a value the device never has.

## BUG-007 — DR-05 (ALLOW_AUTO) was unreachable on a device

- **Date / reporter:** 2026-11-23, project lead, on-device demo run.
- **Status:** Fixed (`fix/ai-advisor-on-device`).
- **Symptom:** Every install ended in REQUIRE_REVIEW, including clean
  new installs and same-signature updates.
- **Classification:** Data defect (facts not propagated).
- **Root cause:** `PrepareApprovalUseCase` called the evidence mapper
  with default `Facts`, so the version relation, platform and ABI
  compatibility, and confirmed absence stayed UNKNOWN although the
  analysis had them.
- **Fix:** `AutoDialogFactsDeriver` derives them from the analysis and
  device facts (SDK, ABIs, process user), failing toward UNKNOWN.
  Covered by `AutoDialogFactsDeriverTest` and an end-to-end DR-05 test
  with a negative control.
- **Gap exposed:** No test drove a device-shaped install to DR-05.

## BUG-008 — The model was never called on a device

- **Date / reporter:** 2026-11-23, project lead, on-device demo run.
- **Status:** Fixed (`fix/ai-advisor-on-device`).
- **Symptom:** The card showed only "Needs your review" with no
  advice; the decision was DR-08 ADVICE_UNUSABLE.
- **Classification:** Wiring defect (consent gate).
- **Root cause:** The request builder and transport refuse a call unless
  a `ModelRequestGate` is in the coroutine context. Every test installed
  `ModelRequestGate { true }`; no production code did (ADR-002.5 even
  recorded this), so every request failed as `ConsentUnavailable`.
- **Fix:** `InstallerViewModel` installs the gate around each
  assessment, re-reading the persisted switch and consent on each check.
- **Gap exposed:** Tests supplied the context that production must
  provide, and the ADR note was not turned into a task.

## BUG-009 — Every model reply failed with EOFException on a device

- **Date / reporter:** 2026-11-23, project lead, on-device demo run.
- **Status:** Fixed (`fix/ai-advisor-on-device`).
- **Symptom:** `NETWORK_ERROR EOFException` for every call, although the
  server returned HTTP 200 (confirmed by replaying the same body).
- **Classification:** Transport defect.
- **Root cause:** `OkHttpAiTransport` read the body with
  `readByteArray(max + 1)`, which demands exactly that many bytes and
  throws EOF for any smaller reply. It was never executed before: OkHttp
  cannot run in JVM tests (BUG-004) and the live test used a
  `java.net.http` transport. Diagnosis was first misattributed to the
  phone's VPN and the course WAF; the stack trace settled it.
- **Fix:** `readCappedBody` uses `request(max + 1)` to detect an
  oversize reply and otherwise reads the whole body;
  `ReadCappedBodyTest` covers small, empty, at-cap, and over-cap bodies.
- **Gap exposed:** The production transport had no executed test, and a
  network-looking symptom was chased before reading the stack trace.

## BUG-010 — AI attempt had no total time limit and could not be cancelled

- **Date / reporter:** 2026-11-23, project lead, code review against
  REQ-IX-PER-001.
- **Status:** Fixed (`fix/ai-advisor-on-device`).
- **Symptom:** Only a 7 s read-idle timeout existed; connect (15 s),
  write (15 s), and DNS were unbounded by it, there was no 15 s attempt
  budget, and Cancel left the blocking HTTP call running.
- **Classification:** Requirement gap (PER-001).
- **Fix:** OkHttp `callTimeout(7 s)` for the whole call; the call is
  cancelled when its coroutine is; `PrepareApprovalUseCase` bounds the
  assessment at 12 s and routes a timeout through the engine to DR-08.
  `AttemptBudgetTest` (virtual time) checks one call, cancellation, no
  submission, and a decision within 15 s.
- **Gap exposed:** `callTimeout` is verified on device only (OkHttp in
  JVM tests, BUG-004).
