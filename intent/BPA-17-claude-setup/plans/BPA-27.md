# Plan: BPA-27 Adopt the playbook artifact chain: intent → spec → plan → PR

Jira: BPA-27 (Task, epic BPA-17)
Story last updated: 2026-10-28T17:59:03.731+0400
Status: Closed (2026-10-28; approved by Ahsan 2026-10-28 19:05 PKT, in the claude.ai session)
Approver: Ahsan (Build Lead)
Branch: BPA-27-artifact-chain, from main 9b3322e

Subtasks: BPA-28 to BPA-33.

## Readiness check (Task, so a light check)
- Goal, scope and done criteria stated in the Jira description: yes
- Decision behind it recorded: yes (amends 09 L1, replaces 09 L14; Ahsan and Usama, 28 Oct 2026)
- Blocking questions: none. Sign-offs owed by Faraz and Khitaba are non-blocking and listed in the PR.

## Why
The AI Native SDLC Playbook makes each stage commit a file the next stage reads
(intent.md → spec.md → plan.md → diff and tests → PR review). Today plan.md is deleted
before merge (CLAUDE.md) or gitignored (09 L14), so the audit trail and the
"diff matches plan" check are lost.

## Files that change

| File | Subtask | Change |
|---|---|---|
| intent/BPA-17-claude-setup/plans/BPA-27.md | BPA-27 | This plan (first commit) |
| docs/adr/0015-delivery-artifact-chain.md | BPA-28 | New ADR (number: see question Q1) |
| docs/adr/README.md | BPA-28 | New: ADR index, notes the L3 list and BPA-19 |
| CLAUDE.md | BPA-29 | Plan mode first; plan.md location and lifecycle; precedence; repo as master; Jira linkage; remove delete-before-merge rule; layout line adds intent/ and REVIEW.md |
| docs/02-story-standard.md | BPA-30 | DoR line (from 28 Oct 2026), DoD line; version v0.2, pending Khitaba |
| docs/04-conventions.md | BPA-30 | intent/ and plans/ naming; plan header fields |
| REVIEW.md | BPA-31 | New: bugs, security, compliance passes; Important vs Nit; skips |
| .claude/agents/qa-reviewer.md | BPA-31 | New: read-only reviewer subagent |
| .claude/settings.json | BPA-32 | New: PreToolUse (Edit/Write/MultiEdit/NotebookEdit) and PostToolUse (Bash) hooks |
| .claude/hooks/require-plan.sh | BPA-32 | New: block edits outside intent/ and docs/ until the branch's plan exists |
| .claude/hooks/plan-sync-check.sh | BPA-32 | New: after a commit, warn when it changed build files but not the plan |
| intent/README.md | BPA-33 | New: generated, non-authoritative index |
| intent/generate-index.sh | BPA-33 | New (departure, added 28 Oct): the script that generates intent/README.md, so "generated" is true |
| intent/BPA-3-work-pack-assignment/intent.md | BPA-33 | New: from 00-context and the BPA-3 epic |
| intent/BPA-3-work-pack-assignment/spec.md | BPA-33 | New: links to 07, 09, ADRs, data model; Delivery order section |
| intent/BPA-17-claude-setup/plans/BPA-18.md | BPA-33 | Rebuilt from git history (555f874:plan.md), Closed |
| .claude/hooks/test-hooks.sh | BPA-32 | New (departure, review fix): committed hook tests, so the proof can be re-run |
| .gitignore | BPA-32 | Changed (departure, review fix): ignore `.claude/worktrees/` and `.claude/settings.local.json` |

Departures recorded in the commits that made them:
- BPA-32: the commit check runs as PostToolUse on the finished commit, not PreToolUse. Claude usually
  runs `git add … && git commit` as one command, so nothing is staged when PreToolUse fires.
- BPA-33: `intent/generate-index.sh` added so the index really is generated.
- Review fixes (independent review at 896b1c8, 9 Important findings; see "Review" below): hooks
  rewritten for worktrees, missing scripts, false commit triggers and `..` paths; test script and
  `.gitignore` entries added; `qa-reviewer` tools changed to inherit with file-editing tools removed;
  CLAUDE.md, ADR 0015, REVIEW.md, intent.md and BPA-18.md wording corrected.

