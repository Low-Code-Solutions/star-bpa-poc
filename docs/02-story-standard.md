# 02 Story standard: STAR BPA POC

Status: AGREED v0.1; v0.2 PROPOSED (BPA-30, ADR 0015), pending the owner's sign-off. Owner: Khitaba.
Every story follows this template so Claude Code can turn each Gherkin scenario into an automated test.

## Jira mapping (space BPA)
| Template part | Jira field |
| --- | --- |
| Title | Summary: "[Persona] can [capability]" |
| As a / I want / So that | Description |
| Gherkin scenarios | Acceptance Criteria |
| Process and element IDs | BPMN Reference |
| Data, SOR per field, decisions, integration | SOR Notes |
| FRD and section, workshop date, or ASSUMPTION | Source Reference |
| Estimate (1, 2, 3, 5, 8) | Story point estimate |

Labels: claude-generated (anything Claude creates); assumption; contradiction; one area label from
process, decisions, data, integration, forms, agent-task.

## Template (illustrative example)

```
BPA-14  Site supervisor can log a near-miss from a mobile form

As a site supervisor
I want to log a near-miss with location, category and photo
So that HSE can triage it the same shift

Acceptance criteria
Scenario: Valid near-miss starts the incident process
  Given I am an authenticated site supervisor
  When I submit a near-miss with location, category and description
  Then a process instance of Process_HSE_Incident starts
  And the incident status is "Reported"

Scenario: Missing location is rejected
  Given I am an authenticated site supervisor
  When I submit a near-miss without a location
  Then the form shows "Location is required"
  And no process instance starts

BPMN reference: Process_HSE_Incident / StartEvent_NearMissReported
SOR notes: incident (id uuid, category enum, location_code text,
           reported_at timestamptz) -- SOR: POC database
           employee_ref -- SOR: myPeople (ID only, looked up)
Decisions: none
Integration: myPeople employee lookup, read-only, mocked in POC
Source: FRD-HSE-xx section x.x
Estimate: 3
```

## Definition of Ready
- Follows the template, with a persona and outcome
- Every acceptance criterion is a testable Gherkin scenario, including at least one unhappy path
- BPMN element IDs exist in the current process model
- Every field has a named SOR
- DMN tables are complete (all inputs, outputs and hit policy)
- No open question or contradiction blocks the story
- Estimated at 8 points or fewer
- The epic's `intent.md` and `spec.md` are accepted by the Product Owner (from 28 Oct 2026; stories
  already Ready on that date are not moved back)
- Product Owner (Faraz) has approved it

## Definition of Done
- Code merged to main through a reviewed pull request referencing the Jira key
- One automated test per Gherkin scenario, all passing in CI
- Process runs end to end in the local Camunda stack
- BPMN and DMN files in the repo match the deployed models
- Claude Code's open questions for the story are resolved or logged as new stories
- The story's plan matches the merged diff and is marked `Status: Closed`
- Demoed at sprint review

## Workflow
Backlog -> Needs Answer -> Ready -> In Progress -> In Review -> Done.
New items go to Backlog, or Needs Answer if a question blocks them. Only the Product Owner moves stories to Ready.
