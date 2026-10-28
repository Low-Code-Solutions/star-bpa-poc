# STAR BPA POC

LCS proof of concept: one business-process slice for STAR on Camunda 8 + PostgreSQL. D365 F&O, SAP,
FMS and myPeople stay the systems of record (SOR); the POC automates the work between them.
Codename "STAR" in all outputs. Synthetic data only. Never invent a business rule: mark it ASSUMPTION.

**Team:** Faraz (Product Owner and Architect; also works as BA alongside Khitaba and as a developer),
Khitaba (BA, Scrum Master), Ahsan (Build Lead; approves and merges PRs), Usama (Lead Developer).
Apply the rules for the role the person is working in: Faraz's BA and dev work follows the same
branch, PR and write-back rules as everyone else's.

## Stack and layout
- `docker compose up -d` (copy `.env.example` to `.env` first): Camunda 8.10 on :8080 (demo/demo),
  Connectors :8086, PostgreSQL :5432 (databases `camunda` and `bpa`, schema `bpa`), WireMock :8089.
- Code lives under `backend/` (`models/`, `db/migration`, `api/`, `mocks/`, `app/`); paths in docs/04
  are relative to it. Architecture decisions go in `docs/adr/`.

## Requirements source: Jira
Jira space **BPA** (lowcodesol.atlassian.net) is the single source of requirements.

**At session start**
1. Take the key from the branch name (or ask). Read the item with the Atlassian MCP (view `evidence`):
   Description, Acceptance Criteria, BPMN Reference, SOR Notes, Source Reference, status, `updated`.
2. If `plan.md` exists and Jira's `updated` is newer than the time it records, re-read the story and
   list what changed before continuing.
3. Check the story against the Definition of Ready (docs/02). Report unmet items; do not build a
   story that is not Ready unless the developer says to.

**plan.md** (repo root, committed on the item's branch) starts with `Jira: BPA-<n> <summary>` and
`Story last updated: <Jira updated>`, then the plan: one test per scenario, models, migrations, questions.
Delete plan.md in a final commit once the PR is approved, before merge, so it never reaches main.

**Write-back rules**
- Never edit Summary, Description, Acceptance Criteria, BPMN Reference, SOR Notes, Source Reference
  or estimates; propose changes in a comment instead.
- Comment each open question, contradiction or ASSUMPTION found while building, citing the file or
  scenario. Show the text to the developer and get a yes before posting.
- Never create work items without explicit confirmation; check for duplicates; label `claude-generated`.

**Status rules** (PROPOSAL, not yet agreed; ask before every transition)
- Ready → In Progress when work starts; In Progress → In Review when the PR is opened.
- Any → Needs Answer when an open question blocks the story, with the question as a comment.
- Never move an item to Ready or Done. Faraz makes those moves in Jira as Product Owner, even when
  working as BA or developer in the session.

## Branches and pull requests
- Never commit or push to main. All changes go through a pull request.
- Start every item from the latest main: `git switch main && git pull`,
  then create the branch. If a branch for the key already exists, use it.
- Branch name: <KEY>-<short-kebab-description>, e.g. BPA-10-print-released-work-pack.
- Stories: always one branch per story. Never combine stories.
- Tasks: one branch per task that changes the repo. Small related tasks
  may share one branch: name it after the first key and list every key
  in the PR title, e.g. "BPA-18, BPA-19: CLAUDE.md, Jira rules and ADR 0001".
  Tasks are related when they touch the same area and would be reviewed
  together. If unsure, ask before grouping.
- Tasks that do not change the repo get no branch.
- Subtasks: commit on the parent story's branch, never their own.
- Epics: never get a branch.
- Commit messages start with the key(s): "BPA-18: add Jira rules".
- Keep each branch to its Jira item(s). If you find unrelated work,
  propose a new Jira item instead of changing it here.
- Open the PR when the item's done criteria and tests pass. The PR
  description lists the Jira keys, a short summary, the test output,
  the plan.md path, and any open questions.
- After opening the PR, comment the PR link on each Jira item and move
  it to In Review.
- Never merge a PR. Ahsan approves and merges.

## Reference (master copies in SharePoint "Project knowledge"; copy changes here, do not edit in place)
@docs/02-story-standard.md
@docs/03-sor-matrix.md
@docs/04-conventions.md
