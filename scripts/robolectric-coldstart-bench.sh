#!/usr/bin/env bash
# Times cold Robolectric test JVMs under different JVM flags, interleaving the configurations each round so
# that load from other jobs hits them all alike. Prints per-config medians (wall, process CPU, Robolectric's
# "initialization" and "loadNativeRuntime" perf stats). See docs/robolectric-spike.md.
#
#   scripts/robolectric-coldstart-bench.sh <classpath-file> <test-class> <rounds> <config-file>
#
# <classpath-file>: the test task's runtime classpath, ':'-separated (docs/robolectric-spike.md shows a Gradle init
# script that writes it). Run from the module directory (Robolectric resolves paths against the working dir).
# <config-file>: one config per line, "label|jvm args"; the first line is the reference.
set -euo pipefail
cp_file=$1; test_class=$2; rounds=$3; configs=$4
cp=$(cat "$cp_file")
work=$(mktemp -d "${TMPDIR:-/tmp}/robo-bench.XXXXXX")
mkdir -p "$work/src/rb" "$work/out/META-INF/services"
cat > "$work/src/rb/Bench.java" <<'EOF'
package rb;
import java.lang.management.ManagementFactory;
import org.junit.runner.JUnitCore;
public class Bench {
  public static void main(String[] a) throws Exception {
    boolean ok = new JUnitCore().run(Class.forName(a[0])).wasSuccessful();
    long cpu = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    System.err.println("BENCH ok=" + ok + " cpuMs=" + cpu / 1_000_000);
    System.exit(ok ? 0 : 1);
  }
}
EOF
cat > "$work/src/rb/Reporter.java" <<'EOF'
package rb;
import java.util.Collection;
import org.robolectric.pluginapi.perf.*;
public class Reporter implements PerfStatsReporter {
  public void report(Metadata md, Collection<Metric> ms) {
    for (Metric m : ms) System.err.println("PERF " + m.getName().replace(' ', '_') + " " + m.getElapsedNs() / 1_000_000);
  }
}
EOF
echo rb.Reporter > "$work/out/META-INF/services/org.robolectric.pluginapi.perf.PerfStatsReporter"
javac -nowarn -d "$work/out" -cp "$cp" "$work"/src/rb/*.java
echo "load before: $(uptime | sed 's/.*load/load/')"
for r in $(seq 1 "$rounds"); do
  while IFS='|' read -r label args; do
    [ -z "$label" ] && continue
    mkdir -p "$work/runs/$label"
    t0=$(perl -MTime::HiRes=time -e 'printf "%d", time*1000')
    # shellcheck disable=SC2086
    java $args -cp "$work/out:$cp" rb.Bench "$test_class" > "$work/runs/$label/$r.log" 2>&1 < /dev/null || true
    t1=$(perl -MTime::HiRes=time -e 'printf "%d", time*1000')
    echo "WALL $((t1 - t0))" >> "$work/runs/$label/$r.log"
  done < "$configs"
done
echo "load after: $(uptime | sed 's/.*load/load/')"
python3 - "$work/runs" "$configs" <<'EOF'
import os, re, statistics, sys
runs, configs = sys.argv[1], sys.argv[2]
def med(logs, pattern, first=True):
    vals = [int(m[0 if first else -1]) for s in logs for m in [re.findall(pattern, s)] if m]
    return int(statistics.median(vals)) if vals else 0
ref = None
for label in [l.split('|')[0] for l in open(configs) if l.strip()]:
    d = os.path.join(runs, label)
    logs = [open(os.path.join(d, f)).read() for f in os.listdir(d)]
    wall = med(logs, r'WALL (\d+)')
    ref = wall if ref is None else ref
    fails = sum('ok=true' not in s for s in logs)
    print(f"{label:24s} wall={wall:5d} ({wall - ref:+5d}) cpu={med(logs, r'cpuMs=(\d+)'):5d} "
          f"init={med(logs, r'PERF initialization (\d+)')} native={med(logs, r'PERF loadNativeRuntime (\d+)')} "
          f"failed={fails}/{len(logs)}")
EOF
