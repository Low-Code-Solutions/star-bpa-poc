# Plan: BPA-75 Supervisor can only book hours for a Worker who is not on approved leave that day
Jira: BPA-75 (Story, epic BPA-4)
Story last updated: 2026-11-23T23:46:53.151+0400
Status: Closed (24 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-75-leave-blocks-hours, from main ec3051a

> Checked read-only: `BPA-75-leave-blocks-hours` is at **ec3051a** (the merge of PR #65, BPA-74), with no commits of its own and a clean tree (only the untracked `.postman/` and `postman/`, which stay out).

## Context
FRD-5-12 §5.12.5.4 says no hours are booked on a Worker's leave days. `worker_leave` (BPA-48) holds approved leave, and `Decision_HoursWithinBudget` already has the rule (rule 2: `workerOnLeave` → Blocked, `WORKER_ON_LEAVE`, after CBS_CLOSED, before the daily limit). But nothing reads the table: `HourLineService.checkBudget` (:235) and `PrepareHoursSubmissionWorker` (:75) both send `workerOnLeave = false`, and no message exists for the reason (HourLineService :250, "WORKER_ON_LEAVE cannot happen yet; BPA-75 gives its text"). BPA-75 makes the flag real. It has the same shape as BPA-69 (closed CBS).

Decision 12 Nov 2026 (Khitaba for the PO): half-day leave is out of scope; any approved leave on a date blocks the whole day.

## Readiness check (Definition of Ready, docs/02, against Jira 23 Nov 23:46)
| Check | State |
|---|---|
| Jira status | **In Progress** (`statuscategorychangedate` = `updated` = 23 Nov 23:46: the move; the 2 comments are 17 Nov "Ready" and 20 Nov naming note). Any other transition only when you say so (Q4) |
| Template, persona, outcome | Met (supervisor, production-team) |
| Gherkin incl. unhappy paths | Met: 4 scenarios (2 blocked, 2 allowed) |
| BPMN element IDs exist | Met: `UserTask_BookHours`, `ServiceTask_PrepareHoursSubmission` |
| DMN complete | Met: rule `Rule_WorkerOnLeave` in `Decision_HoursWithinBudget` (hit policy FIRST, inputs, outputs) |
| Every field has an SOR | **Contradiction (Q1):** SOR Notes say `worker_leave (employee_ref, leave_date, status)` "to add in the data model"; BPA-48 built `worker_leave (worker_id, leave_from, leave_to)`, approved only, no status (Q-DM23) |
| No blocking question | Q1 and Q2 do not block: build on what exists |
| intent.md / spec.md accepted | **Not met** (Jira DoR box unticked; as every Epic 2 story) |
| Estimate ≤ 8 | 3 |

## What already exists (code; closed plans BPA-50 to 53 and 69 are history only)
- **The decision:** `models/dmn/Decision_HoursWithinBudget.dmn`, FIRST: CBS_CLOSED, WORKER_ON_LEAVE, DAILY_LIMIT_EXCEEDED, PHASE_BUDGET_EXCEEDED, Allowed. **No DMN change.**
- **At save:** `HourLineService.checkBudget` runs on add (:124), change (:145) and the Timekeeper's amend (:174, BPA-53). `Sheet` already carries `workerId` and `employeeRef` (the badge). A Blocked answer becomes a 400 `Refused`, the text from `HourLineChecks` (`dailyLimitMessage`, `phaseBudgetMessage`, `cbsClosedMessage`).
- **Upload:** `TimesheetFileService` saves each row through `HourLineService.add`; a `Refused` becomes that row's outcome, the other rows still save (BPA-52). Upload is open to supervisor and production-team (`SecurityConfig`).
- **At send:** `PrepareHoursSubmissionWorker` builds one `lineCheck` per Draft or Returned line (`SendLine` has `workerId`, `badgeNo`, `workDate`) with the message texts. `BusinessRuleTask_CheckHoursWithinBudget`'s `outputElement` maps the reason to a message: CBS_CLOSED, DAILY_LIMIT_EXCEEDED, PHASE_BUDGET_EXCEEDED; **WORKER_ON_LEAVE gives `null`** today. A Blocked line returns the instance to Book hours with `validationMessages` (BPA-51).
- **The pattern to copy (BPA-69):** `persistence/CbsBlockRepository.closedForProductiveHours`, one lookup called from both places; `HourLineChecks.cbsClosedMessage`; one branch in the BPMN `outputElement`.
- **Data:** `worker_leave (id, worker_id, leave_from, leave_to inclusive, audit columns)`, V2 :492; SOR myPeople (ASSUMPTION), approved only (Q-DM23), overlapping rows allowed (data model §13, `worker_leave`). Seed: E1203 on leave 23 to 26 Nov 2026 (`R__seed_reference_data.sql` :279). **No table change.**
- **Date text:** `HourLineChecks.show(LocalDate)` gives "18 Nov 2026" (`d MMM yyyy`, English). `work_date` is already a business-zone date (Asia/Riyadh, ADR 0013), so no zone conversion is needed.

## Design
### 1. One shared lookup
- **New `persistence/WorkerLeaveRepository.onLeave(UUID workerId, LocalDate workDate)`:** `SELECT EXISTS (SELECT 1 FROM bpa.worker_leave WHERE worker_id = ? AND leave_from <= ? AND leave_to >= ?)`. Read only, one query; overlapping rows are harmless (EXISTS).
- **Both hard-coded `false` values are replaced by this one call, with no second copy:**
  - `HourLineService.checkBudget`: `workerLeave.onLeave(sheet.workerId(), date)`.
  - `PrepareHoursSubmissionWorker`: `workerLeave.onLeave(line.workerId(), line.workDate())`.
- The comments that say "false until BPA-75" (HourLineService :31, :230, :250; worker :26; the BPMN documentation of `ServiceTask_PrepareHoursSubmission`) are updated.

### 2. The message
- **`HourLineChecks.workerOnLeaveMessage(String badge, LocalDate date)`** builds "Worker " + badge + " is on leave on " + `show(date)` (Jira's text). Next to the other three, used in all places:
  - **At save (form):** `checkBudget`'s switch gains `case "WORKER_ON_LEAVE" -> HourLineChecks.workerOnLeaveMessage(sheet.employeeRef(), date)`, a **400**; the comment line at :250 goes.
  - **Upload row outcome:** the same `Refused` text, through `add`; the other valid rows are saved (BPA-52 behaviour, unchanged).
  - **At send:** the worker adds `workerOnLeaveMessage` to each line check (null when not on leave, as `cbsClosedMessage`).

### 3. The send re-check (one BPMN expression, Q5)
- `BusinessRuleTask_CheckHoursWithinBudget`'s `outputElement` gains one branch, after CBS_CLOSED: `else if budgetCheck.budgetReason = "WORKER_ON_LEAVE" then lineCheck.workerOnLeaveMessage`. Without it, leave approved after a line was saved would return the instance to Book hours with **no** message.
- No new element, no gateway change; the gateway's documentation already names BPA-75. The `ServiceTask_PrepareHoursSubmission` documentation gains `workerOnLeaveMessage`. `npm run lint:bpmn`.

### 4. Order
- The leave check is inside the decision, so it comes after BPA-50's and BPA-52's own checks, and second among the decision's rules (after CBS closed, before daily limit and phase budget). A line on a closed CBS **and** a leave day gets the CBS text. No separate check ahead of the decision (as BPA-69, Q2 there).

### 5. Change and amend (Q3)
- `checkBudget` also runs on the Supervisor's change (PUT) and the Timekeeper's amend (BPA-53), so both are refused on a leave day with the same 400 text. Recommended: keep it (one rule wherever a line's hours are set; BPA-69 decided the same for a closed CBS), ASSUMPTION, Jira comment.

### 6. Docs and contract
- `api/openapi.yaml`: the 400 text on the lines endpoints (add, change), the amend endpoint and the upload outcomes list (texts only).
- Data model §13 `worker_leave`: an "As built by BPA-75" note: who reads it, the inclusive range, approved-only (no status), the whole day (12 Nov decision).
- No ADR change, no migration, no frontend change (the screens already show 400 texts, row outcomes and `validationMessages`).

## Files that change
| Area | Files |
|---|---|
| Backend, new | `persistence/WorkerLeaveRepository.java` |
| Backend, changed | `timesheet/HourLineService.java` (lookup, WORKER_ON_LEAVE case, comments), `domain/HourLineChecks.java` (`workerOnLeaveMessage`), `worker/PrepareHoursSubmissionWorker.java` (lookup, `workerOnLeaveMessage`, comment) |
| Model | `models/bpmn/Process_PPC_TimesheetToProgress.bpmn`: the `outputElement` branch and the documentation of `ServiceTask_PrepareHoursSubmission` (Q5). DMN unchanged |
| Contract | `api/openapi.yaml` (texts only) |
| Tests | new `WorkerLeaveApiTest` |
| Docs | data model note; plan; REVIEW.md; `intent/README.md` regenerated |
| Frontend | none |

## Order of work
1. Approve the plan and commit it (`Approved`).
2. Write `WorkerLeaveApiTest` (4 scenarios and the extras), run it **red before any production code** (compile stub: `workerOnLeaveMessage` returning null if a test needs it), commit with the red output in the message.
3. Build: the repository, the message helper, the two lookups, the BPMN branch. Then green; then the neighbours `CbsBlockApiTest`, `HoursBudgetApiTest`, `BookHoursApiTest`, `TimesheetFileApiTest`, `HoursValidationApiTest`.
4. Contract texts and data model note; `npm run lint:bpmn`; full `./mvnw -B verify`; the local check; `qa-reviewer`; fixes (red first where behaviour changes); REVIEW.md; plan Closed. Push, PR and Jira comments only when you ask.

## Scenario-to-test table
One class, **`WorkerLeaveApiTest`**, on the integration stack (per-class Postgres, shared Camunda for the decision and the send), shaped as `CbsBlockApiTest`.

**Background (each test):**
- today **19 Nov 2026 09:00 Riyadh** (`TestClock`), so 16 to 19 Nov are in the open week (Fri 13 to Thu 19) and none is in the future;
- `BookHoursFixture.reset()` + `fmsBackground()` (WP-000457, CBS-139-PIP-01 open, E1021 and E1099 assigned);
- `worker_leave` emptied, then the test inserts its own rows; **no dependence on the E1203 seed**.

| # | Scenario | Test | Arrange / act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | Booking on a leave day is blocked | `BPA75_bookingOnLeaveDayIsBlocked` | E1099 leave 18 to 18 Nov; supervisor POSTs 8.00 h for E1099 on 18 Nov, WP-000457 | 400, detail "Worker E1099 is on leave on 18 Nov 2026"; no line saved | **red** (201: `workerOnLeave` false) |
| 2 | Booking on a working day is accepted | `BPA75_bookingOnWorkingDayIsAccepted` | E1099 leave 18 Nov only; 8.00 h on 17 Nov | 201, the line saved as Draft | **green before code** (a guard against an over-wide check) |
| 3 | Upload row on a leave day is rejected | `BPA75_uploadRowOnLeaveDayIsRejected` | E1099 leave 18 Nov; **production-team** uploads a file (`TimesheetFileBuilder`) with E1099 18/11/2026 and E1021 18/11/2026 | E1099's row outcome is "Worker E1099 is on leave on 18 Nov 2026"; E1021's row is saved; E1099 has no line | **red** (outcome "Saved") |
| 4 | Leave not yet approved does not block | `BPA75_leaveNotYetApprovedDoesNotBlock` | no `worker_leave` row for E1021 (an unapproved request cannot be stored, Q2); supervisor books 8.00 h for E1021 on 19 Nov | 201, saved as Draft | **green before code** (Q2) |

**Extras (not scenarios):**
- `leaveFromAndLeaveToAreBothBlocked`: E1099 leave 17 to 18 Nov; 17 Nov and 18 Nov both 400 with their own date in the text.
- `theDaysEitherSideAreAllowed`: same leave; 16 Nov and 19 Nov both 201.
- `leaveApprovedAfterSavingStopsTheSend`: an E1099 Draft line on 18 Nov saved with no leave; then a leave row for 18 Nov; the timesheet instance at Book hours; supervisor completes Book hours → the instance returns to `UserTask_BookHours` with "Worker E1099 is on leave on 18 Nov 2026" in `validationMessages`; the line is still Draft. **red**.
- `aChangeOnALeaveDayIsRefused` and `anAmendOnALeaveDayIsRefused` (Q3): a line saved before the leave row, then PUT or the Timekeeper's amend → 400 with the text.
- `theCbsCheckComesBeforeLeave`: leave on a day and PIP-01 closed → the CBS text (DMN order).
- `anotherWorkersLeaveDoesNotBlock`: E1021 on leave 18 Nov, E1099 books 18 Nov → 201.

`grep -c "void BPA75_"` = 4: 2 red, 2 green before code (guards); the plan and the PR say so.

## Proof (one per Definition of Done line)
| DoD line | Evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=WorkerLeaveApiTest verify`; `grep -c "void BPA75_" backend/src/test/java/com/lcs/star/bpa/WorkerLeaveApiTest.java` = 4; full `./mvnw -B verify`; `gh pr checks <n>` (Actions may not start: billing) |
| Process runs end to end locally | rebuild `bpa-app` and redeploy the model; **in the portal** as supervisor, Book hours for **E1203 on 24 Nov 2026** (seeded leave 23 to 26 Nov) → refused with "Worker E1203 is on leave on 24 Nov 2026" on the screen; 27 Nov (or the nearest day not in the future and not on leave) → saved. Needs a Work Pack with E1203 assigned in the open week; if none, the check says which one was used or that the API was used instead |
| BPMN and DMN match deployed | the deployed `Process_PPC_TimesheetToProgress` equals `models/` (whitespace-normalised hash, as BPA-67/69); `Decision_HoursWithinBudget` unchanged |
| Open questions resolved or logged | the Jira comment (shown first, posted on your yes) |
| Plan Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-75.md` |
| Demoed at sprint review | Human gap |

## Risks
- **The BPMN changes** (one FEEL branch). A typo would make every send fail; `lint:bpmn`, the send extra and `HoursBudgetApiTest`/`CbsBlockApiTest` send tests guard it. An instance already waiting keeps the old model version (no leave message at its send), as BPA-69's deploy note.
- **The local check on 24 Nov:** today is 24 Nov, so the date is not in the future; it needs E1203 assigned to a Work Pack whose time sheet exists in the open week. Unknown until the stack is read.
- **The seed's E1203 leave:** no test uses E1203 today (grep: only the seed), so it cannot break an existing test. E1203 is not assigned to any seeded Work Pack: the local check first assigns E1203 to a Released Work Pack through the portal's Worker assignment upload (D58), or the check says what was used instead.

## Questions
1. **The SOR Notes contradict the data model.** Jira: `worker_leave (employee_ref, leave_date, status)` "to add in the data model". Built (BPA-48): `worker_leave (worker_id, leave_from, leave_to)`, approved only, no status (Q-DM23, 11 Nov).
   - **Recommended:** build on what exists (no table change), and post a comment asking Khitaba to update the SOR Notes (I can't edit them).
2. **Scenario 4, "leave not yet approved does not block".** With no status column an unapproved request cannot be stored, so the test can only show that a Worker with no leave row is allowed (green before code).
   - **Recommended:** accept that, and say so in the same Jira comment.
3. **Change and amend on a leave day.** `checkBudget` also runs on the Supervisor's change (PUT) and the Timekeeper's amend (BPA-53), so both are refused. Jira is silent.
   - **Recommended:** keep it (one rule wherever a line's hours are set, as BPA-69 decided for a closed CBS), ASSUMPTION, in the Jira comment.
4. **Jira:** BPA-75 is In Progress. I'll ask before any transition or comment, and show each comment's text first.
5. **The BPMN expression.** You said backend only; the send re-check needs one FEEL branch in `BusinessRuleTask_CheckHoursWithinBudget`'s `outputElement`, or a leave approved after saving returns to Book hours with no message.
   - **Recommended:** add the branch (no new element; as BPA-69).
   - Alternative: no model change, and the backend fills a missing message from `budgetReason` (a second copy of the mapping).
6. **ASSUMPTIONs:** the message is Jira's; `leave_from` and `leave_to` inclusive (data model); a closed CBS wins over leave (DMN order, ASSUMPTION there); the local check uses E1203's seeded leave.

## Resolutions (Ahsan, 24 Nov 2026, plan approved as written)
- **Q1:** build on the existing `worker_leave` (no table change); a Jira comment asks Khitaba to update the SOR Notes (shown first, posted on a yes).
- **Q2:** scenario 4 is shown by a Worker with no leave row (green before code); said in the same comment.
- **Q3:** change and amend on a leave day are refused with the same 400 text (ASSUMPTION, in the comment).
- **Q5:** one WORKER_ON_LEAVE branch in `BusinessRuleTask_CheckHoursWithinBudget`'s `outputElement`; no new element.
- **Q4, Q6:** as written; I ask before any Jira transition or comment.

## Progress and results (24 Nov 2026)
- **Red first (adbb0b2):** 6 of 11 `WorkerLeaveApiTest` tests failed (the line was saved, 201 or 200, and the upload row said "Saved", where a 400 and the leave text were expected; the send went on to Validate hours). Two of the four `BPA75_` scenarios were red (`bookingOnLeaveDayIsBlocked`, `uploadRowOnLeaveDayIsRejected`); `bookingOnWorkingDayIsAccepted` and `leaveNotYetApprovedDoesNotBlock` passed before code, as the plan said (guards).
- **Green (de56ae4):** `WorkerLeaveRepository.onLeave` (the one lookup, at save and at send), `HourLineChecks.workerOnLeaveMessage`, the WORKER_ON_LEAVE case in `HourLineService.checkBudget`, `workerOnLeave` and `workerOnLeaveMessage` in `PrepareHoursSubmissionWorker`, one FEEL branch in the send step's `outputElement`, contract texts, data model note. `WorkerLeaveApiTest` 11/11; neighbours `CbsBlockApiTest` 10, `HoursBudgetApiTest` 11, `BookHoursApiTest` 28, `TimesheetFileApiTest` 21, `HoursValidationApiTest` 14.
- **Full suite:** `./mvnw -B verify` 768 tests, 0 failures, BUILD SUCCESS. `npm run lint:bpmn` clean. No frontend change; DMN and migrations unchanged.
- **Deployed model:** the deployed `Process_PPC_TimesheetToProgress` equals `models/` (whitespace-normalised hash) and contains the WORKER_ON_LEAVE branch.
- **Local-stack check (24 Nov, over the API as supervisor; nobody clicked a portal screen):** E1203 (seeded leave 23 to 26 Nov) is not assigned to any seeded Work Pack, so a synthetic assignment, journal line and time sheet on WP-000001 were added by SQL and removed afterwards. The waiting hours submission of WP-000001 (at the Production Supervisor from earlier testing) was moved to Closed/Returned for the check, because "hours in approval" (409) is checked before the leave rule, and restored (stage ProductionSupervisor, outcome NULL). Results: 24 Nov refused 400 "Worker E1203 is on leave on 24 Nov 2026"; 23 Nov (leave_from) refused; 22 Nov (the day before) saved as Draft; 26 Nov was not reached by the leave rule (a future date, 400 "Work date cannot be in the future").

## After the `qa-reviewer` pass (24 Nov 2026; REVIEW.md "BPA-75 at de56ae4")
- **0 High, 0 Important, 5 Nits.** Nothing needed a code change.
- **Recorded (Nits):**
  1. A timesheet instance already waiting at Book hours keeps the old model version, whose `outputElement` has no WORKER_ON_LEAVE branch: with the new backend such a send can be Blocked with a null message. Redeploy the model with the backend (as BPA-69's deploy note).
  2. The Timekeeper's amend is refused on a leave day, even when the leave was approved after the line was sent (the plan's Q3 ASSUMPTION, as the CBS rule): in the Jira comment.
  3. "is read, only, by" in the data model note follows the BPA-69 wording; left.
  4. The `POST /timesheet-files` operation text does not mention the leave outcome (it is in the outcomes list and the lines endpoints, as the plan said): left.
  5. The extra tests have no `BPA75_` prefix (the plan counts four `BPA75_` tests, one per scenario).
- **Plan vs diff:** no departure; the diff holds exactly the planned files (repository, service, checks, worker, BPMN, contract, data model, `WorkerLeaveApiTest`). No existing test was edited.
- **For Ahsan and Jira (shown first, posted only on a yes):** (1) the SOR Notes name `worker_leave (employee_ref, leave_date, status)`; the table as built (BPA-48) is `(worker_id, leave_from, leave_to)`, approved leave only, no status (Q-DM23): Khitaba to update the SOR Notes; (2) scenario 4 ("leave not yet approved") can only be shown as a Worker with no leave row; (3) ASSUMPTIONs: change and the Timekeeper's amend are refused on a leave day; a closed CBS comes before leave (DMN order); leave dates are inclusive and block the whole day (12 Nov decision); (4) deploy note above.

## Human-gap log
- **DoR:** intent.md and spec.md not accepted; the SOR Notes need Khitaba's update (Q1).
- **DoD:** "Demoed at sprint review". People.
- **DoD:** "passing in CI". Actions may not start (billing).
