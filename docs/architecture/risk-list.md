# Risk List

**Status:** draft — pending human review.
**Owner:** Member C (Software Architect).
**Baseline:** InstallerX Revived `816ea541`; repository state `0f718739`.
**Requirements:** `docs/req/requirements.yaml` rev.6
(`status: draft_for_group_review`).
**Related:** `architecture-blueprint.md`, `option-ledger.md`,
`runtime-policy-catalog.md`, `memory-design.md`.

Course marker `[Optional]` — optional artifact, populated for review
value.

Scale: likelihood and impact are Low / Medium / High. The mitigation is
what the architecture or process already does, or must add. Owner is
whoever must act. This list is an architecture risk register, not a
project issue tracker.

---

## 1. Technical risks

| ID | Risk | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|---|
| RK-T-01 | APK SHA-256 is not carried on `PackageAnalysisResult`, so approval binding (INV-003) has no source value | High | High | Add a local-only hash carrier to the evidence model before binding is implemented; hash function already exists (`FileHashUtils.kt:17,33`) | Member C / build |
| RK-T-02 | TOCTOU: the APK file is replaced between evidence read and submission | Medium | High | Re-read SHA-256 immediately before submit and compare to the bound value; mismatch → `INVALIDATED`, 0 submissions (ADR-002.7) | Member C |
| RK-T-03 | Android 17 self-update can kill the process before the call returns | Medium | Medium | Reuse `SelfUpdateRecoveryManager.arm()` durable pre-action record; do not invent a second recovery path | Member C |
| RK-T-04 | Room schema change for approval/audit breaks existing migrations (version 18) | Medium | Medium | Decide ADR-002.3 before coding; use a versioned migration and `exportSchema` artifacts | Member C |
| RK-T-05 | Koin wiring mistakes create two `ApprovalCoordinator` instances and break the choke point | Medium | High | Register as a singleton in an existing `di/` module; add a test that asserts a single coordinator instance | build |
| RK-T-06 | `AutoNotification` (Should priority) is dropped late, leaving tests and docs inconsistent | Medium | Low | Scope envelope states the Should status and the "no acceptance claim if not delivered" rule (SCOPE-001) | Member C |
| RK-T-07 | The 15 s latency budget is missed because of queueing outside the model call | Medium | Medium | Budget is defined from `AWAITING_AI` to persisted decision and excludes human time; test with a timeout stub | build |
| RK-T-08 | Policy engine accidentally depends on a model or network type | Medium | High | ADR-001 Option B has no compiler boundary; a static/import test rule is mandatory; `planned: PolicyWithoutModelTest` | build |

## 2. Security risks

| ID | Risk | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|---|
| RK-S-01 | Prompt injection through label, version name, or permission names | High | High | `EvidenceSanitizer` (strip C0/C1, NFKC, JSON-escape, 4 KB/field, 32 KB total, neutralize instruction text); authorization stays deterministic. Explicitly **reduced, not eliminated** (SEC-005) | build |
| RK-S-02 | Outbound request leaks a local-only identifier | Medium | High | Allowlist is the single source of truth; full-prompt scan for local-only identifiers (NFC-OUT-001, NFC-OUT-002); `planned: OutboundWhitelistTest` | build |
| RK-S-03 | Model returns an install or approve instruction and it is acted on | Medium | High | `AIOutputValidator` classifies action fields as `INVALID`; advisory elements have no backend reference (INV-001, FR-004) | build |
| RK-S-04 | AI advice or human confirmation overrides a hard block | Low | Critical | DR-01 dominates; `planned: HardBlockPrecedenceTest` | build |
| RK-S-05 | Approval record tampering goes undetected | Low | High | Append-only hash chain (DATA-001); `planned: ApprovalTamperTest`, `planned: AuditIntegrityTest` | build |
| RK-S-06 | Credentials or tokens appear in audit or logs | Low | High | FR-009 forbids storing credentials; log scan in `planned: OutboundWhitelistTest` | build |
| RK-S-07 | Outbound egress occurs while the in-app network switch is off | Low | High | `NetworkGate` checks the setting before any dispatch; 0 dispatches when off (SEC-004) | build |
| RK-S-08 | `ActionHandler` or `InAppInstallProviderImpl` bypass is mistaken for an AI-protected path | Medium | Medium | Documented as known bypasses and non-goals; the project makes no protection claim (SCOPE-001) | Member C |

