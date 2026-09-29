#!/usr/bin/env bash
# Full tier, pre-merge: every compiler variant (k2120, k220, k230, k2320, k240), runtime and Gradle-plugin
# unit tests, krispr-gradle's functionalTest (Gradle TestKit builds of sample Android/KMP projects), and
# scripts/verify-clean-build.sh. ~1 min warm, ~8-12 min cold (docs/dev-loop.md has the measured breakdown). Run
# scripts/check-fast.sh for everyday iteration instead.
#
# --playground (opt-in) also runs scripts/playground-bench.sh --verify afterwards: a whole-project krisprRun
# on playground-android and its verdict diff against fresh JVMs (docs/perf.md, "Playground baseline"). It
# adds 10+ minutes, so it is not part of the default path.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
playground=false
for arg in "$@"; do
    case "$arg" in
        --playground) playground=true ;;
        *) echo "unknown option $arg" >&2; exit 2 ;;
    esac
done
"$root/gradlew" --max-workers=4 check
"$root/scripts/verify-clean-build.sh"
if $playground; then
    "$root/scripts/playground-bench.sh" --verify
fi
