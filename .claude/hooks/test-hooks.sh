#!/bin/sh
# Tests for require-plan.sh and plan-sync-check.sh (ADR 0015, BPA-32).
# Usage: sh .claude/hooks/test-hooks.sh   (from the repo root; needs git; works with dash or bash)
# Builds throwaway repositories under a temp directory; never touches this checkout.
set -u
HOOKS=$(cd "$(dirname "$0")" && pwd)
RP="$HOOKS/require-plan.sh"; PS="$HOOKS/plan-sync-check.sh"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
pass=0; fail=0
check() { if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS $3"; else fail=$((fail+1)); echo "FAIL $3 (got $1, want $2)"; fi; }
rp() { printf '{"tool_name":"Write","tool_input":{"file_path":"%s","content":"x"}}' "$1" | sh "$RP" >/dev/null 2>&1; echo $?; }
ps_() { printf '{"tool_name":"Bash","cwd":"%s","tool_input":{"command":"%s","description":"%s"}}' "$1" "$2" "${3:-run}" | sh "$PS" >/dev/null 2>&1; echo $?; }

R="$T/repo"; mkdir -p "$R" && cd "$R"
git init -q -b main . && git config user.email t@example.invalid && git config user.name tester
mkdir -p docs backend && echo x > docs/readme.md && git add -A && git commit -qm init
export CLAUDE_PROJECT_DIR="$R"

echo "== require-plan.sh"
git switch -q -c BPA-999-test
check "$(rp "$R/backend/src/main/java/Foo.java")" 2 "keyed branch, no plan, code edit -> blocked"
check "$(rp "$R/intent/BPA-3-x/plans/BPA-999.md")" 0 "keyed branch, no plan, intent/ edit -> allowed"
check "$(rp "$R/docs/04-conventions.md")" 0 "keyed branch, no plan, docs/ edit -> allowed"
check "$(rp "backend/src/main/java/Foo.java")" 2 "relative path, no plan -> blocked"
check "$(rp "$R/docs/../backend/x.java")" 2 "docs/../backend path, no plan -> blocked"
check "$(rp "/etc/hosts")" 0 "path outside any repo -> ignored"
check "$(printf '{"tool_input":{"notebook_path":"%s"}}' "$R/backend/n.ipynb" | sh "$RP" >/dev/null 2>&1; echo $?)" 2 "notebook_path, no plan -> blocked"
check "$(rp "$R/backend/dir with space/A.java")" 2 "path with spaces, no plan -> blocked"
mkdir -p intent/BPA-3-x/plans && echo plan > intent/BPA-3-x/plans/BPA-999.md
check "$(rp "$R/backend/src/main/java/Foo.java")" 0 "keyed branch, plan exists -> allowed"
check "$(rp "$R/backend/dir with space/A.java")" 0 "path with spaces, plan exists -> allowed"
git add -A && git commit -qm "BPA-999: plan"
git switch -q main
check "$(rp "$R/backend/src/main/java/Foo.java")" 0 "main -> no effect"
git switch -q -c scratch-unkeyed
check "$(rp "$R/backend/src/main/java/Foo.java")" 0 "unkeyed branch -> no effect"

echo "== require-plan.sh in a git worktree (session root on BPA-999, which has a plan)"
git switch -q BPA-999-test
git worktree add -q -b BPA-45-wt "$T/wt" main
check "$(rp "$T/wt/backend/x.java")" 2 "worktree on BPA-45, no plan -> blocked (root's plan does not count)"
check "$(rp "$T/wt/intent/BPA-3-x/plans/BPA-45.md")" 0 "worktree can write its own plan"
mkdir -p "$T/wt/intent/BPA-3-x/plans" && echo plan > "$T/wt/intent/BPA-3-x/plans/BPA-45.md"
check "$(rp "$T/wt/backend/x.java")" 0 "worktree plan exists -> allowed"

echo "== settings.json guard when the hook script is missing"
G="$T/guard"; mkdir -p "$G"
cmd='f="$CLAUDE_PROJECT_DIR/.claude/hooks/require-plan.sh"; if [ -f "$f" ]; then sh "$f"; fi'
check "$(echo '{}' | CLAUDE_PROJECT_DIR="$G" sh -c "$cmd" >/dev/null 2>&1; echo $?)" 0 "missing script -> exit 0, not a block"

echo "== plan-sync-check.sh"
cd "$R" && git switch -q -c BPA-997-sync main
echo a > backend/a.txt && git add -A && git commit -qm "BPA-997: code only"
check "$(ps_ "$R" 'git commit -m x')" 2 "code changed, plan not -> warn"
check "$(ps_ "$R" 'git -C repo commit -m x')" 2 "git -C <dir> commit form -> warn"
check "$(ps_ "$R" 'ls -la' 'check status before git commit')" 0 "git commit only in description -> ignored"
check "$(ps_ "$R" 'git status')" 0 "non-commit git command -> ignored"
mkdir -p intent/BPA-3-x/plans && echo p > intent/BPA-3-x/plans/BPA-997.md && echo b > backend/b.txt
git add -A && git commit -qm "BPA-997: code and plan"
check "$(ps_ "$R" 'git commit -m x')" 0 "code and plan changed -> quiet"
echo c > backend/c.txt && git add -A && git commit -qm "BPA-997: tweak, plan unaffected"
check "$(ps_ "$R" 'git commit -m x')" 0 "'plan unaffected' in message -> quiet"
echo d > docs/d.md && git add -A && git commit -qm "BPA-997: docs only"
check "$(ps_ "$R" 'git commit -m x')" 0 "docs-only commit -> quiet"
echo e > backend/e.txt && git add -A && GIT_COMMITTER_DATE="2001-01-01T00:00:00" git commit -qm "BPA-997: old"
check "$(ps_ "$R" 'git commit -m x')" 0 "HEAD not made just now (failed or other-repo commit) -> quiet"
git switch -q main
check "$(ps_ "$R" 'git commit -m x')" 0 "main -> no effect"

echo
echo "RESULT: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
