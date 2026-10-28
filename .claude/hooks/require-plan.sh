#!/bin/sh
# PreToolUse hook (Edit, Write, MultiEdit, NotebookEdit). ADR 0015, BPA-32.
# On a branch named BPA-<n>-..., block edits outside intent/ and docs/ until the item's plan
# exists at intent/<EPIC-KEY>-<kebab>/plans/BPA-<n>.md. No effect on main or unkeyed branches.
# Exit 2 blocks the tool call and shows stderr to Claude.

input=$(cat)
root=${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}
[ -n "$root" ] || exit 0

branch=$(git -C "$root" branch --show-current 2>/dev/null)
key=$(printf '%s' "$branch" | sed -n 's/^\(BPA-[0-9][0-9]*\)-.*/\1/p')
[ -n "$key" ] || exit 0

# "file_path" (or "notebook_path") value in the hook input. Portable sed (GNU and BSD).
flat=$(printf '%s' "$input" | tr -d '\n')
path=$(printf '%s' "$flat" | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
[ -n "$path" ] || path=$(printf '%s' "$flat" \
  | sed -n 's/.*"notebook_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
[ -n "$path" ] || exit 0

case "$path" in
  "$root"/*) rel=${path#"$root"/} ;;
  /*) exit 0 ;;   # outside the repo: not this hook's business
  *) rel=$path ;;
esac

case "$rel" in
  intent/*|docs/*) exit 0 ;;
esac

for plan in "$root"/intent/*/plans/"$key".md; do
  [ -f "$plan" ] && exit 0
done

cat >&2 <<EOF
Blocked by .claude/hooks/require-plan.sh (ADR 0015): branch $branch has no plan yet.
Make the plan first, in plan mode, at intent/<EPIC-KEY>-<kebab>/plans/$key.md
(header and sections: docs/04-conventions.md "Delivery artifacts"), get it approved,
and commit it. Edits under intent/ and docs/ are allowed meanwhile.
EOF
exit 2
