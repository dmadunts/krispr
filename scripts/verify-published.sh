#!/usr/bin/env bash
# Proves the published artifacts work on their own: publishes every krispr artifact to Maven local,
# copies sample/ out of the repository, swaps its composite build for mavenLocal(), and runs krisprRun.
# A release is only as good as this: the composite build hides a missing artifact, a wrong POM or a
# KRISPR_VERSION that points at nothing.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
version="$(grep '^version=' "$root/gradle.properties" | cut -d= -f2)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

"$root/gradlew" -p "$root" publishToMavenLocal --console=plain -q

# The sample's own tests expect to run from a directory named "sample".
cp -r "$root/sample" "$work/sample"
cp -r "$root/gradle" "$root/gradlew" "$work/sample/"
cd "$work/sample"
rm -rf build .gradle
python3 - "$version" <<'PY'
import sys
version = sys.argv[1]
s = open('settings.gradle.kts').read()
s = s.replace('    // Resolves id("dev.krispr") from the local krispr-gradle project.\n    includeBuild("..")\n', '')
s = s.replace('// Substitutes dev.krispr:krispr-compiler-<variant> and dev.krispr:krispr-runtime with the local projects.\nincludeBuild("..")\n', '')
s = s.replace('    repositories {\n', '    repositories {\n        mavenLocal()\n')
assert 'includeBuild' not in s, 'sample/settings.gradle.kts changed; update this script'
open('settings.gradle.kts', 'w').write(s)
b = open('build.gradle.kts').read()
assert '    id("dev.krispr")\n' in b, 'sample/build.gradle.kts changed; update this script'
open('build.gradle.kts', 'w').write(b.replace('    id("dev.krispr")\n', f'    id("dev.krispr") version "{version}"\n'))
PY
./gradlew krisprRun --console=plain | grep '^krispr: [0-9]* mutants:'
test -s build/krispr/report.json
echo "published $version artifacts run the sample without the composite build"
