# Plan: BPA-13 Production Team can replace a Work Pack's Worker assignment with a new Excel file
Jira: BPA-13 (Story, epic BPA-3)
Story last updated: 2026-11-06T17:06:52.920+0400 (Sprint and Rank only; the content last changed 29 Oct 13:46, by Faraz)
Status: Approved (Ahsan, Build Lead, 2026-11-09)
Approver: Ahsan (Build Lead)
Branch: BPA-13-replace-worker-assignment, from main 2e085e2


## Context
Since BPA-15 (merged, 2e085e2) a Work Pack's first assignment runs to the end, and the Work Pack is **Assigned**. The team then
has no way to change who is on it. BPA-12 answers any file for an Assigned Work Pack with the placeholder "Replacement is not
available yet".

BPA-13 makes such a file a **replacement**:
- the upload sets the Work Pack to Replacing and starts `Process_PPC_WorkerReassignment` (message `AssignmentFileReplaced`);
- a new `replace-worker-assignment` worker replaces the Worker list all-or-nothing per Work Pack (D35);
- it keeps history (D38), keeps and flags Workers with hours loaded (D36), and brings the Hour Journal Lines (BPA-14) and the
  Digital Time sheets (BPA-15) into line (D7, D72);
- it sets the Work Pack back to Assigned.

It reuses BPA-12's upload screen and endpoint and BPA-22's row checks. There is no portal change: the result screen already shows
`wpOutcome` and synthetic rows.

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met |
| Gherkin, testable, unhappy paths | Met: 11 scenarios (1 outline, 2 rows) |
| BPMN IDs exist | Met: Process_PPC_WorkerReassignment / StartEvent_AssignmentFileReplaced (message `AssignmentFileReplaced`, correlation key workpackNumber, TTL 0) → ServiceTask_ReplaceAssignment (`replace-worker-assignment`, retries 3; "Outputs replacementApplied") → Gateway_IsReplacementApplied (`=replacementApplied = true`, default not applied) → EndEvent_AssignmentReplaced / EndEvent_ReplacementNotApplied |
| SOR per field | Met: worker_assignment, worker_assignment_history, digital_timesheet_line, hour_journal_line.status, digital_timesheet.submission_status, workpack.assignment_state, assignment_file_row.wp_outcome (POC database). Data model §5.2–5.8, §8 |
| DMN complete | n/a |
| No blocking question | Met: D61, D71, D72, B1, C3, D75 decided |
| ≤ 8 points | **Not met by my estimate**: 5 on the story, about **10 to 12** by mine. **Ahsan decided to keep it whole** (9 Nov); recorded here, and I'll stop and tell you if it overruns further |
| Spec accepted | Met (Faraz, 2 Nov) |
| PO approved | Faraz, 28 Oct (comment 10385). **Re-confirmation after his own 29 Oct change (comment 10414) is not in Jira**; Ahsan reports Faraz re-confirmed (9 Nov). A Jira comment will record that (text shown first) |

Notes:
- **A Camunda behaviour is not yet known.** BPA-25 planned check 7: what correlate answers when a second `AssignmentFileReplaced`
  for the same key arrives while an instance is still active, and when nothing is waiting. Neither answer was recorded. Data model
  §8 says "the code is built against the observed response". So the first test here probes it (order of work 2). The upload's
  revert texts depend on it: the D75 path, and the "no instance starts" path, which reads "Replacement already in progress".
- The seed has E1021, E1099 and E1150; E9999 does not exist. The AC's Work Pack numbers are illustrative.

## How the previous assignment is replaced (the explicit rule)
The rule is in the sources, not invented here:
- BPA-13's Description, SOR Notes and AC scenarios 1, 2, 8 and 11;
- D7, D35, D36, D38 and D72;
- data model §5.3 and §5.4 (with F1, F12 and B14).

A correction to the brief: **`worker_assignment` rows are not moved into `worker_assignment_history`.** 03-sor-matrix only names
both tables as POC workflow state. The history is an append-only change log, one row per change with old and new hours, and the
assignment rows are changed in place.

Per Worker, comparing the Work Pack's current assignments with the new file (only when the whole file is valid for that Work Pack):

