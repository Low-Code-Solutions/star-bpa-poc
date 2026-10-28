# Plan: BPA-38 Harness: protect main, shared Atlassian MCP config, plan-closed CI check

Jira: BPA-38 (Task, epic BPA-17)
Story last updated: 2026-10-28T21:40:13.155+0400
Status: Approved (Ahsan, 2026-10-28 22:45 PKT, in chat)
Approver: Ahsan (Build Lead)
Branch: BPA-38-harness-protect-main, from main 04ed323

## Readiness check (Task, so a light check)
- Goal, changes, limits and done criteria stated in Jira: yes. Jira status In Progress.
- Decisions behind it: 09 L13 (deny push to main and force pushes; hook blocking commits on main;
  CLAUDE.md states PR-only), L14 (`.mcp.json`, Atlassian remote MCP, per-developer OAuth, no
  secrets; `qa-reviewer` read-only), ADR 0015 (plans, Closed before merge).
- Facts checked 28 Oct against the Claude Code docs (permissions, hooks, mcp, sub-agents pages) and
  Atlassian's MCP server repo; see Sources.
- Blocking questions: none.

## Design

**1. Protect main: two layers (L13).** The docs say permission rules match command text and are
"not a security boundary" (`git -C . push origin main` slips past `Bash(git push *)`). So:
- **Deny rules** in `.claude/settings.json` `permissions.deny` (first line, cheap):
  `Bash(git push * main)`, `Bash(git push * main *)`, `Bash(git push *:main)`,
  `Bash(git push *:main *)`, `Bash(git push --force*)`, `Bash(git push * --force*)`,
  `Bash(git push -f*)`, `Bash(git push * -f*)`, plus `*:refs/heads/main` and `*:refs/heads/main *`
  (ten; amended in build from six, to cover a trailing option and any `<src>:main` form).
  The docs confirm deny rules apply to chained commands (`&&`, `;`, subshells) and to subagents.
- **A guard hook** `.claude/hooks/git-guard.sh` (PreToolUse on Bash; exit 2 blocks and tells
  Claude why). It normalises `git -C <dir>` and `git -c k=v` forms, then blocks:
  - `git commit` when the checkout's current branch is `main`;
  - `git push` whose destination ref is `main` (`main`, `HEAD:main`, `refs/heads/main`, `x:main`),
    or a push with no refspec while on `main`;
  - force pushes (`--force`, `-f`, `--force-with-lease`, `+refspec`);
  Rules for main and force pushes apply to this repository and its worktrees only (same git common
  dir as `$CLAUDE_PROJECT_DIR`), so scratch repos elsewhere are not affected (amended in build).
  - any git command outside a read-only allowlist (status, diff, log, show, rev-parse, and the
    read forms of branch, tag, config, stash, remote, worktree, notes, reflog) when the
    call comes from the `qa-reviewer` subagent (hook input carries `agent_type`), which makes the
    agent's read-only promise enforced rather than prose (closes the BPA-31 note).
  Limits, stated in CLAUDE.md: guards Claude Code sessions only; not a person's own terminal;
  aliases such as `git config alias.p push` are not detected. Real protection is a GitHub plan with
  branch protection (Faraz, 09 L13).

**2. Shared Atlassian MCP config (L14).** `.mcp.json`:
`{"mcpServers": {"atlassian": {"type": "http", "url": "https://mcp.atlassian.com/v1/mcp"}}}`.
No secrets: each developer authenticates once with `/mcp` (OAuth in the browser) and approves the
project server when Claude Code asks. Tools are then `mcp__atlassian__*` for everyone. CLAUDE.md says
which server to use and how to sign in; `qa-reviewer` already inherits tools, so no change there.

**3. Plan check in CI.** `.github/workflows/plan-check.yml` on pull requests, running
`.github/scripts/plan-check.sh` (a script, so it can be tested locally):
- branch `BPA-<n>-…`: **fail** if no `intent/*/plans/BPA-<n>.md`; **warn** (annotation, not fail)
  if its Status is not `Closed`, because the plan is closed in the final commit after review;
- branches without a key: pass with a notice.
The warning is visible on the PR before merge, which is where the BPA-27 and BPA-34 misses happened.

## Files that change

| File | Change |
|---|---|
| .claude/settings.json | Add `permissions.deny` (ten rules above) and a PreToolUse Bash hook for git-guard.sh (same missing-file guard as the existing hooks) |
| .claude/hooks/git-guard.sh | New: the guard hook above (POSIX sh, portable sed/grep like the others) |
| .claude/hooks/test-hooks.sh | Extend: git-guard cases (commit on main blocked / on branch allowed; push to main in 5 forms blocked; force push in 4 forms blocked; normal branch push allowed; `git -C` form; chained command; qa-reviewer write blocked, read allowed; non-git command ignored) |
| .mcp.json | New: Atlassian remote MCP server `atlassian`, HTTP, no secrets |
| .github/workflows/plan-check.yml | New: runs plan-check.sh on pull requests; a second job runs test-plan-check.sh and test-hooks.sh (amended in build, so the guards stay tested) |
| .github/scripts/plan-check.sh | New: the check above |
| .github/scripts/test-plan-check.sh | New: local tests for plan-check.sh (missing plan fails; Draft/Approved warn; Closed passes; unkeyed branch passes) |
| CLAUDE.md | "Branches and pull requests": guards, their limits and the CI plan check; "Requirements source: Jira": the `atlassian` server and the one-time `/mcp` sign-in. Short |
| .claude/agents/qa-reviewer.md | Two lines: git writes are now enforced by git-guard.sh (other shell writes are not); the shared server is `atlassian` (amended in build: the plan said no change, but L14 asks that qa-reviewer and CLAUDE.md agree) |
| intent/BPA-17-claude-setup/plans/BPA-38.md, intent/README.md | This plan; regenerated index |

