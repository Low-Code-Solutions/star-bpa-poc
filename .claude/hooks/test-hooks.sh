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

echo "== git-guard.sh (09 L13, BPA-38). Commands are written as they appear inside the JSON string."
GG="$HOOKS/git-guard.sh"
# gg <cwd> <command> [agent_type]
gg() {
  if [ -n "${3:-}" ]; then a=",\"agent_id\":\"a1\",\"agent_type\":\"$3\""; else a=""; fi
  printf '{"tool_name":"Bash","cwd":"%s"%s,"tool_input":{"command":"%s","description":"run"}}' "$1" "$a" "$2" \
    | sh "$GG" >/dev/null 2>&1; echo $?
}
cd "$R" && git switch -q main
echo "-- on main"
check "$(gg "$R" 'git commit -m x')" 2 "commit on main -> blocked"
check "$(gg "$T" 'git -C repo commit -m x')" 2 "git -C <dir> commit on main -> blocked"
check "$(gg "$T" 'cd repo && git commit -am x')" 2 "cd <dir> && git commit on main -> blocked"
check "$(gg "$R" 'git add -A && git commit -m \"a b\"')" 2 "chained add && commit on main -> blocked"
check "$(gg "$R" 'git push')" 2 "push with no refspec on main -> blocked"
check "$(gg "$R" 'git push origin')" 2 "push to remote with no refspec on main -> blocked"
check "$(gg "$R" 'git push origin \"$(git branch --show-current)\"')" 2 "push of \$(current branch) on main -> blocked"
check "$(gg "$R" 'git push --tags')" 0 "push --tags on main -> allowed (tags only)"
check "$(gg "$R" 'git status && git log --oneline -3')" 0 "read-only git on main -> allowed"
check "$(gg "$R" 'git switch main && git pull')" 0 "switch main && pull -> allowed"
git switch -q -c BPA-996-guard
echo "-- on BPA-996-guard"
check "$(gg "$R" 'git commit -m x')" 0 "commit on a keyed branch -> allowed"
check "$(gg "$R" 'git push -u origin BPA-996-guard')" 0 "push own branch -> allowed"
check "$(gg "$R" 'git push origin \"$(git branch --show-current)\"')" 0 "push of \$(current branch) on a branch -> allowed"
check "$(gg "$R" 'git push origin main:BPA-996-guard')" 0 "push main as source to another branch -> allowed"
check "$(gg "$R" 'git commit -m \"docs: explain git push origin main\"')" 0 "push text inside a commit message -> allowed"
check "$(gg "$R" 'echo git push origin main')" 0 "echo of a push command -> allowed"
check "$(gg "$R" 'ls -la')" 0 "non-git command -> ignored"
check "$(gg "$R" 'git push origin main')" 2 "push origin main -> blocked"
check "$(gg "$R" 'git push origin HEAD:main')" 2 "push HEAD:main -> blocked"
check "$(gg "$R" 'git push origin HEAD:refs/heads/main')" 2 "push HEAD:refs/heads/main -> blocked"
check "$(gg "$R" 'git push origin :main')" 2 "push :main (delete) -> blocked"
check "$(gg "$R" 'git push origin --delete main')" 2 "push --delete main -> blocked"
check "$(gg "$R" 'git push origin main --follow-tags')" 2 "push main with a trailing option -> blocked"
check "$(gg "$R" "git -c push.default=current push origin 'main'")" 2 "git -c ... push 'main' (quoted) -> blocked"
check "$(gg "$T" 'git -C repo push origin main')" 2 "git -C <dir> push origin main -> blocked"
check "$(gg "$R" 'git status && git push origin main 2>&1 | tail -3')" 2 "push main inside a chain and pipe -> blocked"
check "$(gg "$R" 'git push --all origin')" 2 "push --all -> blocked"
check "$(gg "$R" 'git push --mirror origin')" 2 "push --mirror -> blocked"
check "$(gg "$R" 'git push --force origin BPA-996-guard')" 2 "push --force -> blocked"
check "$(gg "$R" 'git push -f')" 2 "push -f -> blocked"
check "$(gg "$R" 'git push -uf origin BPA-996-guard')" 2 "push -uf (clustered) -> blocked"
check "$(gg "$R" 'git push --force-with-lease origin BPA-996-guard')" 2 "push --force-with-lease -> blocked"
check "$(gg "$R" 'git push origin +BPA-996-guard')" 2 "push +refspec -> blocked"
check "$(gg "$R" 'git switch main && git commit -m x')" 2 "switch main && commit -> blocked"
check "$(gg "$R" 'git checkout main\ngit commit -m x')" 2 "checkout main, newline, commit -> blocked"
echo "-- worktree of this repository, and another repository"
check "$(gg "$T/wt" 'git push origin main')" 2 "push main from a worktree of the project -> blocked"
O="$T/other"; mkdir -p "$O" && git -C "$O" init -q -b main
check "$(gg "$O" 'git commit -m x')" 0 "commit on main in another repository -> allowed"
check "$(gg "$O" 'git push origin main')" 0 "push main in another repository -> allowed"
check "$(gg "$O" 'git push --force origin main' qa-reviewer)" 2 "qa-reviewer push in another repository -> blocked"
echo "-- qa-reviewer subagent (agent_type), on BPA-996-guard"
check "$(gg "$R" 'git commit -m x' qa-reviewer)" 2 "qa-reviewer commit -> blocked"
check "$(gg "$R" 'git push -u origin BPA-996-guard' qa-reviewer)" 2 "qa-reviewer push -> blocked"
check "$(gg "$R" 'git checkout main' qa-reviewer)" 2 "qa-reviewer checkout -> blocked"
check "$(gg "$R" 'git stash' qa-reviewer)" 2 "qa-reviewer stash -> blocked"
check "$(gg "$R" 'git branch tmp-x' qa-reviewer)" 2 "qa-reviewer branch create -> blocked"
check "$(gg "$R" 'git tag v1' qa-reviewer)" 2 "qa-reviewer tag create -> blocked"
check "$(gg "$R" 'git config user.name x' qa-reviewer)" 2 "qa-reviewer config set -> blocked"
check "$(gg "$R" 'git status && git diff main...HEAD' qa-reviewer)" 0 "qa-reviewer status and diff -> allowed"
check "$(gg "$R" 'git log --oneline -5 && git show HEAD --stat' qa-reviewer)" 0 "qa-reviewer log and show -> allowed"
check "$(gg "$R" 'git branch --show-current' qa-reviewer)" 0 "qa-reviewer branch --show-current -> allowed"
check "$(gg "$R" 'git stash list' qa-reviewer)" 0 "qa-reviewer stash list -> allowed"
check "$(gg "$R" 'git tag -l' qa-reviewer)" 0 "qa-reviewer tag -l -> allowed"
check "$(gg "$R" 'git config user.name' qa-reviewer)" 0 "qa-reviewer config get -> allowed"
check "$(gg "$R" 'sh .claude/hooks/test-hooks.sh' qa-reviewer)" 0 "qa-reviewer runs tests -> allowed"
check "$(gg "$R" 'git commit -m x' plugin:qa-reviewer)" 2 "namespaced qa-reviewer commit -> blocked"
check "$(gg "$R" 'git commit -m x' Explore)" 0 "other subagent commit on a branch -> allowed"
echo "-- settings.json guard for git-guard.sh when the script is missing"
cmd='f="$CLAUDE_PROJECT_DIR/.claude/hooks/git-guard.sh"; if [ -f "$f" ]; then sh "$f"; fi'
check "$(echo '{}' | CLAUDE_PROJECT_DIR="$G" sh -c "$cmd" >/dev/null 2>&1; echo $?)" 0 "missing git-guard script -> exit 0, not a block"

echo
echo "RESULT: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
