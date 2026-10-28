# Review instructions: STAR BPA POC

Used by the `qa-reviewer` subagent before a PR is opened, and by any reviewer after. Findings never
approve or block a PR on their own; Ahsan approves and merges.

Inputs for every review: the diff against `main`, the Jira item (read with the Atlassian MCP), the
item's plan (`intent/<EPIC-KEY>-<kebab>/plans/BPA-<n>.md`), the epic's `spec.md`, CLAUDE.md,
docs/02 (DoR and DoD) and docs/04 (conventions).

## Passes
Run three passes and tag each finding with its pass and a severity.

**Bugs**
- Logic errors, broken edge cases, regressions, wrong error handling.
- Every boundary value and unhappy path in the Gherkin scenarios behaves as stated.
- Workers idempotent on their business key; retries and BPMN errors as the ADRs say.
- Dates and times: business dates in the configured business zone, timestamps in UTC, no `now()` in
  FEEL or DMN.

**Security and data**
- No secrets, tokens or passwords committed; `.env` values only as placeholders.
- No real personal data. Test and seed people are obviously synthetic (e.g. "Worker E1001").
- No client names, no names of people from source documents, no signed-document IDs or file names;
  codename "STAR" only; sources cited by source-register key.
- Backend enforces authorisation (group and scope, 403); the UI only hides.
- Injection risks, unsafe file handling, missing input validation.

**Compliance**
- The diff does what the Jira acceptance criteria say, and nothing outside the item.
- The diff matches the plan: every file in "Files that change" is touched or explained; every changed
  file appears in the plan or the plan was updated in the same commit.
- One test per Gherkin scenario, named `BPA<n>_<camelCaseScenarioTitle>`; each was shown failing first.
- Names follow docs/04 (process, element, decision IDs; tables; `_ref` columns; API paths; JSON fields).
- Every field touched has exactly one SOR, matching docs/03 and the data model.
- BPMN and DMN in the repo are the ones deployed; DMN tables complete (inputs, outputs, hit policy).
- No business rule invented: anything not in a source is marked ASSUMPTION and commented in Jira.
- Plan header complete; `Status: Closed` set before approval; human-gap log filled in.
- ADRs or docs updated when the change alters a decision, convention or CLAUDE.md rule.

## Severity
- **Important**: wrong behaviour, security or data-rule breach, missing or failing scenario test,
  diff departs from the plan or the acceptance criteria, invented business rule. Must be fixed or
  explicitly accepted by the reviewer in the PR.
- **Nit**: naming, wording, formatting, small clarity improvements. Cap at 5 per review; list the
  rest as a count.

## Skip
- Generated files (`intent/README.md`, OpenAPI-generated sources, build output).
- Anything CI already enforces (bpmnlint, Redocly lint, formatter) unless CI is not yet running.
- Style preferences not written in docs/04.

## Output format
```
Review: BPA-<n> at <sha>
Important (<count>)
- [Bugs|Security|Compliance] <file>:<line or element> <finding> -> <suggested fix>
Nits (<count>)
- ...
Plan check: <files in plan but not in diff> / <files in diff but not in plan>
Scenarios: <n> in Jira, <n> tests found, missing: <list>
```

When a review flags the same mistake a second time, propose a CLAUDE.md change in the PR
(playbook, Stage 5).
