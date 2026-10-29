#!/bin/sh
# Path-form tests for require-plan.sh (ADR 0015, BPA-41): POSIX, relative, and Windows paths.
# Usage: sh .claude/hooks/test-require-plan.sh   (needs git; Windows forms run only where cygpath exists)
# Builds a throwaway repository under a temp directory; never touches this checkout.
set -u
HOOKS=$(cd "$(dirname "$0")" && pwd)
RP="$HOOKS/require-plan.sh"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
pass=0; fail=0
check() { if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS $3"; else fail=$((fail+1)); echo "FAIL $3 (got $1, want $2)"; fi; }
# $1 = file_path exactly as it appears in the hook's JSON input
rp() { printf '{"tool_name":"Write","tool_input":{"file_path":"%s","content":"x"}}' "$1" | sh "$RP" >/dev/null 2>&1; echo $?; }
# Same path with backslashes doubled, as JSON carries them from Claude Code on Windows.
json() { printf '%s' "$1" | sed 's|\\|\\\\|g'; }

R="$T/repo"; mkdir -p "$R" && cd "$R"
git init -q -b main . && git config user.email t@example.invalid && git config user.name tester
mkdir -p docs backend && echo x > docs/readme.md && git add -A && git commit -qm init
git switch -q -c BPA-998-paths
export CLAUDE_PROJECT_DIR="$R"

# Windows spellings of the same repo, when cygpath exists (Git Bash).
W=""; D=""; M=""
if command -v cygpath >/dev/null 2>&1; then
  W=$(cygpath -w "$R")                        # C:\Users\...\repo
  D=$(cygpath -m "$R")                        # C:/Users/.../repo
  d=$(printf '%s' "$D" | cut -c1)
  low=$(printf '%s' "$d" | tr 'A-Z' 'a-z')
  M="$low${D#?}"                              # lower-case drive letter, forward slashes
fi

run_cases() {   # $1 = label, $2 = expected exit for code edits, $3 = expected for intent/ and docs/ edits
  echo "== $1"
  check "$(rp "$R/backend/src/Foo.java")" "$2" "/abs/path code edit"
  check "$(rp "backend/src/Foo.java")" "$2" "relative path code edit"
  check "$(rp "$R/intent/BPA-3-x/plans/BPA-998.md")" "$3" "/abs/path intent/ edit"
  check "$(rp "$R/docs/readme.md")" "$3" "/abs/path docs/ edit"
  if [ -n "$W" ]; then
    check "$(rp "$(json "$W\\backend\\src\\Foo.java")")" "$2" "C:\\x\\y (JSON-escaped) code edit"
    check "$(rp "$W\\backend\\src\\Foo.java")" "$2" "C:\\x\\y (raw) code edit"
    check "$(rp "$D/backend/src/Foo.java")" "$2" "C:/x/y code edit"
    check "$(rp "$M/backend/src/Foo.java")" "$2" "c:/x/y (lower-case drive) code edit"
    check "$(rp "$(json "$W")/backend\\src/Foo.java")" "$2" "mixed separators code edit"
    check "$(rp "$(json "$W\\intent\\BPA-3-x\\plans\\BPA-998.md")")" "$3" "C:\\x\\y intent/ edit"
    check "$(rp "$D/docs/readme.md")" "$3" "C:/x/y docs/ edit"
    check "$(rp "$(json "$W")/docs\\readme.md")" "$3" "mixed separators docs/ edit"
    check "$(rp "$(json "$W\\docs\\..\\backend\\x.java")")" "$2" "C:\\x\\docs\\..\\backend path"
  else
    echo "SKIP Windows path forms (no cygpath)"
  fi
}

run_cases "no plan: code edits blocked, intent/ and docs/ allowed" 2 0
mkdir -p intent/BPA-3-x/plans && echo plan > intent/BPA-3-x/plans/BPA-998.md
run_cases "plan present: everything allowed" 0 0

echo; echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
