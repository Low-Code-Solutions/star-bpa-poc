# Plan: BPA-15 Supervisor can find a Digital Time sheet ready for each Worker on a Work Pack
Jira: BPA-15 (Story, epic BPA-3)
Story last updated: 2026-11-09T13:16:36.003+0400 (status moved to In Progress; the content last changed 2 Nov 13:04)
Status: Approved (Ahsan, Build Lead, 2026-11-09)
Approver: Ahsan (Build Lead)
Branch: BPA-15-digital-timesheets, from main 274bd9c


## Context
BPA-14 (merged, 274bd9c) creates one Project Hour Journal Line per assigned Worker, then the instance stops at
`ServiceTask_CreateDigitalTimesheets` with the placeholder incident "built by BPA-15".

BPA-15 builds the real `create-digital-timesheets` worker. It creates one Digital Time sheet per Worker per Work Pack, from each
Open Line:
- **Project ID and WBS** come from the Work Pack's activity (`activity.wbs_ref`, then `wbs.project_ref`).
- **CBS code** comes from the activity's CBS assignment (`cbs_activity`).
- **Cost centre** comes from the Worker's personal cost centre (`worker.cost_centre_ref`, D40).
- **CBS code and cost centre may be blank:** the time sheet is still created.

The worker then sets `workpack.assignment_state = Assigned` (D61), and the process ends at `EndEvent_WorkPackAssigned`. Nothing of
Epic 2 (BPA-4) is started.

It also builds `GET /api/v1/digital-timesheets?workpackNumber=` for the group `supervisor` only. **That group is an ASSUMPTION in
the story;** the PR and the Jira comment flag it. There is no department or project scope (BPA-44, PO 2 Nov).

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met (Supervisor) |
| Gherkin, testable, unhappy paths | Met: 9 scenarios |
| BPMN IDs exist | Met: ServiceTask_CreateDigitalTimesheets (`create-digital-timesheets`, retries 3; documentation "Idempotent: unique(worker_id, workpack_id). D40. Sets workpack.assignment_state = Assigned (G7)"), then EndEvent_WorkPackAssigned |
| SOR per field | Met: digital_timesheet (POC database; real SOR D365 timesheet / digital tab); project_ref, wbs_ref (D365 F&O PM), activity_ref (P6 via PMS), cbs_code_ref (D365 Cost Control Report), cost_centre_ref (myPeople, ASSUMPTION D40). Data model §5.7 |
| DMN complete | n/a |
| No blocking question | Met: C8, C13, C14, D40, D61 decided; the reader group, the blanks and the re-run rule are ASSUMPTIONs (non-blocking) |
| ≤ 8 points | 2. **My estimate is about 3**: the story is small, but this step ends the process, so three earlier test classes must be re-checked (below) |
| Spec accepted | Met (Faraz, 2 Nov) |
| PO approved | Faraz, 28 Oct (comment 10387). **The re-approval after the 2 Nov change is recorded by Ahsan, not by a Faraz comment** (see the note) |

Notes:
- **2 Nov change and re-approval.** On 2 Nov Ahsan:
  - edited the AC: the department-scope scenarios became the supervisor and production-team personas (BPA-44);
  - moved the story Backlog → Ready;
  - changed the DoR line from "re-approval needed" to "Faraz, 2 Nov 2026".

  No comment on BPA-15 from Faraz records that re-approval, and CLAUDE.md says only the PO moves a story to Ready. I take the DoR
  line as Ahsan recording Faraz's decision (BPA-44, PO 2 Nov), but flag it here rather than resolve it silently.
- **Contract already in place.** `listDigitalTimesheets` and `DigitalTimesheet` came in with BPA-46:
  - fields: employeeRef, workpackNumber, projectRef, wbsRef, activityRef, cbsCodeRef and costCentreRef (both absent when blank),
    submissionStatus;
  - responses: 400, 401, 403, 404 "Work Pack not found".

  **No contract change.** Null fields are already left out (`default-property-inclusion: non_null`).
