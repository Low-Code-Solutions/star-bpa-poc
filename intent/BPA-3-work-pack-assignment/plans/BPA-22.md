# Plan: BPA-22 Production Team can have each assignment row validated
Jira: BPA-22 (Story, epic BPA-3)
Story last updated: 2026-11-06T12:58:36.056+0400 (status only; the content last changed 28 Oct 13:29, before the PO's approval at 13:31)
Status: Approved (Ahsan, Build Lead, 2026-11-06)
Approver: Ahsan (Build Lead)
Branch: BPA-22-validate-assignment-rows, from main d4fdc13


## Context
BPA-12 (merged, d4fdc13) takes the upload, records every row and sends `AssignmentFileReceived`. The routed instance then
stops at `ServiceTask_ValidateWorkerAssignment` with the placeholder incident "built by BPA-22", and its rows read "Pending".

BPA-22 builds the real `validate-worker-assignment` worker. For the rows of the file that belong to this instance's Work
Pack, it:
- checks every row;
- saves the valid Workers in `worker_assignment`;
- writes every row's outcome to `assignment_file_row`, so the BPA-12 result screen shows it;
- sets `assignment_state`;
- outputs `assignedWorkerCount` for `Gateway_HasAssignedWorkers`.

The next step, `ServiceTask_CreateHourJournalLines` (`create-hour-journal-lines`), is BPA-14. Until then it raises an
incident "built by BPA-14", the same way BPA-12 did here.

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met |
| Gherkin, testable, unhappy paths | Met: 13 scenarios (2 outlines: 7 and 2 rows) |
| BPMN IDs exist | Met: ServiceTask_ValidateWorkerAssignment (`validate-worker-assignment`, retries 3; documentation: "outputs assignedWorkerCount"), Gateway_HasAssignedWorkers (Yes `=assignedWorkerCount > 0`; default No → Gateway_MergeAssignment), ServiceTask_CreateHourJournalLines (`create-hour-journal-lines`) |
| SOR per field | Met: worker_assignment, assignment_file_row.outcome, workpack.assignment_state (POC database); worker (myPeople copy). Data model §5.2, §5.3, §7.3, §8 |
| DMN complete | n/a |
| No blocking question | Met: C11, C14, C17, D5, D14, D32, D33, D49, D58, D59, D61 decided; STAR questions 7 and 8 non-blocking |
| ≤ 8 points | 5. My estimate is 5 to 6, including the re-check of the BPA-12 tests |
| Spec accepted | Met (Faraz, 2 Nov) |
| PO approved | Faraz, 28 Oct (comment 10389). The content has not changed since (changelog: last AC and Description edits on 28 Oct, before the approval) |

Notes:
- The seed has E1021, E1099 and agency Worker E2001 (`agency_flag` true); E9999 does not exist.
- Jira's Development panel already shows PR #32, because its text names BPA-22. No action needed.

## Design: the worker (`worker/ValidateWorkerAssignmentWorker`, replacing the placeholder; skill `new-job-worker`)
1. **Inputs:**
   - the Work Pack, found by `job.getProcessInstanceKey()` (as `GenerateWorkPackPrintWorker`, `WorkPackRepository.find`);
   - `assignmentFileId` (the message variable, BPA-12).
2. **Rows:** those of `assignmentFileId` whose `workpack_number` equals the Work Pack's number (BPA-12 groups on the exact
   trimmed text). A blank-number row belongs to no Work Pack: BPA-12 already answers it at intake ("Workpack number is
   required", data model §7.3 B6).
3. **One transaction** (`TransactionTemplate`, as BPA-12), with the Work Pack row locked (`FOR UPDATE`):
   - **Idempotent first** (D65, 09 S7). If none of these rows is still `Pending`, the job already ran. Complete with
     `assignedWorkerCount` = the number of `worker_assignment` rows for (Work Pack, file). This is not keyed on the
     process instance, because one instance can loop to a second file (D5).
   - **State guard:** `assignment_state` must be `AwaitingFile`. Otherwise throw, so the job fails and Operate shows why.
   - Check the rows (below); insert accepted rows into `worker_assignment` with `ON CONFLICT (workpack_id, worker_id) DO
     NOTHING` (data model: the idempotency key); write each row's outcome.
   - At least one accepted row: `assignment_state` AwaitingFile → **Assigning** (D61), with one audit row.
   - Every row rejected: nothing is saved and the state stays **AwaitingFile** (D61, D5). No change, so no audit row.
4. **Complete** with `assignedWorkerCount`. On 0, Gateway_HasAssignedWorkers routes back through Gateway_MergeAssignment
   to the catch event, which waits for a corrected file.
- **Rejected rows are not a BPMN error.** The model has no boundary event here, only the gateway on the count. This
  departs from the skill's "invalid input → BPMN error" rule, which applies to user-task saves.
- **Who did it:**
  - `created_by`, `updated_by` and `audit_log.username` are the file's `assignment_file.uploaded_by` (D63: the person
    whose action made the change).
  - Not `completedBy`, which would be the planner's earlier task completion.
  - `bpa-service` if the file has no uploader.
