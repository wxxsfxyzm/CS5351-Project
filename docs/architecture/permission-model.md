# Permission Model

Status: proposed by agent, pending human review.

This document is the human-readable form of
`.opencode/permissions.jsonc`. The two must stay identical in
meaning; change both in the same pull request.

## Scope and Tooling

- Applies to OpenCode v1 coding-agent sessions (team agreement).
  The configuration uses the v1 `permission` / `agent` format; it
  is not valid for OpenCode v2.
- OpenCode does not load `.opencode/permissions.jsonc` by itself.
  Every session must start with:

  ```bash
  OPENCODE_CONFIG=.opencode/permissions.jsonc opencode
  ```

- `read` and `edit` patterns match paths relative to the repository
  root. `*` matches any characters, including `/`. Within one tool,
  the last matching rule wins.

## Roles

| Role | Purpose | Writes |
|---|---|---|
| `plan` | Read context, produce execution plans, draft architecture and governance documents | `docs/plans/`, governance docs with per-edit approval |
| `build` | Implement an approved plan | Source and test paths |
| `verify` | Run verification and draft QA records | Review memo, bug diary, debt register |

Any other agent, including OpenCode built-in subagents, falls
back to the global rules: deny all reads, edits, and shell
commands.

## Read Rights

Default: deny. All three roles share the same read whitelist.

| Path | plan | build | verify |
|---|---|---|---|
| `AGENTS.md`, `Prompt.md`, `README.md`, `CONTRIBUTING.md`, `architecture-blueprint.md` | allow | allow | allow |
| `docs/` | allow | allow | allow |
| `app/`, `app-process/`, `hidden-api/`, `build-plugins/`, `baselineprofile/` | allow | allow | allow |
| `src/`, `test/`, `prompts/` | allow | allow | allow |
| `gradle/`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` | allow | allow | allow |
| `.github/`, `.opencode/`, `.agent/` | allow | allow | allow |
| `local.properties`, `keystore.properties`, `keystore/`, `*.jks`, `*.keystore`, `*.env*` | **deny** | **deny** | **deny** |
| Anything else (for example `transcripts/`, `Report.md`, `build/`, `.idea/`) | deny | deny | deny |

OpenCode auto-loads `AGENTS.md`. `Prompt.md`, `.opencode/rules.md`,
and `.agent/rules.md` are injected through `instructions`. All four
reach the agent at session start, independent of the read whitelist.

## Write Rights

Default: deny. `ask` means OpenCode prompts a human for every edit.

| Path | plan | build | verify |
|---|---|---|---|
| `docs/plans/` | allow | deny | deny |
| `architecture-blueprint.md`, `docs/req/`, `docs/architecture/`, `docs/code-governance/` | ask | deny | deny |
| `docs/ai-use-log.yaml` | allow | allow | allow |
| `app/src/`, `app-process/src/`, `hidden-api/src/`, `src/`, `test/` | deny | allow | deny |
| `app/build.gradle.kts`, `app-process/build.gradle.kts`, `hidden-api/build.gradle.kts`, `gradle/libs.versions.toml`, `settings.gradle.kts` | deny | ask | deny |
| `docs/review-memo.md`, `docs/bug-diary.md`, `docs/debt-register.md` | deny | deny | allow |
| `.opencode/`, `.agent/` | deny | deny | deny |
| `AGENTS.md`, `Prompt.md`, `README.md`, `.github/`, `transcripts/`, `Report.md`, everything else | deny | deny | deny |

## Shell Rights

| Command | plan | build | verify |
|---|---|---|---|
| `git status`, `git diff`, `git log`, `git show` | allow | allow | allow |
| `./gradlew assemble*`, `test*`, `lint*` | deny | allow | allow |
| `./gradlew check*` | deny | ask | allow |
| `git commit` | deny | ask | deny |
| `git push`, `git merge`, `git rebase`, `git reset --hard`, `gh pr merge`, `rm -rf` | deny | deny | deny |
| Any other command | deny | ask | ask |

## Other Tools

| Tool | All roles |
|---|---|
| `task` (subagents) | deny |
| `skill` | deny |
| `webfetch`, `websearch` | deny |
| `external_directory` | deny |
| MCP servers | none configured |
| `doom_loop` | ask |
| `glob`, `grep`, `list`, `lsp`, `todowrite` | allow (OpenCode default) |

## Guard Rules

| ID | Rule | Enforcement |
|---|---|---|
| G1 | Reads and edits are denied unless whitelisted | `"*": "deny"` first in every `read` and `edit` block |
| G2 | Credentials, keystores, and local properties are never read | Deny rules placed after the read whitelist |
| G3 | Agents never edit their own permissions or rules, `CODEOWNERS`, or workflows | `.opencode/*` and `.agent/*` denied last; `.github/` never whitelisted |
| G4 | Governance documents change only with per-edit human approval | `ask` for the plan role only |
| G5 | No push, merge, or history rewrite; commits need approval | Shell deny rules; `git commit` is `ask` for build only |
| G6 | No subagents, skills, network tools, or paths outside the project | `task`, `skill`, `webfetch`, `websearch`, `external_directory` denied |

Repository-side enforcement complements the agent rules:
`.github/CODEOWNERS` routes governance paths to the project lead,
and branch protection must require passing CI and code-owner
review.

## Known Limitations

- **Forgotten environment variable.** Without `OPENCODE_CONFIG`,
  OpenCode runs with its permissive defaults. Each session must
  run the smoke test below before starting work.
- **`grep` and `glob` are not path-restricted.** They can surface
  file names or matching lines from non-whitelisted files,
  including credential files.
- **Shell `ask` relies on the human.** A command such as
  `cat local.properties` is not blocked automatically for build or
  verify; the reviewer must reject it at the prompt.
- **Per-machine configuration still merges in.** A project-level
  `opencode.json` would override this file; a user's global config
  can add rules that are not listed here.
- **Version drift.** Running OpenCode v2 against this file can
  silently change or drop rules. Check `opencode --version` before
  each session.

## Session Smoke Test

Run at the start of each recorded session and keep the output in
the transcript:

```bash
opencode --version   # expect 1.x
OPENCODE_CONFIG=.opencode/permissions.jsonc opencode debug agent build
```

Then, inside the session, confirm each of these is refused:

1. Reading `local.properties`.
2. Editing `.opencode/permissions.jsonc`.
3. Running `git push`.
4. Editing `app/src/` while in the `plan` or `verify` role.
