# Plan: BPA-67 System can lock a week's progress and complete Work Packs approved at 100%
Jira: BPA-67 (Story, epic BPA-4)
Story last updated: 2026-11-22T16:03:09.233+0400
Status: Approved (Ahsan, 22 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-67-lock-week-complete-workpacks, from main 7111793

> Checked read-only: main 7111793 is the merge of PR #59 (BPA-66). `BPA-67-lock-week-complete-workpacks` is at 7111793 with no commits of its own.

## Context
After the Project Manager approves a week (BPA-66), the weekly instance stops at `ServiceTask_CompleteWorkPacks`, because neither it nor `ServiceTask_LockProgressPeriod` has a worker. BPA-67 closes the week:
- **Complete the Work Packs:** every Work Pack approved at exactly 100% this week becomes Completed, and its time sheet instance ends through the message `WorkPackCompleted`.
- **Lock the week:** the period becomes `Locked`, and its approved % and Earned man-hours never change again.
- **No hours on a Completed Work Pack:** it takes no more hours.
- **Monthly hours lock:** BPA-64 already locks the month and freezes the snapshot. BPA-67 adds the line rule: no line dated in a locked month can be added, changed or approved.

## Readiness check (Definition of Ready, docs/02, against Jira 22 Nov 16:03)
| Check | State |
|---|---|
| Jira status | **In Progress** since 22 Nov 16:03. No other transition without asking (Q10) |
| Template, persona, outcome | Met (the System) |
| Gherkin incl. unhappy paths | Met: 12 scenarios (one Outline). The unhappy paths are the Completed Work Pack, the 99.99% boundary, the lock, the month lock and the sent-back week |
| BPMN element IDs exist | Met: every ID Jira names exists. No BPMN change is planned (Q4) |
| Every field has an SOR | Met (`progress_period.status`, `locked_at`, `workpack.status`, the message) |
| DMN | None needed |
| No blocking question | **Not met in Jira:** "WorkPackCompleted message TTL to agree with the build team (Ahsan)" (Q1). Q2, Q3, Q6 and Q7 change the build |
| intent.md / spec.md accepted | **Not met** (box unticked; same as BPA-58 to BPA-66) |
| Estimate ≤ 8 | 5. The plan finds more: two workers, a message TTL, about eight guards across the hours paths and the progress writers, three existing-test edits (Risks) |
| Comments | 10484: F4/F5 cut-off decided; TTL open for Ahsan. 10495: the S5 monthly lock; it supersedes the description's C-E2-8 bullet. 10558: Ready on 17 Nov |

## What already exists (code and closed plans; history only) and what BPA-67 reuses
- **BPA-58: the cut-off.**
  - `OpenProgressPeriodWorker` (`open-progress-period`) uses the process instance key as its re-run key; a second instance for the same week fails with no retries.
  - `ProgressPeriodRepository.open` is covered by `FmsHoursExportApiTest.BPA58_cutOffCreatesTheWeeklyFile` and `aRerunOfOpenProgressPeriodReturnsThePeriodItOpened`.
  - **Reused as is:** scenarios 1 and 2 should pass before any code.
- **BPA-50: the lines API (`HourLineService`).**
  - "Week ending <d MMM yyyy> is closed" (400) means a `progress_period` row exists for the week.
  - The check applies to a Draft line on change or delete. **A Returned line in a cut-off week can still be changed and deleted** (code comment "BPA-67 a"; `BookHoursApiTest.aDraftLineInACutOffWeekIsClosedButAReturnedOneCanBeCorrected`).
  - "Line is not editable (status <status>)" (409).
  - `Sheet.workpackStatus` is loaded but never read. Nothing checks the Work Pack status.
  - **Reused:** the guard order and the problem+json shape.
- **BPA-53: sending hours.** `PrepareHoursSubmissionWorker` and `SubmitHoursWorker` send every Draft and Returned line, whatever its date (no week or month check). So scenario 8's "send again" already works.
- **BPA-52: the upload.** A row is saved through `HourLineService.add`, and its outcome is the refusal text. **Reused:** the new texts become row outcomes with no extra code.
- **BPA-53, 56 and 57: the decisions.** `RecordHoursDecisionWorker` with `HoursSubmissionRepository.decide`, behind the task API (`TaskValidity`, `HoursSegregationCheck`). There is no week, month or Work Pack check.
- **BPA-64: the month lock.**
  - `MonthLockJob` runs at 00:10 Asia/Riyadh from the injected clock and calls `MonthLockService.lockDueMonth`.
  - On or after `bpa.hours.month-lock-day` (`AlertSettings.monthLockDay()`: 16 gives 16, anything else 1), it locks the previous month once in `hours_month_lock` (month UNIQUE) and freezes `cbs_manhour_snapshot`.
  - **Nothing reads `hours_month_lock`** ("Refusing hours dated in a locked month is BPA-67").
  - **Reused as is:** the job, the table and the setting (BPA-65's close window reads the same setting).
- **BPA-66: the approval path.**
  - `Gateway_IsWeekApproved` → `ServiceTask_CompleteWorkPacks` → `ServiceTask_LockProgressPeriod` → `EndEvent_PeriodLocked`.
  - `WorkpackProgressRow.unsentLines` lists the Draft and Returned lines of a Work Pack at 100%, shown on the PM screen as a table.
  - `ProgressPeriodRepository.lockState` takes the period row `FOR UPDATE`.
  - **Reused:** `lockState` for the lock and the guards.
- **BPA-49: publishing a message.**
  - `CamundaProcessClient.publish(name, key, messageId, variables)` calls `POST /v2/messages/publication` with **`timeToLive` hard-coded to 0**.
  - `TimesheetProcessStarter` guards duplicates with `published_message` (name, Work Pack number): it publishes, then records.
  - ADR 0008 lists every message with TTL 0, and `WorkPackCompleted` is not in it.
- **`Process_PPC_TimesheetToProgress`:**
  - `BoundaryEvent_WorkPackCompleted` is **interrupting and attached only to `UserTask_BookHours`**, with `correlationKey="=workpackNumber"`; `EndEvent_TimesheetsClosed` is reached only through it.
  - While hours are in approval the instance waits at `UserTask_ValidateHours`, `UserTask_ApproveHoursProduction` or `UserTask_ApproveHoursPpc`, and every decision loops back to `Gateway_MergeBooking` → `UserTask_BookHours`. So a message buffered with a TTL is caught when the instance is back at Book hours.
- **Writers of the week's progress rows and their "locked" checks** (text "Week ending <d MMM yyyy> is locked"):

  | Writer | Locked check? | Atomic? |
  |---|---|---|
  | `CompletionApprovalService.record` (BPA-60) | yes | no |
  | `CompletionProgressService.decide` (`reported_pct`, BPA-59) | **no** | no |
  | `ProgressCheckService.record` (`rule_result`, BPA-61) | **no** | no |
  | `EarnedManhoursService.calculate` (BPA-62) | yes | no |
  | `RetractionDecisionService.decide` (BPA-63) | yes | no |
  | `CbsReviewService.record` (BPA-64) | yes | no |
  | `WeekDecisionService.record` (BPA-66) | yes | **yes** |
- **`workpack.status`:** CHECK (Draft, Released, In Progress, Completed). In Progress is set when the first line is saved, and nothing sets Completed.

## Design
### 1. `complete-workpacks` (new `CompleteWorkPacksWorker`, `WorkPackCompletionService`)
1. **Complete in the database** (one transaction, with the period row taken through `lockState`):
   - Refuse a Locked week (the job fails with no retries).
   - Each Work Pack whose `workpack_progress.approved_pct = 100.00` for **this** period and whose status is not Completed → `Completed`, with one `audit_log` row and `updated_by` = `bpa-service`. A Work Pack at 99.99 is untouched.
   - The re-run key is the values: a Completed Work Pack is not written again.
2. **Then publish per Work Pack** (outside the transaction, as BPA-49 does: publish, then record):
   - `WorkPackCompleted`, correlation key and `messageId` = the Work Pack number, variables `workpackNumber`, **TTL P7D (ASSUMPTION, Q1)**.
   - It is skipped when `published_message` already has (`WorkPackCompleted`, Work Pack number).
   - A re-run inside the TTL is deduplicated by Camunda through the messageId; a duplicate answer counts as published (checked in the build).
   - `CamundaProcessClient.publish` gets a TTL argument, so BPA-49's call keeps 0.
3. **Failures:** a publish failure is not caught, so Camunda retries and then raises an incident (ADR 0007). The Work Pack status is already committed and is not undone.
4. **When the time sheet instance catches it:**
   - If the instance is at `UserTask_BookHours`, the boundary event fires at once.
   - If its hours are in approval, the message stays buffered until the instance is back at `UserTask_BookHours` (ASSUMPTION in Jira; the model's documentation already says so).
   - If it is not caught within 7 days, it expires and the instance keeps running (Q1).
   - Every Work Pack completed this week is published once: no Work Pack is completed without its message.

### 2. `lock-progress-period` (new `LockProgressPeriodWorker`, `ProgressLockService`)
- **The lock:** `lockState` (FOR UPDATE), then `status` CutOff → `Locked` and `locked_at` = now from the clock, with two `audit_log` rows. An already Locked period writes nothing (the re-run key). `EndEvent_PeriodLocked` is left as it is (Q4).
- **One shared guard:** `ProgressPeriodRepository.requireOpen(periodId)` takes the row `FOR UPDATE` and throws "Week ending <d MMM yyyy> is locked", the existing text. It is used inside the transaction of every progress write, so a write and the lock never interleave (Q3):
  - `CompletionApprovalService.record` (the approved %): replaces its unlocked status read. This covers scenario 7.
  - `EarnedManhoursService.calculate`, `RetractionDecisionService.decide` and `CbsReviewService.record`: replace their unlocked reads.
  - **New:** `CompletionProgressService.decide` (`reported_pct`) and `ProgressCheckService.record` (`rule_result`).
  - `WeekDecisionService` already locks the row.
- **No new check in the task API:** the instance has ended once the week is Locked, so its tasks no longer exist. The record steps are the guard.

### 3. A Completed Work Pack takes no hours (BPA-50 lines API and BPA-52 upload)
- **Add:** a new check in `HourLineService.add` right after the time sheet is found (before the date checks, so the story's text wins for 20 Nov): Work Pack Completed → **409 "Work Pack <WP> is Completed"** (Jira).
- **Upload:** the same text becomes the row outcome, through `add`.
- **Change and delete:** a Draft or Returned line of a Completed Work Pack is refused with the same 409 (ASSUMPTION, Q7). Normally there is none: the time sheet instance ended, and a 100% Work Pack's unsent lines were on the PM's screen.

### 4. Monthly hours lock: the line rule (S5)
- **The check:** `HourLineChecks.monthLocked(workDate)` is true when an `hours_month_lock` row exists for the work date's month. The text is **"Hours for <MMMM yyyy> are locked"** (Jira), for example "Hours for November 2026 are locked".
- **Where it applies** (status Q2; recommended **400**, the same as "Week ending … is closed"):

  | Path | Rule |
  |---|---|
  | A add (and E upload, as a row outcome) | refused. Checked before the week check (ASSUMPTION: the month text when both apply) |
  | B change, C delete | refused for **any** status once the month is locked, Returned included. Before the lock day a Returned line can still be changed (scenario 12) |
  | D amend (Timekeeper) | refused |
  | F send (`PrepareHoursSubmissionWorker`) | a Draft or Returned line dated in a locked month stops the send with the BPMN error `HOURS_INVALID` and "Hours for <MMMM yyyy> are locked", shown on Book hours (ASSUMPTION) |
  | G decisions (Q2) | the task API refuses **Validated and Approved** for a submission with any line dated in a locked month: 409 "Hours for <MMMM yyyy> are locked". Returned and Rejected stay allowed so the hours do not get stuck (ASSUMPTION) |
  | H FMS export | unchanged: lines approved before the lock still go to FMS (exporting does not change a line) |

- The job, the table and the snapshot are BPA-64's and stay unchanged.

### 5. A Returned line in a cut-off week (scenario 8)
This already works:
- the Returned line can be changed (BPA-50);
- sending it moves it to Submitted (BPA-53 has no week check);
- a new line for that week is still refused ("Week ending … is closed").

BPA-67 adds no code here. The test is a guard and may pass before any code.

Screen gap (Q8): Book hours shows only the days from the open week onwards, so a Returned line dated in a cut-off week is not on the screen.

### 6. Scenario 9: the PM screen text (Q6)
Jira's scenario says the screen lists "WP-000461: 6 h not sent". BPA-66 shows a table titled "Unsent hours of WP-000461 (approved at 100%)" with the lines.

Recommended: add a summary line in the story's words, "WP-000461: 6 h not sent" (the sum of the unsent lines, `two()` format without trailing zeros, ASSUMPTION), above that table. This is one React change.

The backend part already holds: a send-back never reaches `ServiceTask_CompleteWorkPacks` (BPA-66 decision (b)).

### 7. Docs
- ADR 0008: amended with one row, WorkPackCompleted (key and messageId `workpackNumber`, TTL P7D ASSUMPTION, guard `published_message`), plus why it buffers when the others don't.
- Data model: an "As built by BPA-67" note in §13.3.1, plus the workpack status.
- The OpenAPI texts: the lines endpoints and the upload outcomes list.

## Files that change
| Area | Files |
|---|---|
| Model | none (Q4); documentation text only if a detail changes |
| Backend, new | `worker/CompleteWorkPacksWorker`, `worker/LockProgressPeriodWorker`, `progress/WorkPackCompletionService`, `progress/ProgressLockService` |
| Backend, changed | `adapter/CamundaProcessClient` (TTL); `persistence/ProgressPeriodRepository` (`lock`, `requireOpen`); `persistence/WorkpackRepository` or a new method for Completed, with audit; `CompletionApprovalService`, `CompletionProgressService`, `ProgressCheckService`, `EarnedManhoursService`, `RetractionDecisionService`, `CbsReviewService` (the guard); `timesheet/HourLineService`, `domain/HourLineChecks`, `DigitalTimesheetLineRepository` (Completed, month lock); `worker/PrepareHoursSubmissionWorker` (month lock); `task/` a decision check for the month lock (Q2) |
| Contract | `api/openapi.yaml`: the 409 and 400 texts on the lines endpoints, the upload outcomes list, and the task decision text |
| Frontend | `ApproveWeeklyProgress` summary line (Q6); Book hours, if Q8 says so |
| Test support | `ProcessTestApplication`: the two new database workers are left out of its scan |
| Existing tests (said in the PR) | `WeekApprovalApiTest.BPA66_projectManagerApprovesTheWeek` (it asserts the instance waits at `ServiceTask_CompleteWorkPacks` and the period is `CutOff`; now the instance ends and the period is Locked); `aRerunOfRecordWeekDecisionChangesNothing` (its second instance would now hit a Locked week: it becomes a double call of the service on a CutOff week); `CompletionApprovalApiTest.aLockedWeekIsNeverChanged` and `EarnedManhoursApiTest.aLockedWeekIsRefused` keep their texts (checked in the build) |
| Tests | new `WeekLockApiTest` and `HoursLockApiTest`; React (Q6) |
| Docs | ADR 0008 amendment; data model note; plan; REVIEW.md; `intent/README.md` |

## Order of work
1. Approve the plan and commit it (`Approved`).
2. Contract text, once answered.
3. Tests, backend then React, run red **before any production code**, each in its own commit. Compile stubs only.
4. Backend:
   - the TTL in `publish`;
   - the two workers and their services;
   - `requireOpen` in every writer;
   - the Completed check;
   - the month-lock checks (lines, send, decisions).
   Then green, then the existing tests listed above.
5. Frontend (Q6, Q8); lint, format, test, build.
6. Docs; full `./mvnw -B verify`; the local-stack proof; `qa-reviewer`; fixes; REVIEW.md; plan Closed. Push, PR and Jira comments only when you ask.

## Scenario-to-test table
Both classes use the injected `TestClock` and set the month-lock day through an Environment property source, as `CompletionApprovalApiTest.progressSetting` does. Jobs are fired directly: the weekly instance is started at its element, `MonthLockJob.run()` is called, and the worker's `handle` is called with a mocked job for a retry. There is no real waiting.

**`WeekLockApiTest`** (real workers, Camunda, Mailpit; time sheet instances started at `UserTask_BookHours` for WP-000461 as `HoursPpcApprovalApiTest.startAtBookHours` does):

| # | Scenario | Test | Act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | The cut-off opens the period | `BPA67_cutOffOpensThePeriod` | clock Thu 19 Nov 23:59 Riyadh; start at `ServiceTask_OpenProgressPeriod` (the timer itself is not waited for) | a period for 19 Nov, `CutOff`; `POST …/lines` for 19 Nov → 400 "Week ending 19 Nov 2026 is closed" | **green before code** (BPA-58, BPA-50): a guard |
| 2 | One period per week on a retry | `BPA67_onePeriodPerWeekOnRetry` | `OpenProgressPeriodWorker.handle` twice with a mocked job of the same instance key | exactly one period for 19 Nov | **green before code** (BPA-58) |
| 3 | 100% completes the Work Pack | `BPA67_hundredPercentCompletesTheWorkPack` | WP-000461 approved 100 for 19 Nov; its time sheet instance at Book hours; weekly instance started at `ServiceTask_CompleteWorkPacks` | WP-000461 `Completed`; its time sheet instance completed at `EndEvent_TimesheetsClosed` | red: no worker, the instance waits |
| 5 | Completion waits while hours are in approval | `BPA67_completionWaitsForApprovalInProgress` | the WP-000461 instance brought to `UserTask_ApproveHoursPpc`; complete-workpacks runs; then PPC approves | the instance is still active after the publish; after the PPC decision it ends at `EndEvent_TimesheetsClosed` | red |
| 6 | Below 100% stays In Progress | `BPA67_belowHundredStaysInProgress` | WP-000457 at 99.99 | the step ran (the instance reached `EndEvent_PeriodLocked`) and WP-000457 is still `In Progress` | red (no worker, so the instance never ends) |
| 7 | The lock freezes the week | `BPA67_lockFreezesTheWeek` | weekly instance from `ServiceTask_CompleteWorkPacks` | period `Locked`, `locked_at` = clock; `CompletionApprovalService.record` for WP-000457 → "Week ending 19 Nov 2026 is locked", the row unchanged | red |
| 9 | A week sent back leaves the Work Pack In Progress | `BPA67_sentBackWeekLeavesWorkPackInProgress` | WP-000461 100% with 6 h Draft; the PM screen data (API) shows 6 h unsent; the PM sends back to Progress (BPA-66 task API) | WP-000461 `In Progress`; its time sheet instance still active. React test of the same name: "WP-000461: 6 h not sent" (Q6) | **Java green before code** (BPA-66); React red |

**`HoursLockApiTest`** (lines API, upload, send, decisions):

| # | Scenario | Test | Act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 4 | A Completed Work Pack takes no hours | `BPA67_completedWorkPackTakesNoHours` | WP-000461 `Completed` by SQL; the supervisor adds E1021, 20 Nov, Cutting, 2 h | 409 "Work Pack WP-000461 is Completed" | red (201 or the future-date 400) |
| 8 | A Returned line in a cut-off week can be corrected | `BPA67_returnedLineInCutOffWeekCanBeCorrected` | 19 Nov period `CutOff`; E1021's 19 Nov line `Returned`; PUT 7 h, then send (Book hours completed) | the line `Submitted`, 7 h; a new 19 Nov line → 400 "Week ending 19 Nov 2026 is closed" | **green before code** (BPA-50, BPA-53): a guard |
| 10 | Hours lock on the configured day (Outline) | `BPA67_hoursLockOnConfiguredMonthDay` | lock day 1, clock 1 Dec 00:10: `MonthLockJob.run()`; then lock day 16, clock 15 Dec, run (no lock), then 16 Dec, run | an `hours_month_lock` row for November on 1 Dec (day 1) and on 16 Dec but not 15 Dec (day 16); then a November line is refused "Hours for November 2026 are locked" | red: the job works (BPA-64) but nothing refuses the line |
| 11 | A November line refused after the lock | `BPA67_lineRefusedAfterMonthLock` | lock day 1, today 2 Dec, the month locked; the supervisor books 4 h on 30 Nov | 400 "Hours for November 2026 are locked" (Q2 code) | red (201) |
| 12 | Before the lock day a Returned line can be corrected | `BPA67_returnedLineCorrectableBeforeMonthLock` | lock day 16, today 10 Dec, the 30 Nov line Returned; PUT 4 h, send | the line `Submitted`, 4 h | **green before code** (BPA-50, BPA-53): it guards against an over-wide month check |

**Extras (not scenarios):**
- An upload row on a Completed Work Pack, and one in a locked month, get those outcomes.
- Change, delete and amend in a locked month are refused.
- A send with a locked-month line returns to Book hours with the message.
- A Validated or Approved decision on a locked-month submission is refused 409, and a Returned one is allowed (Q2).
- `aRerunOfCompleteWorkPacksPublishesOnce` (`published_message` holds one row).
- `aRerunOfLockProgressPeriodChangesNothing`.
- `reportedPctAndRuleResultRefuseALockedWeek` (the two new guards).

**Count:** `grep -c "void BPA67_"` across the two classes = 12. Five are expected green before any code (1, 2, 8, 12, and 9's Java test), as the brief allows; the plan and the PR will say so.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest='WeekLockApiTest,HoursLockApiTest' test`; `grep -c "void BPA67_"` = 12; `npm test`; `gh pr checks <n>` (Actions may not start: billing) |
| Process runs end to end locally | the run below |
| BPMN and DMN match deployed | the deployed `Process_PPC_WeeklyProgressPeriod` and `Process_PPC_TimesheetToProgress` equal `models/` (whitespace-normalised hash) |
| Open questions resolved or logged | the Questions below as Jira comments (shown first, posted on your yes) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-67.md` |
| Demoed at sprint review | Human gap |

**End-to-end run in the local stack** (rebuild `bpa-app` and the portal):
1. **Setup:** reset the 19 Nov week (the `start66.sh` and `review66.sh` scratch scripts). One Work Pack of the week is set to 100% and has a time sheet instance waiting at Book hours; one stays below 100%.
2. **The PM approves the week in the portal:**
   - the Work Pack at 100% becomes `Completed`;
   - its time sheet instance ends at `EndEvent_TimesheetsClosed` (Operate or REST search);
   - the period becomes `Locked` with `locked_at`;
   - the weekly instance ends at `EndEvent_PeriodLocked`;
   - the other Work Pack stays In Progress.
3. **Afterwards:** a line on the Completed Work Pack gives 409; an approved % write for the week gives "is locked".
4. **Month lock:** shown by the tests only (the stack runs on the real clock), unless you want the month-lock day set by hand.

## Risks
- **Size:** 5 points; the work is two workers, a TTL, about eight guards across two areas, a decision check and three existing-test edits. Nearer 8.
- **Shared Camunda in tests:** the new workers run in every test context, so an instance left at `ServiceTask_CompleteWorkPacks` by another class would be taken by a context with another database (the job fails: no period). `cancelWaiting` lists gain both elements.
- **The message expires:** the hours stay in approval for more than 7 days (Q1).
- **A publish fails after the status commit:** the Work Pack is Completed but its instance is still open. The Camunda retries cover a short outage; an incident needs a person.
- **The month lock refuses lines that were Submitted before the lock** and are decided after it (Q2: Approved is refused, Returned is allowed).
- **Old CutOff periods (Q5):** on a database where the timer opened periods that were never locked, BPA-60's "must be locked first" blocks every later week.

## Questions
1. **WorkPackCompleted TTL** (the Jira DoR item open for you). Jira says P7D.
   - The message is buffered only while it lives. If the hours stay in approval for more than 7 days, it expires: the time sheet instance never ends, it sits at Book hours (or comes back there), and the supervisor still cannot add lines (409 Completed), so it would wait forever.
   - Options:
     - **(a) P7D** as Jira, with the gap logged (the three approval tasks have 24 h reminders, so a week is unlikely);
     - **(b)** a longer TTL, for example P30D;
     - **(c) P7D plus a safety net:** when the time sheet instance reaches `ServiceTask_PrepareHoursSubmission` for a Completed Work Pack, it fails with a message. That is a BPMN or worker change.
   - **Recommended: (a)**, and amend ADR 0008 with the row.
2. **Refusing an approval after the month lock.** Jira says a line in a locked month "cannot be … approved".
   - **Recommended:**
     - the task API refuses a **Validated** (Timekeeper) or **Approved** (Production Supervisor, PPC) decision on a submission with any line dated in a locked month, **409** "Hours for <MMMM yyyy> are locked";
     - Returned and Rejected stay allowed, so the hours do not get stuck;
     - the lines API, upload and send refuse with **400**, the same as "Week ending … is closed".
   - Alternatives: 409 everywhere, or no decision check.
3. **Where each "locked" guard goes.** All of these use one `requireOpen` that takes the period row `FOR UPDATE`:
   - existing: the approved % (BPA-60), Earned man-hours (BPA-62), the retraction decision (BPA-63), the CBS review (BPA-64);
   - **new:** `reported_pct` (BPA-59's read step) and `rule_result` (BPA-61);
   - not needed: the week decision (already locked) and the task API (the instance has ended).

   Missed so far, and recommended to leave: `variance_alert` (the weekly send step runs before the lock; the daily check's Cost rows carry no period); `cbs_review`'s read side; `progress_retraction` rows (only through BPA-63, which is guarded).
4. **`EndEvent_PeriodLocked` stays a plain end event**; Epic 3 (BPA-84) turns it into the message end event for ProgressPeriodLocked. Confirm. No BPMN change in BPA-67.
5. **Old CutOff periods.**
   - Local: the database has 12 Nov `Locked` and 19 Nov `CutOff` only, so nothing blocks.
   - On QA, every Thursday since BPA-48 may have opened a period that was never locked, and BPA-60 then refuses the next week ("Week ending … must be locked first").
   - **Recommended:** a one-off SQL in the PR description, to run by hand on QA after deploy. It does three things: (i) cancel weekly instances still waiting at a step before `ServiceTask_CompleteWorkPacks` for past weeks; (ii) `UPDATE progress_period SET status = 'Locked', locked_at = now() WHERE status = 'CutOff' AND week_ending < <the current week>`; (iii) leave the latest week open.
   - No migration: it is environment data, not schema.
6. **Scenario 9's screen text "WP-000461: 6 h not sent".**
   - **Recommended:** add that line (the sum of the unsent lines) above BPA-66's unsent lines table, with a React test of the same name.
   - Alternative: treat BPA-66's table as meeting it and change nothing.
7. **A Completed Work Pack's other paths:** change, delete and amend of its lines also get 409 "Work Pack <WP> is Completed" (ASSUMPTION; Jira states only add). Which Work Packs complete: any status other than Completed with `approved_pct = 100` this week (in practice In Progress).
8. **The Returned line in a cut-off week on the screen.** Book hours shows only the days from the open week onwards, so such a line can be corrected through the API (scenario 8 passes) but not on the screen.
   - **Recommended:** log it as a new story (claude-generated, on your yes); no UI change here.
   - Alternative: list Returned lines outside the grid on Book hours in this story.
9. **Texts (ASSUMPTION):**
   - the month text format "Hours for November 2026 are locked" (English month name and year);
   - the month check comes before the week check;
   - the send step's `HOURS_INVALID` uses the same text;
   - the upload outcomes reuse the lines texts.
10. **Jira:** BPA-67 is In Progress. I'll ask before any transition.

## Resolutions (Ahsan, 22 Nov 2026, in plan mode)
- **Q1:** TTL **P7D** as Jira; the expiry gap is logged; ADR 0008 gains the WorkPackCompleted row. This answers the Jira DoR item that was open for Ahsan.
- **Q2:**
  - the lines API, upload and send refuse with **400** "Hours for <MMMM yyyy> are locked";
  - the task API refuses a **Validated** or **Approved** decision on a submission with a line dated in a locked month, with **409** and the same text;
  - Returned and Rejected stay allowed.
- **Q6, Q8:** add the summary line "WP-000461: 6 h not sent" on the PM screen (React test of the same name). The Book hours gap (a Returned line in a cut-off week is not on the screen) is logged as a new story, created only on your yes.
- **Q3, Q4, Q5, Q7, Q9:** as recommended:
  - one shared `requireOpen` (FOR UPDATE) in the six writers, with the new `reported_pct` and `rule_result` guards;
  - `EndEvent_PeriodLocked` unchanged;
  - a one-off QA SQL clean-up in the PR description (no migration);
  - 409 "Work Pack <WP> is Completed" also on change, delete and amend;
  - the month text and order as written.
- **Q10:** ask before any Jira transition.

## Progress and departures (22 Nov 2026)
**Commits:** 32bcc13 plan Approved; 2afa92e compile stubs and red backend and React tests; then the green commit (workers, guards, hours rules, summary line, ADR 0008 and data model).

**Red runs shown before any production code (2afa92e):**
- Backend: `WeekLockApiTest` 7 of 10 red, `HoursLockApiTest` 8 of 10 red (15 of 20). The three week scenarios 3, 5 and 6 and the lock scenario 7 waited 60 s for the work that no worker did (the stub workers have no `@JobWorker`, so the instance waits as before); the hours tests failed on the status (201 or 200 instead of 400 or 409) and the send test on the missing return to Book hours.
- **Green before any code, as the plan said:** `BPA67_cutOffOpensThePeriod`, `BPA67_onePeriodPerWeekOnRetry` (BPA-58, BPA-50), `BPA67_sentBackWeekLeavesWorkPackInProgress` (the Java part, BPA-66), `BPA67_returnedLineInCutOffWeekCanBeCorrected` and `BPA67_returnedLineCorrectableBeforeMonthLock` (BPA-50, BPA-53). They are guards, not red-first tests.
- React: `BPA67_sentBackWeekLeavesWorkPackInProgress` red (no summary line); the other 6 tests of the screen passed.

**Departures from the plan:**
1. **`WorkPackCompleted` answered 409 `ALREADY_EXISTS` counts as published.** The plan said "checked in the build": Camunda rejects a publish whose message id is still buffered, so a retry after a crash between publish and record would have failed forever. `CamundaProcessClient.publish` returns on that 409 (and only that one); `publish` has a time-to-live overload, BPA-49's call keeps 0.
2. **Tests: Work Pack 461 gets a number of its own in each `WeekLockApiTest` test** (`WP-9nnnnn`). The message is buffered for P7D with the Work Pack number as id and key (ADR 0008), so in the one Camunda that all test classes share a message of an earlier test would end the next test's time sheet instance at once, or a second publish of the same id would be dropped. The first green run showed it (nine 409s). The Jira text "WP-000461" is kept in the scenario names and the React test.
3. **`BPA67_returnedLineInCutOffWeekCanBeCorrected`:** the new-line refusal "Week ending 19 Nov 2026 is closed" is asserted **before** the send (the story's text). After the send the hours are in approval, and BPA-53's rule "Hours for <WP> are in approval; book them after the decision" comes first, so the test only asserts that the line is still refused (400 or 409). The story's "still refused" holds either way.
4. **Existing tests edited:** `WeekApprovalApiTest.BPA66_projectManagerApprovesTheWeek` (the instance now ends at `EndEvent_PeriodLocked` and the period is Locked, was: waits at `ServiceTask_CompleteWorkPacks` and CutOff) and `aRerunOfRecordWeekDecisionChangesNothing` (the week is locked after the approval, so a second instance at the record step would fail; it is now the service called twice on the open week); `ProcessTestApplication` leaves both new workers out of its scan. `CompletionApprovalApiTest.aLockedWeekIsNeverChanged` and `EarnedManhoursApiTest.aLockedWeekIsRefused` kept their texts and pass.
5. **Files in the diff that the plan did not list:** `ProgressPeriodRepository.requireOpen` and `lock`; `ProgressWeekRepository.approvedAtHundred` and `markCompleted`; `DigitalTimesheetLineRepository.monthLocked`; `HoursSubmissionRepository.lockedMonthOf`; `task/HoursMonthLockCheck` (named in the plan as "a decision check"); the `CompletionProgressService` constructor now takes `ProgressPeriodRepository`.
6. **The guard is used in all six writers of the week's rows** (plan Q3), through one `requireOpen` that holds the period row `FOR UPDATE` in the writer's own transaction; the new ones are `CompletionProgressService.decide` (reported %) and `ProgressCheckService.record` (rule result).
7. **Summary line (plan Q6):** one `<p class="notice">` per Work Pack above its unsent lines table, "<WP>: <hours> h not sent", hours as a plain number (6 for 6.00).
8. **Docs:** ADR 0008 has the `WorkPackCompleted` row and why it is the one buffered message; data model §13.3.1 has "As built by BPA-67"; the contract texts (lines endpoints, upload outcomes, task decisions) name the new refusals.

**Verification so far:** `WeekLockApiTest` 10/10, `HoursLockApiTest` 10/10, `WeekApprovalApiTest` 12/12; full `./mvnw -B verify` **696 tests, 0 failures**; frontend lint, `format:check`, build and `lint:bpmn` clean, `npm test` 148/148 (one run of the whole suite timed out in `CreateWorkPack.test.tsx` `BPA6_dateToCompleteBoundary` under load, 5.3 s against the 5 s limit; it passes alone, 9/9, and in the next full run).

## Human-gap log
- None yet.
