# Structural Constraints

Status: proposed by agent, pending human review.

Module organization rules for all generated code. Detailed
InstallerX conventions are in `installerx-baseline.md`.

## 1. One Element, One Module

- Each architectural element in `architecture-blueprint.md` maps to
  exactly one module or package. No element is split across
  modules, and no module implements two elements.
- Module and package names match the element names used in the
  blueprint and ADRs.
- The mapping between architectural elements, Gradle modules, and
  the course `src/` and `test/` layout is decided in an ADR. Until
  that ADR is accepted, do not create new modules.

## 2. Extend the Baseline, Never Replace It

- The code baseline is upstream InstallerX Revived at commit
  `816ea541`.
- New work adds to the existing modules and layers. Do not rewrite,
  move, or delete baseline code unless an approved plan requires it
  and names the affected files.
- Keep the existing layering under `app/src/main/java/com/rosan/installer/`:
  `core`, `data`, `di`, `domain`, `framework`, `ui`, `util`.
  Domain code must not depend on UI or Android framework classes.
- Register new injectable components in the existing Koin modules
  under `di/`.

## 3. Traceability in Code

- Every new module and every public class or function added for the
  CS5351 work carries a KDoc tag that names the ADR and, where
  relevant, the invariant it implements, for example:

  ```kotlin
  /**
   * Implements: ADR-001
   * Enforces: REQ-IX-INV-001
   */
  ```

- Every test names the requirement or invariant it verifies (see
  `verification-constraints.md`).
- Code with no traceable requirement or ADR is out of scope.

## 4. Authority Boundaries

- **Advisory:** the AI component produces recommendations and
  explanations only. It must not hold references to, or call,
  installer sessions, privileged backends (Root, Shizuku, Dhizuku,
  `app_process`), or settings that change install behavior.
- **Authoritative:** deterministic policy code makes every install
  decision. It must run without the AI component, model SDKs, or
  network access.
- **Human:** a human approval can confirm a decision but cannot
  override a hard block from deterministic policy.
- Dependencies point from the AI component toward shared models, not
  toward installer or privileged modules. Structural checks must
  fail the build if this direction is broken.

## 5. Existing Boundaries to Preserve

- Network requests stay disabled when the in-app network setting is
  off.
- Dialog, notification, and automatic install flows remain separate;
  a feature added to one is not implied for the others.
- Material 3 and Miuix UI families stay separate; shared behavior
  changes update both.
- Privileged behavior is not widened from one backend to another
  without explicit evidence.
