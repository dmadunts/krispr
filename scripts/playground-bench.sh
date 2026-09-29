#!/usr/bin/env bash
# Whole-project Krispr benchmark on playground-android (see playground-android/README.md for its shapes).
#
# Runs, in one invocation and in this order:
#   1. a warm-up build (compiles everything, so neither timed step pays for compilation),
#   2. the unit-test baseline: testDebugUnitTest --rerun across every module,
#   3. krisprRun across every module (history off, the build-wide JVM cap at 4),
#   4. with --verify, krisprRun again with robolectricReuse=fresh, and a verdict diff against step 3,
# then prints one table: baseline and Krispr wall, their ratio, and per module the verdict counts, wall,
# start offset (when its krisprRun began, from the start of step 3), fresh JVMs against reused ones, and
# survivors re-checked in fresh JVMs and how many changed.
#
# Usage: scripts/playground-bench.sh [--verify] [--stress N] [--stress-delay SECS] [--no-baseline] [-- GRADLE_ARGS...]
#   --stress N          start N CPU burners (yes > /dev/null) while Krispr runs, to provoke #42's
#                       load-induced timeouts; they are killed by PID when the script exits
#   --stress-delay S    start the burners S seconds into the Krispr run (default 0), after the recordings
#                       have measured their times at normal load
#   --no-baseline       skip step 2 (the table then has no ratio)
#   GRADLE_ARGS         passed to every krisprRun, e.g. -- -Pkrispr.confirmSurvivors=false
# Results (logs and every module's report.json) go to playground-android/build/bench/<timestamp>/.
# Exits non-zero if the warm-up, the baseline or a krisprRun fails; verdict differences only print.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
project="$root/playground-android"
verify=false
stress=0
stress_delay=0
baseline=true
extra=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --verify) verify=true ;;
        --stress) stress="$2"; shift ;;
        --stress-delay) stress_delay="$2"; shift ;;
        --no-baseline) baseline=false ;;
        --) shift; extra=("$@"); break ;;
        -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
    esac
    shift
done

if [[ ! -f "$project/local.properties" && -f "$root/sample-android/local.properties" ]]; then
    cp "$root/sample-android/local.properties" "$project/local.properties"
fi

out="$project/build/bench/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
gradle=("$root/gradlew" -p "$project" --max-workers=4 --console=plain)
krispr_args=(-Pkrispr.maxConcurrentJvms=4 -Pkrispr.history=false --continue ${extra[@]+"${extra[@]}"})
modules=(catalog checkout settings session pricing mocks feed status units)

failed=0
launcher_pid=""
pidfile="$out/burners.pid"
cleanup() {
    if [[ -n "$launcher_pid" ]]; then kill "$launcher_pid" 2>/dev/null || true; fi
    if [[ -f "$pidfile" ]]; then
        while read -r pid; do kill "$pid" 2>/dev/null || true; done < "$pidfile"
        rm -f "$pidfile"
    fi
}
trap cleanup EXIT INT TERM

start_stress() {
    [[ "$stress" -gt 0 ]] || return 0
    : > "$pidfile"
    (
        sleep "$stress_delay"
        for _ in $(seq 1 "$stress"); do
            yes > /dev/null &
            echo $! >> "$pidfile"
        done
        wait
    ) &
    launcher_pid=$!
    echo "stress: $stress CPU burners from ${stress_delay}s into the Krispr run"
}

stop_stress() {
    cleanup
    launcher_pid=""
}

now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

# Keeps each module's report.json, and when it was written, before the next run overwrites it.
save_reports() {
    local dest="$1"
    mkdir -p "$dest"
    for m in "${modules[@]}"; do
        local report="$project/$m/build/krispr/report.json"
        if [[ -f "$report" ]]; then
            cp -p "$report" "$dest/$m.json"
            python3 -c 'import os,sys; print(int(os.stat(sys.argv[1]).st_mtime * 1000))' "$report" > "$dest/$m.mtime"
        fi
    done
}

# Deletes the previous run's reports so a module that fails this time is not read from the last one.
clear_reports() {
    for m in "${modules[@]}"; do rm -f "$project/$m/build/krispr/report.json"; done
}

run_krispr() {
    local label="$1"; shift
    clear_reports
    local start end status=0
    echo "krisprRun ($label) ..."
    start=$(now_ms)
    "${gradle[@]}" krisprRun "${krispr_args[@]}" "$@" > "$out/$label.log" 2>&1 || status=$?
    end=$(now_ms)
    echo "$start $end $status" > "$out/$label.time"
    save_reports "$out/$label"
    [[ $status -eq 0 ]] || { echo "krisprRun ($label) exited $status; see $out/$label.log" >&2; failed=1; }
}

echo "load average before: $(sysctl -n vm.loadavg 2>/dev/null || uptime)" | tee "$out/load.txt"
echo "warm-up build ..."
"${gradle[@]}" testDebugUnitTest --continue > "$out/warmup.log" 2>&1 || { echo "warm-up failed; see $out/warmup.log" >&2; exit 1; }

if $baseline; then
    echo "unit-test baseline ..."
    start=$(now_ms)
    "${gradle[@]}" testDebugUnitTest --rerun --continue > "$out/baseline.log" 2>&1 || { echo "baseline failed; see $out/baseline.log" >&2; exit 1; }
    end=$(now_ms)
    echo "$start $end 0" > "$out/baseline.time"
fi

start_stress
run_krispr sandbox
stop_stress
echo "load average after: $(sysctl -n vm.loadavg 2>/dev/null || uptime)" | tee -a "$out/load.txt"

if $verify; then
    run_krispr fresh -Pkrispr.robolectricReuse=fresh
