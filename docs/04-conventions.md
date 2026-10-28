# 04 Conventions: STAR BPA POC

Status: PROPOSED. Owner: Ahsan. The same file lives in the repo's docs/ folder so Claude in the
Project and Claude Code use identical names.

## Jira
- Space key: BPA. Work item keys: BPA-<n>.
- Story summary: "[Persona] can [capability]".
- Commit messages and branch names start with the key: "BPA-14: ...", branch "BPA-14-short-description".

## BPMN and DMN (Camunda 8)
- Process ID: Process_<Area>_<Name>, e.g. Process_HSE_Incident
- Elements: <Type>_<PascalName>, e.g. StartEvent_NearMissReported, UserTask_TriageIncident,
  ServiceTask_LookupEmployee, Gateway_IsSevere, EndEvent_IncidentClosed
- Decision ID: Decision_<Name>, e.g. Decision_IncidentSeverity
- Job worker types: kebab-case, e.g. lookup-employee
- Process variables: camelCase, e.g. incidentId, severityLevel
- Files: models/bpmn/<process-id>.bpmn, models/dmn/<decision-id>.dmn

## Database (PostgreSQL)
- Schema: bpa
- Tables: snake_case, singular, e.g. incident, corrective_action
- Primary keys: id uuid
- Columns referencing another SOR end in _ref and hold that system's ID, e.g. employee_ref (myPeople)
- Audit columns on every table: created_at, created_by, updated_at, updated_by
- Migrations: db/migration/V<n>__<description>.sql (Flyway)

## API
- OpenAPI 3.1 in api/openapi.yaml
- Paths: /api/v1/<plural-resource>, kebab-case
- JSON fields: camelCase

## Delivery artifacts (ADR 0015)
- Epic folder: intent/<EPIC-KEY>-<kebab-epic-name>/, e.g. intent/BPA-3-work-pack-assignment/
- Epic files: intent.md (problem, outcome, actors, constraints, open questions) and
  spec.md (design links, shared contracts, Delivery order)
- Plans: intent/<EPIC-KEY>-<kebab>/plans/BPA-<n>.md, one per story or repo-changing Task
- Plan header (first lines, in this order):
  - `# Plan: BPA-<n> <Jira summary>`
  - `Jira: BPA-<n> (<type>, epic <EPIC-KEY>)`
  - `Story last updated: <Jira updated timestamp>`
  - `Status: Draft | Approved (<who>, <when>) | Closed (<date>)`
  - `Approver: <name and role>`
  - `Branch: <branch>, from main <sha>`
- Plan sections: Readiness check; Files that change; Order of work; Scenario-to-test table
  (stories); Risks; Proof; Questions; Human-gap log
- intent/README.md is generated and non-authoritative; never edit it by hand

## Tests
- One test per Gherkin scenario, named after the story key and scenario,
  e.g. BPA14_validNearMissStartsIncidentProcess
