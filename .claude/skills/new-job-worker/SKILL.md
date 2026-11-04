---
name: new-job-worker
description: Adds a Camunda 8 job worker to the Spring Boot backend the way BPA-6 built save-workpack. Covers idempotency on the process instance key, the BPMN error with validationMessages, created_by from completedBy, Asia/Riyadh dates from the injected clock, audit rows and the integration test pattern. Use when a story adds or implements a ServiceTask_* job type (save-workpack-schedule, record-release-signoff, create-hour-journal-header, ...).
---

# New job worker

Follow `backend/CLAUDE.md`. The story's plan comes first (root CLAUDE.md). Every rule below cites its source; do not add
rules the sources do not give. Worked example: `backend/src/main/java/com/lcs/star/bpa/worker/SaveWorkPackWorker.java`.

## 1. Read the sources
- The story: AC (every message text, exactly), BPMN Reference, SOR Notes.
- The model: `models/bpmn/<process>.bpmn`. Note the element's `zeebe:taskDefinition type`, its boundary error event and
  `errorCode` (BPA-6: `Error_WorkPackInvalid`, `WORKPACK_INVALID`), and the output mapping (`validationMessages`).
- Tables and columns: `docs/data-model.md` (SOR per column, unique keys, CHECKs, the audit rules in §1.2 and §6.1).

## 2. Write the tests first (L15, ADR 0014)
- One test per Gherkin scenario, `BPA<n>_<camelCaseScenarioTitle>`, in a class like `WorkPackApiTest`. Show each test
  failing for the expected reason before writing the worker.
- The pattern (`WorkPackApiTest`):
  - `@SpringBootTest(RANDOM_PORT)` with `@Tag("integration")` and `@Testcontainers`. In `@DynamicPropertySource`, call
    `IntegrationApp.background()` and point `camunda.client.rest-address`, `camunda.client.grpc-address`,
    `bpa.camunda.rest-url` and `camunda.client.auth.password` at `IntegrationCamunda`, so the app's worker runs for real.
  - `@DirtiesContext(classMode = AFTER_CLASS)`, so this class's workers stop before other classes run.
  - A settable `Clock` bean (`TestClock`, `@Primary`) for "Given today is …".
  - Drive the process as React does: log in with `TestBrowser`, then call `/api/v1/...` and the task API; poll, since
    Camunda's search is eventually consistent (BPA-25).
  - A retry scenario calls the worker's `handle` twice with a mocked `JobClient` (`RETURNS_DEEP_STUBS`) and `ActivatedJob`.
    Use an instance key Camunda cannot generate itself (partition 2's range, e.g. 4503599627370497).
- If `ProcessTestApplication` would pick up a worker that needs the database, exclude it there (BPA-6).

## 3. Build the worker (`worker/<Name>Worker.java`)
- `@Component` with `@JobWorker(type = "<type>", autoComplete = false)` and `handle(JobClient client, ActivatedJob job)`.
  Do not add custom retries or backoff: Camunda defaults apply (S7, ADR 0007).
- **Idempotent on the business key, checked first** (D65, 09 S7, ADR 0007). For a row per instance, look it up by
  `process_instance_key` (a UNIQUE column) before any validation. If it exists, complete the job with it. Insert with
  `ON CONFLICT (...) DO NOTHING` and re-read, so a racing retry leaves one row (`WorkPackRepository.save`). A rerun must
  never fail on data that has since changed (BPA-6 review: a "today" date past midnight).
- **Invalid input → BPMN error, not a failure** (G2, D56, ADR 0006): collect every message (exact AC texts) and call
  `client.newThrowErrorCommand(key).errorCode("<CODE>").variables(Map.of("validationMessages", messages))`. The boundary
  event returns the user task, which shows them. On success, complete with `validationMessages: []` (BPA-23).
- **Who did it** (D63, data model §1.2 B12, ADR 0004 amendment): read the process variable `completedBy` (set by the task
  API on every completion) and use it for `created_by`, `updated_by` and `audit_log.username`. Fall back to `bpa-service`
  when it is missing.
- **Audit** (D64, data model §6.1): one `audit_log` row per changed business column, in the same transaction. Old value
  is null on insert. Values in the canonical text form (numbers with `stripTrailingZeros().toPlainString()`, dates ISO).
  The four audit columns are not logged.
- **Time** (09 S9, ADR 0013): inject `Clock` and the `ZoneId` bean (`app.business-zone`, Asia/Riyadh). Business dates are
  `LocalDate.ofInstant(clock.instant(), businessZone)`; code does not read the system clock (ADR 0013). Write the audit
  columns and `changed_at` from `clock.instant()` rather than the tables' `now()` default (one clock; BPA-6 review). No
  `today()`/`now()` in FEEL or DMN: the worker passes today (S9).
- **Variables:** read them with `job.getVariablesAsMap()`. Numbers arrive as Integer, Long or Double; turn them into
  `BigDecimal` with `new BigDecimal(value.toString())`. A value of the wrong type reads as missing.
- **A job type shared with a step a later story builds.** Until that story exists, fail such jobs with no retries and a
  plain message, so Operate shows an incident and nothing is saved (Ahsan, 4 Nov 2026; BPA-6 plan). The one use,
  `save-workpack` on `ServiceTask_SaveWorkPackCorrections`, **ended with BPA-23** (5 Nov 2026), which built that path.
  Use the pattern again only for a job type that has such a later story.
- **Corrections update rows in place** (data model F13, A4; BPA-23, `WorkPackRepository.correct`). Match child rows by
  their natural key (phases by name) or by the `id` the screen round-trips (material lines). Update changed columns,
  insert new rows and delete only those no longer present; never delete-and-reinsert, which would change ids and break the
  audit row. One audit row per changed column, old → new. Applying the same correction twice changes nothing, which is
  what makes a retried job harmless.
- Repository code in `persistence/` (JdbcTemplate, `@Transactional`), pure checks in `domain/` (as `WorkPackValidator`).
- Do not add Micrometer (pom enforcer; BPA-43).

## 4. Check
- `./mvnw -B verify` (on Windows, see the story plan's Human-gap log for the path-with-a-space work-round).
- On the local stack (`docker compose up -d --build bpa-app`): run the process to the element, then check the row, the
  audit rows, and in Operate that the element completed or looped back through the boundary event.