Not changed here: 09 itself (lives in the Claude Project until L17 copies it to the repo;
the Project copy is amended after merge), backend/ layout (L1 restructure is separate work),
L13 deny rules (BPA-18 harness work).

## Order of work (one commit per subtask, messages start with the subtask key)
1. BPA-27: this plan, once approved.
2. BPA-28: ADR and ADR index.
3. BPA-29: CLAUDE.md.
4. BPA-30: 02 and 04.
5. BPA-31: REVIEW.md and qa-reviewer.
6. BPA-32: hooks and settings, with a local test run.
7. BPA-33: intent/ backfill and BPA-18 plan.
8. BPA-27: open the PR with this plan still Approved.
9. BPA-27: after review fixes, set this plan to Closed in the final commit, before Ahsan merges.
   Done late: PR #3 was merged (3d7aaee) before this step, so the plan was closed in follow-up
   PR BPA-27-close-plan.

## Risks
- CLAUDE.md and the hooks steer every later session; a wrong rule spreads. Mitigation:
  the hook only acts on branches named BPA-<n>-…, and the commit check warns, never blocks.
- The require-plan hook could block this branch's own later commits if the path pattern is wrong.
  Mitigation: this plan exists before the hook is added; tested against this branch.
- spec.md could copy 07/09 content and drift. Mitigation: it links, and copies only the
  Delivery order, which exists nowhere else.
- Sources disagree (SharePoint, Project, repo). Mitigation: intent.md and spec.md are
  built from the Claude Project copies dated 28 Oct (00-context ACTIVE, 09 LOCKED, 11 v0.4),
  and the PR says so.

## Proof
- Hooks: scripted test runs on throwaway branches (BPA-999-test with and without a plan,
  main, an unkeyed branch), output pasted in the PR. Done 28 Oct: 17 passed, 0 failed; after the
  review fixes, `sh .claude/hooks/test-hooks.sh` (committed): 25 passed, 0 failed under dash and bash.
- qa-reviewer: run once against this branch's diff; findings pasted in the PR.
- Docs: checked by a fresh read that no personal names, client names or signed-document IDs appear.
- No application code changes, so no unit tests apply.

## Review
Independent review (separate agent, fresh context, REVIEW.md passes) at 896b1c8: 9 Important,
8 Nits. All 9 Important fixed in the review-fix commit; Nits fixed: plan table layout, plan headers,
ADR wording on L14, intent.md citations, `..` paths and worktree folder. Not fixed, reported in the
PR: the BPA-17 epic has plans but no intent/spec (now allowed for setup epics), hooks don't see
`plans/archive/`, git aliases such as `git ci` are not detected.
Out of scope, new Jira item proposed: docs/03-sor-matrix.md on main names the partner company.

## Questions
- Q1 ADR numbering: BPA-19 ("ADR 0001: Jira as source of truth") is Done but no file exists, and
  09 L3 assigns 0001-0014 to other topics. Proposal: keep L3 numbers; this ADR is 0015; record
  "Jira as source of truth" as 0016 later under BPA-19's follow-up.
  Resolved: taken as accepted with the plan approval (28 Oct); flagged again in the PR.

## Human-gap log
- 28 Oct: decision to replace L14 taken by Ahsan and Usama in chat; Claude cannot approve it.
- 28 Oct: PR #2 merged by Ahsan (Claude never merges).
- 28 Oct: the session's stop hook required the draft plan to be committed and pushed before
  approval; it was committed as Draft (fe3d485) and approval recorded in the next commit.
- 28 Oct: plan approved by Ahsan in chat.
- 28 Oct: PR #3 reviewed and merged by Ahsan (3d7aaee) before the plan was set to Closed. The close
  step depends on a human remembering the order, the same gap as BPA-18. Proposed fix: a check in
  the repo-restructure CI work that fails a PR whose plan is not Closed (not decided).
- 28 Oct: Ahsan reversed the `infra/` layout amendment and kept 09 L1 as written (mocks and Postgres
  init under backend/). Corrected in spec.md, BPA-18.md and plan-sync-check.sh in the follow-up PR.
