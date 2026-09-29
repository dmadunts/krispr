#!/usr/bin/env bash
# Fast tier: the compiler variant matching the root build's Kotlin version (kotlin = "2.4.20" in
# gradle/libs.versions.toml -> krispr-compiler-k240's newest `test` task), plus the runtime and
# Gradle-plugin unit tests. No other compiler variant, no functional tests (Gradle TestKit), no
# scripts/verify-clean-build.sh. For everyday operator/runtime changes: ~20s warm. Run
# scripts/check-full.sh before merging.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
"$root/gradlew" --max-workers=4 :krispr-compiler-k240:test :krispr-runtime:test :krispr-gradle:test
