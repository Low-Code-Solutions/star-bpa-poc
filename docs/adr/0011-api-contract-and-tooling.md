# 0011 API contract and tooling

- Status: Accepted
- Date: 2026-10-27 (decided, 09 LOCKED); written 2026-10-29 (BPA-37)
- Deciders: Ahsan (Build Lead), Usama (Lead Developer)
- Informed: Faraz (Product Owner and Architect), Khitaba (BA)
- 09 rows: S20, S21; L12

## Context

The design pack listed five endpoints (07 §10), enough for tests to assert on. Moving all task handling to
React (S2, ADR 0004) and the file intake to an upload (S19, ADR 0009) means the portal needs login, task,
reference-list, start and upload endpoints too. Stories carry exact error messages that the API must
return, and React and the backend must agree on the same contract.

## Options considered

1. **The five endpoints of 07 §10** (hour-journal headers and lines, print, drawing revisions,
   notifications). Rejected as the whole API: superseded by S20 (09 §3).
2. **One contract-first OpenAPI file for every endpoint** (S20). Chosen.

The sources record no other option for the contract style or for the S21 tools.

## Decision

- **One contract-first `api/openapi.yaml` (OpenAPI 3.1)** under `/api/v1` (S20): the original five
  (07 §10) plus `POST auth/login`, `POST auth/logout`, `GET me`; the task API (S14); reference lists
  `GET activities`, `drawings`, `materials`, `departments`, `workpacks/{n}/look-ahead`; `POST workpacks`
  (process start from the portal, replacing the Tasklist start of 07 G5); assignment upload
  `POST assignment-files` and `GET assignment-files/{id}/result` (added once S19 is confirmed).
- **Errors:** RFC 9457 problem details carrying the stories' exact messages. **Security:** session-cookie
  scheme (ADR 0005). **Dates:** ISO 8601 (ADR 0013).
- `POST drawing-revisions` is documented as a POC trigger standing in for a D365 Document Control event,
  with `security: []` (S20, S28).
- **Order and ownership:** drafted after the data model; Usama drafts, Ahsan reviews (S20; 09 §4 item 4).
- **Tooling (S21):** backend `openapi-generator` Maven plugin (`spring`, interface-only, Spring Boot 3);
  React `openapi-typescript` + `openapi-fetch`; CI lints with Redocly CLI; Swagger UI serves the same YAML.
- **Scope rule (L12):** the `assignment-files` endpoints are left out until Khitaba confirms S19. Error
  messages come from the stories' text in Jira; any message not found in a story gets
  `x-assumption: true` and goes on a list for Khitaba.

## Consequences

- S19 was confirmed on 27 Oct as D58 (ADR 0009), so the L12 condition for the `assignment-files`
  endpoints is met.
- Stories that add an endpoint add it to the YAML in the same PR (spec.md shared contracts, PROPOSED).
- Later changes to individual endpoints (for example D44's read endpoint, now
  `GET /api/v1/me/notifications` per BPA-16) come from the stories, which take precedence (CLAUDE.md).
- `api/` is empty as of 29 Oct; the draft follows the data-model approval (ADR 0010).

## Sources

09 S2, S14, S19, S20, S21, S28, L12, §3, §4 item 4; 07 §10, §11 G5; 06 D44, D58;
`intent/BPA-3-work-pack-assignment/spec.md`.
