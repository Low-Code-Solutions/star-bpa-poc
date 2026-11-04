# Backend (backend/): rules for Claude Code

Read with the root CLAUDE.md, which comes first. To add a Camunda job worker, use the `new-job-worker` skill.

## Layout
- Spring Boot 3.5, Java 21, Camunda Spring Boot 3 starter 8.10.1 (ADR 0001, 0002). Base package `com.lcs.star.bpa` with
  `api`, `task`, `worker`, `domain`, `persistence`, `adapter`, `config` (09 S23).
- Controllers implement the interfaces generated from `../api/openapi.yaml` (`com.lcs.star.bpa.api.generated`, S21,
  ADR 0011); the contract changes only in the story's PR.
- Models are read from `../models/` (never copied into backend/, ADR 0003). Migrations: Flyway in
  `src/main/resources/db/migration/` (`V1__baseline_schema.sql`, `R__seed_reference_data.sql`; ADR 0010).

## Rules
- **Camunda calls are made as `bpa-service`** (09 S25): job workers through the starter, the task API and process start
  through REST adapters in `adapter/` (`CamundaTaskClient`, `CamundaProcessClient`), using only calls BPA-25 proved or a
  later story checked.
- **The task API** (`task/TaskService`) checks 404 → 403 → 409 → 400 (allow-list in `application.yml`, `bpa.tasks`),
  then assigns to the session user and completes. It adds `completedBy` (ADR 0004 amendment, D63).
- **Job workers** follow `worker/SaveWorkPackWorker` (BPA-6): idempotent on the process instance key before any check
  (D65, S7, ADR 0007); a BPMN error with `validationMessages` for invalid input (G2, D56, ADR 0006); `created_by` from
  `completedBy`, else `bpa-service` (D63, data model §1.2); one `audit_log` row per business column (D64, data model §6.1);
  business dates from the injected `Clock` in `app.business-zone` (S9, ADR 0013). Details: skill `new-job-worker`.
- **Do not add Micrometer.** `micrometer-core` stops job workers in starter 8.10.1; the pom's enforcer bans it (BPA-43).
- **Errors** are RFC 9457 problem details with the story's exact text; unknown texts are marked ASSUMPTION (S20, L12).

## Tests
- One test per Gherkin scenario, `BPA<n>_<camelCaseScenario>`, shown failing first (L15, ADR 0014).
- App-against-Camunda tests use `IntegrationApp.background()` (persona sync, models, demo admin) and `IntegrationCamunda`
  (Camunda 8.10.1 configured like compose). Tests with job workers on point the app at it (`camunda.client.grpc-address`,
  `rest-address`), use a settable clock, and close the context after the class (`@DirtiesContext`), so their workers do not
  take other classes' jobs (`WorkPackApiTest`).
- `ProcessTestApplication` (Camunda Process Test) has no database. Leave database workers out of its scan.
- Run everything with `./mvnw -B verify` (CI runs the same).
