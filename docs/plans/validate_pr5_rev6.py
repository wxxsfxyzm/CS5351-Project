from pathlib import Path
import sys, yaml, json
from jsonschema import Draft202012Validator

p = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
d = yaml.safe_load((p / "requirements.yaml").read_text())
s = json.loads((p / "req-schema.json").read_text())
Draft202012Validator.check_schema(s)
Draft202012Validator(s).validate(d)


def hit(**changes):
    case = dict(
        evidence="COMPLETE",
        advice="VALID_HIGH_SUCCESS_LOW_RISK",
        human="NOT_OBTAINED",
        policy="AUTO_RULES_MET",
        compatibility="COMPATIBLE",
    )
    case.update(changes)

    def matches(k, v):
        t = case[k]
        return (
            v == "ANY"
            or t == v
            or (v == "NOT_HARD_BLOCK" and t != "HARD_BLOCK")
            or (v == "VALID_ANY" and t.startswith("VALID_"))
            or t in v.split("_OR_")
        )

    for r in d["decision_rules"]:
        if all(matches(k, r[k]) for k in case):
            return (r["id"], r["decision"], r["disposition"])


cases = [
    ({}, ("DR-05", "ALLOW_AUTO", "EXECUTE_ONCE")),
    ({"policy": "AUTO_RULES_NOT_MET"}, ("DR-06", "REQUIRE_REVIEW", "AWAIT_HUMAN")),
    ({"compatibility": "UNKNOWN"}, ("DR-07", "REQUIRE_REVIEW", "AWAIT_HUMAN")),
    ({"compatibility": "INCOMPATIBLE"}, ("DR-07", "REQUIRE_REVIEW", "AWAIT_HUMAN")),
    (
        {"advice": "VALID_LOW_SUCCESS_LOW_RISK"},
        ("DR-07", "REQUIRE_REVIEW", "AWAIT_HUMAN"),
    ),
    (
        {"advice": "VALID_HIGH_SUCCESS_HIGH_RISK"},
        ("DR-07", "REQUIRE_REVIEW", "AWAIT_HUMAN"),
    ),
    ({"advice": "INVALID"}, ("DR-08", "REQUIRE_REVIEW", "AWAIT_HUMAN")),
    ({"evidence": "CONTRADICTORY"}, ("DR-04", "REQUIRE_REVIEW", "AWAIT_HUMAN")),
    (
        {"advice": "UNAVAILABLE_CONSENT_DECLINED"},
        ("DR-02", "REQUIRE_REVIEW", "AWAIT_HUMAN"),
    ),
    ({"evidence": "TRUNCATED"}, ("DR-03", "REQUIRE_REVIEW", "RECOLLECT")),
    (
        {"evidence": "UNREADABLE", "advice": "UNAVAILABLE_CONSENT_DECLINED"},
        ("DR-03", "REQUIRE_REVIEW", "RECOLLECT"),
    ),
    (
        {
            "human": "VALID_APPROVAL_AND_REVALIDATION_PASSED",
            "advice": "UNAVAILABLE_CONSENT_DECLINED",
        },
        ("DR-09", "REQUIRE_REVIEW", "EXECUTE_ONCE"),
    ),
    (
        {"evidence": "KEY_UNKNOWN", "human": "REVALIDATION_FAILED_AFTER_APPROVAL"},
        ("DR-10", "REQUIRE_REVIEW", "INVALIDATED"),
    ),
    (
        {"policy": "HARD_BLOCK", "human": "VALID_APPROVAL_AND_REVALIDATION_PASSED"},
        ("DR-01", "BLOCK", "BLOCKED"),
    ),
]
for c, out in cases:
    assert hit(**c) == out, (c, hit(**c), out)
print("PASS: schema and 14 first-match decision cases")
from copy import deepcopy
from graphlib import TopologicalSorter
from collections import Counter

reqs = d["requirements"]
deps = {r["id"]: r["depends_on"] for r in reqs}
assert d["revision"] == 6 and len(deps) == len(reqs) == 41
assert all(dep in deps for targets in deps.values() for dep in targets)
list(TopologicalSorter(deps).static_order())
assert Counter(r["priority"] for r in reqs) == {"Must": 34, "Should": 7}
v = Draft202012Validator(s)
for group, field, replacement in [
    ("requirements", "fit_criterion", None),
    ("requirements", "priority", "Urgent"),
    ("decision_rules", "decision", "APPROVED"),
]:
    invalid = deepcopy(d)
    if replacement is None:
        del invalid[group][0][field]
    else:
        invalid[group][0][field] = replacement
    assert not v.is_valid(invalid), (group, field)
print(
    "PASS: revision 6, 41 unique IDs, priorities, dependency graph, 3 rejected invalid documents"
)
