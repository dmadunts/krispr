#!/usr/bin/env bash
# Proves that applying dev.krispr leaves a normal build untouched: builds the JVM sample's jar, the
# Android sample's library AAR and debug APK, and the KMP sample's JVM jar, with the plugin applied and from
# copies without it, then
# checks every class/dex entry is byte-identical and that nothing references the krispr runtime.
#
# KRISPR_AGP=8.13.2 scripts/verify-clean-build.sh builds the Android sample against that AGP (default 9.4.1),
# through scripts/agp-gradlew.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

hashes() { # jar -> "sha  entry" lines, sorted
    local dir="$work/unpacked-$(basename "$1")-$RANDOM"
    mkdir -p "$dir" && unzip -q -o "$1" -d "$dir"
    (cd "$dir" && find . -type f ! -name MANIFEST.MF -print0 | sort -z | xargs -0 shasum -a 256)
}

echo "Building sample jar with dev.krispr applied"
(cd "$root/sample" && "$root/gradlew" -q --max-workers=4 clean jar)
with="$(ls "$root"/sample/build/libs/*.jar)"

echo "Building sample jar without dev.krispr"
mkdir -p "$work/plain"
cp -R "$root/sample/src" "$root/sample/build.gradle.kts" "$work/plain/"
grep -v 'id("dev.krispr")' "$root/sample/build.gradle.kts" > "$work/plain/build.gradle.kts"
cat > "$work/plain/settings.gradle.kts" <<SETTINGS
rootProject.name = "krispr-sample"
dependencyResolutionManagement { repositories { mavenCentral() } }
SETTINGS
(cd "$work/plain" && "$root/gradlew" -q --max-workers=4 jar)
without="$(ls "$work"/plain/build/libs/*.jar)"

if ! diff <(hashes "$with") <(hashes "$without"); then
    echo "FAIL: the sample jar differs when dev.krispr is applied" >&2
    exit 1
fi
if unzip -p "$with" | grep -a -q 'dev/krispr/runtime'; then
    echo "FAIL: the sample jar references dev/krispr/runtime" >&2
    exit 1
fi
echo "OK: $(unzip -Z1 "$with" | grep -c '\.class$') JVM classes byte-identical with and without dev.krispr; no krispr references"

# Android: the library's AAR (its classes.jar) and the app's debug APK (its dex files).
android="$root/sample-android"
android_gradle() {
    if [[ -n "${KRISPR_AGP:-}" ]]; then "$root/scripts/agp-gradlew" "$KRISPR_AGP" "$@"; else "$root/gradlew" "$@"; fi
}
echo "Building sample-android AAR and APK with dev.krispr applied (AGP ${KRISPR_AGP:-default})"
(cd "$android" && android_gradle -q --max-workers=4 clean :lib:assembleDebug :app:assembleDebug)

echo "Building sample-android AAR and APK without dev.krispr"
mkdir -p "$work/android"
rsync -a --exclude build --exclude .gradle --exclude .kotlin "$android/" "$work/android/"
for f in "$work"/android/{lib,app}/build.gradle.kts; do grep -v 'id("dev.krispr")' "$f" > "$f.tmp" && mv "$f.tmp" "$f"; done
grep -v 'includeBuild("..")' "$android/settings.gradle.kts" > "$work/android/settings.gradle.kts"
(cd "$work/android" && android_gradle -q --max-workers=4 :lib:assembleDebug :app:assembleDebug)

android_hashes() { # dir -> hashes of the AAR's classes.jar entries and the APK's dex files
    local aar="$1/lib/build/outputs/aar/lib-debug.aar" apk="$1/app/build/outputs/apk/debug/app-debug.apk"
    [[ -f "$aar" && -f "$apk" ]] || { echo "missing $aar or $apk" >&2; return 1; }
    local dir="$work/aar-$RANDOM"
    mkdir -p "$dir" && unzip -q -o "$aar" classes.jar -d "$dir"
    hashes "$dir/classes.jar"
    dir="$work/apk-$RANDOM"
    mkdir -p "$dir" && unzip -q -o "$apk" 'classes*.dex' -d "$dir"
    (cd "$dir" && shasum -a 256 classes*.dex)
}
android_hashes "$android" > "$work/with.txt"
android_hashes "$work/android" > "$work/without.txt"
if ! diff "$work/with.txt" "$work/without.txt"; then
    echo "FAIL: the sample-android AAR or APK differs when dev.krispr is applied" >&2
    exit 1
fi
if unzip -p "$android/app/build/outputs/apk/debug/app-debug.apk" 'classes*.dex' | grep -a -q 'dev/krispr/runtime'; then
    echo "FAIL: the sample-android APK references dev/krispr/runtime" >&2
    exit 1
fi
echo "OK: sample-android (AGP ${KRISPR_AGP:-default}) $(grep -c '\.class$' "$work/with.txt") AAR classes and $(grep -c '\.dex$' "$work/with.txt") APK dex files byte-identical with and without dev.krispr; no krispr references"

# Kotlin Multiplatform: the JVM target's jar (commonMain + jvmMain); the JS and native targets are never instrumented.
kmp="$root/sample-kmp"
echo "Building sample-kmp JVM jar with dev.krispr applied"
(cd "$kmp" && "$root/gradlew" -q --max-workers=4 clean jvmJar)

echo "Building sample-kmp JVM jar without dev.krispr"
mkdir -p "$work/kmp"
rsync -a --exclude build --exclude .gradle --exclude .kotlin "$kmp/" "$work/kmp/"
# Drops the plugin id and the top-level krispr { } block.
awk '/id\("dev.krispr"\)/ { next } /^krispr \{/ { skip = 1 } !skip { print } skip && /^\}/ { skip = 0 }' "$kmp/build.gradle.kts" > "$work/kmp/build.gradle.kts"
grep -v 'includeBuild("..")' "$kmp/settings.gradle.kts" > "$work/kmp/settings.gradle.kts"
(cd "$work/kmp" && "$root/gradlew" -q --max-workers=4 jvmJar)

with="$(ls "$kmp"/build/libs/*-jvm.jar)"
without="$(ls "$work"/kmp/build/libs/*-jvm.jar)"
if ! diff <(hashes "$with") <(hashes "$without"); then
    echo "FAIL: the sample-kmp JVM jar differs when dev.krispr is applied" >&2
    exit 1
fi
if unzip -p "$with" | grep -a -q 'dev/krispr/runtime'; then
    echo "FAIL: the sample-kmp JVM jar references dev/krispr/runtime" >&2
    exit 1
fi
echo "OK: sample-kmp $(unzip -Z1 "$with" | grep -c '\.class$') JVM classes byte-identical with and without dev.krispr; no krispr references"
