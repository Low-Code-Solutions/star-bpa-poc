# Plan: BPA-66 Project Manager can approve or send back a week's progress before the period locks
Jira: BPA-66 (Story, epic BPA-4)
Story last updated: 2026-11-22T13:23:18.828+0400
Status: Approved (Ahsan, 22 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-66-pm-approve-week, from main 7ea3907

> Checked read-only: main 7ea3907 is the merge of PR #58 (BPA-65) and contains c4901b2 (BPA-64). `BPA-66-pm-approve-week` is at 7ea3907 with no commits of its own.

## Context
After the Cost Controller's review (BPA-64/65) the weekly instance waits at `UserTask_ApproveWeeklyProgress` (group `project-manager`). Nothing serves that task today: it has no allow-list entry, no screen, and it is missing from the `TaskSummary.elementId` enum. No step writes `progress_period.week_approved_by`, `week_approved_at` or `week_decision_reason`.

BPA-66 lets the Project Manager do two things:
- **Approve the week:** the instance goes on to `ServiceTask_CompleteWorkPacks` (BPA-67).
- **Send it back with a reason:** to PPC (`UserTask_ApproveCompletion`) or to the Cost Controller (`UserTask_ReviewActualManhours`).

The PM cannot approve a week whose completion % they approved themselves (FRD-21 §21.8.1).

## Readiness check (Definition of Ready, docs/02, against Jira 22 Nov 13:23)
| Check | State |
|---|---|
| Jira status | **In Progress** since 22 Nov 13:23. No other transition without asking (Q9) |
| Template, persona, outcome | Met (Project Manager) |
| Gherkin incl. unhappy paths | Met: 8 scenarios. The unhappy paths are the missing reason, ppc-team 403 and the segregation 409 |
| BPMN element IDs exist | Met for the IDs Jira names. Q1 recommends one new element, `ServiceTask_RecordWeekDecision`, which the BPMN Reference does not name. I'd propose that in a comment, not edit the field |
| Every field has an SOR | Met: the three `progress_period` columns exist in V2. The reason variable has no name anywhere, so `weekDecisionReason` is proposed (ASSUMPTION) |
| DMN | None needed |
| No blocking question | Q1, Q3 and Q5 change the build |
| intent.md / spec.md accepted | **Not met** (box unticked; same as BPA-58 to BPA-65) |
| Estimate ≤ 8 | 3. The plan finds more than that: a BPMN step and worker, a screen, two screen notices, a read-model change and a security change (Risks) |
| Comments | 10557 (Ready, 17 Nov). 10604 (C-E3-10, 20 Nov): the PM approves the week, so the story does not change |

## What already exists (code and closed plans BPA-60, 62, 64, 65; history only)
- **BPMN:** the flow runs `ServiceTask_RecordCbsReview` → `UserTask_ApproveWeeklyProgress` → `Gateway_IsWeekApproved`.
  - **Approved** (`weekDecision = "Approved"`) goes to `ServiceTask_CompleteWorkPacks` → `ServiceTask_LockProgressPeriod`. Neither step has a worker yet (BPA-67).
  - **Cost review** (`ChangesRequested` with `weekChangeTarget = "CostReview"`) goes to `Gateway_MergeReview`.
  - **Progress** (`Flow_WeekChangesRequested`) is the gateway **default** and goes to `Gateway_MergeApproval`. Any decision that is not Approved and not CostReview takes it, so the task API must check the values.
- **Task API** (`task/TaskService.complete`): checks run in the order 404 → 403 → 409 (task state) → 400 (allow-list) → 400 (`TaskValidity`) → per-task database checks (`Refusal`). It then adds `completedBy` and `completedTaskKey`.
  - `TaskValidity` already has the text `REASON_REQUIRED = "Reason is required"`.
  - Segregation pattern (BPA-63): `RetractionDecisionCheck` in the task API returns 409, and `RetractionDecisionService.decide` re-checks it in the record step, where the job fails with retries 0.
  - Tests seed the same user by SQL, for example `UPDATE … SET requested_by = 'project-manager'`.
- **Second pass, safe for every worker on both loops:**
  - `record-completion-approval` updates `workpack_progress` with the new values and keys on the values themselves.
  - `calculate-earned-manhours` replaces the week's `activity_progress` rows while the week is not Locked (BPA-62 lines 67, 68 and 174; `EarnedManhoursApiTest.aSentBackWeekIsRecalculated`).
  - `record-cbs-review` upserts one row per (period, CBS).
  - The Manhour alert goes once per period (`variance_alert_manhour_once`). The Cost alert goes **once per day** (`variance_alert_cost_once`, V6), so a second pass on a later day sends it again (Q3).
- **Read APIs:**
  - `GET …/workpacks` (BPA-60; ppc-team, project-manager) returns `approvedPct` and `lastLockedPct`. The latter is the latest Locked week before this one, which is last week because BPA-60 refuses to approve until that week is locked.
  - `GET …/earned-manhours` (BPA-62) is for **ppc-team and cost-controller only**. `EarnedManhoursApiTest.earnedManhoursEndpointIsForPpcAndCostController` line 282 asserts that project-manager gets 403.
  - `GET …/cbs-actuals` (BPA-64/65; cost-controller, project-manager) returns `alert`, `costAlert`, `remark` and the man-hour and cost figures.
  - Nothing lists the unsent lines of a Work Pack.
- **Showing a reason on a task:** the pattern is a process variable on the task's `read` allow-list, shown as `<p className="notice">`. `UserTask_BookHours` already does this with `hoursDecision` and `hoursReason`.

## Design
### 1. Record the decision: `ServiceTask_RecordWeekDecision` (Q1, BPMN change)
- **BPMN:** `UserTask_ApproveWeeklyProgress` → **`ServiceTask_RecordWeekDecision`** (job `record-week-decision`, retries 3) → `Gateway_IsWeekApproved`. This is the same shape as `ServiceTask_RecordCompletionApproval` and `ServiceTask_RecordCbsReview`.
  - The new task gets documentation, and its diagram shape and edges are laid out.
  - The user task's documentation names `weekDecisionReason`.
- **Worker:** `RecordWeekDecisionWorker` calls `progress/WeekDecisionService.record(period, decision, target, reason, completedBy)`. In one transaction it locks the period row with `SELECT … FOR UPDATE` (the BPA-62 note).
  - **Approved:** `week_approved_by` = `completedBy` and `week_approved_at` = now from the injected `Clock`. `week_decision_reason` keeps the last send-back reason ("last changes requested reason", data model §13.3.1).
  - **ChangesRequested:** `week_decision_reason` = the reason. `week_approved_by` and `week_approved_at` stay null.
    - ASSUMPTION: the columns are named "approved", so a send-back's who and when are not written there. They come from `audit_log` (`username`, `changed_at`) on the `week_decision_reason` row (Q4).
  - One `audit_log` row per changed column (D64).
- **Re-run key:** the values. A retry of the same job writes nothing new.
- **Backstops (job fails with retries 0, as BPA-63):**
  - a Locked week, with the text "Week ending <d MMM yyyy> is locked" (ASSUMPTION);
  - the segregation rule on Approved, with Jira's text;
  - a missing decision, target or reason.
- **Why not write it in the task API:** the task API only checks and completes, and every write in this process happens in a worker (ADR 0006/0007, BPA-60, BPA-64). Writing in the task API would also record the decision even when the Camunda completion failed.

### 2. `UserTask_ApproveWeeklyProgress` in the task API
- **Contract:** `TaskSummary.elementId` gains `UserTask_ApproveWeeklyProgress`.
- **Allow-list** (`application.yml`): `write: [weekDecision, weekChangeTarget, weekDecisionReason]`, `read: [weekEnding]`.
- **`TaskValidity`** (400s):
  - blank `weekDecision` → "Decision is required" (existing text);
  - a decision other than Approved or ChangesRequested → the existing unknown-decision text (checked in the build; Q8);
  - ChangesRequested with a blank `weekDecisionReason` → **"Reason is required"** (Jira);
  - ChangesRequested with a target missing or other than Progress or CostReview → ASSUMPTION text "Target is required" or "Target <x> is not allowed" (Q8).
  - On Approved, a target or reason sent with it is ignored and not stored (ASSUMPTION).
- **403:** any group other than project-manager, through the existing `requireCandidate` ("You are not allowed to work on this task").
- **409, new `task/WeekDecisionCheck`:**
  - no period for the instance → "The week of this task has no progress period" (existing BPA-63 text);
  - on **Approved**, if any `workpack_progress` row of the period has `approved_by` = the user → **"You cannot approve a week whose progress you approved"** (Jira).
  - A send-back is not blocked by this rule (ASSUMPTION, Q8). The check is wired in `TaskService` and `TaskConfig`.

### 3. Read model for the screen (reuses three APIs; one new field; one role change)
| Screen part | Source | Change |
|---|---|---|
| Per Work Pack: approved % and last week's % | `GET …/workpacks`: `approvedPct`, `lastLockedPct` | none |
| Per activity: Earned man-hours | `GET …/earned-manhours`: `earnedManhours`, `lastLockedEarnedManhours`, `weekIncrease` | **add project-manager** to the `SecurityConfig` rule and to the contract description. This edits the line 282 assertion of an existing test from 403 to 200 (said in the PR) |
| Man-hour and cost alerts with remarks | `GET …/cbs-actuals`: `alert`, `costAlert`, `remark`, figures | none |
| Unsent hours of a Work Pack approved at 100% | **new** optional `WorkpackProgressRow.unsentLines[]` (`employeeRef`, `workDate`, `phase`, `hours`, `status`), filled only when `approvedPct` = 100 | new query in `ProgressWeekRepository`: the Work Pack's `digital_timesheet_line` rows in status **Draft or Returned** (BPA-67 rule (c), Q5). Information only |

The PM's screen gets a new client function `getEarnedManhours` in `progressService.ts`. Nothing new is stored.

### 4. Send-back notice on the two earlier screens
- **Variables:** add `weekDecision`, `weekChangeTarget` and `weekDecisionReason` to the `read` lists of `UserTask_ApproveCompletion` and `UserTask_ReviewActualManhours`.
- **Notice text:** "Week sent back: <reason>" (Jira), shown as `<p className="notice">` next to "Week ending".
  - `ApproveCompletion` shows it only when `weekDecision = ChangesRequested` and `weekChangeTarget = Progress`.
  - `ReviewActualManhours` shows it only when the target is `CostReview`.
- **No clearing needed:** the variables are absent on the first pass, a new PM decision overwrites them, and Approved leaves the loop.
  - After a Progress send-back, the re-run reaches Review actual man-hours with the target still Progress, so the Cost Controller sees no notice there.
  - PPC still sees the notice if the round loops back to Approve completion through Blocked or Declined. Correct: the week is still sent back.

### 5. After a send-back (no new code beyond Q3)
- **Progress:** PPC re-approves, and then the progress check, Earned man-hours (replacing the week's `activity_progress` rows) and the variance check all run again. The Cost Controller reviews, and a new PM task opens.
- **Cost review:** only the review and a new PM task; no % changes.
- **Alerts:** the man-hour alert is never sent twice for the week. For the cost alert, Q3 recommends checking once per week per CBS on the weekly path. In `VarianceAlertService.sendCostAlerts` with a period: skip a recipient and channel that already has a Cost row linked to that period. The daily close check stays once per day. No migration.

### 6. Frontend
- **New screen** `features/progress/components/ApproveWeeklyProgress/` (skill `new-task-screen`), on `TaskScreenShell`, registered in `USER_TASKS` and `taskScreens.ts`.
  - **Read-only tables:** Work Packs (approved %, last week's %, unsent lines when at 100%); activities (Earned MH, last locked, increase); CBS (man-hour and cost figures, "Man-hour alert" and "Cost alert", the Cost Controller's remark).
  - **Inputs:** decision (Approve / Request changes), the target (Progress / Cost review) and the reason.
  - **`check()`:** "Reason is required", and the target text from Q8.
  - **`variables()`:** exactly the write list.
- **`ApproveCompletion` and `ReviewActualManhours`:** the notice from §4.

## Files that change
| Area | Files |
|---|---|
| Model | `models/bpmn/Process_PPC_WeeklyProgressPeriod.bpmn`: `ServiceTask_RecordWeekDecision`, its flows and diagram, and documentation |
| Contract | `api/openapi.yaml`: `TaskSummary.elementId` enum; `WorkpackProgressRow.unsentLines` and a new `UnsentLine` schema; the earned-manhours description (groups) |
| Config | `application.yml` (allow-lists); `config/SecurityConfig` (earned-manhours + project-manager) |
| Backend | new `worker/RecordWeekDecisionWorker`, `progress/WeekDecisionService`, `task/WeekDecisionCheck`. Changed: `task/TaskValidity`, `TaskService`, `TaskConfig`; `persistence/ProgressPeriodRepository` (record the decision); `ProgressWeekRepository` and `ProgressReadService` with the `ProgressPeriodController` mapping (`unsentLines`); `progress/VarianceAlertService` (Q3) |
| Frontend | new `ApproveWeeklyProgress` (with `index.ts` and its test); `ApproveCompletion`, `ReviewActualManhours` (notice); `utils/constants.ts`, `features/tasks/taskScreens.ts`, `progressService.ts` |
| Test support | `ProcessTestApplication`: the new database worker is left out of its scan, in the same way as `RecordCbsReviewWorker` (checked in the build) |
| Existing tests (said in the PR) | `EarnedManhoursApiTest.earnedManhoursEndpointIsForPpcAndCostController` (PM 403 → 200); model or job-type lists in `ModelResourcesTest`/`ModelDeployTest` if they enumerate elements (checked in the build) |
| Tests | new `WeekApprovalApiTest`; React tests (below) |
| Docs | data model §13.3.1 "As built by BPA-66"; plan; REVIEW.md; `intent/README.md` (generated) |

## Order of work
1. Approve the plan and commit it (`Approved`).
2. Contract and BPMN, once answered.
3. Tests, backend then React, run red **before any production code**, each in its own commit. Compile stubs only.
4. Backend: allow-list, validity, `WeekDecisionCheck`, worker and service, read model, security, the Q3 guard. Then green, then the existing tests listed above.
5. Frontend: the screen and the two notices; lint, format, test, build.
6. Docs; full `./mvnw -B verify`; local-stack proof; `qa-reviewer`; fixes; REVIEW.md; plan Closed. Push, PR and Jira comments only when you ask.

## Scenario-to-test table
**`WeekApprovalApiTest`** (`@Tag("integration")`, real workers and decisions, settable `TestClock` at 20 Nov 2026 Asia/Riyadh, `@DirtiesContext`, as `CostVarianceApiTest`).
- **Background (per test):** for the 19 Nov week, `workpack_progress` WP-000457 40% and WP-000461 100%, both approved by ppc-team (seeded as BPA-60's tests do).
- The instance is started at `ServiceTask_CalculateEarnedManhours`. Earned man-hours, the variance check and the alerts run.
- cost-controller completes the review with remarks through the task API. The instance then waits at `UserTask_ApproveWeeklyProgress`.

| # | Scenario | Test | Act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | The PM approves the week | `BPA66_projectManagerApprovesTheWeek` | project-manager completes with `weekDecision` Approved | `week_approved_by` = project-manager and `week_approved_at` = the clock time; an audit row; the instance is **active at `ServiceTask_CompleteWorkPacks`** (Q2: `ServiceTask_LockProgressPeriod` cannot run before BPA-67) | red: 404 (no allow-list entry) |
| 2 | Changes go back to PPC | `BPA66_changesRequestedGoBackToPpc` | ChangesRequested, Progress, "WP-000461 cannot be 100%: punch list open" | the task is at `UserTask_ApproveCompletion` for ppc-team; its `GET /tasks/{key}` variables carry the decision, target and reason; `week_decision_reason` is stored; the period is still `CutOff`. React test of the same name: the notice "Week sent back: WP-000461 cannot be 100%: punch list open" | red: 404 |
| 3 | Changes need a reason | `BPA66_changesNeedAReason` | ChangesRequested with an empty reason | 400 "Reason is required"; the task is still open. React test of the same name: `check()` blocks with that text | red: 404 |
| 4 | Recalculated after changes | `BPA66_rerunAfterChangesRecalculates` | send back (Progress) → ppc-team re-approves WP-000461 at 95 (with a remark) → cost-controller completes the review | A-2201's `activity_progress` row for the week changes to the 95% figure; a **new** `UserTask_ApproveWeeklyProgress` task (a different key) for project-manager; **no second alert row or email** | red: 404 |
| 5 | Alerts and remarks shown | `BPA66_weekShowsAlertsAndRemarks` | seed CBS-139-PIP-01 over both limits; remark "Rework on spools"; GET as project-manager | `cbs-actuals`: `alert` and `costAlert` true with the remark; `earned-manhours` returns 200 for the PM. React test of the same name: both alerts and the remark on the screen | red: PM 403 on earned-manhours |
| 6 | Other personas cannot approve | `BPA66_otherPersonasCannotApproveTheWeek` | ppc-team completes the task | 403; the task is still open | red: 404 |
| 7 | Back to the Cost Controller | `BPA66_changesCanGoBackToCostReview` | ChangesRequested, CostReview, "Explain the spool rework cost" | the task is at `UserTask_ReviewActualManhours` for cost-controller with the variables; `workpack_progress.approved_pct` unchanged. React test of the same name: the notice on Review actual man-hours | red: 404 |
| 8 | Segregation | `BPA66_approverMustNotBePercentApprover` | `UPDATE workpack_progress SET approved_by = 'project-manager'` for the week; the PM approves | 409 "You cannot approve a week whose progress you approved"; the task is still open; nothing written | red: 404 |

**Extras (not scenarios):**
- `segregationAlsoFailsTheRecordStep` calls the service directly and expects the exception.
- `aRerunOfRecordWeekDecisionChangesNothing`.
- `aChangesRequestNeedsATarget` (400, Q8).
- `aSendBackOnALaterDaySendsNoSecondCostAlert` (Q3 (b); the clock moves a day between passes).
- React: `ApproveWeeklyProgress` approving sends exactly the write list, the unsent lines of a 100% Work Pack are listed, and the server's refusal is shown.

**Count:** `grep -c "void BPA66_"` in `WeekApprovalApiTest` = 8. Four names also appear in React (2, 3, 5 and 7), as BPA-65 did.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=WeekApprovalApiTest test`; `grep -c "void BPA66_"` = 8; `npm test`; `gh pr checks <n>` (Actions may not start: billing) |
| Task screen | the React tests; a browser check as project-manager at http://localhost:3000 |
| Process runs end to end locally | the run below |
| BPMN and DMN match deployed | the deployed `Process_PPC_WeeklyProgressPeriod` equals `models/` (whitespace-normalised hash, as BPA-63 to BPA-65) |
| Open questions resolved or logged | the Questions below as Jira comments (shown first, posted on your yes) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-66.md` |
| Demoed at sprint review | Human gap |

**End-to-end run in the local stack** (rebuild `bpa-app` and the portal; redeploy the model):
1. A scratch script resets the 19 Nov week and starts the instance at `ServiceTask_CalculateEarnedManhours`; cost-controller completes the review.
2. **project-manager sends back to PPC:** the portal shows the PM screen (Work Packs, Earned MH, alerts and remarks); send back with target Progress. As ppc-team, Approve completion shows "Week sent back: …". Re-approve WP-000461 at 95.
3. **The week runs again:** `activity_progress` changes; Mailpit and `variance_alert` show no new alert; cost-controller reviews again.
4. **Back at the PM:** a new task. Approve. `progress_period.week_approved_by` and `week_approved_at` are set; Operate shows the instance **at `ServiceTask_CompleteWorkPacks`** (no worker until BPA-67).
5. Also: ppc-team gets 403 on the PM task, and the segregation 409 by SQL seed.

## Risks
- **Size:** 3 points, but the work is a BPMN step and worker, a new screen, two notices, the contract, security and a read-model query. Nearer 5.
- **Stale alert after a re-run:** if a CBS was over the limit on the first pass and within it on the second, its alert row (with the first pass's figures) stays, and `CbsReviewService.alerted` still asks the Cost Controller for a remark. That is accepted by decision (c) "never twice". Recorded, not built.
- **Gateway default:** an unexpected `weekChangeTarget` would take the Progress flow. The task API and the worker both refuse it.
- **Audit of the target:** `weekChangeTarget` is not stored in the database, only in Camunda history (Q4).
- **The same reason twice:** writes no `audit_log` row (D64 audits changed columns only). The send-back still shows in Camunda history (Q4).
- **`unsentLines`** reads every Draft and Returned line of the Work Pack, not only the week's lines (BPA-67 (c) names no date bound; Q5).

## Questions
1. **Recording the decision.**
   - **Recommended:** a new `ServiceTask_RecordWeekDecision` (`record-week-decision`) between the user task and `Gateway_IsWeekApproved`, as BPA-60 and BPA-64 did. It is a BPMN change, and I'd propose the new ID in a Jira comment on the BPMN Reference.
   - The alternative is writing the columns in the task API before completing. That needs no BPMN change, but it is the only write outside a worker, and it records a decision even if the completion then fails.
   - No BPMN change without your answer.
2. **Scenario 1's "ServiceTask_LockProgressPeriod runs".** The Approved path waits at `ServiceTask_CompleteWorkPacks` (BPA-67, no worker yet).
   - **Recommended:** assert that the instance reaches `ServiceTask_CompleteWorkPacks` and that the approval is recorded.
   - Note the gap in the plan and the PR; BPA-67 proves the lock.
3. **A cost alert after a send-back.** Decision (c) for BPA-66 says once per week per CBS; BPA-65 made cost alerts once per day, so a second pass on a later day would send a second cost alert for the same week.
   - **(b) Recommended:** the weekly path skips a Cost alert that already exists for this period, CBS, recipient and channel. The daily close check stays once per day. No migration, plus one extra test.
   - **(a)** Keep BPA-65's once per day: the PM would get a new cost alert per day of re-runs.
4. **The audit trail (FRD-21 §21.7).**
   - **Recommended:** `progress_period` keeps the latest values, and every change writes `audit_log` rows (`username`, `changed_at`, old and new value; D64), which gives the history of each send-back reason and the approval.
   - Not stored in the database: the target, and a repeated identical reason (both stay in Camunda history).
   - The alternative is a `week_decision` history table (a migration). I recommend audit_log.
5. **"Hours not yet sent" for a Work Pack at 100%.** Your note says Approved lines with `exported_at` NULL. BPA-67 rule (c) says "**Unsent lines (Draft, Returned)** of a 100% Work Pack are listed on the PM's approval screen".
   - **Recommended:** follow BPA-67: list the Draft and Returned lines (Worker, date, phase, hours, status), information only, with no date bound.
   - Approved lines not yet in an FMS file are a different thing, picked up by the next week's file (E2-D15); not shown (Resolutions).
6. **The reason variable name:** `weekDecisionReason` (ASSUMPTION; Jira and the data model name only the column). It is also read by the two earlier screens for the notice.
7. **Earned man-hours for the PM:** open `GET …/earned-manhours` to project-manager and change the one existing assertion (403 → 200), said in the PR.
8. **Texts and rules not in Jira (ASSUMPTION):**
   - "Target is required" and "Target <x> is not allowed" for a send-back without a valid target;
   - the existing unknown-decision text;
   - "Week ending <d MMM yyyy> is locked" in the worker;
   - segregation checked on Approved only (a send-back by the % approver is allowed);
   - a target or reason sent with Approved is ignored;
   - no length limit on the reason (the column is `text`).
9. **Jira:** BPA-66 is In Progress. I'll ask before any transition.

## Resolutions (Ahsan, 22 Nov 2026, in plan mode)
- **Q1:** a new BPMN step, `ServiceTask_RecordWeekDecision` (`record-week-decision`). The new ID is proposed in a Jira comment on the BPMN Reference (shown first).
- **Q3:** the weekly path sends a cost alert once per week (period, CBS, recipient, channel). The daily close check stays once per day. No migration.
- **Q5:** the screen lists Draft and Returned lines of a Work Pack approved at 100% (BPA-67 (c)), information only, with no date bound. Approved lines that are not yet exported are not shown.
- **Q2, Q4, Q6 to Q8:** as recommended. The test asserts the instance reaches `ServiceTask_CompleteWorkPacks`; the history is in `audit_log`; the variable is `weekDecisionReason`; earned-manhours is opened to project-manager (one existing test goes from 403 to 200); the texts are ASSUMPTIONs as listed; segregation applies to Approved only.
- **Q9:** ask before any Jira transition.

## Progress and departures (22 Nov 2026)
**Commits:** f24a60f plan Approved; bfb699b contract, compile stub and red backend tests; 78d057d red React tests, screen stub and registration; then the green commit (backend, BPMN, screen, notices, data model).

**Red runs shown before any production code:**
- Backend (bfb699b): `WeekApprovalApiTest` 12 of 12 red. Ten fail on the completion (404 "Task not found": `UserTask_ApproveWeeklyProgress` had no allow-list entry), `BPA66_weekShowsAlertsAndRemarks` on the PM's 403 for earned-manhours, and `segregationAlsoFailsTheRecordStep` on the stub's `UnsupportedOperationException`.
- React (78d057d): 8 of 11 red: the six `ApproveWeeklyProgress` tests (the stub renders no week), `BPA66_changesRequestedGoBackToPpc` and `BPA66_changesCanGoBackToCostReview` (no notice). **Three guard tests passed before any change** (`noNoticeForAWeekThatWasNotSentBack`, `noNoticeForASendBackToCostReview`, `noNoticeForASendBackToProgress`): a screen with no notice shows none. They are guards, not red-first tests.

**Departures from the plan:**
1. **The unknown-decision text is new.** The plan said "the existing unknown-decision text", but that one reads "Decision must be Approved or Declined" (the sign-off). `TaskValidity` and `WeekDecisionService` use "Decision must be Approved or ChangesRequested" (ASSUMPTION, plan Q8).
2. **Existing tests edited:** `EarnedManhoursApiTest.earnedManhoursEndpointIsForPpcAndCostController` is renamed `earnedManhoursEndpointIsForPpcCostControllerAndProjectManager` (project-manager 403 to 200, plus a supervisor 403 check); `ProcessTestApplication` leaves `RecordWeekDecisionWorker` out of its scan (it needs the database). The two React test helpers `fakeBackend` in `ApproveCompletion.test.tsx` and `ReviewActualManhours.test.tsx` gained a `variables` option (no assertion changed). Prettier reformatted the new React tests.
3. **Test helper:** `ppcApprove` sends a remark for every Work Pack. The seeded approvals have no report row, so any approved % differs from the reference (the last locked %) and BPA-60 asks for a remark. The first green run had two failures (400) because of that; the code was not the cause.
4. **Files in the diff that the plan did not list:** `progress/WeekDecisionService` is the real service (planned); `ProgressPeriodRepository.State`, `lockState`, `recordApproval`, `recordReason`; `ProgressWeekRepository.approvedBy` and `unsentLines`; `VarianceAlertRepository.existsCostInPeriod`; `features/progress/weekDecisionMessages.ts`; `EarnedManhoursWeek`, `EarnedManhoursRow` and `UnsentLine` names in `types/api.ts`.
5. **BPMN layout:** the new task sits at x 4422 to 4502, y 320 (between the user task and the gateway); `Flow_ToIsWeekApproved` keeps its ID and now leaves the new task; the new `Flow_ToRecordWeekDecision` leaves the user task.
6. **Contract:** `UserTask_ApproveWeeklyProgress` is documented on the complete-task variables; `UnsentLine` is a new schema; the earned-manhours description names the project-manager.

**Verification so far:** `WeekApprovalApiTest` 12/12 and `EarnedManhoursApiTest` 14/14 green; React `src/features/progress` and `src/features/tasks` 43/43; lint, `format:check` and `lint:bpmn` clean.

## Human-gap log
- None yet.
