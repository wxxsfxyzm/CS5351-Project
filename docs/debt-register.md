# Technical Debt Register

Categories: prompt rot, hallucinated dependencies, untouchable code,
hidden dependencies, agent-induced bloat, evaluation debt, guardrail
debt, human/cognitive debt.

| ID | Category | Description | Impact | Proposed resolution | Owner | Status |
|---|---|---|---|---|---|---|
| DEBT-001 | Guardrail debt | `build` and `verify` allow git and Gradle commands without denying `>`, `--output`, or `--no-index` (BUG-002). For example, `git status > .opencode/x` resolves to `allow`. | An agent in these roles can write or read files outside its `read` and `edit` rules, including its own permission file. | Add the same three deny rules to `build` and `verify` in `.opencode/permissions.jsonc` and update `permission-model.md`. | wxxsfxyzm | Open |
| DEBT-003 | Evaluation debt | Approval persistence not implemented: `ApprovalStateStore` and `SubmissionLedger` are in-memory only, and there is no `DecisionAuditRecorder`. The ADR-002.3 persistence slice is deferred after its step 2 Robolectric smoke test was blocked by the native-runtime abort (BUG-003). | REL-002/REL-003/REL-004, INV-005 across restart, FR-009, and DATA-001 stay Partial. | Resume the approved plan (`docs/plans/2026-11-22-approval-persistence-slice.md`) from step 2 once a JVM test runtime with real SQLite works (see BUG-003). | wxxsfxyzm | Open |