Not changed: `docs/adr/README.md` (avoids conflicts with BPA-37, which may run in parallel);
09 (the Project/repo copy records L13/L14 as delivered later, with BPA-37's ADR 0003/0005 work).

## Order of work
1. This plan (Draft), approval recorded.
2. git-guard.sh with tests (test first: the new cases fail before the hook exists).
3. settings.json deny rules and hook entry; full hook test run.
4. plan-check script and its tests, then the workflow.
5. `.mcp.json`; CLAUDE.md lines.
6. Checks, qa-reviewer pass, PR (the new plan-check runs on its own PR: expect the "not Closed"
   warning, then a pass after the final commit).
7. Final commit: plan Closed, before Ahsan merges.

## Risks
- A guard blocks legitimate work. Mitigation: only `main` targets, force pushes and qa-reviewer
  writes are blocked; quoted text and heredoc bodies (commit messages) are treated as data; tests
  cover allowed cases; messages say how to proceed.
- Fail closed (amended in review): a `cd`/`-C`/`--git-dir` target the hook cannot resolve counts as
  this repository, so `cd "$(…)" && git push origin main` is blocked. Side effect: a commit on main
  in a repository created earlier in the same command is blocked too; run it as a separate command.
- Text matching has limits: git aliases, scripts that run git, and environment set in an earlier
  command are not seen. Stated in CLAUDE.md; GitHub branch protection remains the real control.
- Deny-rule patterns mis-match. Mitigation: the hook is the real check and is tested; deny rules are
  a cheap first layer. Patterns avoid the trailing `:*` form, which the docs treat as prefix syntax.
- `.mcp.json` approval prompt surprises developers. Mitigation: CLAUDE.md and the PR explain it.
- The Atlassian endpoint changes. Mitigation: one line in `.mcp.json`; `/sse` is still served.

## Proof
- `sh .claude/hooks/test-hooks.sh` and `bash` the same: all pass (120: 25 existing plus 95 new).
- `sh .github/scripts/test-plan-check.sh`: all pass (13).
- `.claude/settings.json` and `.mcp.json` are valid JSON.
- The plan-check workflow runs on this PR (warning while Approved, pass after Closed).
- Not provable here: OAuth sign-in to the Atlassian server (each developer, human step).

## Review (qa-reviewer, 28 Oct 2026, at 1660ab3)
7 Important, 5 Nits. All Important fixed in git-guard.sh with a test per bypass or false block
(81 → 120 hook tests):
1. Other spellings of main (`heads/main`, `@`, wildcard refspecs, `main>/dev/null`, `ma\in`): fixed.
2. git after wrappers (`if`, `timeout`, `env -i`, `bash -c`, `eval`, `\git`): git is now found after
   any leading words; `sh -c`/`bash -c`/`eval` scripts are checked as commands. Fixed.
3. Backslash-newline continuation split one command in two: fixed.
4. Unresolved directories failed open: now fail closed; `$CLAUDE_PROJECT_DIR`, `$PWD`, `GIT_DIR=`,
   `--git-dir` resolved. Fixed.
5. `-c remote.*`/`push.*`/`branch.*` on a push: blocked. Fixed.
6. qa-reviewer denylist missed writes (fetch, bisect, symbolic-ref, reflog expire, …): replaced by
   an allowlist. Fixed.
7. False blocks (checkout main -- file, switch --detach, heredoc or parenthesised commit messages,
   worktree list, notes show): fixed.
Nits: backticks inside `$( )` (fixed); `-o<value>` read as -f (fixed); deny rules gained the
`refs/heads/main` forms (`-uf` left to the hook: a `-*f*` pattern would also deny `-u origin feature`);
plan-check now checks a bare `BPA-<n>` branch (fixed, 2 tests; `**Status:**` not supported, docs/04
fixes the header as `Status:`); Jira BPA-38 Development panel shows an older merged PR, to check when
linking this PR.

## Questions
- None blocking. Note for Faraz: the L13 limit stays (no protection against a person pushing
  directly) until a GitHub plan with branch protection is chosen.

## Human-gap log
- (pending) Each developer runs `/mcp` once to sign in to Atlassian and approves the project server.

## Sources
- Claude Code docs: permissions (https://code.claude.com/docs/en/permissions.md), hooks
  (https://code.claude.com/docs/en/hooks.md), MCP (https://code.claude.com/docs/en/mcp.md),
  sub-agents (https://code.claude.com/docs/en/sub-agents.md)
- Atlassian MCP server: https://github.com/atlassian/atlassian-mcp-server (endpoint
  `https://mcp.atlassian.com/v1/mcp`; OAuth 2.1 per user)