fi

python3 - "$out" "$verify" "${modules[@]}" <<'PY'
import json, os, re, sys

out, verify, modules = sys.argv[1], sys.argv[2] == "true", sys.argv[3:]

def times(label):
    path = os.path.join(out, label + ".time")
    if not os.path.exists(path):
        return None
    start, end, status = map(int, open(path).read().split())
    return start, end, status

def task_lines(label):
    """Lifecycle lines per module's krisprRun, from the plain console's '> Task :m:krisprRun' groups."""
    lines, current = {}, None
    path = os.path.join(out, label + ".log")
    if not os.path.exists(path):
        return lines
    for line in open(path, errors="replace"):
        header = re.match(r"> Task :(\w+):(\w+)", line)
        if header:
            current = header.group(1) if header.group(2) == "krisprRun" else None
            continue
        if current and line.startswith("krispr: "):
            lines.setdefault(current, []).append(line.strip())
    return lines

def load(label, module):
    path = os.path.join(out, label, module + ".json")
    if not os.path.exists(path):
        return None, None
    return json.load(open(path)), int(open(os.path.join(out, label, module + ".mtime")).read())

def secs(ms):
    return "%.1f" % (ms / 1000.0)

base = times("baseline")
run = times("sandbox")
lines = task_lines("sandbox")
rows, totals = [], dict(m=0, k=0, s=0, nc=0, to=0, fresh=0, reused=0, started=0, chk=0, chg=0, unsafe=0)
for m in modules:
    report, mtime = load("sandbox", m)
    if report is None:
        rows.append([m, "no report"] + [""] * 10)
        continue
    s = report["summary"]
    runners = [x.get("runner") for x in report["mutants"]]
    fresh, reused = runners.count("fork"), runners.count("worker")
    started = checked = changed = unsafe = None
    for line in lines.get(m, []):
        g = re.search(r"mutants in reused JVMs \((\d+) started\)", line)
        if g: started = int(g.group(1))
        g = re.search(r"(\d+) survivors re-checked in fresh JVMs, (\d+) changed", line)
        if g: checked, changed = int(g.group(1)), int(g.group(2))
        g = re.search(r"(\d+) tests fail in a reused JVM", line)
        if g: unsafe = int(g.group(1))
    wall = s["wallMillis"]
    offset = mtime - wall - run[0]
    rows.append([
        m, s["total"], s["KILLED"], s["SURVIVED"], s["NO_COVERAGE"], s["TIMED_OUT"], secs(wall), secs(offset),
        fresh, "%d/%s" % (reused, "-" if started is None else started),
        "-" if checked is None else "%d/%d" % (checked, changed), "-" if unsafe is None else unsafe,
    ])
    totals["m"] += s["total"]; totals["k"] += s["KILLED"]; totals["s"] += s["SURVIVED"]
    totals["nc"] += s["NO_COVERAGE"]; totals["to"] += s["TIMED_OUT"]; totals["fresh"] += fresh; totals["reused"] += reused
    totals["started"] += started or 0; totals["chk"] += checked or 0; totals["chg"] += changed or 0; totals["unsafe"] += unsafe or 0

header = ["module", "mutants", "killed", "survived", "no-cov", "timed-out", "wall s", "start s",
          "fresh JVMs", "reused/started", "rechecked/changed", "unsafe tests"]
rows.append(["TOTAL", totals["m"], totals["k"], totals["s"], totals["nc"], totals["to"], "", "", totals["fresh"],
             "%d/%d" % (totals["reused"], totals["started"]), "%d/%d" % (totals["chk"], totals["chg"]), totals["unsafe"]])
widths = [max(len(str(r[i])) for r in [header] + rows) for i in range(len(header))]
fmt = lambda r: "  ".join(str(v).rjust(w) if i else str(v).ljust(w) for i, (v, w) in enumerate(zip(r, widths)))

print()
print("load average: " + " | ".join(l.strip() for l in open(os.path.join(out, "load.txt"))))
krispr_wall = run[1] - run[0]
if base:
    base_wall = base[1] - base[0]
    print("baseline wall %ss, Krispr wall %ss, ratio %.1fx" % (secs(base_wall), secs(krispr_wall), krispr_wall / base_wall))
else:
    print("Krispr wall %ss (no baseline)" % secs(krispr_wall))
if run[2]:
    print("krisprRun exited %d" % run[2])
print(fmt(header))
for r in rows:
    print(fmt(r))

if verify:
    fresh_run = times("fresh")
    print()
    print("verify: robolectricReuse=fresh wall %ss (exit %d)" % (secs(fresh_run[1] - fresh_run[0]), fresh_run[2]))
    diffs = 0
    for m in modules:
        a, _ = load("sandbox", m)
        b, _ = load("fresh", m)
        if a is None or b is None:
            print("  %s: missing report (%s)" % (m, "sandbox" if a is None else "fresh"))
            continue
        fa = {x["id"]: x for x in a["mutants"]}
        fb = {x["id"]: x for x in b["mutants"]}
        changed = [i for i in fa if i in fb and fa[i]["status"] != fb[i]["status"]]
        only = set(fa) ^ set(fb)
        diffs += len(changed) + len(only)
        if changed or only:
            print("  %s: %d verdicts differ, %d mutants in one run only" % (m, len(changed), len(only)))
            for i in changed[:5]:
                x = fa[i]
                print("    %s:%d %s: sandbox %s, fresh %s" % (x["file"].split("/")[-1], x["line"], x["operator"], x["status"], fb[i]["status"]))
    print("verify: %d verdicts differ between sandbox and fresh" % diffs)
print()
print("results: " + out)
PY
exit $failed
