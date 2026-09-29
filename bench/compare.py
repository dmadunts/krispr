#!/usr/bin/env python3
"""Summarises one Krispr run and one PIT run of the same module for docs/evidence.md.

Usage: compare.py <krispr report.json> <module dir> <PIT mutations.xml> <source dir,...> <out dir>

Writes <out>/summary.json with counts per tool, PIT's NO_COVERAGE split by whether the mutated line is
inside an `inline` function, and PIT mutants in members the Kotlin compiler generates. Survivor sampling
for blind rating is done separately by sample.py.
"""
import json
import os
import re
import sys
from collections import Counter

KILLED = {"KILLED", "TIMED_OUT", "MEMORY_ERROR"}


def krispr_mutants(path, module_dir):
    report = json.load(open(path))
    return [
        {"file": os.path.join(module_dir, m["file"]), "line": m["line"], "op": m["operator"], "desc": m["description"],
         "status": m["status"]}
        for m in report["mutants"]
    ]


def pit_mutants(path, source_dirs):
    xml = open(path).read()
    out = []
    for attrs, body in re.findall(r"<mutation (.*?)>(.*?)</mutation>", xml, re.S):
        tag = lambda t: (re.search(rf"<{t}>(.*?)</{t}>", body, re.S) or [None, ""])[1]
        cls = tag("mutatedClass")
        pkg = cls.rsplit(".", 1)[0].replace(".", "/") if "." in cls else ""
        out.append({
            "file": resolve(tag("sourceFile"), pkg, source_dirs),
            "line": int(tag("lineNumber")),
            "cls": cls,
            "method": tag("mutatedMethod"),
            "op": tag("mutator").rsplit(".", 1)[-1],
            "desc": tag("description"),
            "status": re.search(r"status='(\w+)'", attrs).group(1),
        })
    return out


def resolve(name, pkg, source_dirs):
    for d in source_dirs:
        p = os.path.join(d, pkg, name)
        if os.path.exists(p):
            return p
    for d in source_dirs:  # Kotlin files need not sit in their package's directory
        for root, _, files in os.walk(d):
            if name in files:
                return os.path.join(root, name)
    return name


_lines = {}


def lines(path):
    if path not in _lines:
        _lines[path] = open(path).read().split("\n") if os.path.exists(path) else []
    return _lines[path]


FUN = re.compile(r"^\s*((?:[a-z]+\s+)*)fun\b")


def in_inline_function(path, line):
    """Whether the nearest `fun` declaration at or above `line` is `inline`. A heuristic: good enough for
    top-level and member functions, which is where kotlin-result, clikt and turbine keep their inline code."""
    src = lines(path)
    for i in range(min(line, len(src)) - 1, -1, -1):
        m = FUN.match(src[i])
        if m:
            return "inline" in m.group(1).split()
    return False


GENERATED = re.compile(r"^(component\d+|copy|copy\$default|access\$.*|.*\$default)$")


def generated_member(m):
    """PIT mutants in members kotlinc writes, not the user: data class componentN/copy, default-argument
    bridges, synthetic accessors, and equals/hashCode/toString the source file never declares."""
    if GENERATED.match(m["method"]):
        return m["method"]
    if m["method"] in ("equals", "hashCode", "toString"):
        declared = any(re.search(rf"\bfun\s+{m['method']}\s*\(", l) for l in lines(m["file"]))
        if not declared:
            return m["method"]
    return None


def mechanical_junk(m):
    """PIT mutants that are compiler or library code by construction, whatever a rater would say:
    a line past the end of the file (stdlib or other inline code, mapped through the SMAP), a removed
    null-check intrinsic, and coroutine state-machine plumbing (throwOnFailure, invokeSuspend's result)."""
    if m["line"] > len(lines(m["file"])):
        return "inlined code (line past end of file)"
    if "kotlin/jvm/internal/Intrinsics::" in m["desc"]:
        return "null-check intrinsic"
    if "ResultKt::throwOnFailure" in m["desc"] or (m["method"] == "invokeSuspend" and m["op"].endswith("ReturnValsMutator")):
        return "coroutine state machine"
    return None


def summarise(ms):
    c = Counter(m["status"] for m in ms)
    killed = sum(c[s] for s in KILLED)
    no_cov = c["NO_COVERAGE"]
    not_measured = c["NOT_MEASURED"]
    covered = len(ms) - no_cov - not_measured
    return {
        "mutants": len(ms),
        "killed": killed,
        "survived": c["SURVIVED"],
        "no_coverage": no_cov,
        "other": dict((k, v) for k, v in c.items() if k not in KILLED | {"SURVIVED", "NO_COVERAGE"}),
        "score_of_covered": round(100 * killed / covered, 1) if covered else None,
        "survivors_per_100_mutants": round(100 * c["SURVIVED"] / len(ms), 1) if ms else None,
    }


def main():
    krispr_path, module_dir, pit_path, dirs, out = sys.argv[1:6]
    source_dirs = dirs.split(",")
    k = krispr_mutants(krispr_path, module_dir)
    p = pit_mutants(pit_path, source_dirs)
    pit_nc = [m for m in p if m["status"] == "NO_COVERAGE"]
    gen = [m for m in p if generated_member(m)]
    summary = {
        "krispr": summarise(k),
        "pit": summarise(p),
        "pit_no_coverage_in_inline_functions": sum(in_inline_function(m["file"], m["line"]) for m in pit_nc),
        "krispr_in_inline_functions": summarise([m for m in k if in_inline_function(m["file"], m["line"])]),
        "pit_in_inline_functions": summarise([m for m in p if in_inline_function(m["file"], m["line"])]),
        "pit_mechanical_junk": Counter(mechanical_junk(m) for m in p if mechanical_junk(m)),
        "pit_mechanical_junk_survivors": Counter(mechanical_junk(m) for m in p if mechanical_junk(m) and m["status"] == "SURVIVED"),
        "pit_mechanical_junk_killed": sum(1 for m in p if mechanical_junk(m) and m["status"] in KILLED),
        "pit_generated_member_mutants": len(gen),
        "pit_generated_member_survivors": sum(m["status"] == "SURVIVED" for m in gen),
        "pit_generated_by_member": Counter(generated_member(m) for m in gen),
        "pit_by_mutator": Counter(m["op"] for m in p),
        "krispr_by_operator": Counter(m["op"] for m in k),
        "unresolved_sources": sorted({m["file"] for m in p + k if not os.path.exists(m["file"])}),
    }
    os.makedirs(out, exist_ok=True)
    json.dump(summary, open(os.path.join(out, "summary.json"), "w"), indent=2, default=dict)
    print(json.dumps(summary, indent=2, default=dict))


if __name__ == "__main__":
    main()
