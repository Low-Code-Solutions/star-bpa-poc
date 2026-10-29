# Plan: BPA-41 n/a (summary pending: the Jira item is being created)
Jira: BPA-41 (Task, epic BPA-17); relates to BPA-32 and BPA-25
Story last updated: 29 Oct 2026
Status: Closed (29 Oct 2026)
Approver: Ahsan (Build Lead)
Branch: BPA-41-windows-hook-demo-admin, from main 6315f5c

## Problem
1. `.claude/hooks/require-plan.sh` treated only paths starting with "/" as absolute. On Windows every
   write on a plan-less branch was blocked, including the plan itself (found in BPA-25, 29 Oct).
2. `docker-compose.yml` created no Camunda admin, although ADR 0005 (S29) assumes `demo/demo`.

## Readiness check (setup Task, so a light check)
- Goal and limits: stated in the task text below. Jira item being created; relates to BPA-32 (the
  hook) and BPA-25 (where the two faults were found). Jira status: n/a (setup Task).
- Decisions behind it: ADR 0015 (plans and the require-plan hook), ADR 0005 S29 (demo/demo kept).
- No business rule is involved; nothing to mark ASSUMPTION.
- Blocking questions: none.

## Files that change
| File | Change |
|---|---|
| .claude/hooks/require-plan.sh | Normalise the received path (backslashes to "/", drive letter `X:/` absolute, repo-root prefix matched case-insensitively); existing rules unchanged; POSIX paths skip the new code |
| .claude/hooks/test-require-plan.sh | New: path-form tests (`/abs/path`, relative, `d:\x\y`, `D:/x/y`, mixed separators), each with a plan present and with none |
| docker-compose.yml | `camunda` service: five `CAMUNDA_SECURITY_INITIALIZATION_*` variables creating the `demo/demo` admin; comment updated |
| intent/BPA-17-claude-setup/plans/BPA-41.md | This plan |
| REVIEW.md | Review log entry for this branch (added in the QA-review commit) |

## Order of work
1. Plan committed first (373c20e).
2. Cause of the hook fault confirmed from the hook and `.claude/settings.json` (settings.json is fine).
3. `test-require-plan.sh` written and run before the fix: 4 Windows-form cases failed.
4. Hook fixed; `test-require-plan.sh` 26 passed, 0 failed; `test-hooks.sh` 120 passed, 0 failed
   (2701177).
5. Compose variables added (2701177); fresh-stack test run by Faraz (47fcb82).
6. Windows session check recorded and plan closed (36aed9e); header and QA review (this commit).

## Scenario-to-test table
n/a (setup Task, no Gherkin scenarios). The tests are the cases in `.claude/hooks/test-require-plan.sh`
(13 path forms, run with a plan present and with none, 26 checks in all). `.claude/hooks/test-hooks.sh`
guards the existing POSIX behaviour.

## Risks
- Variable names: they match the Camunda 8.8 docs and the 8.10 "next" docs, but the 8.10 docs are
  marked unreleased and the property reference could not be read in full. The fresh-stack test settled it.
- The hook uses `cygpath` in Git Bash and a `/<drive>/rest` fallback elsewhere; only the `cygpath`
  path was run in the tests.
- `demo/demo` is a known default credential (S29); revisit with S24 for a shared or showcase environment.
- A mistake in the hook could block all writes or none; mitigated by the two test scripts.

## Proof
- `sh .claude/hooks/test-require-plan.sh` in Git Bash: 26 passed, 0 failed (4 Windows-form cases failed before the fix).
- `sh .claude/hooks/test-hooks.sh`: 120 passed, 0 failed.
- Fresh stack: `curl -u demo:demo http://localhost:8080/v2/topology` returns 200; the same call without credentials returns 401.
- Real Claude Code session on Windows (D:\ paths): see the last line of the human-gap log.

## Questions
None.

## Task as given
Task BPA-41 on branch BPA-41-windows-hook-demo-admin. Follow the committed plan intent/*/plans/BPA-41.md exactly.

1. Read .claude/hooks/require-plan.sh and .claude/settings.json. Explain in 3 lines why a Windows path (d:\... or d:/...) is not recognised as absolute, before changing anything.
2. Fix the hook: normalise the received path (backslashes to /, drive letter X:/ treated as absolute, repo-root prefix stripped case-insensitively), then apply the existing rules unchanged. No behaviour change for POSIX paths.
3. Add .claude/hooks/test-require-plan.sh covering: /abs/path, relative path, d:\x\y, D:/x/y and mixed separators, each with a plan present and with no plan. Run it in Git Bash and show the output.
4. In docker-compose.yml, on the Camunda orchestration service, add:
   CAMUNDA_SECURITY_INITIALIZATION_USERS_0_USERNAME=demo
   CAMUNDA_SECURITY_INITIALIZATION_USERS_0_PASSWORD=demo
   CAMUNDA_SECURITY_INITIALIZATION_USERS_0_NAME=Demo
   CAMUNDA_SECURITY_INITIALIZATION_USERS_0_EMAIL=demo@example.com
   CAMUNDA_SECURITY_INITIALIZATION_DEFAULTROLES_ADMIN_USERS_0=demo
   First confirm these variable names against the Camunda 8.10 docs or the official 8.10 compose bundle. If they differ, use the documented ones and tell me.
5. Do not run `docker compose down -v` yourself: it deletes local Camunda data. Tell me when the change is ready, and I will run the compose test from the plan.
6. Update the plan's human-gap log with any manual step. Commit as "BPA-41: Windows paths in require-plan.sh; demo admin in compose". Do not push to main, and do not change Jira.

## Human-gap log

- 29 Oct: the Camunda variable names (`CAMUNDA_SECURITY_INITIALIZATION_USERS_0_*`, `..._DEFAULTROLES_ADMIN_USERS_0`) match the 8.8 docs and the 8.10 "next" docs. The 8.10 property reference could not be read in full and 8.10 is marked unreleased, so the fresh-stack test is the proof.
- Human step (L19): run `docker compose down -v && docker compose up -d`, then `curl -u demo:demo http://localhost:8080/v2/topology`. Expect HTTP 200, not 401. Claude did not run `down -v`.
- 29 Oct: Faraz ran the fresh-stack test; Camunda 8.10.1 became healthy and `curl -u demo:demo http://localhost:8080/v2/topology` returned HTTP 200 (checked by Claude after the run).
- Human step: confirm in a real Claude Code session on Windows that a write under `backend/` is blocked on a branch with no plan and that a write under `intent/` is allowed.
- shellcheck is not installed on this machine; only `bash -n` was run on the two scripts.
- `.claude/hooks/test-hooks.sh` takes over two minutes on Windows (git worktree steps), so it ran in the background: 120 passed, 0 failed after the fix.
- 29 Oct: verified from a real Claude Code session on Windows (D:\ paths). On a plan-less branch, the backend/ write was blocked with the ADR 0015 message and the intent/ write was allowed.
