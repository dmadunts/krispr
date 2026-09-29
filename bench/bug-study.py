#!/usr/bin/env python3
"""Real-bug retrospective for docs/evidence.md: before each bug fix, did Krispr point at the lines the fix changed?

Usage: bug-study.py <target> <checkout> <module> <out dir> <fix commit>...

For each fix commit F:
  1. check out F^ (the buggy code) and apply Krispr (bench/setup-target.sh);
  2. run krisprRun with targetFiles = the module's main Kotlin files F changes;
  3. take the old-side lines F changes (a pure insertion counts the lines on either side of it);
  4. the bug is flagged when one of those lines has a SURVIVED or NO_COVERAGE mutant.

Pointing at *some* line of a file is easy when many lines are flagged, so each bug also gets the chance a
random draw of the same number of mutated lines would have hit a flagged one (hypergeometric), and the
summary compares observed hits to that expectation.
"""
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
FLAGGED = {"SURVIVED", "NO_COVERAGE"}


def sh(*args, cwd=None, check=True):
    return subprocess.run(args, cwd=cwd, check=check, capture_output=True, text=True).stdout


def changed_main_files(checkout, module, fix):
    files = sh("git", "diff", "--name-only", f"{fix}^", fix, cwd=checkout).split()
    return [f for f in files if f.startswith(f"{module}/src/") and f.endswith(".kt") and re.search(r"/src/\w*[Mm]ain/", f)]


def fix_lines(checkout, fix, path):
    """Old-side line numbers the fix touches in `path`."""
    diff = sh("git", "diff", "-U0", f"{fix}^", fix, "--", path, cwd=checkout)
    lines = set()
    for a, b in re.findall(r"^@@ -(\d+)(?:,(\d+))? \+", diff, re.M):
        a, b = int(a), int(b) if b != "" else 1
        lines.update(range(a, a + b) if b else (a, a + 1))
    return lines


def p_random_hit(mutated, flagged, k):
    """P(at least one flagged line) drawing k of the mutated lines at random."""
    if k == 0 or flagged == 0:
        return 0.0
    if mutated - flagged < k:
        return 1.0
    return 1 - math.comb(mutated - flagged, k) / math.comb(mutated, k)


def run(target, checkout, module, out, fix):
    dest = os.path.join(out, fix)
    os.makedirs(dest, exist_ok=True)
    files = changed_main_files(checkout, module, fix)
    subject = sh("git", "log", "-1", "--format=%s", fix, cwd=checkout).strip()
    result = {"fix": fix, "subject": subject, "files": files}
    if not files:
        return {**result, "outcome": "no main Kotlin file changed"}
    sh("git", "checkout", "-q", "-f", f"{fix}^", cwd=checkout)
    sh("git", "clean", "-fdq", "-e", ".gradle", cwd=checkout)
    sh(os.path.join(HERE, "setup-target.sh"), target, ".", cwd=checkout)
    rel = [f[len(module) + 1:] for f in files]
    start = time.time()
    ok = subprocess.run(
        [os.path.join(HERE, "gradle-retry.sh"), os.path.join(dest, "krispr.log"), f":{module}:krisprRun",
         "-Pkrispr.history=false", f"-Pkrispr.targetFiles={','.join(rel)}", "--console=plain"],
        cwd=checkout,
    ).returncode == 0
    # Each revision may bring its own Gradle and Kotlin versions, and idle daemons of each add up past the
    # container's memory.
    subprocess.run(["./gradlew", "--stop"], cwd=checkout, capture_output=True)
    subprocess.run(["pkill", "-f", "KotlinCompileDaemon"], capture_output=True)
    result["seconds"] = round(time.time() - start)
    report_path = os.path.join(checkout, module, "build/krispr/report.json")
    if not ok or not os.path.exists(report_path):
        return {**result, "outcome": "build or run failed"}
    shutil.copy(report_path, os.path.join(dest, "report.json"))
    mutants = json.load(open(report_path))["mutants"]
    per_file = []
    for f, r in zip(files, rel):
        touched = fix_lines(checkout, fix, f)
        ms = [m for m in mutants if m["file"] == r]
        mutated = {m["line"] for m in ms}
        flagged = {m["line"] for m in ms if m["status"] in FLAGGED}
        per_file.append({
            "file": f,
            "fix_lines": sorted(touched),
            "fix_lines_mutated": sorted(touched & mutated),
            "fix_lines_flagged": sorted(touched & flagged),
            "fix_line_statuses": sorted({m["status"] for m in ms if m["line"] in touched}),
            "mutated_lines": len(mutated),
            "flagged_lines": len(flagged),
            "p_random": p_random_hit(len(mutated), len(flagged), len(touched & mutated)),
        })
    mutated_any = any(p["fix_lines_mutated"] for p in per_file)
    hit = any(p["fix_lines_flagged"] for p in per_file)
    # One draw per bug: the chance any of its files would be hit at random.
    p_miss = math.prod(1 - p["p_random"] for p in per_file)
    return {
        **result,
        "outcome": "flagged" if hit else ("not flagged" if mutated_any else "no mutant on the fix lines"),
        "p_random_hit": round(1 - p_miss, 3),
        "files_detail": per_file,
    }


def main():
    target, checkout, module, out, *fixes = sys.argv[1:]
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, "results.json")
    # Resumable: keep finished fixes, redo the ones whose build or run failed.
    done = {r["fix"]: r for r in (json.load(open(path)) if os.path.exists(path) else []) if r["outcome"] != "build or run failed"}
    results = []
    for fix in fixes:
        if fix in done:
            results.append(done[fix])
            continue
        r = run(target, checkout, module, out, fix)
        print(json.dumps({k: r[k] for k in ("fix", "subject", "outcome") if k in r} | {"p": r.get("p_random_hit")}), flush=True)
        results.append(r)
        json.dump(results + [done[f] for f in done if f not in {x["fix"] for x in results}], open(path, "w"), indent=1)
    sh("git", "checkout", "-q", "-f", "-", cwd=checkout, check=False)


if __name__ == "__main__":
    main()
