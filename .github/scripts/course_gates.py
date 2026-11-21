#!/usr/bin/env python3
"""CS5351 blocking-gate checks used by .github/workflows/ci.yml.

Each subcommand exits non-zero when its gate fails. The gate design is
documented in README.md ("CS5351 blocking gates").

  schema                      Requirements registry validates against its schema.
  changes <base> <head>       Print which gate-relevant path groups changed.
  ai-use-log <base> <head>    Code or prompt changes must update the AI-use log.
  test-approval <base> <head> Code changes need test-approval evidence in the PR body.
  permissions                 Agent permission file keeps its guard rules.
  dependencies                Dependencies are declared only through the version catalog.
  prompt-sync                 Runtime prompt file matches the prompt in code.
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# Path groups (prefix match, repository-relative).
CODE = ("app/src/main/", "app-process/src/", "hidden-api/src/", "src/")
TESTS = ("app/src/test/", "app/src/androidTest/", "test/")
PROMPTS = (
    "prompts/",
    "Prompt.md",
    "app/src/main/java/com/rosan/installer/domain/ai/usecase/AiRequestBuilder.kt",
    "app/src/main/java/com/rosan/installer/data/ai/",
)
SAFETY = (
    "app/src/main/java/com/rosan/installer/domain/ai/",
    "app/src/main/java/com/rosan/installer/data/ai/",
    "app/src/main/java/com/rosan/installer/domain/engine/policy/",
    "app/src/main/java/com/rosan/installer/domain/engine/approval/",
    "app/src/main/java/com/rosan/installer/domain/engine/install/",
    "app/src/main/java/com/rosan/installer/di/AiModule.kt",
    ".opencode/",
    ".agent/",
    "AGENTS.md",
)
AI_USE_LOG = "docs/ai-use-log.yaml"
LOG_FIELDS = ("id", "date", "tool", "role", "operator", "task", "artifacts",
              "human_decisions", "verification", "evidence")


def fail(msg):
    print(f"GATE FAILED: {msg}")
    sys.exit(1)


def changed_files(base, head):
    out = subprocess.run(["git", "diff", "--name-only", f"{base}...{head}"], cwd=ROOT,
                         capture_output=True, text=True, check=True).stdout
    return [line for line in out.splitlines() if line]


def touches(files, prefixes):
    return [f for f in files if any(f == p or f.startswith(p) for p in prefixes)]


# --- schema gate ----------------------------------------------------------

def cmd_schema():
    import yaml
    from jsonschema import Draft202012Validator

    data = yaml.safe_load((ROOT / "docs/req/requirements.yaml").read_text(encoding="utf-8"))
    schema = json.loads((ROOT / "docs/req/req-schema.json").read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    errors = sorted(Draft202012Validator(schema).iter_errors(data), key=lambda e: list(e.path))
    for e in errors[:20]:
        print(f"  {'/'.join(map(str, e.path)) or '<root>'}: {e.message}")
    if errors:
        fail(f"requirements.yaml has {len(errors)} schema error(s)")
    ids = [r["id"] for r in data.get("requirements", [])]
    dups = sorted({i for i in ids if ids.count(i) > 1})
    if dups:
        fail(f"duplicate requirement IDs: {dups}")
    print(f"schema gate passed: {len(ids)} requirements valid against req-schema.json")


# --- change classification ------------------------------------------------

def cmd_changes(base, head):
    files = changed_files(base, head)
    groups = {"code": CODE, "tests": TESTS, "prompts": PROMPTS, "safety": SAFETY}
    for name, prefixes in groups.items():
        hit = bool(touches(files, prefixes))
        print(f"{name}={'true' if hit else 'false'}")


# --- AI-use log gate ------------------------------------------------------

def cmd_ai_use_log(base, head):
    import yaml

    files = changed_files(base, head)
    relevant = touches(files, CODE + TESTS + PROMPTS)
    log = yaml.safe_load((ROOT / AI_USE_LOG).read_text(encoding="utf-8"))
    entries = log.get("entries") or []
    ids = [e.get("id") for e in entries]
    bad_ids = [i for i in ids if not re.fullmatch(r"AI-[A-Za-z0-9_-]+-\d{4}", str(i))]
    dups = sorted({i for i in ids if ids.count(i) > 1})
    missing = [(e.get("id"), [f for f in LOG_FIELDS if f not in e]) for e in entries]
    missing = [m for m in missing if m[1]]
    if bad_ids:
        fail(f"AI-use log IDs not in AI-<handle>-NNNN form: {bad_ids}")
    if dups:
        fail(f"duplicate AI-use log IDs: {dups}")
    if missing:
        fail(f"AI-use log entries missing required fields: {missing}")
    if relevant and AI_USE_LOG not in files:
        print("Code, test, or prompt files changed:")
        for f in relevant[:20]:
            print(f"  {f}")
        fail(f"{AI_USE_LOG} was not updated in this pull request")
    print(f"AI-use log gate passed ({len(entries)} entries; "
          f"{'log updated' if AI_USE_LOG in files else 'no code or prompt change'})")


# --- test approval gate ---------------------------------------------------

def cmd_test_approval(base, head):
    files = changed_files(base, head)
    if not touches(files, CODE + TESTS):
        print("test approval gate passed: no code or test change")
        return
    body = os.environ.get("PR_BODY", "")
    answer = re.search(r"tests approved before implementation\?\s*(.*)", body, re.I)
    evidence = re.search(r"Approval evidence[^:\n]*:\s*(.*)", body, re.I)
    if not answer or not re.search(r"\byes\b", answer.group(1), re.I):
        fail("PR body must answer 'Were the relevant tests approved before implementation?' with Yes")
    ev = evidence.group(1).strip() if evidence else ""
    if not ev:
        fail("PR body must give 'Approval evidence' (an approved plan, PR comment, or transcript)")
    for plan in re.findall(r"docs/plans/[\w./-]+\.md", ev):
        if not (ROOT / plan).is_file():
            fail(f"approval evidence names a plan that does not exist: {plan}")
    print(f"test approval gate passed: evidence = {ev[:120]}")


# --- permission check -----------------------------------------------------

def strip_jsonc(text):
    out, i, in_str = [], 0, False
    while i < len(text):
        c = text[i]
        if in_str:
            out.append(c)
            if c == "\\":
                out.append(text[i + 1])
                i += 1
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            out.append(c)
        elif text.startswith("//", i):
            while i < len(text) and text[i] != "\n":
                i += 1
            continue
        elif text.startswith("/*", i):
            i = text.index("*/", i) + 2
            continue
        else:
            out.append(c)
        i += 1
    return re.sub(r",(\s*[}\]])", r"\1", "".join(out))


def wildcard(text, pattern):
    # Mirrors OpenCode 1.x permission matching.
    p = re.sub(r"[.+^${}()|[\]\\]", lambda m: "\\" + m.group(0), pattern)
    p = p.replace("*", ".*").replace("?", ".")
    if p.endswith(" .*"):
        p = p[:-3] + "( .*)?"
    return re.match("^" + p + "$", text, re.S) is not None


def decide(rules, value):
    if isinstance(rules, str):
        return rules
    result = None
    for pattern, action in rules.items():
        if wildcard(value, pattern):
            result = action
    return result


def cmd_permissions():
    cfg = json.loads(strip_jsonc((ROOT / ".opencode/permissions.jsonc").read_text(encoding="utf-8")))
    problems = []
    glob = cfg.get("permission", {})
    for tool in ("read", "edit", "bash"):
        if decide(glob.get(tool, {}), "anything") != "deny":
            problems.append(f"global {tool} default is not deny")
    protected = [".opencode/permissions.jsonc", ".opencode/rules.md", ".agent/rules.md",
                 ".github/workflows/ci.yml", ".github/CODEOWNERS", "AGENTS.md"]
    secrets = ["local.properties", "app/keystore.properties", "keystore/release.jks", ".env"]
    agents = cfg.get("agent", {})
    for name, agent in agents.items():
        perm = agent.get("permission", {})
        for tool in ("read", "edit"):
            rules = perm.get(tool)
            if not isinstance(rules, dict) or next(iter(rules.items()), None) != ("*", "deny"):
                problems.append(f"{name}: {tool} rules must start with \"*\": \"deny\"")
        for path in protected:
            if decide(perm.get("edit", {}), path) != "deny":
                problems.append(f"{name}: edit {path} is not denied")
        for path in secrets:
            if decide(perm.get("read", {}), path) != "deny":
                problems.append(f"{name}: read {path} is not denied")
        for cmd in ("git push origin main", "git merge x", "git reset --hard HEAD"):
            if decide(perm.get("bash", {}), cmd) != "deny":
                problems.append(f"{name}: bash '{cmd}' is not denied")
        for tool in ("task", "webfetch", "websearch", "external_directory"):
            if decide(perm.get(tool, "deny"), "x") != "deny":
                problems.append(f"{name}: {tool} is not denied")
    plan = agents.get("project-plan", {}).get("permission", {}).get("edit", {})
    allowed = [p for p, a in plan.items() if a != "deny"]
    if allowed != ["docs/plans/*"]:
        problems.append(f"project-plan may edit only docs/plans/*, found {allowed}")
    model = (ROOT / "docs/architecture/permission-model.md").read_text(encoding="utf-8")
    for name in agents:
        if f"`{name}`" not in model:
            problems.append(f"agent {name} is not described in permission-model.md")
    for p in problems:
        print(f"  {p}")
    if problems:
        fail(f"{len(problems)} permission guard violation(s)")
    print(f"permission check passed: {len(agents)} agents keep guard rules G1-G6")


# --- dependency check -----------------------------------------------------

RAW_DEP = re.compile(
    r'\b(implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|ksp|kapt)'
    r'\s*\(\s*"[^"$]+:[^"$]+:[^"$]+"')


def cmd_dependencies():
    hits = []
    for path in ROOT.rglob("*.gradle.kts"):
        rel = path.relative_to(ROOT).as_posix()
        if "/build/" in f"/{rel}" or rel.startswith(".gradle/"):
            continue
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if RAW_DEP.search(line):
                hits.append(f"{rel}:{n}: {line.strip()}")
    for h in hits:
        print(f"  {h}")
    if hits:
        fail("raw dependency coordinates found; declare them in gradle/libs.versions.toml")
    print("dependency check passed: all dependencies go through the version catalog")


# --- prompt sync check ----------------------------------------------------

def kotlin_constant(source, name):
    m = re.search(rf'const val {name}\s*=\s*"([^"]*)"', source)
    return m.group(1) if m else None


def kotlin_system_prompt(source):
    m = re.search(r"val SYSTEM_PROMPT: String = listOf\((.*?)\)\.joinToString\(\"\\n\"\)", source, re.S)
    if not m:
        return None
    return "\n".join(json.loads(f'"{s}"') for s in re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1)))


def cmd_prompt_sync():
    import yaml

    src = (ROOT / PROMPTS[2]).read_text(encoding="utf-8-sig")
    version = kotlin_constant(src, "PROMPT_VERSION")
    model = kotlin_constant(src, "DEFAULT_MODEL_ID")
    max_out = re.search(r"const val MAX_OUTPUT_TOKENS\s*=\s*(\d+)", src)
    system = kotlin_system_prompt(src)
    matches = []
    for path in sorted((ROOT / "prompts").glob("*.yaml")):
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        if doc.get("prompt_version") == version:
            matches.append((path, doc))
    if len(matches) != 1:
        fail(f"expected exactly one prompts/*.yaml with prompt_version '{version}', found {len(matches)}")
    path, doc = matches[0]
    problems = []
    if doc.get("system_prompt", "").rstrip("\n") != system:
        problems.append("system_prompt differs from AiRequestBuilder.SYSTEM_PROMPT")
    if doc.get("model_id") != model:
        problems.append(f"model_id {doc.get('model_id')} != {model}")
    if max_out and doc.get("max_output_tokens") != int(max_out.group(1)):
        problems.append(f"max_output_tokens {doc.get('max_output_tokens')} != {max_out.group(1)}")
    for p in problems:
        print(f"  {p}")
    if problems:
        fail(f"{path.relative_to(ROOT)} is out of sync with the code; bump the version for any prompt change")
    print(f"prompt sync passed: {path.relative_to(ROOT)} matches prompt version {version}")


COMMANDS = {
    "schema": (cmd_schema, 0),
    "changes": (cmd_changes, 2),
    "ai-use-log": (cmd_ai_use_log, 2),
    "test-approval": (cmd_test_approval, 2),
    "permissions": (cmd_permissions, 0),
    "dependencies": (cmd_dependencies, 0),
    "prompt-sync": (cmd_prompt_sync, 0),
}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        sys.exit(2)
    fn, nargs = COMMANDS[sys.argv[1]]
    if len(sys.argv) - 2 != nargs:
        print(__doc__)
        sys.exit(2)
    fn(*sys.argv[2:])
