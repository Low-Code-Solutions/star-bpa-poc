# Plan: BPA-64 Cost Controller can review actual man-hours by CBS and is alerted above 15% over forecast
Jira: BPA-64 (Story, epic BPA-4)
Story last updated: 2026-11-17T11:45:51.610+0400
Status: Approved (Ahsan, 21 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-64-cbs-manhour-review, from main f920187

> Checked read-only: branch `BPA-64-cbs-manhour-review` at f920187 (the PR #56 merge, BPA-63), no commits of its own.

## Context
After BPA-62 the weekly instance waits at `ServiceTask_PrepareVarianceCheck` (no worker). BPA-64 builds the man-hour side of the variance check:
- **Variance check:** actual man-hours per CBS (approved hours) against the Current Forecast, through `Decision_ManhourVariance`.
- **Alerts:** above 15%, an email and an in-system alert to the Cost Controller and an email to the PCON Manager, once per period, CBS and channel.
- **Review:** `UserTask_ReviewActualManhours` (cost-controller), with a remark required for each alerted CBS.
- **Read APIs and screens:** a CBS actuals endpoint, the user's own alerts, and the screens for both.
- **Monthly snapshot (D5):** the man-hours by CBS and activity are frozen when a month locks. Its trigger is open (Q1).

The week then reaches `UserTask_ApproveWeeklyProgress` (BPA-66, no screen yet).

## Readiness check (Definition of Ready, docs/02, against Jira 17 Nov 11:45)
| Check | State |
|---|---|
| Jira status | **Ready** (not In Progress). Ready → In Progress when work starts, only after you say so (Q7) |
| Template, persona, outcome | Met (Cost Controller) |
| Gherkin incl. unhappy paths | Met: 13 scenarios (the Outline counts as one; 400, 403) |
| BPMN element IDs exist | Met. `ServiceTask_PrepareVarianceCheck` → `BusinessRuleTask_CheckManhourVariance` → `BusinessRuleTask_CheckCostVariance` → `ServiceTask_SendVarianceAlerts` → `Gateway_MergeReview` → `UserTask_ReviewActualManhours` → `UserTask_ApproveWeeklyProgress`, strictly in sequence. **But:** both rule tasks have **no input mapping** (the decisions read bare names, but each pass only has `varianceCheck`), and the results do not keep the CBS code (Q8). **No element writes `cbs_review`** (Q9). **Nothing models or codes the month lock** (Q1) |
| Every field has an SOR | Met. **`variance_alert.recipient_username` is NOT NULL with a foreign key to `app_user`, but the PCON Manager has no user** (Q2) |
| DMN | `Decision_ManhourVariance` is complete (FIRST, 3 rules: `null, <= 0` NO_FORECAST; `> 15` OVER_15_PCT; else WITHIN). Its description still says "exactly 15% = no alert is an ASSUMPTION", but D3 made it sourced. That is a doc-only change (Q8) |
| No blocking question | D3 and D5 were decided 10 Nov. Q1, Q2, Q8 and Q9 change the build |
| intent.md / spec.md accepted | **Not met** (Jira DoR box unchecked; same as BPA-58 to BPA-63) |
| Estimate ≤ 8 | 8, at the limit. The plan finds more than that (Q6) |

## What already exists (closed plans BPA-16, BPA-58, BPA-60 to BPA-63; history only)
- **Tables (V2), no code writes them yet:**
  - `variance_alert`:
    - UNIQUE (period, CBS, alert_type, recipient_username, channel): the idempotency key.
    - `recipient_username` NOT NULL, with a foreign key to `app_user`.
    - `variance_pct` numeric(7,2).
  - `cbs_review`: UNIQUE (period, CBS); `remark` at most 500 characters.
  - `cbs_manhour_snapshot`: UNIQUE (month, CBS, activity); never updated.
  - `hours_month_lock`: `month` UNIQUE; `lock_day` 1 or 16.
- **CBS seed data:**
  - `cbs_forecast` holds one row: CBS-139-PIP-01, 1000.00 MH and 250000.00 cost.
  - `cbs_actual_cost` holds CBS-139-PIP-01 for Nov 2026: 180000.00.
  - CBS-139-PIP-02 has no forecast row.
  - **`CBS-140-STR-01` exists nowhere.** `digital_timesheet.cbs_code_ref` has no foreign key, so a test can put it on a time sheet.
- **Settings:** `bpa.hours.month-lock-day: 1` and `bpa.alerts.pcon-manager-email: pcon-manager@example.com`. Nothing reads them yet. `ProgressSettings` is the pattern for reading them.
- **Email (BPA-16, ADR 0012):**
  - `SendRevisionNoticeWorker` writes the InSystem row first, skips the email if one is already recorded, otherwise sends and then records it.
  - A mail exception is not caught: Camunda's 3 tries, then an incident.
  - `NotificationRepository.emails(usernames)` reads `app_user.email`.
  - `FindRevisionRecipientsWorker` resolves a group's members through `CamundaGroupClient`.
  - Tests use Mailpit (`Mailpit.withSubject`) or a `@MockitoSpyBean JavaMailSender`.
- **Approved hours:** lines reach their CBS through the time sheet (`t.cbs_code_ref`). BPA-60 sums Approved lines per Work Pack up to the week ending. **No query sums them by CBS yet.**
- **API, security and frontend:**
  - `GET /api/v1/me/notifications` exists (BPA-16) but **no screen shows it**. The portal has only `TasksPage`, with `WorkPackPrints` as the list pattern.
  - `SecurityConfig` has `/progress-periods/**` for ppc-team and project-manager, so a new `cbs-actuals` rule must come before it.
  - Neither user task has an allow-list entry or a screen.
- **BPA-62:** the instance waits at `ServiceTask_PrepareVarianceCheck`; the local 19 Nov week holds BPA-63's run.

## Design
### 1. `prepare-variance-check` (PrepareVarianceCheckWorker → VarianceCheckService)
For the week of the instance (`ProgressCheckService.periodOf`):
- **Which CBS get a check:** one `varianceCheck` per CBS that has Approved hours up to the week ending, **or** a `cbs_forecast` row (Q3). Order by CBS.
- **`actualManhours`:** Σ `digital_timesheet_line.hours` with `status = 'Approved'` and `work_date ≤ week ending`, grouped by `digital_timesheet.cbs_code_ref`. Cumulative, not this week only (Jira).
- **`currentForecastManhours`:** `cbs_forecast.current_forecast_mh`, null when there is none.
- **Also passed, for BPA-65's cost task:**
  - `revisedBudgetCost` = `cbs_forecast.revised_budget_cost`.
  - `actualCost` = Σ `cbs_actual_cost.actual_cost` for months up to the week ending's month (data model "(A)").
  - Both may be null; the cost decision then gives NO_BUDGET and does not fail.
- **"No CBS":** lines whose time sheet has no CBS are **not** a `varianceCheck` (never into the DMN, D40). They appear only in the read model's "No CBS" row.
- **Output:** each entry is `{cbsCodeRef, actualManhours, currentForecastManhours, revisedBudgetCost, actualCost}`. No table is written.
- **Failures:** no period → fail with no retries (as BPA-61 and 62). An empty list is valid: the multi-instance runs zero times.
- **Re-run:** a pure read, so the same week gives the same list.

### 2. Rule-task wiring (Q8; needs your yes)
- **`BusinessRuleTask_CheckManhourVariance`:**
  - Input mappings `actualManhours = varianceCheck.actualManhours` and `currentForecastManhours = varianceCheck.currentForecastManhours`.
  - `outputElement = {cbsCodeRef: varianceCheck.cbsCodeRef, actualManhours: …, currentForecastManhours: …, manhourAlert: manhourCheck.manhourAlert, manhourResult: manhourCheck.manhourResult}`.
  - This is BPA-61's fix on the progress rule. Without it every CBS comes out NO_FORECAST.
- **`BusinessRuleTask_CheckCostVariance`:** the same mapping for `revisedBudgetCost` and `actualCost`, keeping `cbsCodeRef`. It is wired now so the model changes once; BPA-65 uses the results.
- **`Decision_ManhourVariance` description:** "exactly 15% = no alert" is sourced by D3, not an ASSUMPTION (doc text only).
- **Proved first:** a red test showing 1150.01 does not alert with the current wiring.

### 3. `send-variance-alerts` (SendVarianceAlertsWorker → VarianceAlertService), man-hour part only
For each `manhourCheckResults` entry with `manhourResult = "OVER_15_PCT"`:
- **Text (Jira, exact):** "Man-hours on <CBS> are <pct>% over the Current Forecast (<actual> of <forecast> MH), week ending <d MMM yyyy>".
  - `pct` = (actual − forecast) ÷ forecast × 100, half-up to 2 dp (Q5).
  - `actual` and `forecast` are written with 2 decimals.
  - The date is the week ending in Asia/Riyadh format.
- **Recipients:**
  - Members of group `cost-controller` (`CamundaGroupClient`, as BPA-16), each with their `app_user.email`: one **InSystem** row and one **Email**.
  - The PCON Manager at `bpa.alerts.pcon-manager-email`: one **Email** only (D3). How it is recorded is Q2.
- **Once per period, CBS, type, recipient and channel:**
  - The `variance_alert` unique key with `ON CONFLICT DO NOTHING`.
  - InSystem is recorded first. An email is sent only when its row is not there, then recorded (BPA-16 order). A re-run sends nothing new.
  - A new week has a new period, so it alerts again (decision (c), Q4).
- **Values on the row:** `actual_value`, `baseline_value` = forecast, `variance_pct` (2 dp), `sent_at` from the injected `Clock`. Audit per column (D64).
- **Mail failure:** not caught, as BPA-16: Camunda's tries, then an incident. The InSystem row already exists.
- **Left to BPA-65:** the cost alerts (`costCheckResults`), their recipients (BPMN text says project-manager; D3 adds the PCON Manager) and the cost columns on screen. BPA-64 ignores `costCheckResults`.
- The worker completes with no variables.

### 4. `UserTask_ReviewActualManhours` (cost-controller)
- **Allow-list:** write `[cbsReviews]` = `[{cbsCodeRef, remark}]`; read `[weekEnding]`. Add `UserTask_ReviewActualManhours` to the `TaskSummary`/`Task` `elementId` enums.
- **Checks:**
  - 403 for other groups (existing `requireCandidate`).
  - **`TaskValidity`:** list required ("Reviews are required", **ASSUMPTION**); a remark over 500 characters gives "Remark can be at most 500 characters" (**ASSUMPTION** text; 500 is the column's).
  - **New `CbsReviewCheck`** (database):
    - every CBS with a `Manhour` alert for the period needs a non-blank remark: 400 "A remark is required for <CBS> (alert raised)" (Jira);
    - a `cbsCodeRef` that is not a CBS of this week gives 400 "CBS is not part of this review" (**ASSUMPTION**).
- **Stored:** `cbs_review` per entry (`remark` may be empty for a CBS with no alert), `reviewed_by` = `completedBy`, `reviewed_at`. UNIQUE (period, CBS) is upserted, so a review sent back by the PM (`Flow_WeekCostReviewRequested`) updates it. Audited per column.
- **Where it is written:** Q9. The recommended option is a new `ServiceTask_RecordCbsReview` (`record-cbs-review`) between the review and `UserTask_ApproveWeeklyProgress`, as BPA-60's Q7 A.
- **Then:** `UserTask_ApproveWeeklyProgress` (project-manager) exists. No screen until BPA-66.

### 5. Read APIs (contract first)
**`GET /api/v1/progress-periods/{weekEnding}/cbs-actuals`** (cost-controller, project-manager; a rule placed before `/progress-periods/**`):
- **Response:** `{weekEnding, cbs: [CbsActualRow], noCbsManhours}`.
- **`CbsActualRow`:**
  - `cbsCodeRef`;
  - `actualManhours`, computed live with the same query as §1;
  - `currentForecastManhours` (absent when none);
  - `etc` = forecast − actual (absent without a forecast);
  - `variancePct`, half-up 2 dp (absent without a forecast);
  - `result` (NO_FORECAST | OVER_15_PCT | WITHIN): worked out in Java with the decision's rules (**ASSUMPTION**: a copy of the 3 rules; Q10);
  - `alert` = a `Manhour` `variance_alert` row exists for the period and CBS;
  - `remark` from `cbs_review`.
- **"No CBS":** `noCbsManhours` carries its hours. It has no result and no alert.
- **Errors:** 404 "Week not found" (ASSUMPTION text, as BPA-60 and 62).

**`GET /api/v1/me/variance-alerts`** (cost-controller, project-manager):
- The session user's `InSystem` rows, newest first: `{weekEnding, cbsCodeRef, alertType, message, sentAt}`.
- `message` is rebuilt from the stored values with the same text.

### 6. Frontend (new-task-screen skill; no UI decision docs exist)
- **Review actual man-hours screen** (`frontend/src/features/progress/components/ReviewActualManhours/`), on `TaskScreenShell`:
  - Rows from `cbs-actuals`, with a table: CBS, actual, forecast, ETC, variance %, result, alert, remark input.
  - A negative ETC gets a new class (for example `sign-table__cell--negative`, **ASSUMPTION** styling).
  - "Required" on alerted rows; the "No CBS" row is shown without a remark input.
  - Checks use the server's texts.
- **My alerts:** a simple list section on `TasksPage` for cost-controller and project-manager (pattern `WorkPackPrints`) reading `GET /me/variance-alerts` (Q11).

### 7. Monthly snapshot (D5): planned only once Q1 is answered
**Recommended (Q1 A), for your answer:**
- A `MonthLockJob` (`@Scheduled`, once a day, injected `Clock`, Asia/Riyadh).
- On or after `month-lock-day` it locks the previous month: `hours_month_lock` and `cbs_manhour_snapshot` (Σ Approved hours by CBS and activity with `work_date` in that month) in one transaction.
- `month` UNIQUE makes it run once. A locked month is never rewritten.
- The 2 snapshot scenarios call the job's service with the clock set.
- **Not in this option:** refusing hours in a locked month (BPA-67, spec S5).

## Files that change (pending Q1, Q2, Q8, Q9)
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml`: `/progress-periods/{weekEnding}/cbs-actuals`, `/me/variance-alerts`, schemas; `elementId` enums + `UserTask_ReviewActualManhours` |
| Model (Q8, Q9) | `models/bpmn/Process_PPC_WeeklyProgressPeriod.bpmn`: rule-task mappings and output elements; `ServiceTask_RecordCbsReview` (+ diagram room); docs texts. `models/dmn/Decision_ManhourVariance.dmn`: description |
| Database (Q2) | `V5__variance_alert_recipient_email.sql` |
| Backend | `worker/PrepareVarianceCheckWorker`, `worker/SendVarianceAlertsWorker`, `worker/RecordCbsReviewWorker` (Q9); `progress/VarianceCheckService`, `progress/VarianceAlertService`, `progress/CbsReviewService`, `progress/CbsActualsReadService`, `progress/VarianceMessages`; `persistence/CbsActualsRepository`, `VarianceAlertRepository`, `CbsReviewRepository`; `task/TaskValidity`, `task/CbsReviewCheck` (+ `TaskService`, `TaskConfig`); new controllers for the two GETs; `config/SecurityConfig`; `config/AlertSettings`; `application.yml` allow-list. Q1 A: `progress/MonthLockService`, `MonthLockJob`, `@EnableScheduling` |
| Frontend | `features/progress/components/ReviewActualManhours/`, `features/progress/reviewMessages.ts`, `features/progress/components/MyVarianceAlerts/` (+ hook, service), `pages/TasksPage.tsx`, `utils/constants.ts`, `features/tasks/taskScreens.ts`, `styles/global.css` (one class) |
| Test support | `ProcessTestApplication` excludes the new workers; `BookHoursFixture` clears `variance_alert`, `cbs_review` (and Q1: `cbs_manhour_snapshot`, `hours_month_lock`); app users seeded as `RevisionFlow.seedAppUsers()` |
| Existing tests | `EarnedManhoursApiTest`, `ProgressRuleApiTest`, `RetractionApiTest`, `CompletionApprovalApiTest`: the instance no longer rests at `ServiceTask_PrepareVarianceCheck`; their waits and `cancelWaiting` lists gain the new steps. `FlywayBaselineTest` lists V5 (Q2). To be said in the PR |
| Tests | new `VarianceApiTest` (scenarios 1 to 11), `MonthSnapshotTest` (12, 13; Q1); React tests |
| Docs | data model §13.4/§13.5 "As built by BPA-64"; plan; REVIEW.md; `intent/README.md` |

## Order of work
1. Approve the plan and commit it (`Approved`). Ask before moving Jira Ready → In Progress (Q7).
2. Contract, model (Q8, Q9) and migration (Q2) once answered.
3. Tests (backend, then React). Run them red **before any production code**, each in its own commit. Expected: the instance waits at `ServiceTask_PrepareVarianceCheck` (no worker), the endpoints 404, the task is not served, the screen is missing.
4. Backend: prepare, alerts, review, reads, then green; then the existing tests listed above, `ModelDeployTest`, `ModelResourcesTest`.
5. Frontend; lint, format, test, build.
6. Docs; full `./mvnw -B verify`; local-stack proof; `qa-reviewer`; fixes; REVIEW.md; plan Closed. Push, PR and Jira comments only when you ask.

## Scenario-to-test table
**`VarianceApiTest` setup:**
- Clock 20 Nov 2026 Asia/Riyadh. `BookHoursFixture` reset plus the FMS background.
- Approved hours are set per scenario by SQL lines (`BookHoursFixture.line` and `fill`) on time sheets with the CBS.
- App users seeded. Mail goes to Mailpit (`Mailpit.withSubject`, a subject per scenario through the CBS and week text), as `DrawingRevisionApiTest`.
- An instance is started **at `ServiceTask_PrepareVarianceCheck`** for the 19 Nov week; the real workers, decisions and gateway run.

| # | Scenario | Test | Seed / act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | Alert fires above 15% (Outline) | `BPA64_exactly15PercentNoAlert` and `BPA64_over15PercentAlerts` (Jira lists both) | PIP-01 actual 1150.00, then 1150.01 | no `variance_alert` / an alert | red: the instance stays at the prepare task |
| 2 | Alert to the Cost Controller | `BPA64_over15PercentAlerts` (shared with 1) | 1200.00 | one Email in Mailpit to cost-controller and one InSystem row, text "Man-hours on CBS-139-PIP-01 are 20.00% over the Current Forecast (1200.00 of 1000.00 MH), week ending 19 Nov 2026" | red |
| 3 | Under forecast, no alert | `BPA64_underForecastNoAlert` | 700.00 | no alert for PIP-01 | red |
| 4 | No forecast, no alert | `BPA64_noForecastNoAlert` | a time sheet with CBS-140-STR-01 (no forecast row), 50.00 | `cbs-actuals` shows NO_FORECAST, alert false | red |
| 5 | No CBS never alerts | `BPA64_noCbsLinesNeverAlert` | WP-000470's sheet (no CBS) with 40.00 | `noCbsManhours` 40.00; no alert | red |
| 6 | Once per period | `BPA64_alertSentOncePerPeriod` | 1200.00; the send job run again (worker `handle` with a mocked client, or the service twice) | still one Email and one InSystem | red |
| 7 | Review with remarks | `BPA64_costControllerReviewsWithRemarks` | complete with "Rework on spools" for PIP-01 | `cbs_review` row; a `UserTask_ApproveWeeklyProgress` task for project-manager | red |
| 8 | Other personas cannot see | `BPA64_otherPersonasCannotSeeCbsActuals` | supervisor GET | 403 | red: 404 (no path yet) |
| 9 | Alerted CBS needs a remark | `BPA64_alertedCbsNeedsARemark` | 1200.00; complete without a remark | 400 "A remark is required for CBS-139-PIP-01 (alert raised)" | red |
| 10 | Negative ETC highlighted | `BPA64_negativeEtcIsHighlighted` | 1200.00 | API `etc` −200.00; only one Manhour alert type for PIP-01. React test of the same name: the cell has the highlight class | red |
| 11 | PCON Manager email | `BPA64_pconManagerAlsoReceivesManhourAlert` | `bpa.alerts.pcon-manager-email=pcon.manager@star.example` (test property); 1200.00 | one email to that address, same text | red |
| 12 | Month lock freezes snapshot | `BPA64_monthLockFreezesManhourSnapshot` | Q1: lock day 1; 640.00 approved Nov hours on PIP-01 / A-2201; clock 1 Dec 2026 | snapshot 640.00 | red |
| 13 | Closed month never changes | `BPA64_closedMonthSnapshotNeverChanges` | after 12, 16.00 hours dated 2 Dec approved | November still 640.00; December actual 16.00 (as `cbs-actuals` for a December week, or a month sum, Q1) | red |

Jira names 13 tests for 12 scenarios: the Outline maps to two tests (`exactly15PercentNoAlert`, `over15PercentAlerts`), and `over15PercentAlerts` also asserts the Cost Controller's email and alert.

**Extra tests (not scenarios):**
- `aNewWeekAlertsAgain` (Q4).
- `aRemarkOver500IsRefused`.
- `projectManagerCanReadCbsActuals`.
- `myVarianceAlertsShowsOnlyMine`.
- `aSentBackReviewUpdatesTheRemark`.
- `mailDownLeavesTheInSystemAlert`.
- `prepareVarianceCheckRerunGivesTheSameChecks`.

**React tests:**
- `ReviewActualManhours.test.tsx`: `BPA64_negativeEtcIsHighlighted`, `BPA64_alertedCbsNeedsARemark` (the screen check), `BPA64_costControllerReviewsWithRemarks` (payload).
- `MyVarianceAlerts.test.tsx`: the list.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest='VarianceApiTest,MonthSnapshotTest' test`; `grep -c "void BPA64_"` across both = 13; `npm test`; `gh pr checks <n>` |
| The two GETs documented | `grep -n "cbs-actuals\|me/variance-alerts" api/openapi.yaml` |
| Process runs end to end locally | the run below |
| BPMN and DMN match deployed | the deployed `Process_PPC_WeeklyProgressPeriod` and `Decision_ManhourVariance` equal `models/` (whitespace-normalised hash, as BPA-63) |
| Open questions resolved or logged | the Questions below, as Jira comments (shown first) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-64.md` |
| Demoed at sprint review | Human gap |

**End-to-end run in the local stack** (rebuild `bpa-app` and the portal):
1. **Reset the 19 Nov week** (as BPA-63's proof) and seed Approved lines so CBS-139-PIP-01 is at 1200.00 by SQL. The local data has no CBS time sheets with hours at that size.
2. **Start the weekly instance** at `UserTask_ApproveCompletion`; approve as ppc-team. The instance goes through Earned man-hours to the variance check.
3. **Mailpit** (http://localhost:8025): one email to cost-controller and one to the PCON address, with the Jira text. **`psql`:** `variance_alert` rows.
4. **As cost-controller:**
   - `GET cbs-actuals` (ETC −200.00, alert true, "No CBS" row);
   - `GET /me/variance-alerts`;
   - complete the review without a remark (400 text), then with "Rework on spools" (204).
5. **As supervisor:** `cbs-actuals` returns 403.
6. **Operate:** the instance waits at `UserTask_ApproveWeeklyProgress` (BPA-66, no screen yet). **`psql`:** the `cbs_review` row.
7. **Q1 A:** set the clock-dependent job by calling it for 1 Dec 2026, then `psql` `cbs_manhour_snapshot` and `hours_month_lock`.
8. **Browser:** open the review screen as cost-controller and check the negative ETC and the alert list.

## Risks
- **Size:** 8 points with 12 scenarios (13 tests), 3 workers, 2 endpoints, 2 screens, a BPMN change, a migration and a scheduled month-lock job. This is more than 8 points (Q6).
- **BPMN change** (mappings, a new service task) redeploys the weekly process. Old instances keep the old wiring. The local 19 Nov week must be reset.
- **Existing tests:** BPA-61, 62 and 63 tests wait for `ServiceTask_PrepareVarianceCheck`; their waits must accept the later steps.
- **Live actuals on screen:** `cbs-actuals` is worked out when called, so hours approved after the check can change what the Cost Controller sees from what the alert used (Q10).
- **Mail outage:** the InSystem row exists but the job ends as an incident, as BPA-16.
- **Persona project:** cost-controller is on PRJ-140 while the fixtures are PRJ-139. This doesn't matter today (access by group); it will when BPA-76 to 79 scope by project.

## Questions
1. **Month lock trigger and scope.**
   - Nothing models or codes the month lock; spec S5 gives the month lock to **BPA-67** ("BPA-67 + new scenarios for the month lock"), while D5 puts the snapshot scenarios in BPA-64.
   - **Options:**
     - **(A) recommended:** a Spring `@Scheduled` daily job (injected clock, Asia/Riyadh) that, on or after `month-lock-day`, writes `hours_month_lock` and the snapshot for the previous month in one transaction. No BPMN change. BPA-67 later blocks hours in a locked month.
     - **(B)** a new BPMN timer process (a new model; a timer on the 1st or 16th).
     - **(C)** a step in the weekly process when a cut-off crosses the lock day. It locks late, up to 6 days, because the cut-off is Thursday.
   - **Scope:** I'd recommend moving the month lock and the two snapshot scenarios out of BPA-64 into BPA-67 or a new story (see Q6). If you keep them here, I'd build (A).
   - I won't change the BPMN or add a process without your answer.
2. **PCON Manager email idempotency.**
   - `variance_alert.recipient_username` is NOT NULL with a foreign key to `app_user`, and the PCON Manager has no user (D3).
   - **Recommended:** `V5__variance_alert_recipient_email.sql`:
     - `recipient_username` becomes NULL-able;
     - a new `recipient_email text`;
     - a CHECK that exactly one of the two is set;
     - the UNIQUE becomes a unique index on (period, CBS, type, `coalesce(recipient_username, recipient_email)`, channel).
   - The PCON row is then (period, CBS, Manhour, email, Email).
   - Alternatives:
     - an `app_user` row "pcon-manager": breaks "exactly eleven personas" and the persona sync;
     - no record for the PCON email, so a re-run may resend it: breaks "once per period … and channel".
   - I won't add V5 without your yes.
3. **Which CBS get a row.**
   - **Recommended:** every CBS with Approved hours up to the week ending **or** a forecast row.
   - A forecast-only CBS shows actual 0 and WITHIN, so the Cost Controller sees every forecast line.
   - "No CBS" is a separate total, never a check.
4. **"Once each week":** confirm the guard is per **period**, so the same CBS over 15% next week alerts again. That is the existing UNIQUE key; there's an extra test for it.
5. **Variance %:** (actual − forecast) ÷ forecast × 100, half-up to 2 dp, in the message, `variance_alert.variance_pct` and the API: (1200 − 1000) ÷ 1000 × 100 = "20.00". Actual and forecast also show 2 decimals. Confirm.
6. **Size (8 points).** The plan shows more than 8. **Proposed split** (I won't split it myself):
   - **(a)** BPA-64 keeps the variance check, alerts, review task, the two GETs and the two screens (11 scenarios, 11 tests);
   - **(b)** a new story, or BPA-67, takes the month lock and snapshot (scenarios 12 and 13 and Q1).
7. **Jira:** BPA-64 is **Ready**. Should I move it to In Progress when work starts? I'll ask before any transition.
8. **Rule-task wiring (BPMN change, needs your yes).** Both rule tasks lack input mappings, and their results lose the CBS. **Recommended:**
   - add the mappings and an output element with `cbsCodeRef` to **both** tasks now (one model change; BPA-65 then uses the cost results);
   - correct the DMN description (exactly 15% is sourced by D3).
   - Proved by a red test first (1150.01 not alerting) before the model change.
9. **Where `cbs_review` is written.** Nothing sits between the review task and `UserTask_ApproveWeeklyProgress`.
   - **Recommended:** a new `ServiceTask_RecordCbsReview` (`record-cbs-review`), as BPA-60 added `ServiceTask_RecordCompletionApproval` (BPMN change, needs your yes).
   - The alternative is the task API writing `cbs_review` before it completes the task. That departs from "workers write" (ADR 0006).
10. **`result` on the read API.**
    - **Recommended:** work it out in Java with the decision's 3 rules (a copy, **ASSUMPTION**), with `alert` from the stored `variance_alert` rows.
    - Actuals are live; the alert stays what the check found.
    - The alternative is reading `manhourCheckResults` from the instance's variables through Camunda: fewer copies, but a REST call per request and nothing after the instance ends.
11. **In-system alerts UI.** No UI decision exists. **Recommended:** a "My alerts" list on the Tasks page for cost-controller and project-manager, read from `GET /me/variance-alerts`.
    - Not a bell or new route.
    - BPA-16's `/me/notifications` still has no screen; that is out of scope here, as a note for the backlog.
12. **Texts not in Jira (ASSUMPTION):**
    - "Reviews are required";
    - "Remark can be at most 500 characters";
    - "CBS is not part of this review";
    - "Week not found" (as earlier stories);
    - the email subject: "Man-hour alert: <CBS>, week ending <d MMM yyyy>" (**ASSUMPTION**; Jira gives only the message).
13. **`CBS-140-STR-01`** (scenario 4) is not in the reference data. **Recommended:** the test puts it on a time sheet (no foreign key), with no `cbs_forecast` row; the reference data is not changed.

## Human-gap log
- None yet. No inputs were staged before this plan.

## Resolutions (21 Nov 2026)
Approved with: Q1 keep the month lock and snapshot in BPA-64, as option A (a daily Spring @Scheduled job; no BPMN change) and so Q6 no split; Q2 the V5 migration (recipient_email, recipient_username nullable, exactly one set, unique index on coalesce); Q3 every CBS with approved hours or a forecast; Q4 the guard is per period; Q5 half-up 2 dp; Q7 no Jira transition without asking; Q8 input mappings and cbsCodeRef on both rule tasks, DMN description corrected; Q9 new ServiceTask_RecordCbsReview; Q10 the read-API result worked out in Java; Q11 a My alerts list on the Tasks page; Q12 the ASSUMPTION texts as written (to comment in Jira after a yes); Q13 CBS-140-STR-01 only on a test time sheet.
