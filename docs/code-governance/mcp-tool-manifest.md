# MCP Tool Manifest

Status: proposed by agent, pending human review.

Tools the coding agent may call, by role. Enforced by
`.opencode/permissions.jsonc`; path-level rights are in
`docs/architecture/permission-model.md`.

## MCP Servers

None are enabled (`"mcp": {}`).

Adding an MCP server requires:

1. A pull request that names the server, its source, the tools it
   exposes, and which roles may call them.
2. An update to this manifest, the permission model, and
   `.opencode/permissions.jsonc` in the same pull request.
3. Human approval from the project lead.

Servers that need network access must also respect the
network-access boundary in `AGENTS.md`.

## Built-in OpenCode Tools

| Tool | Purpose | plan | build | verify |
|---|---|---|---|---|
| `read` | Read a file | whitelist | whitelist | whitelist |
| `glob`, `grep`, `list` | Find files and text | allow | allow | allow |
| `lsp` | Code intelligence | allow | allow | allow |
| `edit`, `write`, `patch` | Change files | `docs/plans/`; governance docs (ask) | source and test paths | QA records |
| `bash` | Run shell commands | read-only git | git read, Gradle build/test/lint; others ask | git read, Gradle build/test/lint/check; others ask |
| `todowrite` | Track task steps | allow | allow | allow |
| `question` | Ask the human a structured question | allow | allow | deny (ask in plain text) |
| `task` | Start a subagent | deny | deny | deny |
| `skill` | Load an agent skill | deny | deny | deny |
| `webfetch`, `websearch` | Network access | deny | deny | deny |

"Whitelist", "ask", and "deny" follow the definitions in the
permission model.

## Rationale

- Subagents are denied because a subagent runs with its own
  permission set rather than the calling role's, which would blur
  role separation and hide work from the main transcript.
- Skills and network tools are denied so that session context comes
  only from the repository and the human, which keeps transcripts
  reproducible.
- Documentation lookups that need the web are done by a human and
  pasted into the session, so they appear in the transcript.
