#!/usr/bin/env python3
"""Draws survivors from both tools into one shuffled, tool-blind list for rating (docs/evidence.md).

Usage: sample.py <out dir> <per-tool sample size> <target spec>...
  target spec: name|checkout|krispr report.json|krispr module dir|PIT mutations.xml|source dir,...

Writes <out>/items.jsonl (what raters see: an id, the target, file:line, the source around it and the
mutation as the tool described it) and <out>/key.json (id -> tool, kept away from raters). Tool names
and operator ids are left out; the description style can still give a tool away, which evidence.md
says. The seed is fixed so the draw can be repeated.
"""
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
from compare import krispr_mutants, lines, pit_mutants  # noqa: E402

SEED = 20260929


def context(path, line, around=4):
    src = lines(path)
    lo, hi = max(1, line - around), min(len(src), line + around)
    return "\n".join(f"{'>' if n == line else ' '}{n:5} {src[n - 1]}" for n in range(lo, hi + 1))


def main():
    out, size, specs = sys.argv[1], int(sys.argv[2]), sys.argv[3:]
    rng = random.Random(SEED)
    items, key = [], {}
    for spec in specs:
        name, checkout, k_report, k_module, p_xml, dirs = spec.split("|")
        pools = {
            "krispr": [m for m in krispr_mutants(k_report, k_module) if m["status"] == "SURVIVED"],
            "pit": [m for m in pit_mutants(p_xml, dirs.split(",")) if m["status"] == "SURVIVED"],
        }
        for tool, pool in pools.items():
            pool = sorted(pool, key=lambda m: (m["file"], m["line"], m["desc"]))
            for m in rng.sample(pool, min(size, len(pool))):
                items.append({
                    "target": name,
                    "checkout": checkout,
                    "file": os.path.relpath(m["file"], checkout),
                    "line": m["line"],
                    "mutation": m["desc"],
                    "source": context(m["file"], m["line"]),
                })
                key[len(items) - 1] = {"tool": tool, "total_survivors": len(pool)}
    order = list(range(len(items)))
    rng.shuffle(order)
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "items.jsonl"), "w") as f:
        for blind, i in enumerate(order):
            f.write(json.dumps({"id": f"S{blind:03}", **items[i]}) + "\n")
    json.dump({f"S{blind:03}": key[i] for blind, i in enumerate(order)}, open(os.path.join(out, "key.json"), "w"), indent=1)
    print(f"{len(items)} items")


if __name__ == "__main__":
    main()
