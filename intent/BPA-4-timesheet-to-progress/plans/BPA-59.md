# Plan: BPA-59 PPC Team can upload the weekly FMS completion report and see each row's outcome
Jira: BPA-59 (Story, epic BPA-4)
Story last updated: 2026-11-19T19:17:08.945+0400
Status: Closed (19 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-59-completion-report-upload, from main f11edaa (plus the inputs commit 596bae1)


## Context
After BPA-58 the weekly instance waits at `UserTask_UploadCompletionReport`, and nothing serves that task. PPC gets FMS's completion report by email or Excel and keys the progress in by hand. BPA-59 lets PPC do three things:
- upload the report (.xlsx or .csv) into the week's task;
- see an outcome for every row;
- complete the task, so the Read rows set `workpack_progress.reported_pct` for that week. Approval of the % is BPA-60, and the 100% and last-week checks are BPA-61.

## Readiness check (Definition of Ready, docs/02, against Jira 19 Nov 18:06)
| Check | State |
|---|---|
| Jira status | **Ready** (comment 10550). No transition without asking (Q6) |
| Template, persona, outcome | Met |
| Gherkin incl. unhappy paths | Met: 12 scenarios (2 Scenario Outlines) |
| BPMN element IDs exist | Met: `Gateway_MergeReport` → `UserTask_UploadCompletionReport` (ppc-team; documentation: "Task output completionReportId") → `ServiceTask_ReadCompletionReport` (`read-completion-report`) → `Gateway_MergeApproval` → `UserTask_ApproveCompletion`. `BoundaryEvent_ReportInvalid` (REPORT_INVALID, output `validationMessages`) → `Gateway_MergeReport`. No BPMN change needed |
| Every field has an SOR | Met (SOR Notes; data model §13.3.3, §13.3.4, Addendum 3). One gap: raw logging of a bad Source (Q1) |
| DMN | None |
| No blocking question | Q1 (migration), Q7 (where the rows are checked) and Q8 (how the upload names its week) change the build |
| intent.md / spec.md accepted | **Not met** (Jira DoR box unchecked) |
| Estimate ≤ 8 | Met (5). It is tight with a shared file-intake refactor, a worker, an endpoint pair and a screen |

## What already exists (closed plans BPA-52, BPA-58; history only)
- **Schema (V2), with no file column:**
  - `completion_report`: `progress_period_id`, `file_name`, `sha256`, `uploaded_by`, `received_at`, `outcome` NULL.
  - `completion_report_row`: `row_number`, `workpack_number`, `completion_pct_raw`, `actual_hours_raw`, `outcome` NOT NULL, `source` NOT NULL CHECK (FMS, PMS).
  - `workpack_progress`: UNIQUE (`workpack_id`, `progress_period_id`); `reported_pct` numeric(5,2) NULL with no range CHECK, SOR FMS; `previous_pct` NOT NULL DEFAULT 0.
- **BPA-52 intake** (`timesheet/TimesheetFileReader`):
  - .xlsx (first sheet) or .csv (RFC 4180).
  - Headers matched trimmed and case-insensitive, in any order.
  - File outcomes "File cannot be read", "File layout not recognised", "File has no rows", "File has too many rows" (5000).
  - 413 "File is larger than 5 MB" from `UploadLimitAdvice` and `spring.servlet.multipart.max-file-size: 5MB`.
  - Its `REQUIRED` list and `Row` record are time-sheet specific.
- **BPA-58:**
  - `progress_period` is opened by `OpenProgressPeriodWorker` with the instance key.
  - The weekly process is on the task API's `process-ids`, and bpa-service is authorised on it.
  - `UserTask_SendHoursToFms` → `UserTask_UploadCompletionReport`.
- **`bpa.progress.source: FMS`** in `application.yml` (BPA-48). Nothing reads it yet.
- **Patterns to reuse:**
  - BPMN error with `validationMessages` (`SaveWorkPackWorker`, ADR 0006).
  - Re-run keys (ADR 0007).
  - Task screens on `TaskScreenShell` (skill new-task-screen); upload and result-table components from BPA-52 (`TimesheetFileUpload`).
  - `TestClock`; `startInstructions` to start at a task.

## Design
### 1. Contract first (`api/openapi.yaml`)
- **`POST /api/v1/completion-reports`** (multipart, ppc-team):
  - Parts `file` (.xlsx or .csv, at most 5 MB) and `taskKey` (the open `UserTask_UploadCompletionReport`, see Q8).
  - **201** with the result `{id, fileName, weekEnding, fileOutcome, rows: [{rowNumber, workpackNumber, completionPct, actualHours, source, outcome}]}`.
  - **400** "File is required"; **413** "File is larger than 5 MB"; **403**; **404** "Task not found" (texts as BPA-52).
- **`GET /api/v1/completion-reports/{id}/result`**: the same result (ppc-team).
- **`TaskSummary.elementId`** gains `UserTask_UploadCompletionReport`. The complete-variables description gains `completionReportId`.

### 2. Shared file intake, not a copy (BPA-52)
- Extract the format-level part of `TimesheetFileReader` into **`fileintake/TabularFileReader`**: xlsx or csv → header map → raw text rows, plus the four file outcomes. It takes the required and optional header names.
- `TimesheetFileReader` delegates to it, with its own required list and `Row`. **BPA-52's tests stay unchanged and green.**
- **Completion layout:** required `workpack number`, `completion %`, `source`; optional `actual hours`. Matching is trimmed and case-insensitive (BPA-52 rule, Q4), so Jira's "Workpack Number / Actual Hours" and the template's "Workpack number / Actual hours" both match.

### 3. Upload = intake and row check (recommended Q7 A)
`progress/CompletionReportService.upload(taskKey, fileName, bytes, username)`:
1. **Resolve the week.** The task must be an open `UserTask_UploadCompletionReport` that the user's group may work, else 404 or 403 as the task API. Its `processInstanceKey` gives `progress_period` (`process_instance_key`), and the period must be CutOff (decision (d): the report belongs to the week of the instance).
2. **Log the file.** Insert `completion_report` (sha256, `received_at`, `outcome` = the file outcome or NULL).
3. **Check each row** in file order and log it raw with its outcome. The texts are Jira's, unless marked:
   - Required values: "Workpack number is required", "Completion % is required", "Source is required" (**ASSUMPTION**, BPA-52 style).
   - "Work Pack not found".
   - "Work Pack is not In Progress" (any other status).
   - "Duplicate row for <WP>": an earlier row in the file names the same Work Pack, whatever its outcome (**ASSUMPTION**, Q11).
   - "Completion % must be a number".
   - "Completion % can have at most 2 decimal places".
   - Out of storable range (|x| ≥ 1000, `numeric(5,2)`): text **ASSUMPTION** (Q9).
   - Actual hours, when not empty, must be a number ≥ 0: text **ASSUMPTION** (Q9).
   - "Source <x> is not the configured progress source", with <x> as written. A blank source gets "Source is required".
   - Otherwise **"Read"**. 120% and negative % are Read (BPA-61 blocks them, decision (e)).
- **Cell reading:** decimal places are judged on the cell's own value, not Excel's display, so a numeric 40.125 is caught. This is BPA-52's hours reading.
- **Same transaction:** the report and its rows are written in one transaction. Each upload is a new report, so uploading again in the same task is allowed (Q-DM20).

### 4. Completing the task
- **`TaskValidity`:** `completionReportId` is required: "Completion report is required" (**ASSUMPTION**).
- **`CompletionReportCheck`** (task package, as `FmsHandOffCheck`): the report must belong to this task's week, else 400 "Completion report is not for this week" (**ASSUMPTION**).
- **Allow-list:** write `[completionReportId]`; read `[weekEnding]`. `validationMessages` has its own field.

### 5. `read-completion-report` (ReadCompletionReportWorker)
- **Input:** `completionReportId`, which must belong to this instance's period. Otherwise the job fails with no retries.
- **No usable row:** if the report has no Read row (file outcome, or every row rejected), throw **REPORT_INVALID** with `validationMessages` = ["No usable rows in the completion report"] (Jira). Set `completion_report.outcome` = `Invalid` (**ASSUMPTION** value). The boundary event returns the instance to the upload task.
- **Otherwise accept it:**
  - Set `completion_report.outcome` = `Accepted`.
  - Upsert `workpack_progress` (`workpack_id`, period) with `reported_pct` from each Read row (SOR FMS).
  - Work Packs of the period that are not in this report get `reported_pct` = NULL, only while `approved_pct` is NULL (Q10). The latest accepted report wins (Q-DM20).
  - One transaction; audit rows per column (D64).
- **Re-run key:** the report's `outcome`. An Accepted report is not applied twice and an Invalid one throws again. The worker never creates rows, so a re-run can't double them.

### 6. Raw logging of Source (Q1, needs your yes)
- **The gap:** `completion_report_row.source` is NOT NULL CHECK (FMS, PMS), so a blank or unknown Source can't be logged.
- **Recommended migration `V3__completion_row_source_raw.sql`:** add `source_raw text NULL` (SOR FMS, written always), and make `source` NULL (the CHECK stays, set only for FMS or PMS).
- Data model §13.3.3 and Addendum 3 get the change.

### 7. Settings
- **`ProgressSettings`** binds `bpa.progress.source` (FMS or PMS).
- Tests replace it with a settable bean (as `TestClock`), so the Scenario Outline runs its three examples in one test.

### 8. Frontend (skill new-task-screen)
- **`UploadCompletionReport` screen** on `TaskScreenShell`:
  - "Week ending <date>", the file picker and **Upload report**.
  - The result table (row, Workpack number, Completion %, Actual hours, Source, Outcome) and the file outcome.
  - The shell's `validationMessages`, after a REPORT_INVALID return.
- **Completing** sends `{completionReportId}` of the latest upload in the screen. "Complete task" is blocked until a report is uploaded: "Completion report is required".
- Registered in `taskScreens.ts`, `constants.ts`. `TaskList` shows "Completion report sent" (**ASSUMPTION**).

### 9. Templates, fixtures and demo
- `templates/README.md`: a section for `completion-report-template.xlsx` (sheet `Completion`, columns, rules, ASSUMPTION) and the two demo rows.
- **Test fixtures** (comment 10490): `backend/src/test/resources/fms/` holds `fms-completion-wk47.xlsx` (the scenario 1 rows) and `r.xlsx` (a text file renamed). Both are made by a test-source `CompletionReportBuilder`, as `TimesheetFileBuilder`. Other files are built in the test. **Tests never use the demo files.**
- **Demo bad-rows** (Q3): rewritten by the builder so each rule shows: unknown WP, 120%, 40.125, "forty", a duplicate and a PMS row. It still names WP-000001 and WP-000002, with a README note to replace them.

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml` |
| Migration (Q1) | `backend/src/main/resources/db/migration/V3__completion_row_source_raw.sql`; `docs/data-model.md` |
| Intake | new `fileintake/TabularFileReader.java`; `timesheet/TimesheetFileReader.java` delegates |
| Progress | `progress/CompletionReportService.java`, `progress/CompletionRowChecks.java` (pure), `config/ProgressSettings.java` |
| Persistence | `persistence/CompletionReportRepository.java`, `persistence/WorkpackProgressRepository.java` |
| Worker | `worker/ReadCompletionReportWorker.java`; `bpatest/ProcessTestApplication` excludes it (test support) |
| API, task, security | `api/CompletionReportController.java`; `task/TaskValidity.java`, `task/CompletionReportCheck.java`, `task/TaskService.java`, `task/TaskConfig.java`; `application.yml` allow-list; `config/SecurityConfig.java` (`/api/v1/completion-reports/**` → ppc-team) |
| Frontend | `features/progress/components/UploadCompletionReport/*` (+ test), `features/progress/services/completionReportService.ts`, `taskScreens.ts`, `constants.ts`, `TaskList.tsx`; `npm run gen:api` |
| Tests | new `CompletionReportApiTest` (12 BPA59_ + extras), `CompletionRowChecksTest`, `CompletionReportBuilder`; fixtures `src/test/resources/fms/*`; `TaskValidityTest` row; BPA-52 tests unchanged |
| Docs | `templates/README.md`, `templates/demo/completion-demo-bad-rows.xlsx` (Q3), data model notes, plan, REVIEW.md, `intent/README.md` |

Not changed: BPMN, DMN, the seed.

## Order of work
1. Approve the plan; commit it (`Approved`) on top of 596bae1; regenerate `intent/README.md`. No Jira transition without asking (Q6).
2. Contract, fixtures and the 12 BPA59_ tests. Run them red **before any production code**, committed separately; the React red run gets its own commit. Expected:
   - Upload calls get 404 (no endpoint): scenarios 1–6, 8, 10–12.
   - Scenario 7 fails on its Given (no report to complete with).
   - Scenario 9 (supervisor 403) fails because unknown paths answer 404, not 403.
3. Then, in this order:
   - (a) the V3 migration (if Q1 yes);
   - (b) `TabularFileReader` extraction, with BPA-52's suite run green before going on;
   - (c) service, checks, repository, controller, security;
   - (d) worker, task rules, allow-list.
   Green: the class, then `TimesheetFileApiTest`, `TimesheetFileReaderTest`, `TaskApiTest`, `TaskValidityTest`, `FmsHoursExportApiTest`.
4. Frontend: tests red and committed, then the screen. Lint, format, test, build.
5. Docs, full `./mvnw -B verify`, local stack proof, `qa-reviewer`, fixes, REVIEW.md, plan Closed. Push, PR and Jira comment only when asked.

## Scenario-to-test table
**`CompletionReportApiTest` setup:**
- **Clock and fixture:** 20 Nov 2026 Asia/Riyadh. `BookHoursFixture.reset()` plus `completionBackground()`:
  - WP-000457 and WP-000461 **In Progress**;
  - WP-000480 Released, no hours.
- **Weekly instance:** started at `UserTask_UploadCompletionReport` with `startInstructions`, then a `progress_period` row (week ending 2026-11-19, CutOff) inserted with that instance's key. The instance "waits at UserTask_UploadCompletionReport".
- **Upload:** `POST /api/v1/completion-reports` as ppc-team with that task's key. **Complete:** `POST /api/v1/tasks/{key}/complete` with `completionReportId`.

| # | Scenario | Test | Asserts | Expected first run |
|---|---|---|---|---|
| 1 | A valid report is read | `BPA59_validReportIsRead` | upload `fms/fms-completion-wk47.xlsx` → 201, both rows "Read"; complete → `workpack_progress` WP-000457 40.00, WP-000461 100.00 for the week; instance at `UserTask_ApproveCompletion` | red: 404 |
| 2 | Unknown Work Pack row | `BPA59_unknownWorkPackRow` | row WP-999999 → "Work Pack not found" | red |
| 3 | Not In Progress (ASSUMPTION) | `BPA59_workPackNotInProgressRow` | WP-000480 (Released) → "Work Pack is not In Progress" | red |
| 4 | Completion % must be a number | `BPA59_completionNotANumberRow` | "forty" → "Completion % must be a number" | red |
| 5 | At most 2 decimals | `BPA59_completionThreeDecimalsRow` | 40.125 (numeric cell) → "Completion % can have at most 2 decimal places" | red |
| 6 | Over 100% is read | `BPA59_over100IsReadForApproval` | 120 → "Read" | red |
| 7 | No usable row goes back | `BPA59_reportWithNoUsableRowGoesBack` | only WP-999999; complete → instance back at `UserTask_UploadCompletionReport`; the task's `validationMessages` contains "No usable rows in the completion report"; report `outcome` Invalid | red on its Given |
| 8 | Latest upload is used (Q2) | `BPA59_latestUploadIsUsed` | upload 40% (report 1), upload 45% (report 2) in the same open task; complete with report 2 → WP-000457 reported 45.00 | red |
| 9 | Other personas cannot upload | `BPA59_otherPersonasCannotUploadReport` | supervisor POST → 403; no report row | red (404) |
| 10 | Duplicate row | `BPA59_duplicateWorkPackRowIsRejected` | 40% then 45% → row 1 "Read" (40), row 2 "Duplicate row for WP-000457" | red |
| 11 | Outline: unreadable or large file | `BPA59_unreadableOrLargeFileIsRefused` | `fms/r.xlsx` (text) → 201, file outcome "File cannot be read", no rows; a 6 MB file → 413 "File is larger than 5 MB" | red |
| 12 | Outline: configured source | `BPA59_theConfiguredProgressSourceIsAccepted` | three examples in one test, with the settable `ProgressSettings`: FMS/FMS Read; PMS/PMS Read; FMS/PMS "Source PMS is not the configured progress source" | red |

Extra tests (not scenarios):
- **Integration:**
  - `aReportForAnotherWeekIsRefusedAtCompletion` (400).
  - `completingWithoutAReportIsRefused` (400 "Completion report is required").
  - `aRerunOfReadCompletionReportChangesNothing`.
  - `anAcceptedReportClearsWorkPacksMissingFromIt` (Q10).
  - `aBlankSourceIsLoggedRaw` (Q1).
  - `anUnknownColumnOrderIsRead`.
  - `theResultCanBeReadAgain` (GET).
- **Unit:** `CompletionRowChecksTest` (each text, check order, 999.99 bound, Actual hours empty, negative and text).
- **React:** the screen (upload shows the table; Complete is blocked without a report; `validationMessages` shown).
- **BPA-52:** suites unchanged.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=CompletionReportApiTest test`; `grep -c "void BPA59_" backend/src/test/java/com/lcs/star/bpa/CompletionReportApiTest.java` = 12; `gh pr checks <n>` |
| Endpoints in api/openapi.yaml | `grep -n "completion-reports" api/openapi.yaml`; Redocly lint |
| Process runs end to end locally | run below |
| BPMN and DMN match deployed | no model change; deployed `Process_PPC_WeeklyProgressPeriod` XML equals `models/` (whitespace-normalised, as BPA-58) |
| Open questions resolved or logged | Questions below, as Jira comments (shown first) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-59.md` |
| Demoed at sprint review | Human gap |

**End-to-end run in the local stack:**
1. Rebuild `bpa-app` and `frontend` (plain `docker build`, as BPA-58).
2. Weekly instance: the cut-off (by hand, or the cron), then as ppc-team "Send hours to FMS" → Sent to FMS.
3. As ppc-team, open **Upload FMS completion report** and upload `completion-demo-good.xlsx` with WP-000001 and WP-000002 replaced by two In Progress Work Packs. Both rows show Read. Complete.
4. **Operate:** the instance waits at `UserTask_ApproveCompletion`.
5. **`psql`:** `completion_report` Accepted; two `workpack_progress` rows with `reported_pct` 40.00 and 100.00 for the week.
6. **Unhappy run:** upload `completion-demo-bad-rows.xlsx` and see each outcome. Completing with a report that has no Read row returns to the upload task with "No usable rows in the completion report".

## Risks
- **The BPA-52 intake refactor** touches a merged, tested path. Mitigation: extraction only, its suites run before any BPA-59 code is built on it.
- **Week from the task:** the upload must name the task (Q8). A wrong task gives 404 or 403. Two open weeks (BPA-58 Q4) are told apart by the task key.
- **The rows are checked at upload (Q7 A), not by the worker** as the BPMN documentation says ("parses rows … logs each row"). The worker applies and decides "usable". If Q7 B is chosen instead, it needs a file column (another migration) and the screen can't show outcomes before completion.
- **Status at upload vs at completion:** a Work Pack that leaves In Progress between the two keeps its Read outcome (ASSUMPTION; minutes apart in practice).
- **Estimate 5** is tight for the refactor, the worker, two endpoints and a screen.

## Questions
1. **Raw logging of Source.** `completion_report_row.source` is NOT NULL CHECK (FMS, PMS), so a blank or unknown Source can't be logged raw.
   - **Recommended:** V3 migration adds `source_raw text NULL` (SOR FMS, always written) and makes `source` NULL; the CHECK stays and `source` is set only for FMS or PMS.
   - A blank Source gets "Source is required" (ASSUMPTION). An unknown one ("XYZ") gets Jira's "Source XYZ is not the configured progress source".
   - Alternative without a migration: refuse such a row without logging it, which loses the intake log's "every row as written".
   - Migration only on your yes.
2. **"Latest upload is used"** ("it returned for another reason"): the only way back to the upload task is REPORT_INVALID, and a report whose WP-000457 row is Read is never invalid. With Q7 A the first report's 40% is **never stored**: `reported_pct` is written only when an accepted report is completed.
   - Recommended test: two uploads in the same open task, completed with the second, gives 45.
   - Extra test: a first completion that returns as REPORT_INVALID, then a good upload, is read.
   - Agree with this reading?
3. **Demo bad-rows.** WP-000001 appears twice (120, then 40.125), so the duplicate rule hides the decimals message.
   - Recommended: rewrite the file with the test builder so every rule shows once (unknown WP, 120%, 40.125, "forty", an explicit duplicate, a PMS row).
   - It keeps WP-000001 and WP-000002, with the README note to replace them.
4. **Header names:** Jira "Workpack Number / Actual Hours", template "Workpack number / Actual hours". BPA-52's rule (trimmed, case-insensitive, any order) matches both.
   - Required: Workpack number, Completion %, Source. Actual hours may be missing as a column (ASSUMPTION).
   - A missing required header gives "File layout not recognised" (BPA-52's text).
5. **Actual Hours:** kept only as `completion_report_row.actual_hours_raw`. BPA-60 reads them raw from the latest accepted report for reconciliation (information only, decision (a)). No new column now. Confirm.
6. **Jira:** BPA-59 is Ready. I will ask before any transition.
7. **Where the rows are checked.** You wrote "read-completion-report checks each row", but `completion_report` has no file column, so the worker can't re-read the file.
   - **Recommended (A):** the upload endpoint checks and logs every row (BPA-52's pattern, "201 with the result"), so PPC sees the outcomes before completing. The worker then decides "usable", applies the Read rows and throws REPORT_INVALID. No extra migration.
   - (B): the worker checks. This needs `completion_report.file bytea` (a migration) or placeholder row outcomes, and the screen shows outcomes only after completion.
8. **How the upload knows its week:** a `taskKey` multipart part naming the open `UserTask_UploadCompletionReport` (ASSUMPTION, not in Jira). Its instance gives the `progress_period`. Needed because two weeks' tasks can be open at once (BPA-58).
9. **Texts not in Jira (ASSUMPTION):**
   - "Workpack number is required", "Completion % is required", "Source is required";
   - Actual hours: "Actual hours must be a number of 0 or more";
   - out of range: "Completion % must be between -999.99 and 999.99" (the column is `numeric(5,2)`);
   - "Completion report is required", "Completion report is not for this week";
   - `completion_report.outcome` values `Accepted` / `Invalid`;
   - "Completion report sent" (TaskList).
10. **A later accepted report and missing Work Packs:** recommended **yes, it replaces**. Work Packs of the week that are not in it get `reported_pct` = NULL, while not yet approved. Within one instance only one report is accepted (the flow goes on), so this matters for re-runs and data fixes.
11. **Duplicate rule:** a row is a duplicate when an earlier row of the file names the same Work Pack, **whatever that row's outcome** (ASSUMPTION). Jira's example has the first row Read.

## Human-gap log
- Completion report template and demo files staged from the Epic 2 draft pack by Ahsan via Claude before the plan (commit 596bae1), a departure from ADR 0015.

## Resolutions (19 Nov 2026)
Approved with the recommended answers: Q1 the V3 migration (`source_raw`, `source` NULL); Q2 two uploads in one task, completed with the second; Q3 the demo bad-rows file rewritten by the builder; Q4 BPA-52 header rule; Q5 no new column; Q7 option A (rows checked at upload); Q8 a `taskKey` part; Q9 to Q11 as written (ASSUMPTIONs to comment in Jira after a yes).

## Progress and departures (19 Nov 2026)
Approved with the recommended answers (Resolutions above).

**Red runs** (committed apart, 7f11d9a and 4bf23f9):
- `CompletionReportApiTest`: 20 of 20 failed. 404 "No static resource api/v1/completion-reports" on the upload, or on the Given of the scenarios that need an uploaded report (the report id could not be read); the supervisor's expected 403 got 404.
- `TaskValidityTest.uploadCompletionReportNeedsAReport` failed (no rule for the element).
- React `UploadCompletionReport.test.tsx`: "Failed to resolve import ./UploadCompletionReport".
- Green after the implementation: `CompletionReportApiTest` 20/20 on the first run; BPA-52's `TimesheetFileReaderTest` (4) and `TimesheetFileApiTest` (21) passed against the extracted reader before any BPA-59 code was built on it (f447d05).

**Departures from the plan:**
1. **The progress source is read from the environment at each call, not from a settable bean.** `config/ProgressSettings` reads `bpa.progress.source` through `Environment` each time, so the Scenario Outline's three examples run in one test by adding a `MapPropertySource` (no test-only bean, and no production class needed for the red run to compile).
2. **`UploadTaskResolver`** (task package) is a new class: the open upload task, its group and its instance, answered with the task API's texts. A task with no `progress_period` row answers 409 "The week of this task has no progress period" (ASSUMPTION text). A request with no `taskKey` gets the framework's 400.
3. **`TabularFileReader` returns rows as a map** (`TabularRow(rowNumber, values)` keyed by lower-case column name), so both uploads share it; `TimesheetFileReader` maps them back to its typed `Row`.
4. **Existing test support edited (to say in the PR):** `TestBrowser.postMultipart` added (text parts plus the file part); `BookHoursFixture` clears `completion_report_row`, `completion_report` and `workpack_progress` and gained `completionBackground`; `bpatest/ProcessTestApplication` excludes `ReadCompletionReportWorker`. No existing test was changed to make it pass.
5. **Demo bad-rows (Q3)** names `WP-000001` to `WP-000004` (not only the first two), because showing each rule once needs more Work Packs than two. The README says to replace them.
6. **`CompletionRowChecksTest` (9) and `aRerunOfReadCompletionReportChangesNothing`** were written with the implementation, with no red run (they need the new classes).
7. **Row checks order (ASSUMPTION):** required values, Work Pack not found or not In Progress, duplicate, completion number, decimals, range, Actual hours, source. A row for an unknown Work Pack is therefore "Work Pack not found" before it can be a duplicate.

**Local-stack proof (19 Nov 2026, new images, V3 applied to the existing local database at start-up, no BPMN change):**
- The weekly instance of the 19 Nov week waited at `UserTask_UploadCompletionReport`. As ppc-team the task was served with `weekEnding`.
- **Unhappy path:** a `.csv` whose only row was `WP-999999` → 201, "Work Pack not found"; completing the task with it → 204, the report `Invalid`, a new upload task opened whose `validationMessages` was "No usable rows in the completion report".
- **Mixed report** (headers "Workpack Number / Actual Hours", so the header rule was exercised): WP-000011 40 "Read"; WP-000030 120 "Read"; a second WP-000030 "Duplicate row for WP-000030"; WP-000031 at 40.125 "Completion % can have at most 2 decimal places"; WP-000032 at "forty" "Completion % must be a number"; a second WP-000011 with Source PMS "Duplicate row for WP-000011" (the duplicate rule comes before the source check, departure 7). Completing with it → 204; the report `Accepted`; `workpack_progress` for week ending 2026-11-19: WP-000011 reported 40.00, WP-000030 reported 120.00, approved empty; the instance waits at `UserTask_ApproveCompletion`.
- supervisor `POST /api/v1/completion-reports` → 403.
- Not run by hand: an `.xlsx` upload from the portal screen, the 6 MB file, the setting `progress.source` = PMS (covered by `BPA59_unreadableOrLargeFileIsRefused` and `BPA59_theConfiguredProgressSourceIsAccepted`).
- Side effect on the local stack: two `completion_report` rows and two `workpack_progress` rows for the 19 Nov week.

**Found by the full `./mvnw -B verify` (533 tests; 2 failures in existing tests, both fixed):**
8. **No fixture files are committed under `backend/src/test/resources/fms/`.** The plan (and Jira comment 10490) put the two named test files there, but `ModelResourcesTest.BPA43_noModelOrTemplateIsCopiedIntoBackend` allows no `.xlsx` under `backend/` (ADR 0003). The repo's own practice for Epic 1 uploads is files built in memory, so `CompletionReportBuilder.wk47()` and `notASpreadsheet()` make them, the guard stays as it is, and the two files were removed again (`git rm`). **Comment 10490's "test fixtures stay under backend/src/test/resources/fms/" is therefore not followed; to say in the PR and in the Jira comment.**
9. **`FlywayBaselineTest.BPA43_applicationStartsAndFlywayAppliesV1V2AndSeed` expects the migration list** (V1, V2, seed); V3 is the third migration, so its expected list gained "completion row source raw": **an edit to an existing test, to say in the PR.** The table count (50) is unchanged: V3 adds a column, not a table.

**After the `qa-reviewer` pass (19 Nov 2026; REVIEW.md "BPA-59 at 8c4da85"); the plan header now carries Jira's `updated` (a move to In Progress; the description is unchanged):**
10. **A numeric cell is read as its own value** (`TabularFileReader.read(..., rawNumbers)`, used by the completion report upload only): an .xlsx cell 40.125 formatted `0.00` showed "40.13" and read as Read. The time sheet upload keeps the displayed text (BPA-52 unchanged). New test `aFormattedCellIsJudgedOnItsValueNotOnItsDisplay`, red first.
11. **`outcome` of the result is the file-level line only.** `completion_report.outcome` also holds the report's decision (Accepted, Invalid), so `GET .../result` hides those two values, and a report with a file-level line ("File cannot be read") is not overwritten by the decision. Test `theResultKeepsTheFileLevelLineAfterTheDecision`, red first. The field is `outcome` in the contract (the plan called it `fileOutcome`).
12. **A row with a missing value counts for the duplicate rule** (the Work Pack is added to the seen set before the required Completion % and Source checks), as the plan text and the data-model note say; the Source in the message is trimmed. Tests `aRowWithAMissingValueStillCountsForTheDuplicateRule` and `theSourceInTheMessageIsTrimmed`, red first. `theReadJobFailsForAReportOfAnotherWeek` covers the worker's fail branch (it passed at once: the branch existed).
13. **Known and accepted (POC):** the plan said the period "must be CutOff", but the upload does not check the period's status (the weekly instance's period is CutOff until its lock ends the instance); a .csv value with a NUL character (U+0000) is refused by PostgreSQL and the upload answers 500; `WorkpackProgressRepository.setReported` overwrites `reported_pct` on a row that already has `approved_pct` (unreachable until BPA-60: nothing approves a % before the report is read); a Read row whose Work Pack is deleted before the task is completed makes the job throw and retry (an incident); a missing `taskKey` gets the framework's 400; the three tests of the shared reader come from BPA-52's suites (no `TabularFileReaderTest` of its own).
14. **Tests written after the code, with no red run, stated plainly:** `CompletionRowChecksTest` (the first 9) and `aRerunOfReadCompletionReportChangesNothing`; nothing tests the one-transaction claim with a rollback.
