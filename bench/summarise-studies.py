#!/usr/bin/env python3
"""Aggregates bug-study.py and replay-diff.py results for docs/evidence.md.

Usage: summarise-studies.py bugs <results.json>...
       summarise-studies.py replay <results.json>...
"""
import json
import statistics
import sys


def bugs(paths):
    rows = [r for p in paths for r in json.load(open(p))]
    print("| Fix | Subject | Outcome | Chance at random |")
    print("|---|---|---|---|")
    for r in rows:
        p = r.get("p_random_hit")
        print(f"| {r['fix']} | {r['subject'][:70]} | {r['outcome']} | {'' if p is None else f'{100 * p:.0f}%'} |")
    ran = [r for r in rows if r["outcome"] in ("flagged", "not flagged")]
    hits = sum(r["outcome"] == "flagged" for r in ran)
    expected = sum(r["p_random_hit"] for r in ran)
    # Poisson-binomial tail: the chance of at least `hits` flagged bugs if Krispr's flags were placed at random.
    dist = [1.0]
    for r in ran:
        p = r["p_random_hit"]
        dist = [(dist[k] if k < len(dist) else 0) * (1 - p) + (dist[k - 1] * p if k > 0 else 0) for k in range(len(dist) + 1)]
    tail = sum(dist[hits:])
    outcomes = {o: sum(r["outcome"] == o for r in rows) for o in sorted({r["outcome"] for r in rows})}
    print(f"\n{len(rows)} fixes: {outcomes}")
    print(f"of {len(ran)} with a mutant on a fix line: {hits} flagged, {expected:.1f} expected at random, P(>= {hits} at random) = {tail:.4f}")


def replay(paths):
    rows = [r for p in paths for r in json.load(open(p))]
    ran = [r for r in rows if r["outcome"] == "ran"]
    failed = [r for r in rows if r["outcome"] != "ran"]
    s = lambda r, k: r["summary"].get(k, 0)
    with_mutants = [r for r in ran if s(r, "total") > 0]
    surv = [s(r, "SURVIVED") for r in with_mutants]
    secs = [r["seconds"] for r in ran]
    print(f"{len(rows)} commits: {len(ran)} ran, {len(failed)} failed ({[r['commit'] for r in failed]})")
    print(f"{len(with_mutants)} had mutants on changed lines; {len(ran) - len(with_mutants)} changed no mutable line")
    if with_mutants:
        print(f"mutants per commit: median {statistics.median([s(r, 'total') for r in with_mutants])}, max {max(s(r, 'total') for r in with_mutants)}")
        print(f"survivors per commit: median {statistics.median(surv)}, max {max(surv)}, commits with any: {sum(x > 0 for x in surv)}")
        print(f"no-coverage per commit: total {sum(s(r, 'NO_COVERAGE') for r in with_mutants)}")
    print(f"seconds end to end: median {statistics.median(secs)}, p90 {sorted(secs)[int(0.9 * (len(secs) - 1))]}, max {max(secs)}")
    print("\n| Commit | Subject | Mutants | Killed | Survived | No coverage | Seconds |")
    print("|---|---|---|---|---|---|---|")
    for r in rows:
        if r["outcome"] != "ran":
            print(f"| {r['commit']} | {r['subject'][:60]} | {r['outcome']} | | | | {r['seconds']} |")
        else:
            print(f"| {r['commit']} | {r['subject'][:60]} | {s(r, 'total')} | {s(r, 'killed')} | {s(r, 'SURVIVED')} | {s(r, 'NO_COVERAGE')} | {r['seconds']} |")


if __name__ == "__main__":
    {"bugs": bugs, "replay": replay}[sys.argv[1]](sys.argv[2:])
