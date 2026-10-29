#!/bin/sh
# PreToolUse hook (Edit, Write, MultiEdit, NotebookEdit). ADR 0015, BPA-32.
# On a branch named BPA-<n>-..., block edits outside intent/ and docs/ until the item's plan
# exists at intent/<EPIC-KEY>-<kebab>/plans/BPA-<n>.md. No effect on main or unkeyed branches.
# The repo root and branch are taken from the edited file's own checkout, so git worktrees work.
# Exit 2 blocks the tool call and shows stderr to Claude.

input=$(cat)

# "file_path" (or "notebook_path") value in the hook input. Portable sed (GNU and BSD).
flat=$(printf '%s' "$input" | tr -d '\n')
path=$(printf '%s' "$flat" | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
[ -n "$path" ] || path=$(printf '%s' "$flat" \
  | sed -n 's/.*"notebook_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
[ -n "$path" ] || exit 0

# Windows paths (BPA-41): d:\x\y, D:/x/y or mixed separators. JSON may carry "\\", so squeeze "//".
# A drive-letter path is absolute; in Git Bash it becomes /d/x/y. POSIX paths skip this block.
win=0
case "$path" in
  *\\*|[A-Za-z]:[/\\]*)
    win=1
    path=$(printf '%s' "$path" | tr '\\' '/' | sed 's|//*|/|g')
    case "$path" in
      [A-Za-z]:/*)
        if command -v cygpath >/dev/null 2>&1; then
          path=$(cygpath -u "$path")
        else
          drv=$(printf '%s' "$path" | cut -c1 | tr 'A-Z' 'a-z')
          path="/$drv/${path#??/}"
        fi
        ;;
    esac
    ;;
esac

# Relative paths are relative to the session's project directory.
case "$path" in
  /*) ;;
  *) path="${CLAUDE_PROJECT_DIR:-$(pwd)}/$path" ;;
esac

# Nearest existing directory of the target (the file may not exist yet).
dir=$(dirname "$path")
while [ ! -d "$dir" ] && [ "$dir" != "/" ]; do dir=$(dirname "$dir"); done
root=$(git -C "$dir" rev-parse --show-toplevel 2>/dev/null) || exit 0   # not in a repo
[ -n "$root" ] || exit 0

# Windows (BPA-41): git prints the root as C:/x/y; use the same /c/x/y form as the path, and match
# the root prefix case-insensitively (Windows file systems ignore case), keeping the root's spelling.
case "$root" in
  [A-Za-z]:/*)
    if command -v cygpath >/dev/null 2>&1; then
      root=$(cygpath -u "$root")
    else
      drv=$(printf '%s' "$root" | cut -c1 | tr 'A-Z' 'a-z')
      root="/$drv/${root#??/}"
    fi
    ;;
esac
if [ "$win" = 1 ]; then
  lp=$(printf '%s' "$path" | tr 'A-Z' 'a-z'); lr=$(printf '%s' "$root" | tr 'A-Z' 'a-z')
  case "$lp" in
    "$lr"/*) path="$root/$(printf '%s' "$path" | cut -c$((${#root}+2))-)" ;;
  esac
fi

branch=$(git -C "$root" branch --show-current 2>/dev/null)
key=$(printf '%s' "$branch" | sed -n 's/^\(BPA-[0-9][0-9]*\)-.*/\1/p')
[ -n "$key" ] || exit 0

# Path relative to this checkout's root. Compare against the canonical root as well,
# in case the path was given through a symlink (for example /tmp vs /private/tmp).
croot=$(cd "$root" && pwd -P)
cdir=$(cd "$dir" && pwd -P)
rel=""
case "$path" in
  "$root"/*) rel=${path#"$root"/} ;;
esac
if [ -z "$rel" ]; then
  case "$cdir" in
    "$croot") rel=${path##*/} ;;
    "$croot"/*) rel="${cdir#"$croot"/}/${path##*/}" ;;
  esac
fi
[ -n "$rel" ] || exit 0

# Allow intent/ and docs/ before the plan exists, but never via ".." segments.
case "/$rel/" in
  */../*) ;;
  *)
    case "$rel" in
      intent/*|docs/*) exit 0 ;;
    esac
    ;;
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
