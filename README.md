# InstallerX Revived (Community Edition)

**English** | [简体中文](docs/README_CN.md) | [Español](docs/README_ES.md) | [日本語](docs/README_JA.md) | [Deutsch](docs/README_DE.md)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Latest Release](https://img.shields.io/github/v/release/wxxsfxyzm/InstallerX?label=Stable)](https://github.com/wxxsfxyzm/InstallerX/releases/latest)
[![Prerelease](https://img.shields.io/github/v/release/wxxsfxyzm/InstallerX?include_prereleases&label=Beta)](https://github.com/wxxsfxyzm/InstallerX/releases)
[![Telegram](https://img.shields.io/badge/Telegram-2CA5E0?logo=telegram&logoColor=white)](https://t.me/installerx_revived)

> A modern and functional Android app installer. (You know some birds are not meant to be caged, their feathers are just too bright.)

Looking for a better app installer? Try **InstallerX**!

InstallerX Revived is a modern Android package installer and the community-maintained continuation of the original [InstallerX](https://github.com/iamr0s/InstallerX) project.

It is designed to replace limited stock or OEM package installers with a cleaner interface, broader package support, configurable installation profiles, and privileged workflows through Shizuku, Root, Dhizuku, or the system package manager mode.

## CS5351 Course Project

GitHub repository: <https://github.com/wxxsfxyzm/CS5351-Project>

### Purpose and architecture

This repository extends InstallerX Revived (baseline commit `816ea541`)
for the CS5351 Agentic Software Engineering project. It adds an
**AI install-risk advisor** to the AutoDialog installation flow: before
an install, the app sends a sanitized, allowlisted summary of the APK and
device facts to a language model, which returns an estimated install
success likelihood and security risk with reasons.

The safety boundary is that **AI output is advisory only**. A
deterministic policy engine makes every install decision (`ALLOW_AUTO`,
`REQUIRE_REVIEW`, or `BLOCK`) from local facts, and AI advice can only
keep or raise a restriction, never lower it. The AI component holds no
installer, Root, Shizuku, or Dhizuku capability, and the policy engine
has no model SDK or network dependency. Model egress is gated by user
consent and the in-app network switch.

Main elements (ADR-001, Option B: in-tree packages in the existing `app`
module): `AIRiskAdvisor`, `EvidenceSanitizer`, and `AIOutputValidator`
under `domain/ai` and `data/ai`; `DeterministicPolicyEngine` under
`domain/engine/policy`; `ModelRequestGate` and `AiNetworkGate` for
egress. `ApprovalCoordinator`, `ApprovalBinding`, and
`InstallationGateway` are the remaining build work; see
`architecture-blueprint.md` §3 for the status of each element.

### File organization

| Path | Contents |
|---|---|
| `AGENTS.md`, `Prompt.md` | Coding-agent entry point and static session prompt (D3, D9) |
| `architecture-blueprint.md` | Master constraint document (D2.1) |
| `docs/req/` | Requirements registry and its JSON schema (D1) |
| `docs/architecture/` | ADRs, invariants, trace matrix, memory design, permission model, runtime policy catalog, evaluation table, optional records (D2) |
| `docs/code-governance/` | Structural and verification constraints, MCP tool manifest, InstallerX baseline rules (D3, D4) |
| `docs/ai-use-log.yaml`, `docs/review-memo.md`, `docs/debt-register.md`, `docs/bug-diary.md` | Process and QA records (D4) |
| `docs/plans/` | Approved agent execution plans |
| `.opencode/`, `.agent/` | Agent permissions and always-on rules (D3) |
| `prompts/` | Versioned runtime prompts for the AI advisor |
| `transcripts/` | Annotated agent-session transcripts (D5) |
| `app/src/main/`, `app/src/test/` | Application source and tests (D8) |

The course layout names top-level `src/` and `test/` directories. ADR-001
keeps the new code inside the existing Android module instead, so
`app/src/main/java/com/rosan/installer/` holds the source and
`app/src/test/java/com/rosan/installer/` holds the tests. Deterministic
offline tests and live tests are separated by package: everything under
`com.rosan.installer.live` calls a real model; all other test packages are
offline. `architecture-blueprint.md` sits at the repository root, as in
the course's required repository structure.

### Running the tests

Requires JDK 25 and GitHub Packages credentials (see [Building](#building)).

```bash
# Offline suite: deterministic, no network. Live tests skip themselves
# unless all three AGENT_* variables below are set.
./gradlew testUnstableDebugUnitTest

# Live tests only: call the course LLM service (OpenAI-compatible).
export AGENT_BASE_URL=https://llm.cs.cityu.edu.hk/v1
export AGENT_DEPLOYMENT_MODEL=CS5351/DeepSeek-V4-Flash
export AGENT_API_KEY=<key>        # never commit or log the key
./gradlew testUnstableDebugUnitTest --tests "com.rosan.installer.live.*"

# Full suite: offline and live together (same three variables set).
./gradlew testUnstableDebugUnitTest
```

### CS5351 blocking gates

`.github/workflows/ci.yml` implements the course's blocking gates. The
script `.github/scripts/course_gates.py` holds the non-Gradle checks.

| Gate | When it runs | What blocks the merge |
|---|---|---|
| Test approval | PRs that change source or tests | PR body does not answer "tests approved before implementation?" with Yes, gives no approval evidence, or names a plan file that does not exist |
| Static analysis | Every push and PR | `spotlessCheck` (ktlint) fails, Kotlin compilation fails, or a dependency is declared outside `gradle/libs.versions.toml` |
| Safety-path checks | PRs that touch AI, policy, approval, gateway, or agent-permission paths | Architecture boundary tests (`*IsolationTest`, `*DependencyBoundaryTest`) or the policy decision-table contract test fails |
| Permission check | Every push and PR | `.opencode/permissions.jsonc` loses a guard rule: default deny, protected paths, secret reads, push/merge denial, or `project-plan` editing outside `docs/plans/` |
| Schema | Every push and PR | `docs/req/requirements.yaml` does not validate against `docs/req/req-schema.json`, or IDs repeat |
| Semantic evaluation | PRs that change prompts or AI request code | The fixed offline evaluation set (recorded model replies in the `domain.ai` and `data.ai` suites) fails, or `prompts/*.yaml` is out of sync with the code; the live suite also runs when an `AGENT_API_KEY` secret is set (endpoint and model from the `AGENT_BASE_URL` / `AGENT_DEPLOYMENT_MODEL` repository variables, defaulting to the course service) |
| Regression | Every push and PR | Any test in the full prior suite fails |
| AI-use log | PRs that change source, tests, or prompts | `docs/ai-use-log.yaml` is not updated, or an entry has a malformed ID or missing fields |

Branch protection on `main` must require all `CS5351 Gates` jobs and code
owner review. `.github/CODEOWNERS` routes safety-critical and governance
paths to the project lead, and changes to safety-critical code also need
an architecture decision (`.agent/rules.md` §4).

## Documentation

The full user guide, installation instructions, advanced options, system integration notes, and FAQ are maintained on the [documentation site](https://wxxsfxyzm.github.io/InstallerX-Revived-Website/).

## Highlights

- **Package formats:** APK, APKS, APKM, XAPK, APK files inside ZIP archives, and batch APK installation.
- **Dialog batch selection:** clearing a single-version package row clears its files; selecting it again restores the file choices from before clearing, including split and dex metadata choices. This applies to both UI styles within the same analysis; individual file edits or explicit APK-wide selection changes supersede the saved choices.
- **Install flows:** dialog installation, background notification installation, automatic installation, silent installation when privileges allow it, and Android 16+ Live Activity progress on supported systems.
- **Authorizers:**
  - **Root:** can perform all privileged operations, but may be slower because of cold `app_process` startup.
  - **Shizuku:** obtains shell or root capabilities depending on how it is activated, and is usually faster than direct Root.
  - **Dhizuku:** can perform DevicePolicyManager-based operations such as default installer locking and app installation, but is limited for other privileged tasks.
  - **None:** is fully limited by the system, but can silently install when InstallerX is running as the system package installer.
- **Profiles:** define how installation and uninstallation requests are handled, including install mode, authorizer override, installer/requester metadata, target user, DexOpt, auto-delete behavior, split selection, blacklist policy, and signature gates.
- **System integration:** InstallerX can be locked as the default installer from the Home page status card, used with LSPosed modules such as [InxLocker](https://github.com/Chimioo/InxLocker), or installed as a replacement system package manager by advanced users.
- **Modern UI:** Material 3 Expressive and Miuix interface styles, dark mode, dynamic color, advanced palettes, system icon packs, colorful dialogs, standard notifications, Live Activity, and Xiaomi HyperOS-style island notifications on supported Xiaomi devices.
- **Safety controls:** package-name and SharedUID blacklists, signature mismatch and unknown-signature policy gates, permission preview, install flags, and one-time smart suggestions for selected blocked cases.

## Supported Android Versions

- **Full support:** Android SDK 34 - 37.0
- **Limited support:** Android SDK 26 - 33

Limited support means InstallerX may work, but some features can be unavailable or behave differently because of Android framework, OEM, or authorizer limitations.

## Downloads

- **Stable releases:** https://github.com/wxxsfxyzm/InstallerX-Revived/releases/latest
- **Alpha builds:** https://github.com/wxxsfxyzm/InstallerX/releases
- **CI builds:** https://github.com/wxxsfxyzm/InstallerX-Revived/actions/workflows/auto-preview-dev.yml
- **Telegram channel:** https://t.me/installerx_revived

When reporting bugs, please reproduce them on the latest Alpha or CI build whenever possible, because issues in Stable may already be fixed.

InstallerX is now published as a single APK. Network access is controlled by an in-app setting. The release filename continues to include `online` only for compatibility with older in-app update clients; it no longer identifies a separate build variant.

## Building

InstallerX Revived is an Android Gradle project.

### Prerequisites

- **JDK 25** with `JAVA_HOME` configured correctly.
- Android SDK / Android Studio with the required platform and build tools installed.
- GitHub Packages credentials for the snapshot `miuix` dependency.

### GitHub Packages Authentication

GitHub Packages requires authentication even for public packages. Add your GitHub username and a classic personal access token with the `read:packages` scope to your global Gradle properties file:

- Linux / macOS: `~/.gradle/gradle.properties`
- Windows: `%USERPROFILE%\.gradle\gradle.properties`

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_PERSONAL_ACCESS_TOKEN
```

Do not commit these credentials to this repository.

### Build Commands

For a local debug build:

```bash
./gradlew assembleUnstableDebug
```

For a PR-style test build with a separate application id:

```bash
./gradlew assemblePreviewDebug -PAPP_ID="com.rosan.installer.x.revived.test"
```

## Common Questions

### Where should I report bugs or ask questions?

Use [GitHub Issues](https://github.com/wxxsfxyzm/InstallerX-Revived/issues) for reproducible bugs and concrete feature requests. Good suggestions are welcome. Use [GitHub Discussions](https://github.com/wxxsfxyzm/InstallerX-Revived/discussions) or the [Telegram channel](https://t.me/installerx_revived) for general questions and compatibility discussion.

Before opening an issue, please read [CONTRIBUTING.md](CONTRIBUTING.md) for the required logs and reproduction details.

### I cannot lock InstallerX as the default installer

Some OEM systems strictly control the default package installer. Open the default installer page from the Home page status card and try locking from there. If the ROM still prevents it, use an LSPosed module such as [InxLocker](https://github.com/Chimioo/InxLocker).

### HyperOS says installing system apps requires a valid installer

This is an OEM security restriction. InstallerX can declare installer metadata through profiles, and on HyperOS it uses `com.android.shell` as the default compatibility installer package. Shizuku or Root is required for this workflow; Dhizuku is not enough.

### Notification installation progress is stuck

Some ROMs restrict background services aggressively. Set InstallerX to unrestricted background/battery mode if notification installation stalls. InstallerX cleans up its foreground service shortly after the installation task finishes.

### How do I replace the system package manager?

This is a high-risk advanced workflow. The short version is: use Core Patch to overwrite the APK, flash a matching module, or integrate the matching package into `super` / a ROM build. Before flashing or packaging, verify the package name, mount path, and permissions file for your ROM.

See the system integration guide for details: https://wxxsfxyzm.github.io/InstallerX-Revived-Website/guide/system-integration

## Localization

Help translate InstallerX Revived on Weblate:

https://hosted.weblate.org/engage/installerx-revived/

[![Localization Status](https://hosted.weblate.org/widget/installerx-revived/strings/multi-auto.svg)](https://hosted.weblate.org/engage/installerx-revived/)

## License

Copyright (C) [iamr0s](https://github.com/iamr0s) and [InstallerX Revived Contributors](https://github.com/wxxsfxyzm/InstallerX-Revived/graphs/contributors)

InstallerX Revived is released under the [GNU General Public License v3](http://www.gnu.org/licenses/gpl-3.0).

If you base your work on InstallerX Revived, you must comply with the open-source license terms of the specific source version you use.

## Acknowledgements

This project uses code from, or is based on the implementation of, the following projects:

- [iamr0s/InstallerX](https://github.com/iamr0s/InstallerX)
- [tiann/KernelSU](https://github.com/tiann/KernelSU)
- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)
- [zacharee/InstallWithOptions](https://github.com/zacharee/InstallWithOptions)
- [vvb2060/PackageInstaller](https://github.com/vvb2060/PackageInstaller)
- [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)

## Star History

<a href="https://www.star-history.com/?repos=wxxsfxyzm%2FInstallerX-Revived&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=wxxsfxyzm/InstallerX-Revived&type=date&theme=dark&legend=top-left&sealed_token=NIZn5WH0-dVDGHKTPTVKAke10T3j7OrdIZ6rTbhT4zwN5AyJhj5aJhc36MEg2ZD5FoBrDoxgF3jzozO8cpBCQoJ65WbUGZZestdrI42Rnv8QVdEvE9Jz-qfghs5RpT5BeRHLuDH2NLyDNypEg34_XaPsTDAkx6DZAl0bUc0tpa0C2xzKBPih_ELq-sP2" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=wxxsfxyzm/InstallerX-Revived&type=date&legend=top-left&sealed_token=NIZn5WH0-dVDGHKTPTVKAke10T3j7OrdIZ6rTbhT4zwN5AyJhj5aJhc36MEg2ZD5FoBrDoxgF3jzozO8cpBCQoJ65WbUGZZestdrI42Rnv8QVdEvE9Jz-qfghs5RpT5BeRHLuDH2NLyDNypEg34_XaPsTDAkx6DZAl0bUc0tpa0C2xzKBPih_ELq-sP2" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=wxxsfxyzm/InstallerX-Revived&type=date&legend=top-left&sealed_token=NIZn5WH0-dVDGHKTPTVKAke10T3j7OrdIZ6rTbhT4zwN5AyJhj5aJhc36MEg2ZD5FoBrDoxgF3jzozO8cpBCQoJ65WbUGZZestdrI42Rnv8QVdEvE9Jz-qfghs5RpT5BeRHLuDH2NLyDNypEg34_XaPsTDAkx6DZAl0bUc0tpa0C2xzKBPih_ELq-sP2" />
 </picture>
</a>
