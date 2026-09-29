#!/usr/bin/env python3
"""Unblinds the survivor ratings for docs/evidence.md.

Usage: score-ratings.py <rating dir> <key.json> [adjudication.jsonl]

Reads items.jsonl and ratings-{A,B}-chunk*.jsonl from the rating dir. Prints each rater's classes per
tool and target, their agreement (raw and Cohen's kappa), and the consensus classes: the class both
raters gave, or where they differ, the adjudicator's (a third, independent rating of just those items).
"""
import glob
import json
import os
import sys
from collections import Counter, defaultdict


def load(pattern):
    out = {}
    for path in sorted(glob.glob(pattern)):
        for line in open(path):
            if line.strip():
                r = json.loads(line)
                out[r["id"]] = r["class"].strip().lower()
    return out


def kappa(pairs):
    n = len(pairs)
    po = sum(a == b for a, b in pairs) / n
    ca, cb = Counter(a for a, _ in pairs), Counter(b for _, b in pairs)
    pe = sum(ca[k] * cb[k] for k in set(ca) | set(cb)) / (n * n)
    return (po - pe) / (1 - pe) if pe < 1 else 1.0


def table(label, ids, cls, items, key):
    by = defaultdict(Counter)
    for i in ids:
        by[(key[i]["tool"], items[i]["target"])][cls[i]] += 1
        by[(key[i]["tool"], "all")][cls[i]] += 1
    print(f"\n{label}")
    print("tool    target         n    a real gap   b equivalent   c junk")
    for (tool, target), c in sorted(by.items()):
        n = sum(c.values())
        print(f"{tool:7} {target:13} {n:3}   " + "   ".join(f"{c[k]:3} ({100 * c[k] / n:3.0f}%)" for k in "abc"))
    return by


def main():
    d, key_path = sys.argv[1], sys.argv[2]
    adj = load(sys.argv[3]) if len(sys.argv) > 3 else {}
    items = {json.loads(l)["id"]: json.loads(l) for l in open(os.path.join(d, "items.jsonl"))}
    key = json.load(open(key_path))
    a, b = load(os.path.join(d, "ratings-A-*.jsonl")), load(os.path.join(d, "ratings-B-*.jsonl"))
    ids = sorted(items)
    missing = [i for i in ids if i not in a or i not in b]
    if missing:
        sys.exit(f"unrated items: {missing}")
    pairs = [(a[i], b[i]) for i in ids]
    agree = sum(x == y for x, y in pairs)
    print(f"agreement {agree}/{len(ids)} ({100 * agree / len(ids):.0f}%), Cohen's kappa {kappa(pairs):.2f}")
    for tool in ("krispr", "pit"):
        tp = [(a[i], b[i]) for i in ids if key[i]["tool"] == tool]
        print(f"  {tool}: {sum(x == y for x, y in tp)}/{len(tp)} agree, kappa {kappa(tp):.2f}")
    table("rater A", ids, a, items, key)
    table("rater B", ids, b, items, key)
    disagreed = [i for i in ids if a[i] != b[i]]
    consensus = {i: (a[i] if a[i] == b[i] else adj.get(i)) for i in ids}
    open(os.path.join(d, "disagreements.txt"), "w").write("\n".join(disagreed) + "\n")
    if all(consensus.values()):
        by = table("consensus (agreed, else adjudicated)", ids, consensus, items, key)
        json.dump({f"{t}|{g}": dict(c) for (t, g), c in by.items()}, open(os.path.join(d, "consensus.json"), "w"), indent=1)
    else:
        print(f"\n{len(disagreed)} disagreements need adjudication; ids in disagreements.txt")


if __name__ == "__main__":
    main()
