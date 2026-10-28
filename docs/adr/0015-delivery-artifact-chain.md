# 0015 Delivery artifact chain: intent → spec → plan → PR

- Status: Accepted
- Date: 2026-10-28
- Deciders: Ahsan (Build Lead), Usama (Lead Developer)
- Informed: Faraz (Product Owner), Khitaba (BA)
- Jira: BPA-27, BPA-28
- Amends: 09-architecture-decisions L1 (repo layout); replaces L14 (plan.md handling)

## Context

Claude Code builds only from what the repo and Jira tell it. Planning files were handled in two
conflicting ways:

- CLAUDE.md (BPA-18): `plan.md` at the repo root, committed on the branch, deleted before merge.
  This failed once: BPA-18's plan reached `main` and needed PR #2 to remove it.
- 09 L14: `plan.md` as a local, gitignored working file. In cloud sessions the file disappears with
  the container, reviewers cannot see it, and it cannot be handed between developers.

Both lose the plan at merge. The POC has to measure how far Claude carries delivery and where people
bridge gaps, and that record lives in the plan.

The team's AI Native SDLC Playbook (Project file, Stages 1–3 and 5) prescribes a chain in which
each stage commits a file the next stage reads: intent.md, spec.md, plan.md per change, then the
diff, its tests and the PR review, which checks the diff against spec.md and plan.md. Its sidebar on
legacy systems asks for one named source of truth per artifact.

## Options considered

1. **Plan per story, committed then deleted** (CLAUDE.md as of BPA-18). Rejected: depends on a manual
   step that has already been missed; plan lost at merge.
2. **Plan per story, local and gitignored** (09 L14). Rejected: invisible to review, lost in cloud
   sessions, no audit trail.
3. **Plans at three levels: story, epic and a generated app plan.** Rejected: the epic and app plans
   repeat ADRs, 09 and Jira and drift from them; parallel branches conflict on shared plan files;
   Claude Code cannot tell which plan to follow.
4. **Playbook chain** (chosen): intent.md and spec.md per epic, plan.md per story or repo-changing
   Task, committed and kept; app-level knowledge stays in CLAUDE.md, ADRs, 09 and the data model.

## Decision

| Level | Artifact | Location | Made by / approved by |
|---|---|---|---|
| App | Standing context, no plan | `CLAUDE.md`, `REVIEW.md`, `docs/adr/`, `docs/09-architecture-decisions.md`, `docs/data-model.md`, `docs/02`–`04` | Ahsan approves changes by merging |
| Epic | `intent.md`: problem, outcome, actors, constraints, open questions | `intent/<EPIC-KEY>-<kebab>/intent.md` | Claude drafts; Product Owner accepts |
| Epic | `spec.md`: design (links to ADRs, 09, data model, models) and a **Delivery order** section (story order, which story creates and which extends each shared contract) | `intent/<EPIC-KEY>-<kebab>/spec.md` | Claude drafts; Product Owner accepts |
| Story, or Task that changes the repo | `plan.md`: readiness check, scenario-to-test table, files, order, risks, proof, questions, human-gap log | `intent/<EPIC-KEY>-<kebab>/plans/BPA-<n>.md` | Claude Code in plan mode; developer approves (Ahsan for higher-risk work) |
| Index | Generated list of epics and plans, non-authoritative | `intent/README.md` | Regenerated, never edited by hand |

Plan lifecycle:

1. Made in plan mode at the start of the item, before code.
2. The approved plan is the first commit on the branch (or, where a session hook forces an earlier
   commit, committed as `Status: Draft` and approved in the next commit).
3. Updated in the same commit as any departure from it.
4. Set to `Status: Closed` before the PR is approved; never deleted.
5. Subtasks are sections inside the parent's plan.

Precedence when sources disagree: Jira story → story plan → epic spec.md → ADRs and 09. Claude Code
reads only the plan matching the branch key; Closed plans are history, not instructions.

Source of truth per artifact:

- Jira: story text, acceptance criteria, estimates, status.
- Repo: intent, spec, plans, ADRs, 09, data model, 02–04, models, API, templates. Jira items carry
  the file link and commit SHA (playbook "linkage").
- SharePoint: signed FRDs and workshop minutes, cited by source-register key only.

The repo becoming master for design artifacts is pending the Product Owner's sign-off; until then
CLAUDE.md records it as the working rule.

## Consequences

- The L1 layout gains `intent/`, `REVIEW.md` and `.claude/` (agents, hooks, settings).
- CLAUDE.md, 02-story-standard and 04-conventions change (BPA-29, BPA-30).
- Hooks enforce the plan rule rather than relying on prose (BPA-32); REVIEW.md and the
  `qa-reviewer` subagent check diffs against the plan (BPA-31).
- `main` accumulates closed plans; they can move to `intent/<epic>/plans/archive/` at sprint end.
- New measures come from git: spec.md commits after the first plan commit (spec rework), plan
  updates per story, merged diff matching the plan, entries in the human-gap logs.
- The Definition of Ready line "epic intent.md and spec.md accepted" applies from 28 Oct 2026;
  stories already Ready are not moved back.
- The Claude Project copy of 09 is amended after merge to record L1 and L14 as changed by this ADR.

## Numbering note

09 L3 reserves 0001–0014 for the Epic 1 architecture ADRs (not yet written). Jira BPA-19 ("ADR 0001:
Jira as source of truth") is Done but no file exists. To avoid a clash this ADR takes 0015, and the
Jira-as-source-of-truth decision will be written as 0016. See `README.md`.
