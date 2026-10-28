#!/bin/sh
# PostToolUse hook (Bash). ADR 0015, BPA-32.
# After a Bash call whose command runs `git commit` on a branch named BPA-<n>-..., warn when the
# commit just made changed build files (backend/, frontend/, models/, api/, templates/)
# but not the item's plan, unless the commit message contains "plan unaffected".
# Warns only: exit 2 after a tool call shows stderr to Claude without undoing anything.

input=$(cat)
flat=$(printf '%s' "$input" | tr -d '\n')

# Only the "command" field counts (not the description), and only a real `git commit`
# (optionally `git -C <dir> commit`).
printf '%s' "$flat" \
  | grep -Eq '"command"[[:space:]]*:[[:space:]]*"[^"]*git([[:space:]]+-C[[:space:]]+[^[:space:]"]+)?[[:space:]]+commit' \
  || exit 0

# The checkout the command ran in: "cwd" from the hook input, else the project directory.
cwd=$(printf '%s' "$flat" | sed -n 's/.*"cwd"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
[ -n "$cwd" ] && [ -d "$cwd" ] || cwd=${CLAUDE_PROJECT_DIR:-$(pwd)}
root=$(git -C "$cwd" rev-parse --show-toplevel 2>/dev/null) || exit 0

branch=$(git -C "$root" branch --show-current 2>/dev/null)
key=$(printf '%s' "$branch" | sed -n 's/^\(BPA-[0-9][0-9]*\)-.*/\1/p')
[ -n "$key" ] || exit 0

git -C "$root" rev-parse -q --verify HEAD >/dev/null || exit 0

# Skip unless HEAD was committed just now: a failed commit ("nothing to commit") or a commit in
# another repository leaves an older HEAD here.
now=$(date +%s)
made=$(git -C "$root" log -1 --pretty=%ct)
[ $((now - made)) -le 120 ] || exit 0

files=$(git -C "$root" show --name-only --pretty=format: HEAD)
msg=$(git -C "$root" log -1 --pretty=%B)

printf '%s\n' "$files" | grep -Eq '^(backend|frontend|models|api|templates)/' || exit 0
printf '%s\n' "$files" | grep -Eq "^intent/[^/]+/plans/$key\.md$" && exit 0
printf '%s' "$msg" | grep -qi 'plan unaffected' && exit 0

short=$(git -C "$root" rev-parse --short HEAD)
cat >&2 <<EOF
Warning from .claude/hooks/plan-sync-check.sh (ADR 0015): commit $short changed build files
but not the plan for $key. If the work departs from the plan, update
intent/<EPIC-KEY>-<kebab>/plans/$key.md in a follow-up commit. If the plan is still accurate,
say "plan unaffected" in the commit message next time. Do not amend commits that were pushed.
EOF
exit 2