- **Time:** `created_at`, `updated_at` and `changed_at` come from the injected `Clock` (09 S9).
- **Audit (D64, §6.1):**
  - Each new `worker_assignment` gets one row per business column: `workpack_id`, `worker_id`, `estimated_hours`, `remarks`
    (when present) and `assignment_file_id`. The old value is null.
  - The state change gets one row.
  - `assignment_file_row.outcome` updates are not audited, as BPA-12 left that intake log unaudited (**ASSUMPTION**, Q4).

## Design: row checks (`domain/AssignmentRowValidator`, pure)
One outcome per row: the first rule that fails, in this order (**ASSUMPTION**, Q1; the AC gives the texts, not the order).
1. Badge No blank → "Badge No is required"
2. The same Badge No appears on more than one row of this Work Pack → **all** those rows "Worker listed twice for this
   Work Pack" (D32). The duplicate check counts every row with that Badge No, whatever else is wrong with it.
3. Badge No not in `worker.employee_ref` → "Worker not in worker master data". The match is exact on the trimmed text, the
   same as BPA-12's Worker-name join (**ASSUMPTION**, Q2).
4. Estimated hours, in the outline's order (D14):
   - blank → "Estimated hours is required";
   - not a number (`BigDecimal` cannot parse it, e.g. `abc`, `1,000`) → "Estimated hours must be a number";
   - ≤ 0 → "Estimated hours must be greater than 0";
   - more than 2 decimal places after trailing zeros are stripped → "Estimated hours can have at most 2 decimal places";
   - \> 99999.99 → "Estimated hours cannot exceed 99,999.99".
5. Remarks longer than 250 characters, counted as code points like PostgreSQL's `char_length` → "Remarks can be at most
   250 characters" (D59).
6. Otherwise → "Assigned", saved with the parsed hours and the remarks.

Other rules:
- Agency Workers are assigned like any other (C11).
- The file's Employee name is not read (D59): BPA-12's reader ignores it, and the result shows the master name.
- No active-status check and no hours-against-phase check (story: ASSUMPTION; Epic 2).

## Design: the BPA-14 placeholder
`worker/CreateHourJournalLinesWorker` (`create-hour-journal-lines`) fails every job with no retries and the message
"create-hour-journal-lines is built by BPA-14". This follows the skill's rule for a job type that a later story builds. It
is excluded from `bpatest/ProcessTestApplication`, like the validate worker.

## Design: what the user sees
- The upload's answer is rebuilt from the database after the send (BPA-12). The worker may finish before or after that
  read, so each routed row reads either "Pending" or its final outcome. "Refresh result" reads it again.
- No portal change. The screen already shows any outcome text. The BPA-12 React test already covers a row leaving
  "Pending" on Refresh.
- `templates/README.md`: the sentence "read Pending until BPA-22 is built" is updated to say BPA-22 now answers them.

