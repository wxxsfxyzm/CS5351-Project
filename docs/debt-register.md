# Technical Debt Register

Categories: prompt rot, hallucinated dependencies, untouchable code,
hidden dependencies, agent-induced bloat, evaluation debt, guardrail
debt, human/cognitive debt.

| ID | Category | Description | Impact | Proposed resolution | Owner | Status |
|---|---|---|---|---|---|---|
| DEBT-001 | Guardrail debt | `build` and `verify` allow git and Gradle commands without denying `>`, `--output`, or `--no-index` (BUG-002). For example, `git status > .opencode/x` resolves to `allow`. | An agent in these roles can write or read files outside its `read` and `edit` rules, including its own permission file. | Add the same three deny rules to `build` and `verify` in `.opencode/permissions.jsonc` and update `permission-model.md`. | wxxsfxyzm | Open |
