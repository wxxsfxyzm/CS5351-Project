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