| Worker | `worker_assignment` | `worker_assignment_history` | Hour Journal Line (BPA-14) | Digital Time sheet (BPA-15) | Result row |
|---|---|---|---|---|---|
| In file and assigned, **hours changed** | updated in place: new hours, remarks, `assignment_file_id` = the new file | one `Replaced` row (old → new) | **updated in place**: `estimated_hours` = new (scenario 8: "E1021's Line shows 50") | **left alone** (it holds no hours; its coding is a copy) | "Assigned" |
| In file and assigned, **hours unchanged** | remarks and `assignment_file_id` updated | **none** (data model 5.4, A) | left alone | left alone | "Assigned" |
| In file, **not assigned before** | new row | one `Added` row (0 → new) | new `Open` Line; **if a Closed Line exists from an earlier removal, it is reopened**: `Open`, new hours (D72) | new `Draft` time sheet with BPA-15's derivation; **if a Cancelled one exists, it is reset to `Draft`** (D72) | "Assigned" |
| Assigned, **not in file, no hours loaded** | **deleted**, after its history row (data model 5.3, A) | one `Removed` row (old → 0) | **status `Closed`** (not deleted) | **`Cancelled`** when it is an empty `Draft` (D7) | none |
| Assigned, **not in file, hours loaded** (≥ 1 `digital_timesheet_line` with hours > 0) | **kept**, hours unchanged, `flag = HoursLoadedNotRemoved` | **none** (data model 5.4, A) | left alone (stays `Open`) | left alone | an extra row with no row number: "Hours loaded; not removed" (F1) |

**When the replacement is not applied** (any rejected row for that Work Pack, D35):
- nothing above happens for that Work Pack;
- its rejected rows keep their own outcome, and its valid rows get `outcome` NULL;
- every row of that Work Pack gets `wp_outcome` "Replacement not applied: file has rejected rows" (B1, D71);
- other Work Packs in the same file are decided on their own (D71).

**ASSUMPTIONs at the gaps the sources leave** (each also a Jira question):
- **Q1.** On reopen (D72), a Line's `role` and `designation` and a time sheet's coding (Project, WBS, CBS, cost centre) **stay as
  first copied**; they are not refreshed (data model N3 and §5.7, copies at creation).
- **Q2.** An "empty" Draft time sheet means no `digital_timesheet_line` with hours > 0, the same test as "hours loaded". A sheet
  with only 0-hour lines is Cancelled.
