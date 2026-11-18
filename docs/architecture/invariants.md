# Invariant Registry

**Status:** draft — pending human review.
**Owner:** Member C (Software Architect).
**Baseline:** InstallerX Revived `816ea541`; repository state `0f718739`.
**Requirements:** `docs/req/requirements.yaml` rev.6
(`status: draft_for_group_review`).
**Related:** `adr/001-architecture-choice.md`,
`adr/002-later-important-arch-decisions.md`,
`runtime-policy-catalog.md`, `permission-model.md` §9.

Course marker `[M]` — mandatory artifact.

The registry below restates the seven system invariants that must hold
at every instant of an in-scope installation operation. Each invariant
is derived from the requirements registry; the statements are condensed
but preserve the original meaning. All seven are **conceptual** (not
implemented). Tests named here are planned, per
`docs/code-governance/verification-constraints.md` §2.

An invariant is a property that the deterministic safety chain must
never violate. Advice may be wrong; the system must not behave unsafely
when it is.

---

## 1. Invariant registry

### REQ-IX-INV-001 — AI has no direct privileged execution capability

- **ASR:** no.
- **Statement:** The AI component returns advisory data only and does
  not hold or call installation, uninstallation, Root, Shizuku, Dhizuku,
  `app_process`, or any installation-backend capability.
- **Enforcing component:** `ApprovalCoordinator` (structural ownership)
  with `AIRiskAdvisor` restricted by `permission-model.md` §9.2.
- **Violated when:** an advisory element references, injects, or calls
  the installer backend, a privileged provider, a session repository, or
  `ActionHandler`; or a model response containing install or approve
  instructions results in any backend call.
- **Required negative test:** `planned: AdvisorIsolationTest`
  (static check of the advisory import graph; dynamic model stub
  returning install instructions produces 0 backend calls).
- **Implementation links:** forbidden dependency list in
  `permission-model.md` §9.2; authority separation in
  `adr/002-later-important-arch-decisions.md` ADR-002.6.
- **Status:** conceptual.

### REQ-IX-INV-002 — Advice does not override deterministic prohibition

- **ASR:** no.
- **Statement:** Neither AI advice nor human confirmation overrides an
  active hard-prohibition rule. A high success likelihood or low risk
  assessment does not change a hard-prohibition result.
- **Enforcing component:** `DeterministicPolicyEngine`.
- **Violated when:** a prohibited APK receives `ALLOW_AUTO` or
  `EXECUTE_ONCE`; or any backend submission occurs while a
  hard-prohibition rule is active, regardless of advisory result or
  human action.
- **Required negative test:** `planned: HardBlockPrecedenceTest`
  (AI HIGH/LOW plus human Continue on a prohibited APK yields
  `BLOCK`/`BLOCKED` with 0 backend submissions).
- **Implementation links:** `runtime-policy-catalog.md` DR-01 and §7
  baseline mechanisms; `adr/002-later-important-arch-decisions.md`
  ADR-002.6.
- **Status:** conceptual.

### REQ-IX-INV-003 — Approval binds the installation object

- **ASR:** yes — determines the approval state model, execution
  boundary, and pre-submission revalidation.
- **Statement:** An approval binds operation ID, attempt ID, APK
  SHA-256, package name, target user, install mode, installation
  backend, config snapshot, and policy version. If any binding item is
  missing or changed the approval becomes invalid and must be revalidated
  before submission. The first Continue in `AWAIT_HUMAN` creates this
  binding from the current installation object.
- **Enforcing component:** `ApprovalStateStore` (with
  `ApprovalCoordinator` performing the pre-submission recheck).
- **Violated when:** any bound field differs at submission time; an APK
  replaced between evidence read and submission is installed; an
  approval issued for one installation object is applied to another; a
  binding field is absent but submission proceeds.
- **Required negative test:** `planned: ApprovalBindingTest`
  (negative + TOCTOU). Uses a fixture where target and source package
  names differ, per NFC-OUT-004: changing only the bound target package
  name invalidates the approval and produces 0 backend submissions.
- **Implementation links:** `local_approval_binding.retains` in
  `requirements.yaml`; `adr/002-later-important-arch-decisions.md`
  ADR-002.7; `runtime-policy-catalog.md` F-16 / DR-10.
- **Status:** conceptual.

