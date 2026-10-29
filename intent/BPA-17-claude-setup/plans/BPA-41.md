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
- Human step: confirm in a real Claude Code session on Windows that a write under `backend/` is blocked on a branch with no plan and that a write under `intent/` is allowed.
- shellcheck is not installed on this machine; only `bash -n` was run on the two scripts.
- `.claude/hooks/test-hooks.sh` takes over two minutes on Windows (git worktree steps), so it ran in the background: 120 passed, 0 failed after the fix.
