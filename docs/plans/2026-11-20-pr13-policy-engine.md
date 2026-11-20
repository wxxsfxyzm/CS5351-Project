# PR #13 follow-up — deterministic policy engine

## Approval and scope

The OpenCode plan and test matrix were reviewed in the planning session. The human authorized starting the first batch on 2026-11-20 with “开始吧”. The baseline is PR #13 head `27f21d47a27f6eb4bbb928ca74232438e90d45b7`. Use D1 rev.6's decision table for this batch, as directed by the project lead, and ADR-001 Option B for layering. D1 retains its recorded draft status.

Add the engine, its minimal input/output types, rule tests, and a dependency-boundary check under the existing `:app` module. Source/tests stay in the `domain/engine/policy` packages. Reuse the existing AI advice and evidence enums and validated assessment data. Keep the Advisor, model request path, evidence collection, configuration, UI, installation coordinator, backend, and other install modes untouched.

## Contract

Use a pure Kotlin function of an immutable input. Inputs include evidence completeness, local compatibility, evaluated policy restrictions and auto eligibility, validated advisory axes or an explicit unusable-advice outcome, and human approval/revalidation state. Auto eligibility includes affirmative baseline permission. The caller retains pending revalidation and never represents it as passed. The engine does not check clocks or compare binding fields, run model calls, or submit installs. It returns the business decision, disposition, rule ID and reason code. The caller must use the disposition and enforce final revalidation and single submission. Do not add a business rule or reason code beyond the table.

## Rule order and approved tests

Test every row with the exact decision, disposition, rule ID and reason code. Test precedence with overlapping facts and exercise the required safety-negative paths.

| Rule | Required case and precedence |
| --- | --- |
| DR-01 | Active hard block always wins, including favorable advice and passing human revalidation. No execute disposition. |
| DR-10 | Existing approval that fails revalidation yields review and invalidation. This takes precedence over missing evidence or favorable advice, after a hard block. |
| DR-03 | Unreadable, key-unknown and truncated evidence yield review and recollection, even with favorable advice or a reported passing revalidation. |
| DR-02 | Declined egress consent without a valid human approval yields review and waiting, before contradictory evidence. |
| DR-04 | Contradictory evidence without an obtained approval yields review and waiting. Follow the exact D1 predicate for valid approval and revalidation. |
| DR-05 | Complete evidence, compatible local verdict, validated HIGH success and LOW risk, affirmative auto rules, no human approval yields auto and execute once. Remove each prerequisite separately and assert no automatic execute. |
| DR-06 | Favorable advisory axes with complete compatible evidence but unsatisfied auto rules yields review and waiting. |
| DR-07 | Other valid advice with complete evidence and no valid approval yields review and waiting, including unknown or incompatible local compatibility when there is no hard block. |
| DR-08 | Invalid, unavailable, budget rejected, overage or blocked third advisory results yield review and waiting without human approval. |
| DR-09 | Valid human approval plus successful revalidation, subject to prior rules, yields review and execute once with APPROVED_AFTER_REVIEW. Contrast with hard blocks, failed revalidation and incomplete evidence. |

Also check repeatability for identical inputs, no automatic execution after model failure, and that the policy source stays isolated from Android, model/network execution, install backends and session or privileged APIs. Verify decision outcomes here. Real backend counts are covered by the separate integration work.

## Build and verify

Implement rule tests and the small pure evaluator, then run focused and full JVM tests, followed by Unstable/Preview debug builds where caches support them. Use the existing JDK 25 and SDK 37 setup. Run Gradle offline; do not let an uncached wrapper download. Record commands and actual results. No real Groq requests, device installs, commit or push. Stop for human review.
