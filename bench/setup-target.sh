#!/usr/bin/env bash
# Applies Krispr to a benchmark target through a composite build, the way docs/validation.md did.
# Usage: bench/setup-target.sh <target-name> <checkout-dir>. Idempotent on a clean checkout
# (run `git checkout -- .` first when re-applying).
set -euo pipefail
name="$1"; dir="$2"
krispr="$(cd "$(dirname "$0")/.." && pwd)"
cd "$dir"
# The Kotlin version to build with: BENCH_KOTLIN if set (the head-to-head runs used 2.4.20, as
# docs/validation.md did); otherwise the revision's own when Krispr supports it (2.1.20 and later), else
# 2.1.20, the oldest it supports. Bumping old revisions further breaks their build scripts.
own="$(grep -hoE '^kotlin = "[^"]+"' gradle/libs.versions.toml 2>/dev/null | cut -d'"' -f2 || true)"
if [ -n "${BENCH_KOTLIN:-}" ]; then kotlin="$BENCH_KOTLIN"
elif [ -n "$own" ] && [ "$(printf '%s\n2.1.20\n' "$own" | sort -V | head -1)" = "2.1.20" ]; then kotlin="$own"
else kotlin="2.1.20"; fi

add_composite() { # settings file
  local s="$1"
  if grep -q '^pluginManagement {' "$s"; then
    sed -i "0,/^pluginManagement {/s||pluginManagement {\n    includeBuild(\"$krispr\")|" "$s"
  else
    sed -i "1i pluginManagement { includeBuild(\"$krispr\") }" "$s"
  fi
  printf '\nincludeBuild("%s")\n' "$krispr" >> "$s"
}
add_plugin() { # build file; adds id("dev.krispr") as the first line of its plugins block
  sed -i '0,/^plugins {/s//plugins {\n    id("dev.krispr")/' "$1"
}

case "$name" in
  kotlin-result)
    sed -i "s/^kotlin = \".*\"/kotlin = \"$kotlin\"/" gradle/libs.versions.toml
    add_composite settings.gradle.kts
    add_plugin kotlin-result/build.gradle.kts ;;
  clikt)
    sed -i "s/^kotlin = \".*\"/kotlin = \"$kotlin\"/" gradle/libs.versions.toml
    add_composite settings.gradle.kts
    add_plugin clikt/build.gradle.kts
    # Tests moved to a separate :test module in 2024; before that they are clikt's own commonTest.
    if grep -q 'include("test")' settings.gradle.kts; then
      printf '\nkrispr { testProject.set(":test") }\n' >> clikt/build.gradle.kts
    fi ;;
  turbine)
    # Groovy DSL; already on Kotlin 2.4.20.
    sed -i "0,/^pluginManagement {/s||pluginManagement {\n  includeBuild('$krispr')|" settings.gradle
    grep -q "includeBuild('$krispr')" settings.gradle || sed -i "1i pluginManagement { includeBuild('$krispr') }" settings.gradle
    printf "\nincludeBuild('%s')\n" "$krispr" >> settings.gradle
    sed -i "0,/^plugins {/s//plugins {\n  id 'dev.krispr'/" build.gradle ;;
  kotlinpoet)
    sed -i "s/^kotlin = \".*\"/kotlin = \"$kotlin\"/" gradle/libs.versions.toml
    add_composite settings.gradle.kts
    add_plugin kotlinpoet/build.gradle.kts ;;
  *) echo "unknown target $name" >&2; exit 1 ;;
esac
echo "applied krispr to $name"
