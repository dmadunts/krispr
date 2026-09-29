#!/usr/bin/env bash
# Runs PIT on a target module with the same tests Krispr uses, for the head-to-head in docs/evidence.md.
# Usage: bench/run-pit.sh <checkout> <gradle project path> <test task> <main classes dir> <source dir,...> \
#                         <target class glob> <out dir> [threads]
# PIT's own defaults throughout (DEFAULTS mutators, timeoutFactor 1.25, timeoutConst 4000), as a
# user would get them; only threads is pinned so both tools get the same parallelism.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
checkout="$1"; project="$2"; testTask="$3"; mainClasses="$4"; sourceDirs="$5"; targetClasses="$6"; out="$7"
threads="${8:-2}"
# PIT_HOME holds pitest, pitest-entry and pitest-command-line 1.30.0, pitest-junit5-plugin 1.2.3,
# commons-text 1.14.0 and commons-lang3 3.18.0, straight from Maven Central.
pit="${PIT_HOME:-$HOME/pit}"
mkdir -p "$out"
( cd "$checkout" && "$here/gradle-retry.sh" "$out/classpath-build.log" --init-script "$here/pit-classpath.init.gradle.kts" \
    "-Pbench.testTask=$testTask" "-Pbench.out=$out" "${project%:}:benchClasspath" --no-configuration-cache )
# pitest-entry needs commons-text (and its commons-lang3) for the XML report; the rest is shaded in.
pitJars="$(ls "$pit"/pitest-[0-9]*.jar "$pit"/pitest-entry-*.jar "$pit"/pitest-command-line-*.jar "$pit"/commons-*.jar | paste -sd: -)"
# The JUnit 5 plugin only loads when the tests' classpath has the JUnit Platform; JUnit 4 is built in.
if grep -q junit-platform "$out/classpath.txt"; then pitJars="$pitJars:$(ls "$pit"/pitest-junit5-plugin-*.jar)"; fi
# When tests reach the module under test through its jar (a separate test project), CLASSPATH_SUBST=jar=dir
# swaps in the class directory PIT mutates.
if [ -n "${CLASSPATH_SUBST:-}" ]; then sed -i "s|^${CLASSPATH_SUBST%%=*}\$|${CLASSPATH_SUBST#*=}|" "$out/classpath.txt"; fi
cp="$pitJars:$(paste -sd: "$out/classpath.txt")"
start=$(date +%s)
mkdir -p "$out/tmp"
java -Djava.io.tmpdir="$out/tmp" -cp "$cp" org.pitest.mutationtest.commandline.MutationCoverageReport \
  --reportDir "$out/pit" --outputFormats XML,CSV --timestampedReports=false \
  --targetClasses "$targetClasses" --targetTests "$targetClasses" \
  --mutableCodePaths "$mainClasses" --sourceDirs "$sourceDirs" \
  --threads "$threads" > "$out/pit.log" 2>&1
end=$(date +%s)
echo "pit_wall_seconds=$((end - start))" | tee "$out/pit-wall.txt"
