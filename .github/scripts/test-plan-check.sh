#!/bin/sh
# Tests for plan-check.sh (BPA-38). Usage: sh .github/scripts/test-plan-check.sh (works with dash or bash).
# Builds a throwaway tree under a temp directory; never touches this checkout.
set -u
PC="$(cd "$(dirname "$0")" && pwd)/plan-check.sh"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
pass=0; fail=0
# check <got-exit> <want-exit> <output> <pattern> <name>
check() {
  if [ "$1" = "$2" ] && printf '%s' "$3" | grep -q -- "$4"; then pass=$((pass+1)); echo "PASS $5"
  else fail=$((fail+1)); echo "FAIL $5 (exit $1, want $2; output: $3)"; fi
}
run() { out=$(sh "$PC" "$1" "$T" 2>&1); rc=$?; }
plan() { mkdir -p "$T/intent/$1/plans"; printf '# Plan: %s x\nJira: %s (Task, epic BPA-17)\nStory last updated: x\nStatus: %s\nApprover: x\n' "$2" "$2" "$3" > "$T/intent/$1/plans/$2.md"; }

run "BPA-50-no-plan";            check "$rc" 1 "$out" "::error::plan-check: no plan for BPA-50" "missing plan -> fail"
plan BPA-17-claude-setup BPA-51 "Draft"
run "BPA-51-draft";              check "$rc" 0 "$out" "::warning file=intent/BPA-17-claude-setup/plans/BPA-51.md,line=4::" "Draft plan -> warn, pass"
plan BPA-17-claude-setup BPA-52 "Approved (Ahsan, 2026-10-28)"
run "BPA-52-approved";           check "$rc" 0 "$out" "Status is 'Approved (Ahsan, 2026-10-28)', not Closed" "Approved plan -> warn, pass"
plan BPA-3-work-pack-assignment BPA-53 "Closed (2026-10-29)"
run "BPA-53-closed";             check "$rc" 0 "$out" "is Closed (2026-10-29)" "Closed plan -> pass, no warning"
printf '%s' "$out" | grep -q '::warning' && { fail=$((fail+1)); echo "FAIL Closed plan printed a warning"; }
mkdir -p "$T/intent/BPA-17-claude-setup/plans"; printf '# Plan: BPA-54 x\n\nNo header.\n' > "$T/intent/BPA-17-claude-setup/plans/BPA-54.md"
run "BPA-54-no-status";          check "$rc" 0 "$out" "has no Status line" "plan without Status -> warn, pass"
mkdir -p "$T/intent/BPA-17-claude-setup/plans"; printf 'x\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nStatus: Closed (2026-10-29)\n' > "$T/intent/BPA-17-claude-setup/plans/BPA-55.md"
run "BPA-55-status-in-body";     check "$rc" 0 "$out" "has no Status line" "Status only below the header -> not read as Closed"
plan BPA-17-claude-setup BPA-56 "Draft"; plan BPA-3-work-pack-assignment BPA-56 "Draft"
run "BPA-56-two-plans";          check "$rc" 1 "$out" "more than one plan for BPA-56" "two plans for one key -> fail"
run "BPA-5-x";                   check "$rc" 1 "$out" "no plan for BPA-5\\." "BPA-5 does not match BPA-51's plan"
run "BPA-50";                  check "$rc" 1 "$out" "no plan for BPA-50" "bare key branch BPA-50 -> checked, fail"
run "BPA-501x-y";               check "$rc" 0 "$out" "::notice::" "BPA-501x-y is not a key -> notice"
run "feature-unkeyed";           check "$rc" 0 "$out" "::notice::" "unkeyed branch -> notice, pass"
run "";                          check "$rc" 0 "$out" "::notice::" "no branch given -> notice, pass"
run "bpa-51-lowercase";          check "$rc" 0 "$out" "::notice::" "lowercase key is not a key -> notice"

echo
echo "RESULT: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