## Re-check of the BPA-12 tests (said in the PR)
These tests expected the placeholder incident or an instance resting at VALIDATE. With the real worker, the instance
passes VALIDATE and stops at ServiceTask_CreateHourJournalLines with the BPA-14 incident.
- `BPA12_fileForScheduledWorkPackIsRouted`, `BPA12_fileWithSeveralWorkPacks`, `BPA12_sameFileAgainIsIgnored`,
  `BPA12_resendOfUnroutedFileIsAccepted`, `BPA12_resendIsDuplicateOnlyForPublishedWorkPacks`:
  - each upload-answer row is "Pending" or its BPA-22 outcome;
  - the poll waits for the instance to have passed VALIDATE (at CreateHourJournalLines).
- `aRoutedInstanceStopsWithTheBpa22Incident` becomes `aRoutedInstanceStopsWithTheBpa14Incident` ("built by BPA-14", at
  ServiceTask_CreateHourJournalLines).
- `BPA12_uploadIsRecorded` reads `outcome` in the database right after the upload. It now accepts "Pending" or "Assigned".
- The other BPA-12 tests are unchanged.

## Scenario-to-test table
New class `WorkerAssignmentApiTest`, on the BPA-12 pattern:
- the integration Camunda with workers on, the TestClock at 30 Oct 2026 Asia/Riyadh, `@DirtiesContext`;
- `@BeforeAll` cancels instances left at the catch event (the BPA-12 CI lesson);
- Work Packs taken the real way to AwaitingFile (create, confirm, sign off, schedule, print);
- files from `AssignmentFileBuilder` only, never `templates/demo`;
- the shared set-up (`awaiting()`, `upload()`, outcome and state reads) is moved from `AssignmentFileApiTest` into a
  test helper both classes use.

"Assigned" in the assertions below means a `worker_assignment` row with those hours, and the result row reads "Assigned".

| # | Scenario | Test |
|---|---|---|
| 1 | Workers are assigned from the file | BPA22_workersAreAssignedFromFile: E1021 40, E1099 60 → two `worker_assignment` rows (hours, `assignment_file_id`, `created_by` production-team); GET result reads both "Assigned"; the instance passes Gateway_HasAssignedWorkers (Yes) and stops at ServiceTask_CreateHourJournalLines with the BPA-14 incident |
| 2 | Agency Worker assigned (C11) | BPA22_agencyWorkerIsAssigned: E2001 30 → assigned, "Assigned" |
| 3 | All rows rejected leaves the Work Pack waiting (D5) | BPA22_allRowsRejectedKeepsWaiting: E9999 40 → "Worker not in worker master data", no `worker_assignment`, the instance back at the catch event (Gateway_HasAssignedWorkers took No); a corrected file with E1021 40 → assigned |
| 4 | Worker not in master data | BPA22_workerNotInMasterDataIsRejected: E1021 40 and E9999 20 → E9999 rejected, E1021 assigned |
| 5 | Same Worker twice (D32) | BPA22_duplicateWorkerRowsAreRejected: E1021 40 and E1021 20 → both "Worker listed twice for this Work Pack", E1021 not assigned |
| 6 | Same Worker on two Work Packs | BPA22_sameWorkerOnTwoWorkPacksIsValid: two AwaitingFile Work Packs in one file → E1021 assigned to both, with 40 and 20 |
| 7 | Estimated hours outline (7 rows) | BPA22_estimatedHoursValidation: blank, abc, 0, 0.001 and 100000 on one Work Pack, one file each (every row rejected, so it loops back and waits again); then 0.01 → "Assigned"; 99999.99 on a second Work Pack → "Assigned" |
| 8 | Workpack number required | BPA22_workpackNumberRequired: (WP, E1021, 40) and (blank, E1099, 20) → second row "Workpack number is required" (written at intake, BPA-12), E1021 assigned |
| 9 | Badge No required | BPA22_badgeNoRequired: (WP, blank, 20) → "Badge No is required", E1021 assigned |
| 10 | Employee name ignored | BPA22_employeeNameIsIgnored: Employee name "Different Name" → assigned; result `workerName` "Worker E1021" |
| 11 | Remarks length (2 rows) | BPA22_remarksLength: 251 characters → "Remarks can be at most 250 characters" (loops back); then 250 on the same Work Pack → "Assigned", remarks stored |
| 12 | Accepted row sets Assigning (D61) | BPA22_acceptedRowSetsAssigning: E1021 40 and E9999 20 → E1021 assigned, `assignment_state` Assigning, one audit row AwaitingFile → Assigning |
| 13 | Every row rejected → AwaitingFile (D61, D5) | BPA22_allRejectedSetsAwaitingFile: E9999 40 → state AwaitingFile; a corrected file is routed (claim, rows Pending or Assigned) |