- **Seed matches the AC:**
  - A-2201 → WBS 11031 → PRJ-139, CBS CBS-139-PIP-01;
  - A-2299 → WBS 11031 → PRJ-139, no CBS;
  - E1021 and E1099 have cost centre STRP01; E1150 has none; E9999 does not exist.
- **Project ID and WBS can always be derived.** `workpack.activity_ref`, `activity.wbs_ref` and `wbs.project_ref` are all NOT NULL
  with foreign keys, so there is no "missing Project ID" path.

## Design: the worker (`worker/CreateDigitalTimesheetsWorker`, replacing the placeholder; skill `new-job-worker`)
1. Find the Work Pack by `job.getProcessInstanceKey()` (`WorkPackRepository.find`).
2. One transaction (`persistence/DigitalTimesheetRepository`, `@Transactional`), with the Work Pack row locked `FOR UPDATE`.
3. **State guard (D61):**
   - `Assigning` → go on;
   - `Assigned` → a re-run: go on (inserts nothing);
   - anything else (empty, AwaitingFile, Replacing) → nothing is written, and the job fails with no retries and a message naming
     the state (an incident). This is the same pattern BPA-22 uses.
4. **No Open Line** on the Work Pack (no process path reaches this step without one) → nothing is written, and the job fails with
   no retries and a message.
