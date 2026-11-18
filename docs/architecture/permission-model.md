# Permission Model

Status: proposed by agent, pending human review.

> **Scope note.** Sections 1–8 of this document govern *coding-agent
> access* to the repository and are the human-readable form of
> `.opencode/permissions.jsonc`. They do not describe the AI
> component's runtime privilege boundary. The runtime AI-adapter
> least-privilege isolation model is specified in
> `architecture-blueprint.md` §7 (*Runtime AI-adapter least-privilege
> isolation*), with the concrete forbidden-dependency list and
> enforcement points in §9 below. The two audiences are different: §1–§8
> constrain what a coding agent may read, edit, and run; §9 and
> `architecture-blueprint.md` §7 constrain what the shipped app may do at
> runtime. The naming overlap between "agent permission model" and "AI
> runtime permission model" is recorded as an open question in
> `docs/plans/2026-11-17-d2-architecture-population.md` §8 (Q1).

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

---

## 9. AI Component Runtime Boundary

Status: draft — pending human review.
Owner: Member C (Software Architect).

This section specifies the *runtime* least-privilege boundary of the
AI component once built. It is not enforced by
`.opencode/permissions.jsonc`; it is enforced by module dependency
direction, static checks, and negative tests in the application code.
It restates the authority separation recorded in ADR-002.6 and
REQ-IX-INV-001 / REQ-IX-SEC-001.

### 9.1 Elements and their privilege

| Element | May read | May call | May not |
|---|---|---|---|
| `AIRiskAdvisor` | allowlisted advisory fields after sanitization | the external model adapter | installer backends, sessions, privileged providers, raw APK bytes |
| `EvidenceSanitizer` | untrusted text and evidence fields | pure transformation only | network, model, installer, privileged providers |
| `AIOutputValidator` | the model response | pure validation only | network, installer, privileged providers |
| `DeterministicPolicyEngine` | evidence summary, compatibility result, advisory result, policy version, human state | pure decision only, no model SDK, no network | any model SDK, any network, any installer backend |
| `ApprovalCoordinator` | evidence, advisory, policy result, approval state | `InstallationGateway` only after an allowed decision | direct `AppInstallerRepository`, privileged providers |
| `InstallationGateway` | a revalidated approval | the single authorized installer backend entry point | model, network, UI |

### 9.2 Forbidden dependencies

The advisory elements (`AIRiskAdvisor`, `EvidenceSanitizer`,
`AIOutputValidator`) must not reference any of the following, directly
or transitively:

- `domain/engine/repository/AppInstallerRepository.kt`
  (the installer backend interface) and its implementations under
  `data/engine/executor/appinstaller/`;
- `data/engine/repository/AppInstallerRepositoryImpl.kt`;
- `domain/privileged/provider/**` and `framework/privileged/**`;
- `domain/session/**` session repositories and handlers;
- `data/session/handler/ActionHandler.kt`;
- raw APK byte access through `data/engine/parser/**` beyond the
  sanitized evidence fields.

`DeterministicPolicyEngine` must not reference any model SDK, the model
adapter implementation, or any network client
(`data/session/resolver/OkHttpNetworkResolver.kt`,
`okhttp3.*`).

### 9.3 Enforcement

- **Structural.** ADR-001 is accepted on Option B, so the compiler does
  not enforce the boundary. A static import check walks the advisory and
  policy packages and fails on a forbidden import; code review and KDoc
  traceability tags cover the rest (`adr/001-architecture-choice.md` §6).
- **Static check.** A test asserts the advisory package's import graph
  contains no forbidden type (`planned: AdvisorIsolationTest`).
- **Static check.** A test asserts the policy package references no model
  SDK or network client (`planned: PolicyWithoutModelTest`).
- **Dynamic negative.** A model stub that returns install or approve
  instructions produces 0 backend calls.
- **Dynamic negative.** A favorable advisory result with a hard
  prohibition produces `BLOCK` and 0 backend submissions
  (`planned: HardBlockPrecedenceTest`).

### 9.4 Relationship to the agent permission model

The agent tables in §1–§8 control what a coding agent may read, edit,
and run while working on the repository. They do not constrain the
shipped app. Conversely, §9 constrains the shipped app and does not
constrain the coding agent. Both are drafts and require human review;
neither may be used to justify widening the other.

This section records the boundary as specified by requirements rev.6.
It does not claim that any enforcement is implemented.