- **Q3.** A flagged Worker who is listed again in a later file is updated normally, and **the flag stays** (data model B14: "no
  story clears the flag").
- **Q4.** History rows record **hours only**. A change of remarks alone writes no history row; it is still audited in `audit_log`
  (D64).

## Design: the upload (BPA-12's `AssignmentFileService`, the Assigned branch)
Replace the placeholder "Replacement is not available yet". For a Work Pack group in **Assigned**, after BPA-12's D73 duplicate
check (`published_message`):
1. **In the intake transaction:**
   - claim the pair (sha256, Work Pack) in `published_message`;
   - compare-and-set `assignment_state` Assigned → **Replacing** (`UPDATE … WHERE assignment_state = 'Assigned'`), with one
     audit row;
   - the rows read "Pending".
2. **After the commit:** correlate `AssignmentFileReplaced` with correlation key = Work Pack number and variables
   `assignmentFileId` and `workpackNumber` (`CamundaProcessClient.correlate`).
3. **No instance starts** (the answer recorded by the probe; expected 404) → compensating transaction:
   - revert Replacing → Assigned (audit row);
   - delete the claim;
   - Pending rows → "Replacement already in progress" (C3).
4. **Engine error** (5xx, unreachable, or any other exception) → the same revert; the rows read "Replacement could not be started;
   try again" (D75, ASSUMPTION text).

The other states are unchanged from BPA-12:
- Assigning → "Assignment still in progress; try again later";
- Replacing → "Replacement already in progress".

Every BPA-12 file-level rule still applies.

## Design: the worker (`worker/ReplaceWorkerAssignmentWorker`, `replace-worker-assignment`; skill `new-job-worker`)
- **Input:** the variables `workpackNumber` and `assignmentFileId`. The reassignment instance is not the Work Pack's own instance,
  so the Work Pack is found by number, not by process instance key.
- **One transaction** (`assignment/WorkerReplacementService` with `persistence/WorkerReplacementRepository`), with the Work Pack row
  locked `FOR UPDATE`:
  1. **Idempotent first (D65, 09 S7):** if no row of (file, Work Pack) is still Pending, the job already ran. Complete with
     `replacementApplied` = (no row of that Work Pack has `wp_outcome`).
  2. **State guard:** the Work Pack must be **Replacing**. Otherwise the job fails with no retries and a message naming the state
     (an incident), as BPA-22 and BPA-15 do.
  3. **Check the rows** with BPA-22's `AssignmentRowValidator`, unchanged.
     - **Any row rejected:** write the rejected outcomes, set NULL on the valid rows, and set `wp_outcome` on all of them. Set
       `replacementApplied = false`.
     - **Otherwise:** apply the table above, and write "Assigned" on the rows. Write the synthetic rows with `ON CONFLICT DO
       NOTHING` on the existing partial unique index. History rows use `ON CONFLICT DO NOTHING` on
       `UNIQUE(worker_assignment_id, change_type, assignment_file_id)`. Set `replacementApplied = true`.
  4. **Both outcomes:** `assignment_state` Replacing → **Assigned**, with an audit row (D61).
- **Complete** with `replacementApplied`. Gateway_IsReplacementApplied then ends at EndEvent_AssignmentReplaced or
  EndEvent_ReplacementNotApplied.
- **Who and when:** the uploader of the replacement file is `created_by`, `updated_by` and the audit user (D63). History
  `source_file` is that file's name. Times come from the injected `Clock` (S9). D64 audit rows are written for every changed
  business column of `worker_assignment`, `hour_journal_line`, `digital_timesheet` and `workpack`, and for each new history row.
- **Reuse, not copy, the BPA-14 and BPA-15 derivations.** An Added Worker's new Line and new time sheet come from the same SQL as
  the first assignment. Extract it from `HourJournalLineRepository.createFor` and `DigitalTimesheetRepository.createFor` into
  methods both workers call ("insert the missing Lines or time sheets for this Work Pack, as user U at time T, and audit them").
  Their tests stay green unchanged.
- Excluded from `bpatest/ProcessTestApplication`, as it needs the database.

## Scenario-to-test table
New class `WorkerReplacementApiTest`, on the BPA-15 pattern:
- the integration Camunda with workers on, `@DirtiesContext`;
- the TestClock at **03 Nov 2026** (the AC's Background);
- `@BeforeAll` clean-up of instances left at the catch event;
- the Background is one Work Pack run to EndEvent_WorkPackAssigned with E1021 (40) and E1099 (60), via `AssignmentFlow` and
  `awaitEnd`; replacement files come from `AssignmentFileBuilder`;
- hours loaded are seeded by inserting a `digital_timesheet_line` by hand, as the story says ("tests seed it").

| # | Scenario | Test |
|---|---|---|
| 1 | New file replaces | BPA13_newFileReplacesAssignment: (E1021 50, E1150 30). Then E1021 has 50; E1150 is assigned 30; E1099 is gone; history holds E1021 40→50 Replaced, E1150 0→30 Added and E1099 60→0 Removed, each with `source_file` of the new file; the state is Assigned; the instance ends at EndEvent_AssignmentReplaced |
| 2 | Hours loaded → kept and flagged | BPA13_removedWorkerWithHoursIsKeptAndFlagged: E1099 has an 8 h line. With (E1021 40, E1150 30), E1150 is assigned; E1099 stays 60 with `HoursLoadedNotRemoved`; the result has a row "Hours loaded; not removed" for E1099 |
| 3 | Rejected row → not applied | BPA13_replacementWithRejectedRowIsNotApplied: (E1021 50, E9999 20). E9999 reads "Worker not in worker master data"; every row has `wpOutcome` "Replacement not applied: file has rejected rows"; the assignments are unchanged; the state is Assigned; the instance ends at EndEvent_ReplacementNotApplied |
| 4 | Hours boundary (outline) | BPA13_replacementHoursBoundary: 0 → the row reads "Estimated hours must be greater than 0" and nothing is applied; then 0.01 on the same Work Pack → the row reads "Assigned" and the replacement is applied |
| 5 | While Assigning | BPA13_replacementWhileAssignmentInProgressIsRejected: a Work Pack set to Assigning by hand, with Workers. The result is "Assignment still in progress; try again later", no claim is made, and no reassignment instance starts |
| 6 | While Replacing | BPA13_concurrentReplacementIsRejected: set to Replacing by hand. The result is "Replacement already in progress", and no second instance starts |
| 7 | Start fails (D75) | BPA13_replacementStartFailureReverts: `@MockitoSpyBean CamundaProcessClient` throws `EngineUnavailableException` for `AssignmentFileReplaced`. The result is "Replacement could not be started; try again"; the assignments are unchanged; the state is Assigned; the claim is gone |
| 8 | Lines and time sheets follow | BPA13_linesAndTimesheetsFollowNewFile: E1150 has an Open Line (30) and a Draft time sheet; E1021's Line shows 50; E1099's Line is Closed and its empty Draft time sheet is Cancelled |
| 9 | Re-run idempotent | BPA13_rerunIsIdempotent: after scenario 1's replacement, `handle` is called twice with a mocked `JobClient`. Each Worker has exactly one Line and one time sheet, the history has no duplicates, there are no new audit rows, and the job completes with the same `replacementApplied` |
| 10 | Rejected row blocks only its Work Pack | BPA13_rejectedRowBlocksOnlyItsWorkPack: a second Background Work Pack with E1021 (20); one file with (WP-A, E1021 50) and (WP-B, E9999 10). A is applied (E1021 50); B's row reads "Worker not in worker master data" plus the `wpOutcome`; B is unchanged |
| 11 | Re-added Worker (D72) | BPA13_reAddedWorkerReopensLineAndTimesheet: a first replacement removes E1099, so its Line is Closed and its time sheet Cancelled. A second file (E1021 40, E1099 25): E1099 is assigned 25 with one Line, Open, 25; one time sheet, Draft; and history E1099 0→25 Added |

Other tests:
- **Probe (check 7):** a recorded test of Camunda's answers to a second correlate while active, to a correlate with nothing
  waiting, and to a correlate once the instance has ended.
- **Upload-level outcomes:** an unchanged-hours Worker writes no history row; a flagged Worker listed again keeps its flag (Q3); a
  same-content resend is "Already processed in an earlier upload" (D73).
- **Worker state guard:** a job for a Work Pack that is not Replacing fails with no retries.
- **Audit rows:** one test checking the audit rows of a replacement.
- **Unit tests:** `WorkerReplacementServiceTest` (mocked) for the decision table above, including the "no instance starts" revert
  of the upload (`AssignmentFileServiceTest`).

All 11 scenario tests fail first, against the BPA-12 placeholder, which leaves Assigned Work Packs untouched with "Replacement is
not available yet". The red run is shown before any code.

## Files that change
- Backend:
  - `assignment/AssignmentFileService.java` (the Assigned branch and the two reverts);
  - `persistence/AssignmentFileRepository.java` (compare-and-set and revert);
  - new `worker/ReplaceWorkerAssignmentWorker.java`, `assignment/WorkerReplacementService.java`,
    `persistence/WorkerReplacementRepository.java`;
  - `persistence/HourJournalLineRepository.java` and `DigitalTimesheetRepository.java` (the extracted "insert missing" methods).
- Tests:
  - new `WorkerReplacementApiTest`, `assignment/WorkerReplacementServiceTest`;
  - `AssignmentFileServiceTest` (the new branch);
  - `bpatest/ProcessTestApplication` (exclude the worker);
  - `AssignmentFlow` (a helper to run a Work Pack to the end with given Workers, and to await the reassignment's end events).
- Docs: `templates/README.md`, if it mentions replacements; `REVIEW.md`; this plan; `intent/README.md` (generated).
- No BPMN, DMN, migration, seed, contract or portal change. The contract's outcome list already names the replacement texts; I
  will check that it does not still name "Replacement is not available yet".

## Order of work
1. Commit this plan (Approved). You move statuses.
2. **Probe first:** run the check-7 test against the integration Camunda and record the answers in the plan. If the answer to
   "second correlate while active" is not a clean "nothing started", stop and tell you before building the revert on it.
3. Tests first: the 11 scenario tests, the other tests and the unit tests, against the placeholder. Run them and show they fail for
   the expected reason.
4. Build:
   - the upload branch and the reverts;
   - the extracted Line and time-sheet inserts;
   - the replacement service, repository and worker.

   Get green, then run the full `./mvnw -B verify`.
5. Local stack (rebuild bpa-app), through the portal and the API with real `.xlsx` files, on an Assigned Work Pack:
   - a replacement applied;
   - one not applied;
   - in Operate, the reassignment instance ending at the right end event.
6. qa-reviewer → REVIEW.md → fixes → plan Closed → push → PR → **read the CI result myself** and wait for all checks green → tell
   you → Jira comments (text shown first). You merge.

## Risks
- **Size:** about 10 to 12 against 5; kept whole by your decision.
- **Unknown Camunda answer (check 7):** handled by probing first.
- **Test time:** about 14 Work Packs run to the end at about 25 s each, plus replacements, so the new class runs about 7 to 9
  minutes.
- **Async writes:** the BPA-14/BPA-22 CI lesson applies. Every count in the new tests is scoped to its own Work Pack, never the
  whole table.
- **Mocking the engine for scenario 7** uses a spy bean in this class only (`@DirtiesContext` keeps it out of the others).

## Proof
The probe recorded, `./mvnw -B verify` (red first, then green), the local-stack run with Operate, CI.

## Questions (for Khitaba, ASSUMPTIONs; Jira comment, text shown first)
- Q1–Q4 above (refresh on reopen, "empty" time sheet, the flag kept, history for hours only).
- Q5: the D75 text "Replacement could not be started; try again" is an ASSUMPTION in the story; it is used as written.
- For Faraz: record his re-confirmation of Ready after the 29 Oct change.

## Human-gap log
- Local Maven build: map `subst S: "D:\LCS CLaude"` first (lost at reboot); JAVA_HOME per command; Docker Desktop must be running.

## Probe result (9 Nov 2026): Camunda's answers to the replacement message (BPA-25 check 7)
Recorded by `Bpa13CorrelationChecksTest` (Camunda only, no application workers; the instance waits at ServiceTask_ReplaceAssignment until the test completes its job):
- **First correlate** of `AssignmentFileReplaced` for a key with no running instance: **200**, a new `Process_PPC_WorkerReassignment` instance starts (7a).
- **Second correlate for the same key while that instance is active: 404 `NOT_FOUND`**, detail "Command 'CORRELATE' rejected with code 'NOT_FOUND': Expected to find subscription for message with name 'AssignmentFileReplaced' and correlation key '…', but no active
  subscription was found. A process instance with this correlation key is already active for a message start event with this message name in process IDs [Process_PPC_WorkerReassignment]. Only one active process instance per correlation key is allowed for message
  start events." No second instance starts (7b). So `CamundaProcessClient.correlate` (200 true, 404 false) already gives "no instance started" for this message, and the upload's revert to "Replacement already in progress" (C3) is built on it as planned.
- **A different key** starts its own instance (200): the guard is per Work Pack number.
- **After the instance ended, the same key** starts a new instance (200), so the key is free again (7d).
No change to the plan: this is the answer the design assumed.

## Build notes (9 Nov 2026, as built)
- Built as planned, with these details:
  - **Rule:** `domain/ReplacementPlanner` (pure) is the table in "How the previous assignment is replaced", one decision per Worker (Added, Replaced, Unchanged, Removed, Kept-and-flagged), hours compared as numbers.
  - **Upload:** `AssignmentFileService` now treats an Assigned Work Pack's group as a replacement: claim the pair (D73), `AssignmentFileRepository.startReplacing` (Assigned to Replacing by compare-and-set, one audit row), send `AssignmentFileReplaced` after the commit
    with `assignmentFileId` and `workpackNumber`. The send has three answers: delivered; not received (404, the probe's answer for a key that is already active), which reverts and reads "Replacement already in progress" (C3); failed (unreachable, or any other
    exception), which reverts and reads "Replacement could not be started; try again" (D75). A compare-and-set that finds another state unclaims and reads "Replacement already in progress". The BPA-12 text "Replacement is not available yet" is gone.
  - **Worker:** `ReplaceWorkerAssignmentWorker` (job `replace-worker-assignment`) reads `workpackNumber` and `assignmentFileId` from the message, finds the Work Pack by number, and completes with `replacementApplied`. `WorkerReplacementService.replace` runs in one transaction
    with the Work Pack locked: a retry (no row still Pending) answers without writing; a Work Pack that is not Replacing fails the job with no retries and its state in the message; rows are checked with BPA-22's validator; a rejected row means nothing is applied, valid rows get no
    outcome of their own and every row gets the Work Pack's message; otherwise the planner's steps are applied, the Lines and time sheets are synchronised, the accepted rows read "Assigned" and the state goes back to Assigned.
  - **Reuse, not copy:** `HourJournalLineRepository.createFor` and `DigitalTimesheetRepository.createFor` keep their behaviour and now call a private `insertMissing` that a replacement calls too, with the replacement's uploader as the user (their tests passed unchanged).
    New `syncWithAssignments` (add missing, reopen, follow the assignment's hours, Close the Lines of Workers no longer assigned) and `syncWithLines` (add missing, reset a Cancelled sheet to Draft when its Line is Open again, Cancel empty Draft sheets of Closed Lines).
    `WorkerAssignmentRepository.insert` answers the new assignment's id (the history row needs it); `assign` is unchanged for BPA-22.
  - **Audit (D64):** every changed business column of `worker_assignment` (old and new, new null for a deleted one), `worker_assignment_history` (all columns of a new row), `hour_journal_line`, `digital_timesheet` and the Work Pack's state, by the replacement's uploader (D63).
- **Departures from the approved plan (said in the PR):**
  - `WorkerReplacementServiceTest` (mocked) was not written. The decision table is covered by `ReplacementPlannerTest` (pure, 6 tests) and the process-level class covers the service end to end; the upload's reverts are in `AssignmentFileServiceTest` (4 new tests).
  - The clock in `WorkerReplacementApiTest` stays at 30 Oct 2026, not the AC's 03 Nov: nothing in a replacement reads the date, and the helpers that take a Work Pack to AwaitingFile are checked for 30 Oct.
  - New test file not in the plan's list: `Bpa13CorrelationChecksTest` (the probe). `AssignmentFlow` gained `settled`, `assignedWorkPack`, `reassignmentInstances` and `awaitReassignmentEnd`.
- Red run (4f7562d): planner 6 of 6 failed (empty plan); the 4 new upload tests failed with "Replacement is not available yet"; 12 of 14 process tests failed for the same text or because no reassignment instance ever started or ended. **Two passed against the old code, said here:**
  `BPA13_replacementWhileAssignmentInProgressIsRejected` and `BPA13_concurrentReplacementIsRejected` pin BPA-12's existing Assigning and Replacing answers, which this story does not change.
- Green: the new class 14/14 (one of my own tests had a wrong expectation, E1099 is correctly removed by a file that lists only E1021, and was fixed), BPA-14's and BPA-15's classes unchanged and green, and the full `./mvnw -B verify` 275 tests, 0 failures, 30 minutes.
- Local stack (rebuilt bpa-app, through the API with real `.xlsx` files, WP-000026): first assignment E1021 40 and E1099 60 run to Assigned (Lines Open, sheets Draft); replacement (E1021 50, E1150 30): result rows both "Assigned", assignments E1021 50 and E1150 30,
  history `E1021 40->50 Replaced`, `E1099 60->0 Removed`, `E1150 0->30 Added`, Lines E1021 Open 50, E1099 Closed, E1150 Open 30, sheets E1021 Draft, E1099 Cancelled, E1150 Draft, the state audit `AwaitingFile->Assigning`, `Assigning->Assigned`, `Assigned->Replacing`,
  `Replacing->Assigned`; a second replacement with a rejected row (E1021 60, E9999): rows with the Work Pack message, E9999 "Worker not in worker master data", assignments unchanged; the first file again: "File already processed". Camunda: `EndEvent_AssignmentReplaced` and
  `EndEvent_ReplacementNotApplied` each completed once for the Work Pack, no incident. Not checked in a browser (no portal change).
- Size: about 11 against the 5 on the story (kept whole by Ahsan, 9 Nov). The backend suite now takes about 30 minutes locally (it was about 20): every new class adds Work Packs that run to the end.
