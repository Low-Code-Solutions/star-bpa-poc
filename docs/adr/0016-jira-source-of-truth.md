# 0016 Jira as source of truth for requirements

- Status: Accepted
- Date: decision date not recorded in the sources (BPA-19 created 2026-10-22, Done 2026-10-23); L7, L13,
  L14 on 2026-10-27 (09 LOCKED); written 2026-10-29 (BPA-37)
- Deciders: "the team" (Product Owner statement; BPA-19 records no deciders; BPA-19 was raised by Faraz);
  L7, L13, L14 by Ahsan (Build Lead) and Usama (Lead Developer)
- Jira: BPA-19 (Done, no file until now), BPA-37
- 09 rows: L7, L13, L14 (the `.mcp.json` part; the plan.md part is replaced by ADR 0015)

**Numbering.** BPA-19 asked for this record as `docs/adr/0001-jira-source-of-truth.md`. 09 L3 later gave
0001-0014 to the Epic 1 architecture ADRs, so this record is 0016 (ADR 0015, numbering note).

## Context

Khitaba had already created the epics and stories in Jira (space BPA). The POC asks whether Claude Code can
build from stories held in Jira with few or no clarifying questions (00-context, question 2). Claude Code
only builds from what the repo and Jira tell it, so it needs one place to read requirements from, a way to
report back what it found, and a known boundary between what lives in Jira and what lives in the repo.

## Options considered

1. **Linkage-only.** Named by the Product Owner as the option the team chose against (statement recorded
   in the claude.ai Project). The sources give no further detail.
2. **Jira as source of truth**: Claude reads the record at session start and writes outcomes back through
   the Atlassian MCP connector (BPA-19). Chosen.

## Decision

- **Jira is the source of truth for requirements** (BPA-19). Claude reads the item at session start and
  writes outcomes back through the Atlassian MCP connector.
- **Boundary** (BPA-19, refined by ADR 0015): Jira holds epics, stories, acceptance criteria, estimates,
  status and open questions. The repo holds intent, spec and plans, code, tests, BPMN/DMN, DDL, OpenAPI,
  the ADRs, 09 and the data model. Jira items carry the file link and commit SHA (linkage).
- **Main risk and mitigation** (BPA-19): a story changes mid-build. The plan records the story's
  last-updated time; if Jira's `updated` is newer, Claude re-reads the story and lists what changed before
  continuing (CLAUDE.md, "At session start" step 3). Plans live in `intent/` (ADR 0015), not `plan.md`.
- **How Claude delivers (L7).** Claude works in `Low-Code-Solutions/star-bpa-poc` directly: branch, PR,
  Ahsan reviews and merges; Claude never pushes to main. Branches and commits use the Jira key.
- **Protect main (L13).** `.claude/settings.json` deny rules for `git push` to main and force pushes, and a
  PreToolUse hook that blocks commits on main; CLAUDE.md states PR-only. These guard Claude Code sessions
  only, not a person pushing directly. Faraz decides on a GitHub Team plan for real branch protection.
- **Jira access (L14, `.mcp.json` part).** `.mcp.json` uses the Atlassian remote MCP with per-developer
  OAuth; no secrets committed. A read-only `qa-reviewer` subagent (fresh context) is built from the
  02-story-standard DoR and DoD.
- **Write-back rules** (CLAUDE.md, as applied): never edit Summary, Description, Acceptance Criteria, BPMN
  Reference, SOR Notes, Source Reference or estimates (propose changes in a comment); comment open
  questions, contradictions and ASSUMPTIONs only after the developer agrees; never create work items without
  explicit confirmation; never move an item to Ready or Done.

## Consequences

- Precedence when sources disagree: Jira story → the item's plan → the epic's spec.md → ADRs and 09
  (ADR 0015; CLAUDE.md).
- L13 and L14 were delivered under BPA-27 and BPA-38 (deny rules, `git-guard.sh`, `.mcp.json`,
  `qa-reviewer`, CI plan check). Each developer signs in to the Atlassian MCP once (`/mcp`), a human step
  (L19).
- L7 says the 09 §4 work is "a Task … under BPA-3 with one subtask per work item; branches and commits use
  the subtask key". The team created that work as separate Tasks (BPA-34 to BPA-37), and CLAUDE.md now
  says subtasks commit on their parent's branch. Recorded here as found; not changed by this ADR.
- The status-transition rules in CLAUDE.md are still a PROPOSAL, not part of this decision.
- `main` has no GitHub branch protection on the current plan (08 R2); the guards above are not a security
  boundary.

## Sources

Jira BPA-19; 09 L3, L7, L13, L14, L19; ADR 0015; CLAUDE.md; 00-context; 08 R2.
