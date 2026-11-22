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
