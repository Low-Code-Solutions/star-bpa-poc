# Plan: BPA-58 PPC Team can download each week's approved hours as an FMS timesheet file
Jira: BPA-58 (Story, epic BPA-4)
Story last updated: 2026-11-19T14:08:54.283+0400
Status: Approved (Ahsan, Build Lead, 19 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-58-fms-hours-export, from main a9840f0 (plus the inputs commit 0c204ce)


## Context
Approved hours (BPA-57) stop at the POC. Today PPC re-keys them into FMS from ad-hoc Excel. BPA-58 builds the start of the weekly process `Process_PPC_WeeklyProgressPeriod`:
- the cut-off opens a progress period;
- a worker writes every Approved line not yet exported into an .xlsx in the FMS layout, one file per calendar month of the work dates;
- PPC downloads the file, uploads it to FMS by hand, and records the hand-off, or that FMS rejected the file.

The process has been deployed since BPA-48 with no workers, so its instances wait at the first job.

## Readiness check (Definition of Ready, docs/02, against Jira 19 Nov 14:08)
| Check | State |
|---|---|
| Jira status | **Ready** (comment 10549, 17 Nov). No transition without asking (Q6) |
| Template, persona, outcome | Met |
| Gherkin incl. unhappy paths | Met: 13 scenarios (403, reason required, FMS rejection) |
| BPMN element IDs exist | Met: `StartEvent_WeeklyCutOff` (cron `0 59 20 * * THU`), `ServiceTask_OpenProgressPeriod` (`open-progress-period`), `ServiceTask_GenerateFmsHoursFile` (`generate-fms-hours-file`), `UserTask_SendHoursToFms` (ppc-team). `UserTask_SendHoursToFms` → `Gateway_MergeReport` → `UserTask_UploadCompletionReport`, with no service task between them (Q7) |
| Every field has an SOR | Met (SOR Notes; data model §13.3.1, §13.3.2, Addendum 2). The tables and columns already exist in `V2__epic2_timesheet_to_progress.sql`, so there is **no migration** |
| DMN | None needed |
| No blocking question | Q1 (one task for two month files), Q5 (per-file rejection) and Q7 (where the hand-off is written) change the build |
| intent.md / spec.md accepted | **Not met** (Jira DoR box unchecked; open since BPA-53) |
| Estimate ≤ 8 | Met (8, at the limit): two workers (three if Q7 A), a file writer, a download endpoint, a task rule and a screen |
| Story-specific | The layout `templates/fms-hours-export-template.xlsx` is agreed as an ASSUMPTION. Comment 10489 (10 Nov) says Employment Type and CBS Cost Centre were "proposed, not applied". The committed template (0c204ce) and the Description now contain both, so I follow Jira's Description and the template |

## What already exists (closed plans BPA-48, BPA-50, BPA-57; history only)
- **Schema (BPA-48):**
  - `progress_period`: `week_ending` UNIQUE, `cut_off_at`, `status` CutOff/Locked, `process_instance_key` UNIQUE.
  - `fms_hours_export`: `period_month`; UNIQUE (`progress_period_id`, `period_month`); `file` bytea, `sha256`, `row_count` ≥ 0, `generated_at`, `sent_by`, `sent_at`; `fms_rejected_at` and `fms_rejected_reason`, both null or both set (CHECK).
  - `digital_timesheet_line.exported_at` and `fms_hours_export_id` (FK).
- **BPA-48 risk R4 / Q12:** the weekly process was deployed knowingly, with orphan `open-progress-period` jobs accepted until this story.
- **BPA-50 cut-off rule:** a week is closed when a `progress_period` row exists for its `week_ending` (`DigitalTimesheetLineRepository.weekClosed`, `latestClosedWeekEnding`). Every row this story writes closes that week for booking.
- **BPA-57:** Approved lines are final; `status = 'Approved'` is what this story exports.
- **Seed** (`R__seed_reference_data.sql`), with everything the Gherkin names:
  - E1021 and E1099: Direct, cost centre STRP01.
  - E1150: Direct, no cost centre.
  - E2001: agency (`agency_flag = true`), cost centre STRP02.
  - Project PRJ-139, activities A-2201 (CBS-139-PIP-01) and A-2299 (no CBS), department Fabrication.
  - **No seed rows to add.**
- **Test fixture:** `BookHoursFixture` builds WP-000457 (A-2201, department Fabrication) with time sheets for E1021, E1099 and E1150, and the 12 Nov cut-off row. Its time sheets have no `cbs_code_ref`, and there is no WP-000470.
- **Patterns to reuse:**
  - Writer: POI 6.0.0 (`AssignmentFileBuilder`, `TimesheetFileReader`).
  - Template check: `AssignmentTemplateTest`.
  - Download: `GET .../template` returns xlsx (`downloadTimesheetTemplate`); front-end blob downloads in `timesheetFileService.ts`.
  - Test clock: `AssignmentFileApiTest.TestClock`, settable.
  - Starting at an element: `startInstructions` (HoursPpcApprovalApiTest).
  - Task API: `TaskValidity`, allow-list, `completedBy` / `completedTaskKey`.
  - Worker pattern: skill `new-job-worker`. Screen: skill `new-task-screen`.

## Design
### 1. `open-progress-period` (OpenProgressPeriodWorker)
The BPMN documentation of this task says E2-16, but BPA-58's BPMN Reference names it, and the file needs a period. So **BPA-58 builds it**, and E2-16 keeps the rest of the period (lock, approvals).
- **Re-run key: the process instance key** (`progress_period.process_instance_key` UNIQUE; ADR 0007). On a re-run the worker reads the instance's row and returns it.
- **`week_ending`:** the latest Thursday on or before the business date of the injected Clock in Asia/Riyadh (ADR 0013). The timer fires on Thursday at 23:59 Riyadh, and a late job still gets that Thursday. ASSUMPTION (Q8): the timer passes no date to the job.
- **`cut_off_at`:** that Thursday at 23:59:00 Asia/Riyadh, the nominal cut-off (ASSUMPTION, Q8). **`status`:** CutOff. `created_by`: `bpa-service`.
- **Week already cut off by another instance** (UNIQUE `week_ending`; stale instances, Q3): the job fails with no retries: "Week ending <d MMM yyyy> already has a progress period" (ASSUMPTION text). That raises an incident and creates no second period.
- **Output variables:** `progressPeriodId`, `weekEnding` (ISO date), as the BPMN documentation says.

### 2. `generate-fms-hours-file` (GenerateFmsHoursFileWorker + FmsHoursFileWriter)
- **Re-run key:** `progressPeriodId`.
  - If `fms_hours_export` rows already exist for the period, the worker returns them without rebuilding a file or stamping lines again.
  - Selecting, writing the files, inserting them and stamping the lines happen in **one transaction**.
  - The lines are selected `FOR UPDATE` and stamped `WHERE fms_hours_export_id IS NULL`, so two instances can't take the same line.
- **Lines:**
  - `status = 'Approved' AND fms_hours_export_id IS NULL AND work_date <= week_ending`, from any week. Late approvals go in the next file (E2-D15).
  - The `work_date <= week_ending` filter is an ASSUMPTION (Q8): Jira says "every approved line not yet sent".
- **One file per calendar month of `work_date`** (S5), with `period_month` = the first day of that month.
  - No lines: one headers-only file (Q-DM19), with `period_month` = the month of `week_ending` (ASSUMPTION).
- **Layout** (template sheet `FMS Hours`, header row 1, the 10 columns in template order):

  | Column | Value |
  |---|---|
  | Type | "FMS" |
  | Date | text `dd/MM/yyyy`, as in the demo file |
  | Badge No. | `worker.employee_ref` |
  | Employment Type | `agency_flag` ? "Agency" : "Direct" |
  | Project | `digital_timesheet.project_ref` |
  | Activity | `digital_timesheet.activity_ref` |
  | Cost Centre | `worker.cost_centre_ref`, blank when null (Q9) |
  | CBS | `digital_timesheet.cbs_code_ref`, blank when null (D40) |
  | CBS Cost Centre | `workpack.department_ref` |
  | Hrs | numeric cell, format `0.00` (Q10) |

  - Rows are sorted by Date, Badge No., Project, Activity.
  - The generated file has only the `FMS Hours` sheet, like the demo. The template's README sheet is not copied (ASSUMPTION).
- **Stored:** `file`, `sha256`, `row_count`, `generated_at` (clock). Each line gets `exported_at` and `fms_hours_export_id`. Audit rows per changed column (D64).
- **Output variable:** `fmsHoursExports` = a list of `{id, periodMonth, rowCount}` for the task screen.
- **No line is exported twice:** the stamp, plus the re-run key, plus FOR UPDATE.

### 3. `UserTask_SendHoursToFms` (one task per instance; Q1)
- **Write allow-list:** `fmsHandOffs` = a list of `{fmsHoursExportId, outcome, reason}`, one per file of the task (Q1, Q5).
  - `outcome`: "Sent to FMS" or "Rejected by FMS". "Sent to FMS" is an ASSUMPTION; Jira names only the rejection.
- **Read allow-list:** `fmsHoursExports`, `weekEnding`.
- **`TaskValidity`** for `UserTask_SendHoursToFms`:
  - One entry for each of the task's files: "Outcome is required for each file" (ASSUMPTION).
  - Outcome is one of the two values.
  - "Reason is required" (Jira) for "Rejected by FMS".
  - "Reason can be at most 250 characters" (ASSUMPTION text; the 250 limit is Jira's).
  - The rule reads the task's own `fmsHoursExports`, so a file of another week can't be named.
- **Recording** (Q7, recommended A): a new `ServiceTask_RecordFmsHandOff` (job `record-fms-hand-off`) between the user task and `Gateway_MergeReport`. **This is a BPMN change that needs your yes.**
  - The worker writes, for each entry:
    - Sent: `sent_by` = `completedBy`, `sent_at` = clock.
    - Rejected: `fms_rejected_at` and `fms_rejected_reason`; the file's lines get `exported_at` and `fms_hours_export_id` = NULL; the file is kept (S4).
  - Re-run key: a file that already has `sent_at` or `fms_rejected_at` is left as it is.
- **After every outcome** the instance goes on to `UserTask_UploadCompletionReport` (scenario 8). After a rejection it goes there too, because the model has no gateway (ASSUMPTION, Q13).
- **`TaskSummary.elementId`** gains `UserTask_SendHoursToFms`. The task has no `workpackNumber` (already optional).
- **Two open tasks** (Q4): each weekly instance has its own task, and nothing correlates or blocks them, so a file not sent before the next cut-off stays open beside the new one (agreed (b)).

### 4. `GET /api/v1/fms-hours-exports/{id}/file` (api/openapi.yaml first)
- `operationId: downloadFmsHoursExport`.
- **200:** `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, with `Content-Disposition: attachment; filename="fms-hours-<weekEnding>-<yyyy-MM>.xlsx"` (file name ASSUMPTION).
- **403:** any group other than ppc-team (`SecurityConfig`: `/api/v1/fms-hours-exports/**` → `PPC_TEAM`). **404:** "File not found" for an unknown id.
- **The stored bytes** are served every time and never rebuilt (agreed (d)).

### 5. Frontend: Send hours to FMS screen (skill new-task-screen)
- `features/timesheets/components/SendHoursToFms/`, on `TaskScreenShell`. It shows "Week ending <date>" and one row per file: month, row count and a **Download** link (blob through `apiClient`).
- Each row has the two outcomes, and a reason box when "Rejected by FMS" is chosen.
- Client checks use the same texts as the backend. A server 400 is shown.
- Registered in `taskScreens.ts` and `utils/constants.ts`. `TaskList` shows "Hand-off recorded" after completion (ASSUMPTION text).

### 6. Docs and templates
- `templates/README.md`: a section for `fms-hours-export-template.xlsx` (sheet, columns, values, sources, ASSUMPTION; generated by the worker, not a download template) and a demo row for `demo/fms-hours-demo-week-2026-11-19.xlsx`.
- A test (`FmsHoursTemplateTest`) checks that the committed template's header row equals the writer's header.
- `docs/data-model.md` §13.3: "As built by BPA-58" notes (week_ending rule, re-run keys, line filter, file per month, release on rejection).
- BPMN documentation text (Q2, Q7).

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml`: new path `/api/v1/fms-hours-exports/{id}/file`; `TaskSummary.elementId` + `UserTask_SendHoursToFms`; complete-variables description |
| Model | `models/bpmn/Process_PPC_WeeklyProgressPeriod.bpmn`: documentation of `ServiceTask_GenerateFmsHoursFile` gains Employment Type (Q2); `ServiceTask_RecordFmsHandOff` if Q7 A |
| Workers | `worker/OpenProgressPeriodWorker.java`, `worker/GenerateFmsHoursFileWorker.java`, `worker/RecordFmsHandOffWorker.java` (Q7 A) |
| Persistence | `persistence/ProgressPeriodRepository.java`, `persistence/FmsHoursExportRepository.java` |
| File | `fms/FmsHoursFileWriter.java` (POI; same package style as `print/`) |
| API, task, security | `api/FmsHoursExportController.java`; `task/TaskValidity.java`; `application.yml` allow-list; `config/SecurityConfig.java` |
| Frontend | `SendHoursToFms/*` (+ test), `services/fmsHoursExportService.ts`, `taskScreens.ts`, `utils/constants.ts`, `TaskList.tsx`; `npm run gen:api` |
| Tests | new `FmsHoursExportApiTest` (13 BPA58_ + extras); `FmsHoursFileWriterTest`; `FmsHoursTemplateTest`; `TaskValidityTest` row; `BookHoursFixture` (reset also clears `fms_hours_export`; new `fmsBackground()` sets CBS-139-PIP-01 on WP-000457's time sheets and adds WP-000470 (A-2299, Fabrication) with E1150's time sheet and E2001's time sheet on WP-000457) |
| Docs | `templates/README.md`; `docs/data-model.md` notes; plan; REVIEW.md; `intent/README.md` |

Not changed: database schema, seed, DMN, `HourLineService` (the BPA-50 rule picks up the new rows by itself).

## Order of work
1. Approve the plan; commit it (`Approved`) on top of 0c204ce; regenerate `intent/README.md`. No Jira transition without asking (Q6).
2. Contract (`openapi.yaml`) and, if agreed, the BPMN changes. Then the 13 BPA58_ tests and extras. Run them and show them red **before any production code**, committed separately. Expected:
   - Scenarios 1–8 and 10–13 fail because no worker takes `open-progress-period`: no period row appears within the wait.
   - Scenario 9 fails on its Given (no file to download), not on the 403.
3. Backend:
   - `OpenProgressPeriodWorker`, writer, `GenerateFmsHoursFileWorker`, the download endpoint and security, `TaskValidity`, the allow-list, `RecordFmsHandOffWorker`.
   - Green: the class, then `BookHoursApiTest`, `TaskApiTest`, `TaskValidityTest`, `ModelDeployTest` and `ModelResourcesTest`.
4. Frontend: tests red and committed, then the screen. Run lint, format, test and build.
5. `templates/README.md`, data-model notes, full `./mvnw -B verify`, local stack proof, `qa-reviewer`, fixes, REVIEW.md, plan Closed. Push, PR and Jira comment only when asked.

## Scenario-to-test table
**`FmsHoursExportApiTest` setup** (SpringBootTest with the workers on, `IntegrationApp` / `IntegrationCamunda`, `@DirtiesContext`):
- **Clock:** settable, 20 Nov 2026 Asia/Riyadh.
- **Background:** `BookHoursFixture.reset()` plus `fmsBackground()`, then the five Background lines inserted by SQL with their statuses.
- **"The cut-off for week ending X fires":** the test sets the clock to X at 23:59:30 Asia/Riyadh. It then starts `Process_PPC_WeeklyProgressPeriod` through REST with `startInstructions` at `ServiceTask_OpenProgressPeriod`, and waits for `UserTask_SendHoursToFms`. It never waits for the cron.
- **Reading a file:** download through the API as ppc-team, then read with POI and `DataFormatter`.

| # | Scenario | Test | Asserts | Expected first run |
|---|---|---|---|---|
| 1 | The weekly cut-off creates the FMS file | `BPA58_cutOffCreatesTheWeeklyFile` | `progress_period` week_ending 2026-11-19, CutOff; one `fms_hours_export` for it with row_count 3; a `UserTask_SendHoursToFms` task, candidate group ppc-team | red: no period (no worker) |
| 2 | The file has the FMS layout | `BPA58_fileHasTheFmsLayout` | header row exactly the 10 Jira columns; a row "FMS, 16/11/2026, E1021, Direct, PRJ-139, A-2201, STRP01, CBS-139-PIP-01, Fabrication, 8.00" | red |
| 3 | Only Approved lines are exported | `BPA58_onlyApprovedLinesAreExported` | no row E1021 17/11/2026 (Submitted) and none E1099 17/11/2026 (Rejected) | red |
| 4 | A line is exported once | `BPA58_lineIsExportedOnce` | after the 19 Nov file, the 26 Nov cut-off's file has no row E1021 16/11/2026 | red |
| 5 | A late approval goes in the next file | `BPA58_lateApprovedLineGoesInNextFile` | E1021's 17 Nov line set Approved (SQL) after the 19 Nov file; 26 Nov file has the row E1021 17/11/2026 | red |
| 6 | Blank CBS and cost centre stay blank | `BPA58_blankCbsAndCostCentreStayBlank` | E1150's row: Cost Centre "" and CBS "" (and CBS Cost Centre Fabrication) | red |
| 7 | An empty week gives headers only | `BPA58_emptyWeekGivesHeadersOnly` | after the 19 Nov file took every Approved line, the 26 Nov file has the header row only; row_count 0 | red |
| 8 | PPC records the hand-off | `BPA58_ppcRecordsTheHandOff` | ppc-team completes with "Sent to FMS" → 204; `sent_by` ppc-team, `sent_at` = clock; the instance waits at `UserTask_UploadCompletionReport` | red |
| 9 | Only PPC can download | `BPA58_onlyPpcCanDownloadTheFile` | Given the 19 Nov file exists (asserted first); supervisor GET → 403 | red on its Given |
| 10 | Employment Type Direct or Agency | `BPA58_employmentTypeShowsDirectOrAgency` | E2001 Approved 5 h on 18 Nov on WP-000457; row E2001 "Agency", row E1021 "Direct" | red |
| 11 | Month-end gives one file per month | `BPA58_weekAcrossMonthEndGivesTwoFiles` | Approved E1021 29 Nov 8 h and 1 Dec 6 h (only these waiting); 3 Dec cut-off → two files, period_month 2026-11-01 and 2026-12-01; Nov file only 29/11/2026, Dec only 01/12/2026 | red |
| 12 | An FMS rejection releases the lines | `BPA58_fmsRejectionReleasesLinesToNextFile` | complete with "Rejected by FMS" + "Badge E1099 unknown in FMS" → `fms_rejected_at` set, reason stored; the 26 Nov file has E1099 16/11/2026 again | red |
| 13 | A rejection needs a reason | `BPA58_fmsRejectionNeedsAReason` | complete with "Rejected by FMS" and no reason → 400 "Reason is required"; the task stays open; the file has no rejection | red |

Extra tests (not scenarios):
- **Re-runs:**
  - `aRerunOfOpenProgressPeriodReturnsTheSamePeriod` (mocked JobClient).
  - `aRerunOfGenerateDoesNotBuildASecondFile` (one export, lines stamped once).
  - `aRerunOfRecordHandOffChangesNothing`.
- **Download:**
  - `unknownFileIs404` ("File not found").
  - `theSameBytesAreServedTwice` (equal sha256, no rebuild).
- **Rules:**
  - `aSecondPeriodForTheSameWeekFailsWithoutRetry` (Q3).
  - `reasonOver250IsRefused`.
  - `aLineDatedAfterTheWeekIsNotExported` (Q8).
- **Unit and template:**
  - `FmsHoursFileWriterTest`: sort order, `0.00` format, blank cells.
  - `FmsHoursTemplateTest`: committed header = writer header.
  - `TaskValidityTest`: a row for `UserTask_SendHoursToFms`.
- **React** (`SendHoursToFms.test.tsx`):
  - download links per file;
  - Sent completes with `fmsHandOffs`;
  - Rejected without a reason shows "Reason is required" and makes no call;
  - a server 400 text is shown.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=FmsHoursExportApiTest test` (13 BPA58_, 0 failures); `grep -c "void BPA58_" backend/src/test/java/com/lcs/star/bpa/FmsHoursExportApiTest.java` = 13; `gh pr checks <n>` |
| GET /api/v1/fms-hours-exports/{id}/file in api/openapi.yaml | `grep -n "fms-hours-exports/{id}/file" api/openapi.yaml`; Redocly lint in CI |
| Process runs end to end in the local stack | run below |
| BPMN and DMN match deployed | `curl -u demo:demo localhost:8080/v2/process-definitions/<key>/xml` diffed against `models/bpmn/Process_PPC_WeeklyProgressPeriod.bpmn` (latest version) |
| Open questions resolved or logged | Questions below, as Jira comments (shown first) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-58.md` |
| Demoed at sprint review | Human gap |

**End-to-end run in the local stack** (after Q3's clean-up):
1. Rebuild `bpa-app` and `frontend`.
2. In the portal, take WP-000011's hours through BPA-57 (send, validate, production approve, PPC approve), so Approved lines are waiting.
3. **Trigger the cut-off by hand:** `POST /v2/process-instances` (demo/demo) with `processDefinitionId` `Process_PPC_WeeklyProgressPeriod` and `startInstructions` `[{elementId: ServiceTask_OpenProgressPeriod}]`. With the real clock on Thursday the week ending is that day. Otherwise set `BPA_CLOCK_TODAY` to a Thursday.
4. **As ppc-team:** open **Send hours to FMS** and download the file. Check the 10 columns, the rows and the `0.00` hours in Excel. Choose "Sent to FMS".
   - Second run: a new week (`BPA_CLOCK_TODAY` = the next Thursday). Choose "Rejected by FMS", first with no reason (refused), then with a reason.
5. **`psql`:**
   - `progress_period` has the row;
   - `fms_hours_export` has `row_count`, `sent_by` ppc-team / `sent_at`, or the rejection;
   - the lines are stamped, or released after the rejection.
6. **Operate:** the instance waits at `UserTask_UploadCompletionReport`. As supervisor, booking a date in the closed week gives "Week ending … is closed" (BPA-50).

## Risks
- **Stale weekly instances (Q3):**
  - Every Thursday since BPA-48, each running stack has started an instance waiting at `open-progress-period`.
  - The next fire is tonight, Thu 19 Nov at 20:59 UTC.
  - When the worker ships, those jobs run at once. The first creates the current week's `progress_period`, which closes that week for booking (BPA-50 rule) and exports every Approved line. The rest fail as duplicates of the week and raise incidents.
  - I couldn't count them locally because Docker Desktop was not running during planning.
- **The cut-off date comes from the clock, not the timer:** a job delayed by more than a week would open the wrong week (Q8).
- **One task for several files** (Q1): the per-file outcome list makes the screen and the validity rule more complex than a single choice.
- **A BPMN change** (Q7 A, Q2) redeploys `Process_PPC_WeeklyProgressPeriod` as a new version. Instances already running stay on their old version without the record step: on those, completing the task records nothing.
- **Estimate 8, at the limit.** If the build runs long, the screen's per-file outcome is the first candidate for simplifying, after asking.
- **The FMS layout is an ASSUMPTION** until STAR sends its template (S1). Only the writer and the template change then.

## Questions
1. **Month-end split: one task for two files?** The model has one `UserTask_SendHoursToFms` per instance.
   - **Recommended:** one task covers all the week's files, and the screen lists each file with its own download and outcome. No BPMN change.
   - Alternative: a multi-instance task per file, which is a BPMN change.
2. **BPMN documentation of `ServiceTask_GenerateFmsHoursFile`** leaves out Employment Type. Recommended: fix the documentation text only, and record it in the plan. It still redeploys a new version.
3. **Stale weekly instances.** Recommended:
   - **Local:** before starting the new `bpa-app`, cancel every active `Process_PPC_WeeklyProgressPeriod` instance (Operate, or `POST /v2/process-instances/{key}/cancellation`), and delete any `progress_period` row they leave. Then trigger the cut-off by hand for the proof.
   - **QA server:** the same clean-up as a deploy step in the PR description, done by you (Ahsan) at deploy time. I won't touch QA.
   - **In code:** a second instance for the same week fails with no retries and an incident (design 1), so a stale instance can't create two periods or export lines twice.
   - Do you also want the timer left as it is (cron Thursday), or paused on QA until the demo?
4. **Two open `UserTask_SendHoursToFms` tasks:** confirmed that nothing in the model blocks it. One instance per week, no boundary event on the task, no message correlation. The test for scenario 4 has two instances open at once.
5. **FMS rejects one month's file:** recommended **only that file's lines are released** (per-file outcome, Q1). The other file can still be "Sent to FMS".
6. **Jira:** BPA-58 is Ready. I will ask before any transition.
7. **Where the hand-off is recorded.** Completing a user task writes nothing in the POC database, and the model goes straight from `UserTask_SendHoursToFms` to `Gateway_MergeReport`.
   - **Recommended (A):** add `ServiceTask_RecordFmsHandOff` (job `record-fms-hand-off`, re-run key per file) after the user task, as every other decision in the repo is recorded. BPMN change, your yes needed.
   - Alternative (B): the task API writes before completing the task. No BPMN change, but the record and the completion are not atomic.
8. **Cut-off date and line filter (ASSUMPTION):**
   - `week_ending` = the latest Thursday on or before the business date when the job runs.
   - `cut_off_at` = Thursday at 23:59 Asia/Riyadh.
   - The file takes Approved lines with `work_date <= week_ending` only, so a line from the open week waits for its own cut-off.
   - Agree?
9. **Cost Centre source:** `worker.cost_centre_ref` (the live myPeople copy, as Jira's "the Worker's personal cost centre") or `digital_timesheet.cost_centre_ref` (the copy taken when the time sheet was made, BPA-15)? Recommended: `worker.cost_centre_ref`. They are equal in all seed and test data.
10. **Cell types:** Date as text `dd/MM/yyyy` (as the demo file); Hrs as a number with format `0.00` (Jira "2 dp"; the demo has a plain 8). Agree?
11. **Texts not in Jira (ASSUMPTION; to comment after a yes):**
    - "Sent to FMS";
    - "Outcome is required for each file";
    - "Reason can be at most 250 characters";
    - "Week ending … already has a progress period";
    - "Hand-off recorded";
    - the file name `fms-hours-<weekEnding>-<yyyy-MM>.xlsx`;
    - `period_month` of a headers-only file = the month of the week ending;
    - no README sheet in the generated file.
12. **`open-progress-period` scope:** BPA-58 builds it (BPMN Reference) although its documentation says E2-16. E2-16 keeps the lock and approvals. Confirm, and should a comment go on E2-16's Jira item?
13. **After "Rejected by FMS"** the instance still goes to `UserTask_UploadCompletionReport` (the model has no gateway there). Accept as is?

## Human-gap log
- FMS layout template and demo staged from the Epic 2 draft pack by Ahsan via Claude before the plan (commit 0c204ce), a departure from ADR 0015.
- Docker Desktop was not running during planning, so the count of stale weekly instances on the local stack is unknown.
