# Plan: BPA-14 PPC Team can see a Project Hour Journal Line for each assigned Worker
Jira: BPA-14 (Story, epic BPA-3)
Story last updated: 2026-11-09T11:28:07+0400 (status moved to In Progress; the content last changed 28 Oct 13:29, before the PO's approval at 13:31)
Status: Closed (2026-11-09); approved by Ahsan, Build Lead, 2026-11-09
Approver: Ahsan (Build Lead)
Branch: BPA-14-hour-journal-lines, from main 9f78ea2


## Context
BPA-22 (merged) assigns Workers to a Work Pack and sends the instance on to `ServiceTask_CreateHourJournalLines`
(`create-hour-journal-lines`). Today a BPA-22 placeholder stops it there with the incident "built by BPA-14".

BPA-14 builds the real worker:
- one Project Hour Journal Line per assigned Worker, under the Work Pack's Header (BPA-7);
- `role` and `designation` copied from the Worker master (the value's SOR is myPeople; a seeded copy here; C14);
- the estimated hours of the assignment, status "Open".

It also builds the read endpoint `GET /api/v1/hour-journal-lines?workpackNumber=` (D39), for the groups `ppc-team` and
`supervisor` only. **Those groups are an ASSUMPTION in the story;** the PR and the Jira comment flag them.

The next step, `ServiceTask_CreateDigitalTimesheets` (`create-digital-timesheets`), is BPA-15. Until then it raises an incident
"built by BPA-15", the same pattern.

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met (PPC Team member) |
| Gherkin, testable, unhappy paths | Met: 6 scenarios |
| BPMN IDs exist | Met: ServiceTask_CreateHourJournalLines (`create-hour-journal-lines`, retries 3; documentation "Idempotent: unique(header_id, worker_id)"), next ServiceTask_CreateDigitalTimesheets (`create-digital-timesheets`) |
| SOR per field | Met: hour_journal_line (POC database; real SOR D365 Project Hour Journal, Int-5); worker_id, role, designation (myPeople, seeded copy; C11, C14). Data model §5.6 |
| DMN complete | n/a |
| No blocking question | Met: C3, C14, D39 decided. Allowed groups and the re-run rule are ASSUMPTIONs (non-blocking) |
| ≤ 8 points | 3. My estimate is 3 to 4, including the BPA-15 placeholder and the re-checked tests |
| Spec accepted | Met (Faraz, 2 Nov) |
| PO approved | Faraz, 28 Oct (comment 10386). Since then only Sprint and Rank changed (6 Nov) |

Notes:
- **Contract already in place.** The operation (`listHourJournalLines`), its parameter `WorkpackNumberQuery` (WP-######) and the
  responses (`HourJournalLine` with employeeRef, role, designation, estimatedHours, status; 400 InvalidWorkpackNumber; 401; 403;
  404 WorkPackNotFound "Work Pack not found") came in with BPA-46. **No contract change** is planned.
- **Seed matches the AC:** E1021 Welder Grade 3, E1099 Fitter Grade 2, E1150 Rigger Grade 1; E9999 does not exist.
- Jira's Development panel already shows PR #33, because its text names BPA-14. No action needed.

## Design: the worker (`worker/CreateHourJournalLinesWorker`, replacing the placeholder; skill `new-job-worker`)
1. Find the Work Pack by `job.getProcessInstanceKey()` (`WorkPackRepository.find`, as the print and validate workers do).
2. One transaction (`persistence/HourJournalLineRepository`, `@Transactional`):
   - read the Work Pack's Header (`hour_journal_header`, unique per Work Pack, written by BPA-7). No Header → fail the job
     with no retries and a plain message (an incident; no process path reaches this step without one);
   - for every `worker_assignment` of the Work Pack, joined to `worker`, insert a Line with `ON CONFLICT (header_id, worker_id)
     DO NOTHING`, using:
     - the assignment's `estimated_hours`, as `numeric(7,2)`, so 0.01 stays 0.01;
     - `role` and `designation` copied from `worker` at this moment, a snapshot that is never refreshed (data model N3);
     - status "Open".
3. **Rejected rows get no Line.** BPA-22 never saves them in `worker_assignment`, so there is nothing to copy.
4. **Idempotent (D65, 09 S7; the story's re-run ASSUMPTION).** A re-run inserts nothing new, because the unique key holds, and
   writes no audit row. Camunda's default 3 retries then an incident apply (S7; no custom retry).
5. **Who did it (D63).** `created_by`, `updated_by` and `audit_log.username` are the assignment's `created_by`, which is the
   uploader of the file (BPA-22). The `completedBy` variable is not used: it would name the planner's earlier task.
6. **Audit (D64).** One row per business column of each new Line, with old value null: `header_id`, `worker_id`, `role`,
   `designation`, `estimated_hours`, `status`. The times come from the injected `Clock` (09 S9).
7. Complete the job with no variables. BPA-15 reads the Lines from the database.

## Design: the read endpoint (`api/HourJournalLineController`, implements the generated `HourJournalLinesApi`)
- `GET /api/v1/hour-journal-lines?workpackNumber=` returns the Lines of the Work Pack's Header as `HourJournalLine` (employeeRef
  from `worker.employee_ref`, role, designation, estimatedHours, status), ordered by employeeRef.
- **Unknown Work Pack:** 404 "Work Pack not found".
- **Missing or malformed number:** 400 "Workpack number must match WP-######", the same handler as `HourJournalHeaderController`.
- **A Work Pack with no Lines yet:** 200 with an empty list. This is an ASSUMPTION, as BPA-7 chose for the Header.
- **SecurityConfig:** `/api/v1/hour-journal-lines` for `ppc-team` and `supervisor` only (a new `SUPERVISOR` constant), on
  **every** method, so a HEAD request cannot skip the rule (the BPA-10 lesson). Anyone else gets 403 with no Line data;
  anonymous callers get 401.

## Design: the BPA-15 placeholder
`worker/CreateDigitalTimesheetsWorker` (`create-digital-timesheets`) fails every job with no retries and the message
"create-digital-timesheets is built by BPA-15", as BPA-22 did for this step. It is excluded from
`bpatest/ProcessTestApplication`, like the other DB-backed and placeholder workers. The real Lines worker is excluded too,
because it needs the database.

## Re-check of earlier tests (said in the PR)
These tests expected the instance to stop at ServiceTask_CreateHourJournalLines with "built by BPA-14". Now it passes that
step and stops at ServiceTask_CreateDigitalTimesheets with "built by BPA-15".
- `WorkerAssignmentApiTest` (BPA-22): `CREATE_LINES` → the timesheet step; `atBpa14Incident` → `atBpa15Incident`, used in
  `BPA22_workersAreAssignedFromFile`.
- `AssignmentFileApiTest` (BPA-12): `CREATE_LINES` → the timesheet step, in the five routed-scenario polls and in "nothing
  changes in the process"; `aRoutedInstanceStopsWithTheBpa14Incident` → `...Bpa15Incident`.
- No assertion is loosened: only the element ID and the incident text move one step on.

## Scenario-to-test table
New class `HourJournalLineApiTest`, on the BPA-22 pattern:
- the integration Camunda with workers on, the TestClock at 30 Oct 2026 Asia/Riyadh, `@DirtiesContext`;
- `@BeforeAll` cancels instances left at the catch event;
- Work Packs taken the real way to AwaitingFile (`AssignmentFlow`), then an assignment file from `AssignmentFileBuilder`.

| # | Scenario | Test |
|---|---|---|
| 1 | Lines are created for assigned Workers | BPA14_linesAreCreatedForAssignedWorkers: assign E1021 (40) and E1099 (60), then as ppc-team the GET returns two Lines, "Open", `E1021/Welder/Grade 3/40` and `E1099/Fitter/Grade 2/60`; the instance stops at ServiceTask_CreateDigitalTimesheets with the BPA-15 incident |
| 2 | Rejected rows get no Line | BPA14_rejectedRowsGetNoLine: E1021 (40) and E9999 (20); E9999's row is rejected (BPA-22), and only E1021 has a Line |
| 3 | Decimal hours kept exactly | BPA14_decimalHoursAreKeptExactly: E1150 with 0.01, so the Line shows 0.01 (in the database `0.01`; in the JSON exactly 0.01) |
| 4 | Re-run does not duplicate | BPA14_rerunDoesNotDuplicateLines: after scenario 1's set-up, call the worker's `handle` again twice (mocked `JobClient`, `RETURNS_DEEP_STUBS`) for the instance. The Header still has exactly one Line for E1021 and one for E1099, the job completes each time, and no audit row is added |
| 5 | Unknown Work Pack | BPA14_unknownWorkPackReturnsNotFound: GET WP-999999 as ppc-team gives 404 "Work Pack not found" |
| 6 | Other roles cannot read | BPA14_otherRolesCannotReadLines: as production-planner, GET for a Work Pack with Lines gives 403, and the body holds no `employeeRef` |

Non-scenario tests:
- `supervisor` can read the Lines; HEAD as production-planner gives 403; anonymous gives 401; `workpackNumber=WP-12` gives 400.
- A known Work Pack with no Lines yet gives 200 and `[]`.
- **Audit:** six rows per new Line, by the uploader.
- **Snapshot:** after a Line exists, changing the Worker's role in `worker` does not change the Line (data model N3).
- **The BPA-15 incident:** the re-checked tests.

Stubs keep the classes compiling for the red run: a controller that returns an empty list, and the current placeholder worker.
All 6 scenario tests are expected to fail first for the expected reason. If one passes against a stub, I'll say so in the PR.

## Files that change
- Backend:
  - `worker/CreateHourJournalLinesWorker.java` (rewritten);
  - new `persistence/HourJournalLineRepository.java`, `api/HourJournalLineController.java`,
    `worker/CreateDigitalTimesheetsWorker.java` (BPA-15 placeholder);
  - `config/SecurityConfig.java` (the rule and `SUPERVISOR`).
- Tests:
  - new `HourJournalLineApiTest`;
  - `WorkerAssignmentApiTest`, `AssignmentFileApiTest` (re-check);
  - `bpatest/ProcessTestApplication` (exclude the BPA-15 placeholder).
- Docs: `REVIEW.md`, this plan.
- No BPMN, DMN, migration, seed, contract or portal change.

## Order of work
1. Commit this plan (Approved). You move statuses.
2. Tests first: the 6 scenario tests, the non-scenario tests and the re-checked tests, with stubs. Run them and show they fail
   for the expected reason.
3. Build the repository, the worker, the controller, the security rule and the BPA-15 placeholder. Get green, then run the full
   `./mvnw -B verify`.
4. Local stack (rebuild bpa-app), through the API:
   - a Work Pack assigned E1021 and E1099;
   - GET the Lines as ppc-team and as supervisor (200), and as production-planner (403);
   - in Operate, the instance at ServiceTask_CreateDigitalTimesheets with the BPA-15 incident.
5. qa-reviewer → REVIEW.md → fixes → plan Closed → push → PR → wait for all CI checks green → tell you → Jira comments (text
   shown first). You merge.

## Risks
- **Test time:** about 6 Work Packs taken to AwaitingFile at about 20 s each, so the new class runs about 2 to 3 minutes.
- **Class order on CI:** handled by the `@BeforeAll` clean-up, as in BPA-12 and BPA-22.

## Proof
`./mvnw -B verify` (red first, then green), the local-stack run with Operate, CI.

## Questions (for Khitaba, ASSUMPTIONs; Jira comment, text shown first)
- Q1: the readers are `ppc-team` and `supervisor` only (the story's ASSUMPTION, flagged as asked).
- Q2: a known Work Pack with no Lines yet answers 200 `[]`, not 404 (as BPA-7 for the Header).
- Q3: the Lines' `created_by` and audit user are the file's uploader (D63), not the system.

## Human-gap log
- Local Maven build: `subst S:` work-round and JAVA_HOME per command; Docker Desktop must be running.

## Build notes (9 Nov 2026, as built)
- Built as planned, with these details:
  - **Repository:** `persistence/HourJournalLineRepository` creates the Lines with one `INSERT ... SELECT ... ON CONFLICT (header_id, worker_id) DO NOTHING RETURNING ...` over the Work Pack's `worker_assignment` rows joined to
    `worker` and to the Header, in one `@Transactional` method; it writes six audit rows (`header_id`, `worker_id`, `role`, `designation`, `estimated_hours`, `status`) for each Line the insert returns, so a re-run
    returns nothing and audits nothing. The user is the assignment's `created_by`, the uploader of the file (D63). The read method returns the Lines by Badge No, or no value for an unknown Work Pack.
  - **Worker:** `CreateHourJournalLinesWorker` finds the Work Pack by process instance, calls `createFor`, and completes with no variables; a Work Pack with no Header fails the job with no retries and a message.
  - **Endpoint:** `HourJournalLineController` implements the generated `HourJournalLinesApi` (404 "Work Pack not found", 400 "Workpack number must match WP-######", an empty list for a Work Pack without Lines).
    `SecurityConfig` lets `ppc-team` and `supervisor` through on every method (new `SUPERVISOR` constant).
  - **BPA-15 placeholder:** `CreateDigitalTimesheetsWorker` fails with "create-digital-timesheets is built by BPA-15", excluded in `ProcessTestApplication` with the real Lines worker.
  - **Tests:** `HourJournalLineApiTest` has the 6 scenario tests and 5 more (supervisor, HEAD and anonymous, the 400s, the empty list, the audit rows, the role and designation snapshot).
  - **Re-checked earlier tests:** in `WorkerAssignmentApiTest` and `AssignmentFileApiTest` the constant for the step the instance stops at moved from ServiceTask_CreateHourJournalLines to ServiceTask_CreateDigitalTimesheets,
    `atBpa14Incident` became `atBpa15Incident` and `aRoutedInstanceStopsWithTheBpa14Incident` became `...Bpa15Incident` ("built by BPA-15"). No assertion was changed beyond that.
- Red run (0238dd3): 17 of 49 failed for the expected reasons: no Lines were ever created (`timed out waiting for 2 Line(s) ...; last: []`), the stub answered 200 where 404 and 400 were expected, and the re-checked tests found the
  instance still at ServiceTask_CreateHourJournalLines. One test passed against a stub, said in the PR: `aWorkPackWithoutLinesYetAnswersAnEmptyList` (the stub returns an empty list). `BPA14_otherRolesCannotReadLines` failed
  in its set-up (no Lines), so its 403 assertion was not exercised red; it is exercised green, and `supervisorReadsAndHeadAndAnonymousFollowTheRule` covers the same rule.
- Green: the three process classes 49/49, and the full `./mvnw -B verify` 226 tests with 0 failures.
- Local stack (rebuilt bpa-app, through the API with a real `.xlsx`): a Work Pack at AwaitingFile and a file with E1021 (40), E1099 (60), E1150 (0.01) and a rejected E9999: three Lines (Welder Grade 3 40.00, Fitter Grade 2 60.00,
  Rigger Grade 1 0.01, all Open, by production-team), none for E9999, 18 audit rows; GET as ppc-team 200 with the three Lines, as supervisor 200, as production-planner 403, an unknown Work Pack 404, `WP-12` 400; the instance at
  ServiceTask_CreateDigitalTimesheets with the incident "built by BPA-15". The first attempt failed in my script, not in the code (it ran just after midnight in Riyadh and its schedule date was a day inside the lead time:
  "Date cannot be earlier than 16 Nov 2026"); I widened its offsets and ran it again. Not checked in a browser (no portal change).
- Size: 3 points as estimated.

## Review fixes (9 Nov 2026, after the qa-reviewer pass; see REVIEW.md)
- **Snapshot against a re-run (M2):** the snapshot test now changes the Worker's role and designation and then runs the job again; the Line keeps Welder / Grade 3 and its hours, there is still one Line, and no audit row is
  added. (Before, replacing `DO NOTHING` with `DO UPDATE` would have passed every test.)
- **Pinned by tests that passed on first run (said in the PR):** a Work Pack without a Header fails the job with no retries, the message and no completion; a job creates Lines only for its own Work Pack (another Work Pack's
  assignment, whose Lines were removed by hand, gets none); the Lines are ordered by Badge No (the file lists E1150, E1099, E1021); a trailing slash on the path is never a 200 with data; the audit test now checks all six
  columns with their values and one `changed_at` equal to the injected clock's instant.
- **Test hygiene (L2, L6):** "no Line for E9999" was always true (E9999 is not in the master), so the test now also waits for the instance to pass the step and counts the Lines under the Header; the audit counts are for this
  Work Pack's Lines only, not the whole `audit_log`.
- **Repository (L7):** the insert selects `ORDER BY a.worker_id`, so two concurrent jobs take the unique-index locks in the same order.
- **Index (M1):** `intent/README.md` is regenerated; the first run was cut short when the command was stopped, which dropped nine rows. It now differs from main by the BPA-14 row only.
- **Header (L9):** "Story last updated" is 2026-11-09T11:28:07 (status moved to In Progress; no content change).
- **Not changed:** the 403 assertion of `BPA14_otherRolesCannotReadLines` was not seen failing red (the test failed in its set-up first); `supervisorReadsAndHeadAndAnonymousFollowTheRule` and the green run exercise it. I did not
  remove the rule to prove it.
- Final run after the review fixes: full `./mvnw -B verify` 229 tests, 0 failures. No portal change, so no frontend run beyond CI.
