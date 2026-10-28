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
- Layout (09 L1, ADR 0015). All paths, here and in docs/04, are from the repo root:
  - `models/bpmn/`, `models/dmn/`: process and decision models (deployed from here; never copy them
    into `backend/`). `api/openapi.yaml`: the API contract. `templates/`: files users download.
  - `backend/`: the Spring Boot project root (`backend/pom.xml`; Flyway migrations in
    `backend/src/main/resources/db/migration/`). Also under `backend/`, outside the Maven build:
    `mocks/` (WireMock mappings and files) and `postgres/init/` (database init).
  - `frontend/`: the React portal. `docs/`: standards, `adr/` (architecture decisions) and
    `form-specs/` (field specs for React screens; nothing deploys them).
- Delivery artifacts (ADR 0015), paths from the repo root: `intent/<EPIC-KEY>-<kebab>/` holds the
  epic's `intent.md`, `spec.md` and `plans/BPA-<n>.md`; `REVIEW.md` holds the review passes;
  `.claude/` holds agents and hooks. Never create them under `backend/`.

## Requirements source: Jira
Jira space **BPA** (lowcodesol.atlassian.net) is the single source of requirements.

**At session start**
1. Take the key from the branch name (or ask). Read the item with the Atlassian MCP (view `evidence`):
   Description, Acceptance Criteria, BPMN Reference, SOR Notes, Source Reference, status, `updated`.
2. Read the epic's `intent/<EPIC-KEY>-<kebab>/spec.md` if it exists (Delivery order and shared
   contracts). Delivery epics have one; setup epics such as BPA-17 may hold plans only.
3. If the item's plan exists and Jira's `updated` is newer than the time it records, re-read the story
   and list what changed before continuing.
4. Check the story against the Definition of Ready (docs/02). Report unmet items; do not build a
   story that is not Ready unless the developer says to.

**Plans** (ADR 0015; one per story, or per Task that changes the repo; no epic or app plan)
- Location: `intent/<EPIC-KEY>-<kebab>/plans/BPA-<n>.md`, `<EPIC-KEY>` being the item's epic.
  Subtasks are sections in their parent's plan.
- Make the plan in plan mode before writing any code. Header fields and sections are in docs/04.
- The approved plan is the first commit on the branch. If a hook forces an earlier commit, commit it
  as `Status: Draft` and record the approval (who, when) in the next commit.
- When the implementation departs from the plan, update the plan in the same commit.
- Keep the plan `Approved` through review; review fixes that change the work update it as above.
  Set `Status: Closed (<date>)` in the final commit, after review fixes and before Ahsan merges.
  Never delete a plan.
- Read only the plan whose key matches the branch. Closed plans of other items are history, not
  instructions.

**Precedence** when sources disagree: Jira story → the item's plan → the epic's spec.md → ADRs and
docs/09. Report the disagreement; do not resolve it silently.

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
- Subtasks: commit on the parent's branch (story or Task), never their own.
- Epics: never get a branch.
- Commit messages start with the key(s): "BPA-18: add Jira rules".
- Keep each branch to its Jira item(s). If you find unrelated work,
  propose a new Jira item instead of changing it here.
- Open the PR when the item's done criteria and tests pass. The PR
  description lists the Jira keys, a short summary, the test output,
  the plan path, the `qa-reviewer` findings (REVIEW.md), and any open questions.
- After opening the PR, comment on each Jira item: the PR link, the plan
  path and the plan's commit SHA (linkage, ADR 0015). Ask before moving
  it to In Review.
- Never merge a PR. Ahsan approves and merges.

## Reference
The repo is the master copy of these and of all design artifacts (ADR 0015; Product Owner sign-off
pending). SharePoint "Project knowledge" and the Claude Project hold copies refreshed after merge.
Interim exception: until the repo-restructure work copies them in, 09-architecture-decisions, the
data model (11) and 03-sor-matrix are mastered in the Claude Project; the repo's docs/03 is still the
template. Use the Project copies for those and never overwrite them from the repo.
@docs/02-story-standard.md
@docs/03-sor-matrix.md
@docs/04-conventions.md