### REQ-IX-INV-004 — Model failure does not silently approve

- **ASR:** yes — shapes the failure-handling structure and degradation
  path across coordinator, UI, and audit.
- **Statement:** On model timeout, network loss, rate limiting, service
  error, invalid output, preflight token rejection, or reported post-call
  overage, the system does not auto-approve. A hard prohibition remains
  `BLOCK`; otherwise the result routes to `REQUIRE_REVIEW`. Consent
  refusal is not a model failure and is handled by REQ-IX-FR-011.
- **Enforcing component:** `ApprovalCoordinator`.
- **Violated when:** any listed failure produces `ALLOW_AUTO` or an
  automatic backend submission; a preflight rejection is counted as a
  provider dispatch; a post-call overage is counted as 0 completed
  calls.
- **Required negative test:** `planned: FailureFallbackTest`
  (fault injection per failure type; automatic executions 0; hard block
  preserved; preflight rejection 0 dispatches; post-call overage 1
  completed call).
- **Implementation links:** `runtime-policy-catalog.md` §3 failure
  catalog (F-01 … F-09), `adr/002-later-important-arch-decisions.md`
  ADR-002.6.
- **Status:** conceptual.

### REQ-IX-INV-005 — At most one submission per repeated event

- **ASR:** no (per the registry; see §3 open question).
- **Statement:** For the same installation operation ID, repeated
  events, concurrent callbacks, notification replays, process recovery,
  and user retries produce at most one automatic submission. A retry
  grants no new submission right.
- **Enforcing component:** `ApprovalStateStore`.
- **Violated when:** the same operation ID produces two or more backend
  submissions; a notification replay or recovery replay adds a
  submission; a user retry creates a new submission right rather than
  only a new attempt ID.
- **Required negative test:** `planned: IdempotencyTest`
  (20 concurrent execution events for one operation ID yield at most 1
  submission).
- **Implementation links:** `runtime-policy-catalog.md` §6 counter
  semantics and F-16; REQ-IX-REL-004 deduplication.
- **Status:** conceptual.

### REQ-IX-INV-006 — Policy decision independent of model

- **ASR:** yes — forces model and policy separation; the structural
  basis of the AI cage.
- **Statement:** Policy decision-making is independent of model
  availability. Without a model it deterministically produces `BLOCK` or
  `REQUIRE_REVIEW`. Policy code does not depend on a model SDK or
  network. The local compatibility assessment is also deterministic and
  model-independent.
- **Enforcing component:** `DeterministicPolicyEngine`.
- **Violated when:** a policy test requires a model or network; the
  policy package imports a model SDK or network client; a policy
  decision changes when the model is removed.
- **Required negative test:** `planned: PolicyWithoutModelTest`
  (model disconnected or empty adapter injected; all policy unit tests
  pass; static check finds no model or network dependency in the policy
  package).
- **Implementation links:** `permission-model.md` §9.2 (policy forbidden
  dependencies) and §9.3; `adr/001-architecture-choice.md` §6
  (check-based enforcement under the accepted Option B).
- **Status:** conceptual.

### REQ-IX-INV-007 — AI does not lower deterministic restrictions

- **ASR:** yes — determines the precedence between AI and policy.
- **Statement:** AI advice does not lower a restriction produced by
  deterministic policy. When the base policy is `REQUIRE_REVIEW` or
  `BLOCK`, AI may only maintain or raise the restriction. Auto-approval
  is allowed only when the base policy is `ALLOW_AUTO` and
  `AUTO_RULES_MET`, success likelihood is HIGH and security risk is LOW.
- **Enforcing component:** `DeterministicPolicyEngine`.
- **Violated when:** a `REQUIRE_REVIEW` base becomes `ALLOW_AUTO` on
  favorable advice; a `BLOCK` base is overridden by human approval; any
  case auto-submits above its base policy.
- **Required negative test:** `planned: NoDowngradeTest`
  (decision-table negative: base REVIEW plus AI LOW stays
  `REQUIRE_REVIEW`; base `ALLOW_AUTO` plus HIGH/LOW/rules-met becomes
  `ALLOW_AUTO`; base `BLOCK` plus human approval stays `BLOCK`).
- **Implementation links:** `runtime-policy-catalog.md` DR-05 … DR-09;
  `adr/002-later-important-arch-decisions.md` ADR-002.6.
