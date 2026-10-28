#!/bin/sh
# PostToolUse hook (Bash). ADR 0015, BPA-32.
# After a `git commit` on a branch named BPA-<n>-..., warn when the commit changed build files
# (backend/, frontend/, models/, api/, templates/, infra/) but not the item's plan, unless the
# commit message contains "plan unaffected". Warns only: the commit has already happened.
# Exit 2 after a tool call shows stderr to Claude without undoing anything.

input=$(cat)
printf '%s' "$input" | grep -q 'git[^"]*commit' || exit 0

root=${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}
[ -n "$root" ] || exit 0

branch=$(git -C "$root" branch --show-current 2>/dev/null)
key=$(printf '%s' "$branch" | sed -n 's/^\(BPA-[0-9][0-9]*\)-.*/\1/p')
[ -n "$key" ] || exit 0

git -C "$root" rev-parse -q --verify HEAD >/dev/null || exit 0
files=$(git -C "$root" show --name-only --pretty=format: HEAD)
msg=$(git -C "$root" log -1 --pretty=%B)

printf '%s\n' "$files" | grep -Eq '^(backend|frontend|models|api|templates|infra)/' || exit 0
printf '%s\n' "$files" | grep -Eq "^intent/[^/]+/plans/$key\.md$" && exit 0
printf '%s' "$msg" | grep -qi 'plan unaffected' && exit 0

short=$(git -C "$root" rev-parse --short HEAD)
cat >&2 <<EOF
Warning from .claude/hooks/plan-sync-check.sh (ADR 0015): commit $short changed build files
but not the plan for $key. If the work departs from the plan, update
intent/<EPIC-KEY>-<kebab>/plans/$key.md and amend this commit (or add a follow-up commit).
If the plan is still accurate, say "plan unaffected" in the commit message next time.
EOF
exit 2
