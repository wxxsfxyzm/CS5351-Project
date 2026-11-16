# Project Covenant

Status: proposed by agent, pending human review.

Standing rules for every coding-agent session. Loaded at session
start through `.opencode/permissions.jsonc`. Agents must not edit
this file.

## 1. Abstraction Limits

- Extend the nearest existing pattern before adding a new one.
- Add no interface, base class, or factory with only one
  implementation unless an ADR requires it.
- Keep call depth for a new feature to at most three new layers
  (for example UI → use case → provider) on top of the existing
  architecture.
- Do not add generic frameworks, plugin systems, or configuration
  options that no approved requirement needs.

## 2. Dependency Constraints

- Add, remove, or upgrade dependencies only with human approval,
  and only through `gradle/libs.versions.toml`.
- Every new dependency must exist on its declared repository;
  confirm the exact coordinates before use.
- Policy and decision code must not depend on model SDKs or network
  libraries.
- Respect the existing module dependency direction; do not add
  dependency cycles.

## 3. Test Independence

- Every feature has at least one negative test.
- Every invariant has at least one test that fails when the
  invariant is violated.
- Derive tests from the requirement and fit criterion, not from the
  implementation under test.
- Keep deterministic offline tests separate from tests that call a
  live model or network service.
- Do not weaken, skip, or delete an existing test to make a change
  pass. Report the failure instead.

## 4. Safety-Critical Changes

Safety-critical code includes install decisions, policy evaluation,
approval binding, privileged backends (Root, Shizuku, Dhizuku,
`app_process`), signing, and the network-access boundary.

Changing it requires:

1. An accepted ADR, new or updated, that covers the change.
2. Explicit human approval of the plan and tests before coding.
3. Human code review before merge.

## 5. Context Freshness

- When a requirement changes, update the affected rules,
  architecture documents, and tests within one sprint.
- If a rule conflicts with an approved requirement or ADR, stop and
  report the conflict; do not choose one silently.
- Record each rule update in `docs/ai-use-log.yaml`.
