# Verification Constraints

Status: proposed by agent, pending human review.

Rules the verification agent applies to every change. A change
that breaks any rule is not complete.

## 1. Coverage Rules

- Every fit criterion in `docs/req/requirements.yaml` has at least
  one test or executable check.
- Every invariant has at least one negative test that fails when
  the invariant is violated.
- Every feature has at least one negative test.
- Every test references the requirement or invariant it verifies,
  for example in its name or KDoc:

  ```kotlin
  /** Verifies: REQ-IX-INV-004 */
  @Test
  fun modelTimeout_neverAutoApproves() { ... }
  ```

- `docs/architecture/trace-matrix.md` maps each requirement to its
  tests.

## 2. Test Approval

- Tests for a change are approved by a human before implementation
  begins, as part of the execution plan (see `Prompt.md`).
- Changing an approved test's assertions needs the same approval.
- Approval evidence is a link to the approved plan, pull request
  comment, or transcript entry.

## 3. Three Verification Tiers

| Tier | Checks | Fails when |
|---|---|---|
| Structural | Imports and module dependencies, layer boundaries, permission rules, architectural fitness functions | A dependency crosses an authority boundary, a layer rule is broken, or permission files disagree |
| Regression | All prior test suites, plus the standard build | Any previously passing test fails |
| Behavioral | Quality thresholds for the AI component (for example faithfulness and relevance scores) on a fixed evaluation set, and safety checks | A threshold is missed, or any safety escape occurs |

A safety escape is any case where AI output changes an install
decision, bypasses a hard block, or reaches a privileged backend.
The allowed number of safety escapes is zero.

## 4. Test Layout

- Deterministic offline tests run without network access or model
  calls, using fixed model responses where needed.
- Live tests call a real model or service and are kept separate.
- The mapping of these suites to Gradle source sets and the course
  `test/offline/` and `test/live/` directories is decided in the
  module-layout ADR.

## 5. Regression Rule

Every new round of work runs all previous rounds' test suites. Any
regression blocks the change until it is fixed or a human records
an explicit decision in `docs/review-memo.md`.

## 6. Commands

Standard build checks, until the test suites exist:

```bash
./gradlew assembleUnstableDebug   # smoke build
./gradlew assemblePreviewDebug    # PR-equivalent build
```

Test commands for the offline, live, and full suites are added here
once the module-layout ADR is accepted.

## 7. Reporting

- Report each command run and its result.
- State explicitly what was not run and why.
- Never report a check as passed unless it ran and passed.