Non-scenario tests:
- **Retry:** call the worker's `handle` twice for the same job (mocked `JobClient` with `RETURNS_DEEP_STUBS`, instance key
  from partition 2). This gives one set of `worker_assignment` rows, the same `assignedWorkerCount`, and no second audit
  row.
- **State guard:** Pending rows on a Work Pack set to Assigning by hand → the job fails.
- **Audit rows** for a new assignment.
- **`AssignmentRowValidatorTest` (unit):**
  - the rule order (a duplicate with bad hours; blank Badge No twice);
  - hours parsing: `1,000`, `-5`, `40.10` (2 decimals after stripping), `1E-7`;
  - remarks of 250 code points made of emoji.
- **The BPA-14 incident** (re-checked BPA-12 test).

All 13 scenario tests fail first, against the placeholder (the incident leaves the rows "Pending"). The red run is shown
before any worker code.

## Files that change
- Backend:
  - `worker/ValidateWorkerAssignmentWorker.java` (rewritten);
  - new `worker/CreateHourJournalLinesWorker.java`, `domain/AssignmentRowValidator.java`,
    `persistence/WorkerAssignmentRepository.java`.
- Tests:
  - new `WorkerAssignmentApiTest`, `domain/AssignmentRowValidatorTest`, and a shared assignment test helper;
  - `AssignmentFileApiTest` (re-check, uses the helper);
  - `bpatest/ProcessTestApplication` (exclude the BPA-14 placeholder).
- Docs: `templates/README.md` (one sentence), `REVIEW.md`, this plan.
- No BPMN, DMN, migration, seed, contract or portal change.

## Order of work
1. `git switch main && git pull`, then create the branch. Commit this plan (Approved). You move statuses.
2. Tests first: the helper, the 13 scenario tests and the re-checked BPA-12 tests, the validator unit test against a stub.
   Run them and show they fail for the expected reason.
3. Build the validator, the repository, the worker and the BPA-14 placeholder. Get green, then run `./mvnw -B verify`.
4. Local stack (rebuild bpa-app), through the API with a real `.xlsx`:
   - a Work Pack to AwaitingFile, then upload → "Assigned", `worker_assignment` rows, Assigning, the BPA-14 incident in
     Operate;
   - an all-rejected file → AwaitingFile and the instance waiting again.
5. qa-reviewer → REVIEW.md → fixes → plan Closed → push → PR → wait for all CI checks green → tell you → Jira comments
   (text shown first). You merge.

## Risks
- **Test time:** about 16 Work Packs taken to AwaitingFile at about 20 s each, so the new class runs about 6 minutes.
  Rejected examples reuse their Work Pack (it loops back) to keep this down.
- **Upload answer race:** a routed row may read "Pending" or its final outcome in the upload's answer, so the tests accept
  both there and assert the final outcome through GET result.
- **Class order on CI:** handled by the `@BeforeAll` clean-up, as in BPA-12.

## Proof
`./mvnw -B verify` (red first, then green), the local-stack run with Operate, CI.

## Questions (for Khitaba, ASSUMPTIONs; Jira comment, text shown first)
- Q1: the order of the row checks when a row has several faults, and that the duplicate check counts a row whatever else
  is wrong with it.
- Q2: Badge No matched exactly on the trimmed text (no case folding).
- Q3: resending the same all-rejected file reads "File already processed" (BPA-12's D73 guard). The user must change the
  file to resend.
- Q4: `assignment_file_row.outcome` updates are not written to `audit_log` (the rows are themselves the intake log, as in
  BPA-12).

## Human-gap log
- Local Maven build: `subst S:` work-round and JAVA_HOME per command; `npm.cmd`; Docker Desktop must be running.
