# 0014 Test strategy

- Status: Accepted
- Date: 2026-10-27 (decided, 09 LOCKED); written 2026-10-29 (BPA-37)
- Deciders: Ahsan (Build Lead), Usama (Lead Developer)
- Informed: Faraz (Product Owner and Architect), Khitaba (BA)
- 09 rows: L15; L10 (lint and CI part)

## Context

The POC asks whether Claude Code can build from Jira stories with few clarifying questions (00-context,
question 2). Each story's acceptance criteria are Gherkin scenarios, and the Definition of Done asks for
one automated test per scenario, passing in CI (02-story-standard). A test written after the code tends to
confirm the code rather than the scenario. The first proposal was Camunda Process Test with Testcontainers,
one test per scenario named `BPA<n>_<scenario>`, with CI as the gate (08 Gate 1, proposal for ADR 0009).

## Options considered

1. **08's proposal** (Camunda Process Test + Testcontainers; naming; CI gate). L15 adopts its naming and
   the one-test-per-scenario rule and adds test-first; 09 does not record a decision on the test framework
   beyond S8 (Testcontainers for Mailpit) and the walking skeleton.
2. No other option is recorded in the sources.

## Decision

- **Test-first rule (L15, wording meant for CLAUDE.md):** "For each Gherkin scenario, first write a test named
  `BPA<n>_<camelCaseScenarioTitle>`, run it, and show it fails for the expected reason. Only then write code.
  One test per scenario. Don't edit a test to make it pass without saying so in the PR."
- **Model lint (L10):** commit `.bpmnlintrc` extending `bpmnlint:recommended` and
  `plugin:camunda-compat/camunda-cloud-8-10`, with plugin versions pinned in `package.json`; CI uses the
  same file. The DMN is checked by the deploy test in the walking skeleton.

Test rules decided in other rows, collected here for the reader (each stays in its own ADR):

| Rule | Row | ADR |
|---|---|---|
| PDFBox only in tests; assert extracted text, not bytes | S1 | 0012 |
| Mailpit started with Testcontainers; assert through its API | S8 | 0012 |
| One injectable clock; "Given today is … (Asia/Riyadh)" | S9 | 0013 |
| One React test and one backend test per screen condition | S14 | 0004, 0006 |
| Tests use their own fixtures or `AssignmentFileBuilder`, never the demo files | S18, L20 | 0009 |
| A CI test checks every table has an SOR comment | S17 | 0010 |
| BPA-25 checks as a `.http` file and shell runner; JUnit version with the skeleton | L18 | 0005 |

## Consequences

- `.bpmnlintrc` and `npm run lint:bpmn` are in the repo (BPA-36), with `bpmnlint` 12.0.0 and
  `bpmnlint-plugin-camunda-compat` 2.63.0 pinned; CI runs it on every PR.
- The walking skeleton brings the first Camunda Process Test and the DMN deploy test (08 Gate 3;
  spec.md Delivery order).
- 09 L15 gives the rule as "CLAUDE.md wording", but as of 29 Oct CLAUDE.md does not contain it.
  REVIEW.md (Compliance pass) checks the naming and that each test was shown failing first, and the
  `qa-reviewer` subagent applies REVIEW.md. Adding the wording to CLAUDE.md is follow-up work outside
  this ADR.

## Sources

09 L10, L15, L18, S1, S8, S9, S14, S17, S18, L20; 08 Gate 1, Gate 3; 00-context; 02-story-standard;
`.bpmnlintrc`, `package.json`.
