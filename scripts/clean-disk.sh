#!/usr/bin/env bash
# Reclaim disk from finished krispr worktrees. Dry run by default; pass --apply to delete.
#
# A worktree is removed only if ALL hold:
#   - it is under .claude/worktrees/ and not in KEEP (below or $KRISPR_KEEP_WORKTREES);
#   - its HEAD is already on origin/main (so no commit can be lost);
#   - `git status` shows no tracked changes and no untracked, non-ignored files;
#   - no file in it changed in the last $RECENT_MIN minutes, and no process has its cwd inside it
#     (a freshly created worktree for a running worker sits on origin/main too).
# Its local branch is then deleted with `git branch -d` (refuses unless merged).
# Anything skipped is listed with the reason. Unmerged branches are never touched. A merged, idle
# worktree kept for untracked files still has its pure cache dirs (CACHE_DIRS) cleared.
#
# --gradle-caches also removes old Gradle distribution caches (~/.gradle/caches/<version>) listed in
# OLD_GRADLE_CACHES, but only when no Gradle daemon is running.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && git rev-parse --path-format=absolute --git-common-dir)"
REPO="$(dirname "$REPO")"
KEEP=(merge-d3 ${KRISPR_KEEP_WORKTREES:-})
RECENT_MIN="${RECENT_MIN:-120}"
OLD_GRADLE_CACHES=(8.14.3 9.7.1)
# Pure caches, cleared even from a merged, idle worktree that is kept for its other untracked files.
CACHE_DIRS=(.bt-cache)

apply=0; gradle_caches=0
for a in "$@"; do
  case "$a" in
    --apply) apply=1 ;;
    --gradle-caches) gradle_caches=1 ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) echo "unknown option: $a" >&2; exit 2 ;;
  esac
done

git -C "$REPO" fetch -q origin
freed_kb=0

skip() { printf '  skip  %-18s %s\n' "$1" "$2"; }

while read -r wt; do
  case "$wt" in "$REPO"/.claude/worktrees/*) ;; *) continue ;; esac
  name="${wt##*/}"
  [ -d "$wt" ] || { skip "$name" "missing dir (run: git worktree prune)"; continue; }
  if printf '%s\n' "${KEEP[@]}" | grep -qx "$name"; then skip "$name" "in keep list"; continue; fi
  head="$(git -C "$wt" rev-parse HEAD)"
  if ! git -C "$REPO" merge-base --is-ancestor "$head" origin/main; then
    skip "$name" "has commits not on origin/main"; continue
  fi
  if [ -n "$(find "$wt" -newermt "-${RECENT_MIN} minutes" -print -quit 2>/dev/null)" ]; then
    skip "$name" "files changed in the last ${RECENT_MIN} min (worker may be using it)"; continue
  fi
  if lsof -d cwd -Fn 2>/dev/null | grep -q "^n$wt\(/\|$\)"; then
    skip "$name" "a process is running inside it"; continue
  fi
  if [ -n "$(git -C "$wt" status --porcelain 2>/dev/null)" ]; then
    skip "$name" "uncommitted or untracked files (kept)"
    for c in "${CACHE_DIRS[@]}"; do
      [ -d "$wt/$c" ] || continue
      kb="$(du -sk "$wt/$c" | cut -f1)"; freed_kb=$((freed_kb + kb))
      printf '  %s %-18s %6s MB  (cache dir %s only)\n' "$([ $apply = 1 ] && echo 'rm  ' || echo 'would')" \
        "$name" "$((kb / 1024))" "$c"
      [ $apply = 1 ] && rm -rf "${wt:?}/$c"
    done
    continue
  fi
  kb="$(du -sk "$wt" | cut -f1)"
  branch="$(git -C "$wt" symbolic-ref -q --short HEAD || true)"
  printf '  %s %-18s %6s MB%s\n' "$([ $apply = 1 ] && echo 'rm  ' || echo 'would')" "$name" \
    "$((kb / 1024))" "${branch:+  (branch $branch)}"
  freed_kb=$((freed_kb + kb))
  if [ $apply = 1 ]; then
    git -C "$REPO" worktree remove --force "$wt"
    [ -n "$branch" ] && git -C "$REPO" branch -d "$branch" >/dev/null
  fi
done < <(git -C "$REPO" worktree list --porcelain | sed -n 's/^worktree //p')

[ $apply = 1 ] && git -C "$REPO" worktree prune

if [ $gradle_caches = 1 ]; then
  if pgrep -f GradleDaemon >/dev/null; then
    echo "  skip  gradle caches      a Gradle daemon is running (stop your builds first)"
  else
    for v in "${OLD_GRADLE_CACHES[@]}"; do
      d="$HOME/.gradle/caches/$v"; [ -d "$d" ] || continue
      kb="$(du -sk "$d" | cut -f1)"; freed_kb=$((freed_kb + kb))
      printf '  %s %-18s %6s MB\n' "$([ $apply = 1 ] && echo 'rm  ' || echo 'would')" "gradle $v" "$((kb / 1024))"
      [ $apply = 1 ] && rm -rf "$d"
    done
  fi
fi

echo "$([ $apply = 1 ] && echo Freed || echo 'Would free') ~$((freed_kb / 1024 / 1024)) GB.$([ $apply = 1 ] || echo ' Re-run with --apply to delete.')"
