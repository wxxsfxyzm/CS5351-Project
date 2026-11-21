# Prompt

Static session prompt for the CS5351 project. OpenCode loads
`AGENTS.md` and this file into every session (this file through
`instructions` in `.opencode/permissions.jsonc`). This prompt
starts a supervised session; it never authorizes unattended
completion.

## 0. Session Setup (human)

Before giving the agent a task:

1. Confirm the tool version: `opencode --version` must report 1.x.
2. Start OpenCode with the project permissions:

   ```bash
   OPENCODE_CONFIG=.opencode/permissions.jsonc opencode
   ```

3. Run the smoke test in `docs/architecture/permission-model.md`
   and keep the output in the transcript.
4. Select the agent for this stage (Tab in the OpenCode TUI):
   `project-plan` for the plan role, `build`, or `verify`. Do not
   use OpenCode's built-in `plan` agent; it is hard-wired read-only
   and cannot write plan files.
5. Send the first message in this form, so the transcript records
   the launch:

   ```text
   @Prompt.md Follow this prompt.
   Role: <plan | build | verify>
   Task: <one-line description>
   Related IDs: <requirement, invariant, or ADR IDs>
   Approved inputs: <approved plan and tests, or "none">
   ```

If any step fails, stop the session and fix the setup first. If the
first message does not follow this form, ask the human for the
missing fields before doing any work.

## 1. Load Context

- Follow the reading order in `AGENTS.md`.
- Read only what the task needs; cite every file you rely on.
- Treat draft documents as proposals, not approved baselines.
- If the task, scope, or approval status is unclear, ask before
  continuing.

## 2. Produce an Execution Plan (plan role, agent `project-plan`)

Write the plan to `docs/plans/<date>-<short-task-name>.md` with:

1. **Traceability** — requirement, invariant, and ADR IDs covered.
2. **Interface contract** — inputs, outputs, and module boundaries
   touched, including the AI-advisory versus deterministic-policy
   boundary where relevant.
3. **Scope boundaries** — files to create or change, and files
   explicitly out of scope.
4. **Testing strategy** — tests per fit criterion, at least one
   negative test per feature and per invariant, and the regression
   suites to re-run.
5. **Steps** — ordered implementation steps, each small enough to
   review.
6. **Risks and open questions.**

Then **stop**. Do not write code or tests until a human replies
with explicit approval of both the plan and the tests. Silence,
partial replies, or approval of a different plan do not count.

## 3. Build (build role)

- Implement only the approved steps, in order.
- Write the approved tests before or alongside the code they cover.
- If the plan proves wrong or incomplete, stop and return to the
  plan stage; do not widen scope on your own.
- Annotate new modules and functions with the ADR and invariant
  they implement.
- Ask before any dependency, Gradle, or safety-critical change.

## 4. Self-Verify (build role, then verify role)

- Run the checks named in the plan and in
  `docs/code-governance/verification-constraints.md`.
- Re-run all prior test suites; any regression blocks the stage.
- Report each command, its result, and anything not run.
- In the verify role, draft the review memo entry in
  `docs/review-memo.md` and record significant bugs in
  `docs/bug-diary.md`.

Then **stop** for human review of the results.

## 5. Close the Session

- Update `docs/ai-use-log.yaml` with this session's entry. In
  plan-role sessions the agent cannot edit it; the human adds the
  entry.
- Summarize: what changed, what was verified, what remains
  unverified, and any human interventions.
- Do not commit, push, or open a pull request unless a human asks.
