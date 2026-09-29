#!/usr/bin/env bash
# Runs ./gradlew in the current directory, retrying only when the failure was a dependency download
# (Maven Central rate-limits shared CI egress with HTTP 429). Any other failure is returned as is.
# Usage: bench/gradle-retry.sh <log-file> <gradle args...>
set -uo pipefail
log="$1"; shift
for i in $(seq 1 40); do
  ./gradlew "$@" > "$log" 2>&1 && exit 0
  grep -qE 'status code 429|Could not GET|Could not HEAD|Read timed out|Connection reset|could not resolve plugin artifact|Could not resolve all' "$log" || exit 1
  echo "download failure, retry $i" >&2; sleep 20
done
exit 1