## 3. Scope risks

| ID | Risk | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|---|
| RK-C-01 | Requirements rev.6 is still `draft_for_group_review`, so D2 may target a moving baseline | High | Medium | Mark allowlist and COST numbers provisional; regenerate the trace matrix on change | Member C |
| RK-C-02 | `Outbound_metadata_allowlist` changes after Member A review | Medium | Medium | Treat the registry as source of truth; no architecture guarantee is derived from a pending PMA item | Member C |
| RK-C-03 | Both UI families (M3 and Miuix) must be updated for review/consent | High | Medium | ARCH-002 is a Should requirement; record an exemption if only one family ships, linked to a review record | Member C |
| RK-C-04 | `Authorizer.None` demonstration path differs from Root/Shizuku behaviour | Medium | Medium | Scope is `Authorizer.None` only; root/Shizuku coverage is out of scope for v1 (SCOPE-001) | Member C |
| RK-C-05 | Course expects a `docs/architecture/` blueprint while the repo keeps it at root | Medium | Low | Discrepancy recorded in blueprint header; resolve with the team if required | Member C |
| RK-C-06 | `evaluation-table.md` may be intended for model-evaluation results, not architecture QA | Medium | Low | Assumption stated in the file; flagged as plan Q6 | Member C |

## 4. Governance risks

| ID | Risk | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|---|
| RK-G-01 | D2 drafts are treated as approved architecture | Medium | High | Every file carries `Status: draft — pending human review`; ADR-001 is accepted but the remaining ADRs and requirements rev.6 are drafts | Member C |
| RK-G-02 | Later work introduces a Gradle module that ADR-001 (Option B) does not authorize | Low | Medium | ADR-001 is accepted on Option B; adding a module requires a new or amended ADR, not a silent build change | Member C |
| RK-G-03 | Tests are written before approval (violating the test-approval gate) | Low | High | All tests in D2 are marked `planned:`; `verification-constraints.md` §2 requires approval before implementation | Member C |
| RK-G-04 | Trace matrix drifts from `requirements.yaml` | Medium | Medium | Generated by script; read-only verification script run in Batch 3 | Member C |
| RK-G-05 | AI-use log entry is missing or fabricated | Low | High | Append a real entry describing this session; never claim unverified results | Member C |
| RK-G-06 | Transitive dependency from the AI module to `framework/privileged/**` or `app-process` re-introduces privilege | Low | High | Module dependency direction plus static check; `planned: AdvisorIsolationTest` | build |
| RK-G-07 | Omission risk: a `[Optional]` artifact is ignored and left empty | Low | Low | This batch populates all three; the ledger is a record, not decoration | Member C |

---

## 5. Residual risk statement

No mitigation removes prompt-injection risk entirely (RK-S-01). The
architecture therefore does not rely on the model behaving correctly: the
authorization decision is deterministic, and no model output can create
an approval or a backend call. The controls reduce the probability that
advice is manipulated; they do not make advice trustworthy.

## 6. Traceability

Covers risks to: REQ-IX-INV-001, REQ-IX-INV-003, REQ-IX-INV-004,
REQ-IX-INV-006, REQ-IX-INV-007, REQ-IX-SEC-003, REQ-IX-SEC-004,
REQ-IX-SEC-005, REQ-IX-REL-002, REQ-IX-REL-003, REQ-IX-ARCH-001,
REQ-IX-ARCH-002, REQ-IX-DATA-001, REQ-IX-SCOPE-001, REQ-IX-COMP-001,
REQ-IX-VER-001, REQ-IX-GOV-001, REQ-IX-GOV-002.
