# Plan: BPA-53 Timekeeper can validate or return the hours sent for a Work Pack
Jira: BPA-53 (Story, epic BPA-4)
Story last updated: 2026-11-17T11:45:21.094+0400
Status: Approved (Ahsan, Build Lead, 18 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-53-timekeeper-validate-hours, from main e7f5afa


## Context
BPA-50/51/52 let the Supervisor book hours (form and upload) and re-check the budget when sending, but nothing happens after the check: `ServiceTask_SubmitHours`
(`submit-hours`) and `ServiceTask_RecordTimekeeperDecision` (`record-hours-decision`) have no worker, `UserTask_ValidateHours` has no screen and is not served by the task API,
and the Book hours screen cannot send. BPA-53 builds the send and the Timekeeper's step: lines become Submitted in a new `hours_submission`; the Timekeeper validates
(stage ProductionSupervisor) or returns with a reason (lines Returned, submission Closed, back to Book hours, the reason shown); the Timekeeper may amend a line's hours on
request with a reason; new lines are refused while the hours are in approval. No model change.

## Readiness check (Definition of Ready, docs/02, against Jira 17 Nov 11:45)
| Check | State |
|---|---|
| Jira status | **Ready** (comment 10546). Its Development panel shows a merged PR: PR #47 (BPA-52) mentions BPA-53. No transition without asking |
| Template, persona, outcome | Met |
| Gherkin incl. unhappy paths | Met: 10 scenarios |
| BPMN element IDs exist | Met: `UserTask_BookHours`, `ServiceTask_PrepareHoursSubmission`, `BoundaryEvent_NothingToSubmit`, `ServiceTask_SubmitHours`, `UserTask_ValidateHours`, `ServiceTask_RecordTimekeeperDecision` (header step=Timekeeper), `Gateway_IsValidated` (`Flow_Validated` → `UserTask_ApproveHoursProduction`, `Flow_NotValidated` default → `Gateway_MergeBooking`) |
| Every field has an SOR | Met (SOR Notes; `hours_submission`, `hours_decision` in V2) |
| DMN complete | Met (Decision_HoursWithinBudget re-checks an amendment, BPA-51) |
| No blocking question | Q1 (amend reason storage) and Q6 (how the Supervisor changes a Returned line's phase) change the build; see Questions |
| Persona `timekeeper` | Met (BPA-48) |
| intent.md / spec.md accepted | **Not met in the repo** (both DRAFT) |
| Estimate ≤ 8 | Met (5); two workers, an amend endpoint, a read endpoint for the Timekeeper and two screens make it tight (Risks) |

## What already exists (closed plans BPA-50/51/52; history only)
- `PrepareHoursSubmissionWorker` (BPA-51): builds `lineChecks` from the Work Pack's Draft and Returned lines and throws `HOURS_INVALID` "No Draft or Returned hours to send for <WP>" when there are none; `BoundaryEvent_NothingToSubmit` returns to Book hours with `validationMessages`. **BPA-53 adds nothing to it**: scenario 2 tests what is there (it will pass at once).
- `HourLineService` (BPA-50/51): line rules, `checkBudget` (Decision_HoursWithinBudget, `exceptLine` replaces old hours), `change` (PUT) for Draft/Returned lines.
- `TimesheetFileRepository.inApproval(workpackId)` (BPA-52): an `hours_submission` at stage Timekeeper, ProductionSupervisor or Ppc; text "Hours for <WP> are in approval; book them after the decision" (upload row outcome).
- Task API (BPA-26, BPA-50): serves allow-listed elements of `Process_PPC_WorkPackAssignment` and `Process_PPC_TimesheetToProgress`; sets `completedBy` and `completedTaskKey` on completion; `TaskValidity` holds per-task checks (sign-off texts "Decision is required", "Reason is required"); 403 outside the task's candidate group.
- Book hours screen (BPA-50): `TaskScreenShell` with `canComplete={false}`; shows `validationMessages`.

## Design
### 1. Send: `SubmitHoursWorker` (job `submit-hours`; new-job-worker skill)
- Reads `workpackId`, `completedBy` (the Supervisor who completed Book hours; `bpa-service` if missing), the process instance key.
- **Re-run key** (ADR 0007, BPMN doc "process instance key + loop counter"): `round_no` = 1 + the instance's **Closed** submissions; `INSERT ... ON CONFLICT (process_instance_key, round_no) DO NOTHING`, then read it back. A retried job computes the same round (the open one is not Closed yet), so it finds the same row. ASSUMPTION: a new send only happens after the previous round is Closed (Returned now; the later stages close it in BPA-56/57).
- One transaction: the submission (stage `Timekeeper`, `submitted_by` = completedBy, `submitted_at` from the clock), then every **Draft and Returned** line of the Work Pack → `Submitted` with `hours_submission_id`; audit rows per line (`status`, `hours_submission_id`, D64). Completes with `hoursSubmissionId`.
- The send itself stays as modelled: Book hours → Prepare (BPA-51) → budget check → `Flow_HoursWithinBudget` → Submit → `UserTask_ValidateHours`.

### 2. Timekeeper decision: `RecordHoursDecisionWorker` (job `record-hours-decision`, header `step`)
- **step = Timekeeper only.** Other steps (ProductionSupervisor, Ppc: BPA-56/57) fail the job with no retries and a plain message (the skill's "shared job type, later story" pattern; Operate shows an incident).
- Reads `hoursSubmissionId`, `hoursDecision`, `hoursReason`, `completedBy`, `completedTaskKey`. Re-run key: `hours_decision.task_key` (UNIQUE; `ON CONFLICT DO NOTHING`, D65).
- **Validated:** `hours_decision` (step Timekeeper, decision Validated, decided_by, decided_at); submission `stage` → `ProductionSupervisor`; lines stay Submitted.
- **Returned:** `hours_decision` with the reason; the submission's lines → `Returned`; submission `stage` → `Closed`, `outcome` → `Returned` (data model CHECK: Closed ⇔ outcome). Audit rows for every changed column. `Gateway_IsValidated` sends `Returned` (default flow) back through `Gateway_MergeBooking` to `UserTask_BookHours`.
- Defensive: a decision other than Validated/Returned, or Returned without a reason, fails the job with no retries (the task API refuses both first).

### 3. Task API for `UserTask_ValidateHours`
- `application.yml` allow-list: `UserTask_ValidateHours` write `[hoursDecision, hoursReason]`, read `[hoursSubmissionId, hoursDecision, hoursReason]`; `UserTask_BookHours` read gains `[hoursDecision, hoursReason]` so the Supervisor sees why hours came back.
- `TaskSummary.elementId` enum gains `UserTask_ValidateHours` (`api/openapi.yaml`).
- `TaskValidity` for `UserTask_ValidateHours`: no decision → "Decision is required"; not Validated or Returned → "Decision must be Validated or Returned" (**ASSUMPTION** text, as the sign-off's "Decision must be Approved or Declined"); Returned with a blank reason → "Reason is required" (Gherkin). The Timekeeper cannot reject (decision b): "Rejected" is refused by the same check. 403 outside `timekeeper` comes from the task's candidate group (BPA-26).

### 4. Amend on request (FRD-5-12 §5.12.5.5)
- **New endpoint** `POST /api/v1/digital-timesheet-lines/{lineId}/amendments` body `{hours, reason}` → 200 the line (ASSUMPTION path; group `timekeeper` only, SecurityConfig). Recommended over extending `PUT`: the PUT keeps BPA-50's meaning (Supervisor, Draft/Returned), and the amend has its own group, state and reason rules.
- Rules (`HourLineService.amend`), in order: line found (404 "Line not found"); the line is `Submitted` **and** its submission is at stage `Timekeeper` (i.e. `UserTask_ValidateHours` is open), else 409 "Line is not editable (status <status>)" (ASSUMPTION: reuse of the BPA-50 text); reason required → 400 "Reason is required"; reason above 250 → 400 "Reason can be at most 250 characters" (**ASSUMPTION** text, as the remarks text); hours checks (`HourLineChecks.hours`); `checkBudget` with the line excluded (BPA-51; scenario 10's text). Only `hours` changes; the line stays Submitted; one `audit_log` row for `hours` (old → new, username timekeeper, D64). Work date, phase and Worker cannot change (Jira).
- **Where the reason goes:** Q1 (no column exists). Recommended without a schema change: a second `audit_log` row on the same line with `column_name = 'amend_reason'` (old null, new the reason), written in the same transaction. Alternative: V3 migration (a column or an `hours_amendment` table) — only if you say so.

### 5. "In approval" for the form (409)
- The BPA-52 rule moves into `HourLineService.add` (checked right after the time sheet's Cancelled check): `Refused(409, "Hours for <WP> are in approval; book them after the decision")`. `inApproval` moves from `TimesheetFileRepository` to `DigitalTimesheetLineRepository`, and `TimesheetFileService` stops checking it itself: the upload gets the same text as a row outcome through `add` (Q2). One rule, one text, two surfaces.

### 6. Read the submission (for the Timekeeper's screen)
- **New** `GET /api/v1/hours-submissions/{id}` → `{id, workpackNumber, roundNo, stage, submittedBy, submittedAt, lines[]}` with each line `{id, badgeNo, workDate, phase, hours, status, remarks}` (ASSUMPTION shape). Group `timekeeper` now (production-supervisor and ppc-team join in BPA-56/57). The Timekeeper has no access to the lines endpoints of BPA-50 (supervisor, production-team).

### 7. Frontend
- **New screen `ValidateHours`** (new-task-screen skill; `features/timesheets/components/ValidateHours`), registered for `UserTask_ValidateHours`: the Work Pack and round, a table of the submission's lines (Worker, date, phase, hours, remarks); per line **Amend hours** (hours + reason, max 250, the server's message shown); a decision (Validated / Returned) and a reason (required for Returned); "Complete task" through the shell (`check()` shows "Decision is required" / "Reason is required" before the call; the backend checks again).
- **Book hours:** `canComplete` on, with the button labelled **"Send to Timekeeper"** (`TaskScreenShell` gains an optional `completeLabel`; default "Complete task"); a notice "Hours returned: <reason>" when `hoursDecision` is Returned (ASSUMPTION label); `validationMessages` are already shown by the shell.
- `TaskList`: after a Book hours completion, the notice reads "Hours sent to the Timekeeper" instead of "Task completed; the Work Pack is saved" (ASSUMPTION text); a returned Book hours task (nothing to send, budget) is reopened as today.
- **Existing test changed:** `BPA50_theTaskIsNotCompletedFromThisScreen` asserts there is no Complete button; BPA-53 adds one, so that test is replaced by a BPA-53 test of the Send button (stated in the PR).

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml`: amendment path, `GET /api/v1/hours-submissions/{id}`, schemas `HoursSubmission`, `HoursSubmissionLine`, `LineAmendment`; `TaskSummary.elementId` + `UserTask_ValidateHours` |
| Workers | new `worker/SubmitHoursWorker.java`, `worker/RecordHoursDecisionWorker.java`; `ProcessTestApplication` excludes both |
| Persistence | new `persistence/HoursSubmissionRepository.java` (submission, decision, line status moves, audit); `DigitalTimesheetLineRepository` (`inApproval` moved here) |
| Rules | `timesheet/HourLineService` (in-approval 409 in `add`; new `amend`); `timesheet/TimesheetFileService` (drops its own in-approval check); `task/TaskValidity` (ValidateHours) |
| API | new `api/HoursSubmissionController.java` (read + amend, or two controllers per tag); `config/SecurityConfig` (`/api/v1/hours-submissions/**`, `/api/v1/digital-timesheet-lines/*/amendments` → timekeeper; the existing lines rule keeps its pattern `digital-timesheet-lines/*`) |
| Config | `application.yml`: allow-lists for `UserTask_ValidateHours` and `UserTask_BookHours` read |
| Frontend | new `features/timesheets/components/ValidateHours/*` (+ test), `services/hoursSubmissionService.ts`; `TaskScreenShell` (`completeLabel`); `BookHours.tsx` (Send, return notice); `TaskList.tsx` (notice); `taskScreens.ts`, `utils/constants.ts`, `types/api.ts` |
| Tests | new `HoursValidationApiTest` (10 BPA53_), worker retry tests (`submit-hours`, `record-hours-decision` called twice with a mocked JobClient); `TaskValidityTest`; React tests; `BookHours.test.tsx` (the BPA-50 test above) |
| Docs | data model §13.2.1/13.2.2 note (as built, re-run keys, the amend reason), plan, REVIEW.md, `intent/README.md` |
Not changed: database (unless Q1 says V3), BPMN, DMN.

## Order of work
1. Approve; branch from main; commit this plan (`Approved`); regenerate `intent/README.md`. No Jira transition without asking.
2. Contract; the 10 tests (and retry tests); run, show red. Expected: scenario 2 green at once (BPA-51 built it); the others red (the instance waits at `ServiceTask_SubmitHours`, the task is not served, no amend path, no 409).
3. Backend: repository, submit worker, decision worker, TaskValidity, allow-lists, in-approval move, amend, read endpoint, security. Green: the class, then `BookHoursApiTest`, `HoursBudgetApiTest`, `TimesheetFileApiTest`, `TaskApiTest`.
4. Frontend tests first (red), then the ValidateHours screen, the shell label, Book hours Send and notice, TaskList notice; lint, format, test, build.
5. Docs; full `./mvnw -B verify`; local stack (Proof); `qa-reviewer`; fixes; REVIEW.md; plan Closed; push, PR and Jira comment only when asked.

## Scenario-to-test table (`HoursValidationApiTest`; clock 18 Nov 2026 Asia/Riyadh; Background: `BookHoursFixture` (WP-000457, E1021, E1099), Draft lines E1021 16 Nov Cutting 8, 17 Nov Cutting 8, E1099 16 Nov Cutting 7 by SQL, and an instance of `Process_PPC_TimesheetToProgress` started at `UserTask_BookHours` with `workpackId`/`workpackNumber` (as BPA-51); workers run in the app)
| # | Scenario | Test | Asserts | Expected first run |
|---|---|---|---|---|
| 1 | Supervisor sends the hours | `BPA53_supervisorSendsHoursToTimekeeper` | complete Book hours as supervisor (204); 3 lines Submitted with one `hours_submission`; stage Timekeeper, submitted_by supervisor; a `UserTask_ValidateHours` task for group timekeeper | red: waits at `ServiceTask_SubmitHours` |
| 2 | Nothing to send stays with the Supervisor | `BPA53_nothingToSendStaysWithSupervisor` | no Draft/Returned lines; complete → a new Book hours task with `validationMessages` "No Draft or Returned hours to send for WP-000457" | **green** (BPA-51) |
| 3 | Timekeeper validates | `BPA53_timekeeperValidates` | send; complete ValidateHours as timekeeper with `hoursDecision` Validated → stage ProductionSupervisor; `hours_decision` Validated by timekeeper; lines Submitted; the instance waits at `UserTask_ApproveHoursProduction` | red |
| 4 | Timekeeper returns with a reason | `BPA53_timekeeperReturnsWithReason` | Returned + reason "E1099 16 Nov should be Welding" → 3 lines Returned; submission Closed, outcome Returned; a Book hours task again whose `variables.hoursReason` is the reason | red |
| 5 | A return needs a reason | `BPA53_returnNeedsAReason` | Returned + empty reason → 400 "Reason is required"; the ValidateHours task still open | red |
| 6 | Returned lines corrected and sent again | `BPA53_returnedLinesCanBeCorrectedAndResent` | after a return: E1099's Returned line deleted and a Welding line added (Q6), Book hours completed → a second submission (round 2) at stage Timekeeper with 3 lines | red |
| 7 | Lines booked after sending wait | `BPA53_linesBookedAfterSendingWaitForNextRound` | after send: POST a line E1021 18 Nov Cutting 6 → 409 "Hours for WP-000457 are in approval; book them after the decision" | red (today 201) |
| 8 | Other personas cannot validate | `BPA53_otherPersonasCannotValidate` | production-supervisor completes ValidateHours → 403 | red (task not served: 404) |
| 9 | Timekeeper amends on request | `BPA53_timekeeperAmendsHoursOnRequest` | amend E1099 16 Nov 7 → 7.5 with reason "Supervisor request" → 200; 7.5, still Submitted; exactly one audit row for `digital_timesheet_line.hours` 7 → 7.5 by timekeeper | red (404 no path) |
| 10 | Amendment above the phase budget | `BPA53_amendmentAboveBudgetIsRefused` | Cutting at 299.5 h (non-Rejected, with the sent lines); amend 7 → 8 → 400 "Phase Cutting budget of 300 h would be exceeded"; still 7 | red |
Extra (not scenarios): worker retry tests (a second `submit-hours` for the same round creates no second submission; a second `record-hours-decision` with the same task key changes nothing); `TaskValidityTest` rows; the read endpoint (timekeeper 200, supervisor 403); React: ValidateHours shows the lines, blocks Returned without a reason, amends a line, completes; Book hours shows "Send to Timekeeper" and the return reason.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=HoursValidationApiTest test` (10 run, 0 failures); `gh pr checks <n>` |
| Task screen for UserTask_ValidateHours; allow-list updated | `cd frontend && npm test -- ValidateHours && npm run build`; `grep -n "UserTask_ValidateHours" backend/src/main/resources/application.yml` |
| Process runs end to end in the local stack | rebuild `bpa-app` and `frontend`; on a Work Pack whose Epic 2 instance runs the **current** model version (WP-000011's runs version 1: start a fresh instance at Book hours by hand, as in BPA-51's proof, or take a new Work Pack through Epic 1): in the portal as supervisor book lines → **Send to Timekeeper**; as timekeeper open Validate hours → amend a line with a reason → **Return** with a reason; as supervisor see the reason, correct (delete + add) → Send; as timekeeper **Validate**; then `psql` shows two submissions (round 1 Closed/Returned, round 2 ProductionSupervisor), the decisions and the audit rows; Operate shows the instance at `UserTask_ApproveHoursProduction` |
| BPMN and DMN match deployed | no model change; `curl .../v2/process-definitions/<key>/xml` diff unchanged |
| Open questions resolved or logged | Questions below, as Jira comments (shown first) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-53.md` |
| Demoed at sprint review | Human gap |

## Risks
- **After Validated the instance waits at `UserTask_ApproveHoursProduction`**, which has no screen or worker yet (BPA-56). The submission stays at stage ProductionSupervisor, so the Work Pack is "in approval" and **no new hours can be booked for it** (form or upload) until BPA-56/57 close the round. Demo with a Work Pack you can spare.
- **Instances on older model versions** (started before BPA-51's deploy) skip the send-time budget check; the send itself works on any version (no model change in BPA-53).
- **The round rule** assumes a new send only after the previous round is Closed; BPA-56/57 must close it (Approved/Rejected/Returned) or the next round number collides.
- **Size**: two workers, two endpoints, task validity, two screens and a shell change at 5 points.
- The Book hours "Send" is real from now: a Supervisor can send from the portal; with BPA-56/57 missing, a validated Work Pack is blocked from new hours.

## Questions
1. **Where is the amend reason stored?** No column exists (`audit_log`: table, record, column, old, new, username, changed_at; `hours_decision` is one row per decision with a UNIQUE `task_key`, and an amend is not a decision). Recommend: a second `audit_log` row on the line with `column_name = 'amend_reason'` (new value = the reason), same transaction, no schema change. Alternative: a V3 migration (`digital_timesheet_line.amend_reason` or an `hours_amendment` table). No column without your yes.
2. **"In approval": 409 here, a row outcome in BPA-52.** Recommend one rule and one text: the check moves into `HourLineService.add` (409 for the form), and the upload gets the same text as its row outcome through `add`. Same detection (`hours_submission` at an approval stage). Agree?
3. **Amended lines that are later Returned keep the amended hours** (the amendment changed the line itself; the audit log holds the old value). Not in Jira: ASSUMPTION. Agree?
4. **Return reason length.** Jira gives 250 only for the amend reason; `hours_decision.reason` has no length limit. Recommend no limit for the return reason (do not invent one); the amend reason is limited to 250 with "Reason can be at most 250 characters" (ASSUMPTION text).
5. **After Validated** the instance waits at `UserTask_ApproveHoursProduction` (BPA-56; no screen, no worker): noted in Risks, including that the Work Pack can take no new hours until then.
6. **Changing a Returned line's phase** (scenario 6: "I change E1099's line to Welding"). BPA-50's PUT changes hours, remarks and completion only, and the Book hours screen offers Delete on Draft lines only (Jira comment 10515). Recommend: the screen also offers Delete on **Returned** lines (the API already allows it), and the test deletes and re-adds the line. Alternative: let PUT change date and phase of a Returned line. Which?
7. **Texts not in Jira (ASSUMPTION):** "Decision must be Validated or Returned", "Reason can be at most 250 characters", the Book hours notice "Hours returned: <reason>", the TaskList notice "Hours sent to the Timekeeper", the button label "Send to Timekeeper", the amend path and the read endpoint's shape. To comment in Jira after a yes.
8. **Jira:** BPA-53 is Ready (not In Progress). Its "merged PR" link is PR #47 (BPA-52). I will ask before any transition.

## Human-gap log
- Plan written in plan mode and approved by Ahsan on 18 Nov 2026 ("Plan is fine"); Questions 1-8 were not answered one by one, so the recommended options are applied and recorded as ASSUMPTIONs (Jira comment after a yes).
- The branch existed already (created empty at main e7f5afa) when the plan was committed.
