# Architecture decision records

Format: MADR short form (status, date, deciders in the header; context, options considered,
decision, consequences). Files `NNNN-kebab-title.md`, numbered from 0001 (09 L3).
Each ADR restates the rows of `docs/09-architecture-decisions.md` it cites and adds the reasons and the
options considered. Where an ADR and 09 disagree, report it; precedence is in CLAUDE.md.

| ADR | Title | Covers (09 rows) | Status |
|---|---|---|---|
| [0001](0001-runtime-and-versions.md) | Runtime and versions | G1, S24 | Accepted |
| [0002](0002-application-stack.md) | Application stack | Spring Boot workers (09 §1 note), S23 | Accepted |
| [0003](0003-repo-layout.md) | Repo layout | L1 (amended by 0015), L17 | Accepted |
| [0004](0004-ui-channel-and-task-api.md) | UI channel and task API | S2, S4, S14 (S15 rejected option) | Accepted |
| [0005](0005-identity-and-authorisation.md) | Identity and authorisation | S5, S6, G9, S22, S25-S29, L2, L4, L9, L18 | Accepted |
| [0006](0006-validation-pattern.md) | Validation pattern | G2 (07 G2 / D56), S14 conditions | Accepted |
| [0007](0007-reliability-and-idempotency.md) | Reliability and idempotency | S7, G10 (amended by D70) | Accepted |
| [0008](0008-messaging-and-instance-state.md) | Messaging and instance state | G3, G6, G7 (amended by D61), L10 | Accepted |
| [0009](0009-assignment-file-intake.md) | Assignment file intake | S18, S19 (confirmed as D58), L6, L20 | Accepted |
| [0010](0010-data-model-sor-and-migrations.md) | Data model, SOR and migrations | S16, S17, L5, L8, L11 | Accepted |
| [0011](0011-api-contract-and-tooling.md) | API contract and tooling | S20, S21, L12 | Accepted |
| [0012](0012-print-and-notifications.md) | Print and notifications | S1, S3, S8 | Accepted |
| [0013](0013-time-and-dates.md) | Time and dates | S9 | Accepted |
| [0014](0014-test-strategy.md) | Test strategy | L15, L10 (lint and CI) | Accepted |
| [0015](0015-delivery-artifact-chain.md) | Delivery artifact chain: intent → spec → plan → PR | L1 (amended), L14 (replaced) | Accepted |
| [0016](0016-jira-source-of-truth.md) | Jira as source of truth for requirements | Jira BPA-19; L7, L13, L14 (`.mcp.json`) | Accepted |

## Every 09 row and its ADR

09 §1 (decisions), §2 (items owned by others) and §5 (delivery decisions). 09 has no S11 or S12.
The unnumbered §2 rows (template-layout confirmation, FMS template request, GitHub plan, per-story PO
approval) are actions, not decisions: they are referenced from 0009 and 0016 and need no row here.
Rows marked "no ADR" are not architecture decisions; the reason says where they live (09 L3 does the same
for S10).

| Row | Topic | ADR |
|---|---|---|
| S1 | PDF rendering | 0012 |
| S2 | UI channel | 0004 |
| S3 | Arabic text in the print | 0012 |
| S4 | Viewing the PDF in React | 0004 |
| S5 | Login | 0005 |
| S6 | Roles and identity | 0005 |
| S7 | Retry and idempotency | 0007 |
| S8 | Mock SMTP | 0012 |
| S9 | Time zone | 0013 |
| S10 | Estimates | no ADR: stays in Jira (09 L3) |
| G1 | Camunda version | 0001 |
| G2 | Validation pattern (07 §11 G2, decided as D56; not a row of 09 §1) | 0006 |
| G3 | Result file writer | 0008 |
| G6 | Drawing revision recording | 0008 |
| G7 | Instance state (amended by D61) | 0008 |
| G9 | Candidate groups | 0005 |
| G10 | Duplicate message publish (amended by D70) | 0007 |
| S13 | Modeler review of the models | no ADR: review work owned by Khitaba (09 §2) |
| S14 | Task forms | 0004 (task API), 0006 (conditions) |
| S15 | Forms capability Spike (cancelled) | 0004 (rejected option), 0005 (BPA-25 checks) |
| S16 | Data model | 0010 |
| S17 | Flyway items 11 and 12 | 0010 |
| S18 | Excel files | 0009 |
| S19 | File intake (confirmed as D58) | 0009 |
| S20 | OpenAPI | 0011 |
| S21 | OpenAPI tooling | 0011 |
| S22 | Persona sync | 0005 |
| S23 | Base Java package | 0002 |
| S24 | VM sizing (deferred) | 0001 |
| S25 | Service identity | 0005 |
| S26 | CSRF protection | 0005 |
| S27 | Session handling | 0005 |
| S28 | Mock D365 endpoint access | 0005 (also 0011: `security: []`) |
| S29 | Camunda web apps and database access | 0005 |
| Spring Boot note | Workers are Spring Boot (09 §1, closes 08 R7) | 0002 |
| L1 | Repo layout | 0003 (amended by 0015) |
| L2 | S6 vs S22 | 0005 |
| L3 | ADRs | no ADR: the ADR scheme itself, applied by this index |
| L4 | Walking skeleton: login | 0005 |
| L5 | Walking skeleton: database | 0010 |
| L6 | Excel template | 0009 |
| L7 | How Claude delivers | 0016 |
| L8 | Seed scope | 0010 |
| L9 | personas.yaml | 0005 |
| L10 | Model updates | 0008 (`assignment_state`), 0014 (lint and CI) |
| L11 | Data model files | 0010 |
| L12 | OpenAPI scope | 0011 |
| L13 | Protect main | 0016 |
| L14 | Harness | 0015 (plan.md, replaced), 0016 (`.mcp.json`, `qa-reviewer`) |
| L15 | Test-first rule | 0014 |
| L16 | 04-conventions additions | no ADR: conventions, in `docs/04-conventions.md` |
| L17 | Copy of 09 in the repo | 0003 |
| L18 | BPA-25 | 0005 |
| L19 | Assigned hands-on work | no ADR: assigned work, not a decision |
| L20 | Demo files | 0009 |
