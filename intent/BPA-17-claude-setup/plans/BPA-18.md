# Plan: BPA-18 Repo setup: CLAUDE.md with Jira rules and docs imports

Jira: BPA-18 (Task, epic BPA-17 Claude setup)
Story last updated: 2026-10-22T13:13:38.293+0400
Status: Closed (2026-10-28; rebuilt under BPA-33)
Approver: Ahsan (Build Lead)
Branch: BPA-18-claude-md-jira-rules (PR #1, merged 2026-10-22) and BPA-18-remove-plan-md (PR #2, merged 2026-10-28)

Rebuilt on 28 Oct 2026 from the original root `plan.md` (commit 555f874, removed in 0e2ae8b) so the
audit trail survives (ADR 0015). The content below is the original; only the header is new.

## Plan
- [x] CLAUDE.md at repo root, under one page (67 lines)
- [x] "Requirements source: Jira" section: session start, plan.md, write-back rules, status rules (PROPOSAL)
- [x] "Branches and pull requests" section
- [x] Copy 02-story-standard.md, 03-sor-matrix.md, 04-conventions.md from SharePoint "Project knowledge" into docs/ and import them
- [ ] Fresh Claude Code session repeats the Jira rules correctly
- [ ] Team agrees the status-change rules
- [x] Ahsan approves; ~~delete this file before merge~~ (the file reached main and was removed by PR #2; superseded by ADR 0015)

## Open questions (status 28 Oct 2026)
- docs/04 paths (models/, db/migration/, api/, mocks/) are under backend/ in the repo. Fix the master copy or keep the note in CLAUDE.md?
  Status: decided by 09 L1 (top-level models/, api/, templates/) and the infra/ amendment of 28 Oct; delivered by the repo-restructure work, not BPA-18.
- Does branch protection dismiss approvals on new commits? If so, deleting plan.md after approval needs a re-approval.
  Status: moot. There is no branch protection on the current GitHub plan (08 R2), and plans are no longer deleted (ADR 0015).

## Human-gap log
- 22 Oct: PR #1 reviewed and merged by Ahsan.
- 22 Oct: the delete-before-merge step was missed; plan.md reached main.
- 28 Oct: PR #2 (removal, plus the team paragraph in CLAUDE.md) merged by Ahsan.

## Still open (not part of this file's scope)
- A fresh Claude Code session repeating the Jira and branch rules, run by each developer (08 Gate 4.6).
- Agreement of the status-change rules (CLAUDE.md still marks them PROPOSAL).
- Moving BPA-18 to Done is the Product Owner's call.