5. **Insert.** `INSERT ... SELECT` one time sheet per Open `hour_journal_line` under the Work Pack's Header, with `ON CONFLICT
   (worker_id, workpack_id) DO NOTHING RETURNING ...`, ordered by worker. The columns are:
   - `worker_id`, and `journal_line_id` = the Line;
   - `activity_ref` from `workpack.activity_ref`;
   - `wbs_ref` from `activity`; `project_ref` from `wbs`;
   - `cbs_code_ref` from `cbs_activity`, left-joined, so it can be null;
   - `cost_centre_ref` from `worker`, so it can be null;
   - `submission_status` "Draft".

   These values are copies taken at creation (data model §5.7, A). No `digital_timesheet_line` is created; Epic 2 enters the daily
   lines.
6. **State change.** `UPDATE workpack SET assignment_state = 'Assigned' WHERE id = ? AND assignment_state = 'Assigning'`, with one
   audit row `Assigning → Assigned` when it changes.
7. **Who (D63).** `created_by`, `updated_by` and `audit_log.username` are the Line's `created_by`, which is the file's uploader
   (BPA-14 and BPA-22). The time comes from the injected `Clock` (S9).
8. **Audit (D64).** One row per business column of each new time sheet, with old value null: `worker_id`, `workpack_id`,
   `journal_line_id`, `project_ref`, `wbs_ref`, `activity_ref`, `submission_status`, plus `cbs_code_ref` and `cost_centre_ref`
   only when present. A re-run writes none.
9. **Complete** with no variables. The instance then ends at EndEvent_WorkPackAssigned.

## Design: the read endpoint (`api/DigitalTimesheetController`, implements the generated `TimesheetsApi`)
- `GET /api/v1/digital-timesheets?workpackNumber=` returns the Work Pack's time sheets as `DigitalTimesheet`, ordered by employeeRef.
- 404 "Work Pack not found"; 400 "Workpack number must match WP-######" (the BPA-7 and BPA-14 handler).
- A Work Pack without time sheets yet answers 200 `[]`. This is an ASSUMPTION, as BPA-7 and BPA-14 chose.
- **SecurityConfig:** `/api/v1/digital-timesheets` for `supervisor` only (the existing constant), on every method, so HEAD cannot
  skip the rule. Everyone else gets 403 with no data; anonymous callers get 401.

## Re-check of earlier tests (said in the PR)
This step ends the process, so nothing waits at a BPA-15 incident any more, and a Work Pack moves on from Assigning to Assigned
within seconds.
- **New helper `AssignmentFlow.awaitEnd(Work)`:** polls until `EndEvent_WorkPackAssigned` has completed for the instance
  (`CamundaSteps.completed`). The re-checked tests use it in place of "at ServiceTask_CreateDigitalTimesheets with the BPA-15
  incident".
- **`AssignmentFileApiTest` (BPA-12):**
  - the six `TIMESHEETS` polls become `awaitEnd`;
  - "nothing changes in the process" becomes "the end event completed exactly once";
  - `aRoutedInstanceStopsWithTheBpa15Incident` becomes `aRoutedInstanceRunsToWorkPackAssigned`: the end event completed and the
    instance has no incident.
- **`WorkerAssignmentApiTest` (BPA-22):**
  - `atBpa15Incident` → `awaitEnd`;
  - `BPA22_acceptedRowSetsAssigning`: the state is now Assigned a moment later, so the test waits for the end, then asserts the
    audit trail is exactly `AwaitingFile->Assigning` (by production-team, BPA-22's transition) then `Assigning->Assigned`. The
    final state is Assigned. **This changes what the test asserts at the end; the PR says so.** BPA-22's own transition is still
    proved, by its audit row;
  - the retried-job test waits for the end, and asserts Assigned (unchanged by the retry);
  - the state-guard test waits for the end, sets the state to `Assigning` by hand (as it already sets rows by hand), and keeps its
    message.
- **`HourJournalLineApiTest` (BPA-14):**
  - `atBpa15Incident` → `awaitEnd`;
  - `aJobCreatesLinesOnlyForItsOwnWorkPack` deletes the other Work Pack's time sheets (and their audit rows) before its Lines,
    because `digital_timesheet.journal_line_id` references them.

## Scenario-to-test table
New class `DigitalTimesheetApiTest`, on the BPA-14 pattern:
- the integration Camunda with workers on, the TestClock at 30 Oct 2026 Asia/Riyadh, `@DirtiesContext`;
- `@BeforeAll` clean-up of instances left at the catch event;
- Work Packs taken the real way to AwaitingFile (`AssignmentFlow`), then an assignment file from `AssignmentFileBuilder`.

`AssignmentFlow.draft()` gains an activity parameter (default A-2201) for scenario 4's A-2299.

| # | Scenario | Test |
|---|---|---|
| 1 | Time sheets are created | BPA15_timesheetsAreCreatedForAssignedWorkers: assign E1021 and E1099 on an A-2201 Work Pack. One time sheet each: Employee ID, PRJ-139, CBS-139-PIP-01, STRP01, 11031 / A-2201, "Draft", no `digital_timesheet_line`. The instance ends at EndEvent_WorkPackAssigned |
| 2 | Rejected rows get none | BPA15_rejectedRowsGetNoTimesheet: E1021 and E9999. After the end, one time sheet on the Work Pack (E1021), none for E9999 |
| 3 | Worker on two Work Packs | BPA15_workerOnTwoWorkPacksHasTwoTimesheets: one file, E1021 on two AwaitingFile Work Packs. E1021 has one time sheet for each |
| 4 | Missing CBS or cost centre | BPA15_missingCbsOrCostCentreStillCreatesTimesheet: an A-2299 Work Pack with E1150. The time sheet has PRJ-139 and 11031, and the CBS code and cost centre are null in the database and absent in the JSON |
| 5 | Re-run does not duplicate | BPA15_rerunDoesNotDuplicateTimesheets: after scenario 1's set-up and the end, call `handle` twice (mocked `JobClient`). Still one time sheet per Worker, the job completes each time, no audit row added |
| 6 | Marked Assigned | BPA15_workPackIsMarkedAssigned: after the end, `assignment_state` is Assigned, with one audit row `Assigning->Assigned` by production-team |
| 7 | Supervisor finds them | BPA15_supervisorFindsTimesheets: as supervisor, GET is 200 with two time sheets, each with Worker, Project ID, WBS / Activity, CBS Code, Cost Centre, Submission Status |
| 8 | Other personas | BPA15_otherRolesCannotReadTimesheets: as production-team, 403 and no time sheet data in the body |
| 9 | Unknown Work Pack | BPA15_unknownWorkPackReturnsNotFound: as supervisor, WP-999999 gives 404 "Work Pack not found" |

Non-scenario tests:
- Other personas (ppc-team, production-planner) get 403; HEAD gets 403; anonymous gets 401; a trailing slash is never a 200 with
  data; `WP-12` gives 400; a Work Pack without time sheets gives 200 `[]`.
- **Audit:** the rows of a new time sheet with their values and `changed_at` from the clock. A blank CBS code or cost centre writes
  no row for that column.
- **Snapshot:** after a time sheet exists, changing the Worker's cost centre and re-running the job leaves it as created.
- **State guard:** a job for a Work Pack that is not Assigning or Assigned (a draft) fails with no retries and its message, writing
  nothing.
- **Scope:** a job writes time sheets only for its own Work Pack.

Stubs keep the classes compiling for the red run: a controller that returns an empty list, and the current placeholder worker. All
9 scenario tests are expected to fail first for the expected reason; any that passes against a stub is named in the PR.

## Files that change
- Backend:
  - `worker/CreateDigitalTimesheetsWorker.java` (rewritten; stays excluded in `ProcessTestApplication`, as it now needs the
    database);
  - new `persistence/DigitalTimesheetRepository.java` and `api/DigitalTimesheetController.java`;
  - `config/SecurityConfig.java` (the rule).
- Tests:
  - new `DigitalTimesheetApiTest`;
  - `AssignmentFlow` (activity parameter, `awaitEnd`);
  - `AssignmentFileApiTest`, `WorkerAssignmentApiTest`, `HourJournalLineApiTest` (re-check).
- Docs: `REVIEW.md`, this plan, `intent/README.md` (generated).
- No BPMN, DMN, migration, seed, contract or portal change.

## Order of work
1. Commit this plan (Approved). You move statuses.
2. Tests first: the 9 scenario tests, the non-scenario tests and the re-checked tests, with stubs. Run them and show they fail for
   the expected reason.
3. Build the repository, the worker, the controller and the security rule. Get green, then run the full `./mvnw -B verify`.
4. Local stack (rebuild bpa-app), through the API:
   - a Work Pack assigned E1021 and E1099, with time sheets created;
   - GET as supervisor (200), and as production-team and ppc-team (403);
   - the Work Pack Assigned and, in Operate, the instance completed at EndEvent_WorkPackAssigned with no incident.
5. qa-reviewer → REVIEW.md → fixes → plan Closed → push → PR → wait for all CI checks green → tell you → Jira comments (text
   shown first). You merge.

## Risks
- **Size:** about 3 points against the 2 on the story, because the end of the process changes three earlier test classes.
- **Test time:** about 8 Work Packs taken to AwaitingFile at about 20 s each, so the new class runs about 3 to 4 minutes.

## Proof
`./mvnw -B verify` (red first, then green), the local-stack run with Operate, CI.

## Questions (for Khitaba, ASSUMPTIONs; Jira comment, text shown first)
- Q1: the reader is the `supervisor` group only (the story's ASSUMPTION, flagged as asked).
- Q2: a Work Pack without time sheets yet answers 200 `[]`, not 404.
- Q3: time sheets are made from **Open** Lines only. Closed is set only by BPA-13, so in Epic 1 every Line is Open.
- Q4: a job for a Work Pack whose state is not Assigning or Assigned fails with an incident instead of creating time sheets.
- For Faraz: please confirm on BPA-15 the 2 Nov re-approval that the DoR line records.

## Human-gap log
- Local Maven build: map `subst S: "D:\LCS CLaude"` first (lost at reboot); JAVA_HOME per command; Docker Desktop must be running.
