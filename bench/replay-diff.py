#!/usr/bin/env python3
"""Diff-mode replay for docs/evidence.md: what diff mode would have reported on real merged changes.

Usage: replay-diff.py <target> <checkout> <module> <out dir> <commit>...

For each commit C (a merged PR, squashed): check out C, apply Krispr, and run
`krisprRun -Pkrispr.diffBase=C^`, which mutates only the lines C changed, with C's own tests. Records the
end-to-end time (instrumented build included), the counts and the survivors diff mode would post.
"""
import json
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def sh(*args, cwd=None):
    return subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True).stdout


def run(target, checkout, module, out, commit):
    dest = os.path.join(out, commit)
    os.makedirs(dest, exist_ok=True)
    sh("git", "checkout", "-q", "-f", commit, cwd=checkout)
    sh("git", "clean", "-fdq", "-e", ".gradle", cwd=checkout)
    sh(os.path.join(HERE, "setup-target.sh"), target, ".", cwd=checkout)
    subject = sh("git", "log", "-1", "--format=%s", commit, cwd=checkout).strip()
    start = time.time()
    ok = subprocess.run(
        [os.path.join(HERE, "gradle-retry.sh"), os.path.join(dest, "krispr.log"), f":{module}:krisprRun",
         f"-Pkrispr.diffBase={commit}^", "-Pkrispr.history=false", "--console=plain"],
        cwd=checkout,
    ).returncode == 0
    seconds = round(time.time() - start)
    report = os.path.join(checkout, module, "build/krispr/report.json")
    if not ok or not os.path.exists(report):
        return {"commit": commit, "subject": subject, "outcome": "build or run failed", "seconds": seconds}
    shutil.copy(report, os.path.join(dest, "report.json"))
    diff_md = os.path.join(checkout, module, "build/krispr/diff.md")
    if os.path.exists(diff_md):
        shutil.copy(diff_md, os.path.join(dest, "diff.md"))
    summary = json.load(open(report))["summary"]
    return {"commit": commit, "subject": subject, "outcome": "ran", "seconds": seconds, "summary": summary}


def main():
    target, checkout, module, out, *commits = sys.argv[1:]
    os.makedirs(out, exist_ok=True)
    results = []
    for c in commits:
        r = run(target, checkout, module, out, c)
        s = r.get("summary", {})
        print(json.dumps({"commit": c, "outcome": r["outcome"], "seconds": r["seconds"], "mutants": s.get("total"),
                          "survived": s.get("SURVIVED")}), flush=True)
        results.append(r)
        json.dump(results, open(os.path.join(out, "results.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