- **Status:** conceptual.

---

## 2. Invariant ↔ ASR cross-reference

| Invariant | ASR | Failure-driving ASRs | Structural element |
|---|---|---|---|
| INV-001 | no | INV-006, ARCH-001 | `AIRiskAdvisor` boundary |
| INV-002 | no | INV-007 | `DeterministicPolicyEngine` |
| INV-003 | **yes** | REL-002, REL-003 | `ApprovalStateStore` |
| INV-004 | **yes** | REL-001 | `ApprovalCoordinator` |
| INV-005 | no | REL-002, REL-004 | `ApprovalStateStore` |
| INV-006 | **yes** | MOD-001 | `DeterministicPolicyEngine` |
| INV-007 | **yes** | ARCH-001 | `DeterministicPolicyEngine` |

Only INV-003, INV-004, INV-006, and INV-007 carry `asr: true` in the
registry. The evaluation-table drivers (D1–D4) still depend on all
seven, because INV-001, INV-002, and INV-005 are constraints every
candidate must satisfy.

---

## 3. Enforcement map against baseline code

Each invariant is preserved by a mechanism that already exists at the
baseline, or is enforced by a new element that must not duplicate those
mechanisms. `runtime-policy-catalog.md` §7 lists the same code points.

| Invariant | Existing baseline mechanism | Where |
|---|---|---|
| INV-001 | No baseline equivalent; the baseline has no AI component. New boundary in `permission-model.md` §9. | — |
| INV-002 | Profile signature policy and platform policy blocks | `ProcessInstallationUseCase.checkBlockedByProfile()` (`domain/engine/usecase/ProcessInstallationUseCase.kt:200-249`); `data/engine/policy/PlatformInstallPolicyChecker.kt` |
| INV-003 | APK SHA-256 exists but is not carried on `PackageAnalysisResult` | `data/engine/parser/FileHashUtils.kt:17,33`; `PackagePreprocessor.kt:106,176-177` |
| INV-004 | Session error routing exists; no AI failure path yet | `ActionHandler.startProcessingJob` (`data/session/handler/ActionHandler.kt:189-230`) |
| INV-005 | `OperationHistoryEntity` unique index on `(operation_session_key, operation_type, package_name)` is a partial precedent | `data/settings/local/room/entity/OperationHistoryEntity.kt` |
| INV-006 | No baseline equivalent; decisions today are procedural in the use case | `ProcessInstallationUseCase.installApp` (`domain/engine/usecase/ProcessInstallationUseCase.kt:280-324`) |
| INV-007 | Signature mismatch and unknown-signature blocks are the existing no-downgrade precedent | `ProcessInstallationUseCase.kt:200-249`; `InstallErrorType.BLOCKED_BY_PROFILE_SIGNATURE_MISMATCH` / `..._UNKNOWN` |

Baseline error types that already express a hard block:
`InstallErrorType.BLOCKED_BY_PROFILE`,
`BLOCKED_BY_PROFILE_SIGNATURE_MISMATCH`,
`BLOCKED_BY_PROFILE_SIGNATURE_UNKNOWN`, and `USER_RESTRICTED`
(`domain/engine/model/error/InstallErrorType.kt:133-137`;
`PlatformInstallPolicyChecker.checkUserRestrictions`).

---

## 4. Verification obligations

Per `docs/code-governance/verification-constraints.md` §1:

- Every invariant has at least one **negative** test that fails when the
  invariant is violated. All seven tests above are negative tests.
- Every test names its requirement or invariant in its name or KDoc.
- Removing the protection must make the negative test fail. A test that
  passes with the protection removed does not verify the invariant.
- `trace-matrix.md` maps each invariant to its planned test (§4).

Invariants with `asr: true` additionally require a structural check
(static import or module-dependency check), not only a behavioral test.

---

## 5. Open questions

1. INV-005 is an idempotency constraint with a safety impact but is not
   flagged `asr: true` in the registry. Whether to flag it is a registry
   decision (Member B), recorded as plan Q3.
2. Whether INV-003's TOCTOU test needs a device-level test harness or
   can run as a deterministic unit test with an injectable clock.
3. Audit integrity (REQ-IX-DATA-001) is a data constraint rather than an
   INV entry; it is tracked in `memory-design.md` §6 and
   ADR-002.7.
