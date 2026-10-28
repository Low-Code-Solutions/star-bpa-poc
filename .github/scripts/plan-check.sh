#!/bin/sh
# Plan check for pull requests (ADR 0015, BPA-38).
# Usage: sh .github/scripts/plan-check.sh <branch> [repo-root]
# - Branch BPA-<n> or BPA-<n>-...: fails when intent/*/plans/BPA-<n>.md is missing (or there is more than one);
#   warns, without failing, when the plan's Status is not Closed, because the plan is closed in the
#   final commit after review, just before merge.
# - Branch with no BPA-<n> key: passes with a notice.
# - Branches shared by several Tasks are named after the first key; only that key's plan is checked.
# Prints GitHub Actions annotations (::error::, ::warning::, ::notice::), which read as plain text locally.
set -u
branch=${1:-}
root=${2:-.}

key=$(printf '%s' "$branch" | sed -n 's/^\(BPA-[0-9][0-9]*\)\(-.*\)\{0,1\}$/\1/p')
if [ -z "$key" ]; then
  echo "::notice::plan-check: branch '$branch' has no BPA-<n> key, so no plan is required."
  exit 0
fi

found=""; count=0
for p in "$root"/intent/*/plans/"$key".md; do
  [ -f "$p" ] || continue
  found=$p; count=$((count + 1))
done

if [ "$count" -eq 0 ]; then
  echo "::error::plan-check: no plan for $key. Add intent/<EPIC-KEY>-<kebab>/plans/$key.md as the first commit on the branch (ADR 0015)."
  exit 1
fi
if [ "$count" -gt 1 ]; then
  echo "::error::plan-check: more than one plan for $key under intent/*/plans/. Keep one, in the item's epic folder (ADR 0015)."
  exit 1
fi

rel=${found#"$root"/}
line=$(awk 'NR <= 12 && /^Status:/ { print NR; exit }' "$found")
status=$(awk 'NR <= 12 && /^Status:/ { sub(/^Status:[ \t]*/, ""); print; exit }' "$found")

case $status in
  Closed*)
    echo "plan-check: $rel is $status."
    exit 0 ;;
  "")
    echo "::warning file=$rel::plan-check: $rel has no Status line in its header (docs/04). Set Status: Closed (<date>) in the final commit before merge."
    exit 0 ;;
  *)
    echo "::warning file=$rel,line=$line::plan-check: $key plan Status is '$status', not Closed. Set Status: Closed (<date>) in the final commit, after review fixes and before merge (ADR 0015)."
    exit 0 ;;
esac
