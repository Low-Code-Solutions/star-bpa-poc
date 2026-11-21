# Plan: BPA-65 Project Manager can be alerted when a CBS's cost exceeds its revised budget by more than 10%
Jira: BPA-65 (Story, epic BPA-4)
Story last updated: 2026-11-21T23:54:21.501+0400
Status: Approved (Ahsan, Build Lead, 22 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-65-cost-variance-alerts, from main c4901b2

> Checked read-only: main is at c4901b2, the merge of PR #57 (BPA-64), so 4aaeb55 is in main. The branch `BPA-65-cost-variance-alerts` is at c4901b2 with no commits of its own.

## Context
BPA-64 built the man-hour side of the weekly variance check. The cost side already runs but does nothing with its result:
- `BusinessRuleTask_CheckCostVariance` evaluates `Decision_CostVariance` for each CBS and writes `costCheckResults` entries `{cbsCodeRef, revisedBudgetCost, actualCost, costAlert, costResult}`.
- `send-variance-alerts` ignores those results.

BPA-65 builds four things:
- **Weekly cost alert:** above 10% over the Revised Budget, an email and an in-system alert to `project-manager`, and an email to the PCON Manager.
- **Daily close check:** the same check every day during the month-end close (FRD-22 §22.10). The close window is an ASSUMPTION.
- **Once per day:** the weekly and daily checks share one sender and alert at most once per CBS, channel, recipient and day.
- **Screens and API:** the Cost Controller's review screen gets the cost figures and the remark rule covers cost alerts; `/me/variance-alerts` lists only the user's own alerts.

## Readiness check (Definition of Ready, docs/02, against Jira 21 Nov 23:54)
| Check | State |
|---|---|
| Jira status | **In Progress** since 21 Nov 23:54 (the only change since 17 Nov). No other transition without asking (Q9) |
| Template, persona, outcome | Met (Project Manager) |
| Gherkin incl. unhappy paths | Met: 12 scenarios (2 Outlines); unhappy paths are NO_BUDGET, the supervisor, the missing remark and outside the close |
| BPMN element IDs exist | **Partly.** `BusinessRuleTask_CheckCostVariance` and `ServiceTask_SendVarianceAlerts` exist and are wired (BPA-64 Q8). The proposed `Process_PPC_DailyCloseCostCheck` / `StartEvent_DailyCloseCheck` does not exist (the DoR box is unticked) (Q1) |
| Every field has an SOR | Met. **But** `variance_alert.progress_period_id` is NOT NULL and its guard is per period, so a daily alert (no period) and "once per day" cannot be stored (Q2) |
| DMN | `Decision_CostVariance` is complete (FIRST, 3 rules: `null, <= 0` NO_BUDGET; `> 10` OVER_10_PCT; else WITHIN). Its description still calls "exactly 10% = no alert" an ASSUMPTION, but D3 (comment 10503) made it sourced. That is a doc-only fix (Q11) |
| No blocking question | Q1 to Q4 change the build |
| intent.md / spec.md accepted | **Not met** (box unticked; same as BPA-58 to BPA-64) |
| Estimate ≤ 8 | 5. The plan finds more: a migration, a scheduled job, a contract change, the screen and two existing-test edits (Risks) |

## What already exists (closed plan BPA-64 and the code; history only)
- **Weekly check data:** `VarianceCheckService.checks(weekEnding)` gives one check per CBS with Approved hours **or** a `cbs_forecast` row. Each check carries `revisedBudgetCost` (`cbs_forecast.revised_budget_cost`) and `actualCost`.
  - `actualCost` comes from `CbsActualsRepository.actualCost`: Σ `cbs_actual_cost.actual_cost` where `period_month <= weekEnding.withDayOfMonth(1)`, or null if there are no rows.
  - For the 19 Nov week that is Oct + Nov and not Dec, which matches the "sums the months" scenario (280000.00).
  - **Gap:** a CBS with only `cbs_actual_cost` rows (no hours, no forecast) is not a check, so scenario 3's CBS-140-STR-01 would not appear (Q5).
- **Sender (BPA-64):**
  - `SendVarianceAlertsWorker` reads only `manhourCheckResults`.
  - `VarianceAlertService` resolves the group through `CamundaGroupClient`, skips a user with no `app_user` row, and fails the job with no retries when nobody can be alerted.
  - It writes the InSystem row first, then sends each email only when its row is missing and records it after.
- **`variance_alert` as of V5:**
  - `progress_period_id` NOT NULL, `recipient_username` nullable, plus `recipient_email` (CHECK: exactly one is set).
  - Unique index `variance_alert_once` on (period, CBS, type, coalesce(recipient), channel).
  - `alert_type` CHECK IN (`Manhour`, `Cost`).
  - `VarianceAlertRepository.inSystemOf` **inner-joins `progress_period`**, so a row with no period would be dropped from the list.
- **Settings:**
  - `AlertSettings.monthLockDay()` reads `bpa.hours.month-lock-day` (1 or 16, else 1). `MonthLockJob` uses it at 00:10 Asia/Riyadh.
  - `AlertSettings.pconManagerEmail()` reads `bpa.alerts.pcon-manager-email`.
- **Permissions (PersonaSync):**
  - `bpa-service` has GROUP READ on `workpack-owner`, `project-engineer` and `cost-controller`. **There is none on `project-manager`**, so its member search would come back empty (BPA-64 departure 1).
  - `CREATE_DECISION_INSTANCE` exists only on `Decision_HoursWithinBudget` (BPA-50).
- **DMN from code:** `adapter/CamundaDecisionClient.evaluate(decisionId, variables)` (BPA-50) evaluates a deployed decision as `bpa-service` over REST. The rules stay in the model.
- **Read API, security and screen:**
  - `CbsActualsReadService` and the `CbsActualRow` contract carry man-hours only.
  - `SecurityConfig` limits `/api/v1/me/variance-alerts` to cost-controller and project-manager, and `VarianceApiTest` line 373 asserts **supervisor 403**. The new scenario wants **200 with no alerts** (Q6).
  - The `VarianceAlert` schema requires `weekEnding` (Q3).
  - `ReviewActualManhours.tsx` columns are CBS, Actual MH, Forecast, ETC, Variance %, Result, Alert, Remark.
- **Seed:** `cbs_forecast` CBS-139-PIP-01 (1000.00 MH, 250000.00 SAR) and `cbs_actual_cost` CBS-139-PIP-01 Nov 2026 180000.00 (repeatable seed `R__`). CBS-140-STR-01 exists nowhere.

## Design
### 1. Weekly path: `send-variance-alerts` also sends the cost alert
- **Input:** the worker also reads `costCheckResults`. It takes each entry with `costResult = "OVER_10_PCT"` and passes `{cbsCodeRef, actualCost, revisedBudgetCost}` to `VarianceAlertService.sendCostAlerts(period, checkDate, overs, user)`. An entry with no CBS, or an OVER entry with no figure, fails the job with no retries (as the man-hour side).
- **Check date:** the business date when the job runs (`LocalDate.ofInstant(clock.instant(), Asia/Riyadh)`, ADR 0013), not the week ending (Jira).
- **Text (Jira, exact):** "Cost on <CBS> is <pct>% over the Revised Budget (<actual> of <budget> SAR), <d MMM yyyy>".
  - pct = (actual − budget) ÷ budget × 100, half-up to 2 dp; both amounts are written with 2 decimals.
  - The over/within decision is the DMN's and works on the unrounded value: 275000.01 is 10.000004%, alerts, and shows "10.00".
- **Email subject (ASSUMPTION, Q11):** "Cost alert: <CBS>, <d MMM yyyy>".
- **Recipients:**
  - Members of `project-manager` with an `app_user` row get one InSystem and one Email each. This needs PersonaSync GROUP READ on `project-manager` (Q7).
  - The PCON Manager address gets one Email, recorded by `recipient_email` (V5).
  - **cost-controller gets no cost alert.**
  - If no PM is reachable and no PCON address is set, the job fails with no retries (BPA-64 review rule).
- **Order and failures:** as BPA-64. The InSystem row comes first; an email is sent when its row is missing and recorded after. A mail failure is not caught, so Camunda retries and then raises an incident (ADR 0007, 0012).
- **Stored values:** `actual_value` = actual cost, `baseline_value` = budget, `variance_pct`, `alert_date` = check date (V6), and `progress_period_id` = the week.
- **Man-hour side:** unchanged. Its guard stays per period (Q2).

### 2. Daily close check (Q1: option A recommended)
- **Mechanism:** `DailyCloseCostCheckJob`, a `@Scheduled(cron = "0 0 6 * * *", zone = "Asia/Riyadh")` job like `MonthLockJob`. It calls `DailyCloseCostCheckService.runFor(today)`. No BPMN change.
- **Window (ASSUMPTION, from Jira):** today is in the close when a lock date L (this month's or next month's `monthLockDay`) satisfies 0 ≤ L − today ≤ 3 days.
  - Lock day 1: 28 Nov to 1 Dec 2026 run; 27 Nov and 2 Dec do not.
  - Lock day 16: the 13th to the 16th.
  - Outside the window the job evaluates nothing and writes nothing.
- **What it checks:** the same CBS set and figures as the weekly check, built for today (Q4 sets the month for the sum).
  - Each CBS goes through `Decision_CostVariance` via `CamundaDecisionClient.evaluate`, so the rules are not copied. This needs `CREATE_DECISION_INSTANCE` on `Decision_CostVariance` for `bpa-service` (Q7).
  - Every OVER_10_PCT result goes through the same `sendCostAlerts`, with no period and check date = today.
- **Failures:** there are no Camunda retries outside a process (ASSUMPTION). A mail or engine failure is logged as an error and the job stops for the day. Rows already recorded stay; the next day's run or a weekly check sends again.

### 3. Once per day (Q2: V6 migration, needs your yes)
`V6__variance_alert_daily_cost.sql`:
- `progress_period_id` DROP NOT NULL, plus CHECK (`alert_type = 'Cost' OR progress_period_id IS NOT NULL`), so a Manhour alert always has its week.
- New `alert_date date`: backfilled from `sent_at` in Asia/Riyadh for existing rows, then NOT NULL.
- Drop `variance_alert_once` and replace it with two partial unique indexes:
  - `variance_alert_manhour_once` on (period, CBS, type, coalesce(recipient), channel) WHERE `alert_type = 'Manhour'`: BPA-64's guard, unchanged.
  - `variance_alert_cost_once` on (CBS, coalesce(recipient), channel, alert_date) WHERE `alert_type = 'Cost'`: **once per CBS, recipient, channel and day**, whether the weekly or the daily check sends it.
- **Same day, both checks:** if the daily check ran first that day and the weekly finds the daily row, the weekly sends nothing. Its insert's `ON CONFLICT … DO UPDATE` sets the missing `progress_period_id` on that row (audited), so the week's review still sees the alert (Q3).
- `VarianceAlertRepository`: `recordCost` and `existsCost` keyed by day; `inSystemOf` uses a LEFT JOIN and returns `alert_date`.
- Data model §13.5: "As built by BPA-65".

### 4. Review screen and remark rule (BPA-64 screen; agreed (a), 10 Nov)
- **Contract:** `CbsActualRow` gains optional `actualCost`, `revisedBudgetCost` and `costVariancePct`, plus `costResult` (NO_BUDGET | OVER_10_PCT | WITHIN) and `costAlert` (a Cost row exists with this period id).
- **`costResult` on the read API:** worked out in Java (`CostVarianceMath`, a copy of the 3 rules, ASSUMPTION), as BPA-64 Q10 did for man-hours.
- **Screen:** read-only Cost SAR, Revised Budget SAR, Cost variance % and Cost result columns, on every CBS row (Q8). The Alert column shows "Alert raised" for either type.
- **Remark rule:** `CbsReviewService.alerted(period)` covers Manhour **and** Cost rows of the period. The text stays "A remark is required for <CBS> (alert raised)" (Jira).

### 5. `/me/variance-alerts`
- **Security (Q6):** the rule becomes "any authenticated persona", still listing only the session user's InSystem rows. A supervisor gets 200 `[]`, a project-manager gets cost alerts only (it gets no man-hour alerts), and a cost-controller gets man-hour alerts only.
- **Contract (Q3):** `VarianceAlert.weekEnding` becomes optional (absent for a daily alert) and `alertDate` is added (required). A Cost message is rebuilt from the stored values with the cost text.
- **My alerts list:** shows the week when present and the message. It stays shown to cost-controller and project-manager only.

## Files that change (pending Q1 to Q8)
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml`: `CbsActualRow` cost fields; `VarianceAlert` (`weekEnding` optional, `alertDate`) |
| Model | `models/dmn/Decision_CostVariance.dmn` (description only); `ServiceTask_SendVarianceAlerts` documentation (guard text). No new process under Q1 A |
| Database | `V6__variance_alert_daily_cost.sql` |
| Backend | `worker/SendVarianceAlertsWorker`; `progress/VarianceAlertService` (`sendCostAlerts`), `VarianceMessages` (cost text and subject), new `CostVarianceMath`, new `DailyCloseCostCheckService` and `DailyCloseCostCheckJob`, `VarianceCheckService` (CBS set, Q5), `CbsActualsReadService`, `CbsReviewService`; `persistence/VarianceAlertRepository`, `CbsActualsRepository`; `api/VarianceAlertController`; `config/PersonaSync` (GROUP READ `project-manager`, decision permission), `SecurityConfig` |
| Frontend | `ReviewActualManhours` (cost columns), `MyVarianceAlerts` (optional week), `reviewMessages.ts` if needed |
| Test support | `BookHoursFixture` saves and restores the seeded `cbs_actual_cost`/`cbs_forecast` rows the tests change; `ProcessTestApplication` (the daily job must not run there) |
| Existing tests (to say in the PR) | `PersonaSyncTest.expectedServicePermissions` (2 new entries); `FlywayBaselineTest` (V6); `VarianceApiTest` line 373 (supervisor 403 → 200 `[]`, Q6); `ReviewActualManhours.test.tsx` and `MyVarianceAlerts.test.tsx` fixtures gain the new fields |
| Tests | new `CostVarianceApiTest` (weekly scenarios), `DailyCloseCostCheckTest` (daily scenarios); React tests |
| Docs | data model §13.5 "As built by BPA-65"; plan; REVIEW.md; `intent/README.md` |

## Order of work
1. Approve the plan and commit it (`Approved`).
2. Contract, V6 and model text, once answered.
3. Tests, backend then React, run red **before any production code**, each in its own commit. Expected: no Cost row (the worker ignores cost results), no daily job bean, the new contract fields missing, supervisor 403.
4. Backend: sender, weekly worker, V6 repository, daily service and job, read API, remark rule, security, PersonaSync. Then green, then the existing tests listed above, `ModelDeployTest` and `ModelResourcesTest`.
5. Frontend: lint, format, test, build.
6. Docs; full `./mvnw -B verify`; local-stack proof; `qa-reviewer`; fixes; REVIEW.md; plan Closed. Push, PR and Jira comments only when you ask.

## Scenario-to-test table
**`CostVarianceApiTest`** (`@Tag("integration")`, real workers and decisions, Mailpit, as `VarianceApiTest`):
- Settable `TestClock`, 20 Nov 2026 Asia/Riyadh unless stated.
- `cbs_actual_cost` and `cbs_forecast` rows set per test by SQL and restored after.
- App users seeded. An instance is started at `ServiceTask_PrepareVarianceCheck` for the 19 Nov week.

**`DailyCloseCostCheckTest`** calls `DailyCloseCostCheckJob.run()` directly with the clock set to the date at 06:00 Asia/Riyadh. There is no real waiting, and the cron itself is not exercised.

| # | Scenario | Test | Seed / act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1a | Alert fires above 10% (Outline row 1) | `BPA65_exactly10PercentNoAlert` | PIP-01 cost 275000.00 | no Cost row; review shows costResult WITHIN | red: `costResult` missing from the API |
| 1b | Outline row 2 | `BPA65_over10PercentAlerts` | 275000.01 | a Cost alert for PIP-01 (pct "10.00") | red: no Cost row |
| 2 | No revised budget | `BPA65_noRevisedBudgetNoAlert` | CBS-140-STR-01: `cbs_actual_cost` 10000.00, no forecast row | `cbs-actuals` row with costResult NO_BUDGET, costAlert false; no Cost row | red (the CBS is not a check, Q5) |
| 3 | Actual cost sums months | `BPA65_actualCostSumsMonthsToPeriod` | PIP-01 Oct 150000, Nov 130000, Dec 50000 | actual 280000.00 on the API and the alert row; alert true | red |
| 4 | Cost alert to the PM | `BPA65_costAlertGoesToProjectManager` | 300000.00 | project-manager: 1 Email in Mailpit and 1 InSystem with text "Cost on CBS-139-PIP-01 is 20.00% over the Revised Budget (300000.00 of 250000.00 SAR), 20 Nov 2026"; cost-controller: no Cost row, no cost email | red |
| 5 | My alerts list shows mine only | `BPA65_myAlertsListShowsMyAlertsOnly` (**no name in the DoD**, Q10) | after 4 | project-manager GET → 200, the cost alert, no Manhour alert | red |
| 6 | Other personas | `BPA65_otherPersonasCannotSeeCostAlerts` | after 4 | supervisor GET → 200 `[]` | red: 403 (Q6) |
| 7 | Cost on the review, remark needed | `BPA65_costVarianceOnCostControllerReview` | 300000.00 | `cbs-actuals` PIP-01 actualCost 300000.00, budget 250000.00, costVariancePct 20.00, costAlert true; complete with no remark → 400 "A remark is required for CBS-139-PIP-01 (alert raised)". React test of the same name: read-only cost cells | red |
| 8 | Sent once per day (retry) | `BPA65_costAlertSentOncePerPeriod` (Jira's name says period; the scenario says day) | 300000.00; a second instance started at `ServiceTask_SendVarianceAlerts` with the same variables, same day (BPA-64 departure 4) | still 1 Email and 1 InSystem for the PM that day | red |
| 9 | PCON Manager | `BPA65_pconManagerAlsoReceivesCostAlert` | `pcon.manager@star.example` (class property); 280000.00 | 1 email to that address, the same text as the PM's | red |
| 10a | Daily during close (Outline, 28 Nov and 1 Dec) | `BPA65_dailyCheckRunsDuringClose` | lock day 1; 300000.00; run on 28 Nov 06:00, then 1 Dec 06:00 | each day: 1 Email and 1 InSystem for the PM with that date in the text; no period on the row | red: no job bean |
| 10b | Daily outside close (27 Nov and 2 Dec) | `BPA65_noDailyCheckOutsideClose` | the same, on 27 Nov and 2 Dec | no Cost row, no email | red: no job bean (a compile stub makes it fail on the assertion) |
| 11 | Daily and weekly on the same day | `BPA65_aDailyAndAWeeklyCheckOnTheSameDayAlertOnce` (**no name in the DoD**, Q10) | clock 28 Nov; weekly instance alerts; then the daily job | the PM has 1 Cost InSystem and 1 Email for PIP-01 dated 28 Nov | red |

**Extras (not scenarios):**
- `aDailyAlertBeforeTheWeeklyCheckIsLinkedToTheWeek`: the daily check first, then the weekly the same day. Nothing is resent, and the row gets the period, so the review needs a remark.
- `aNewDayAlertsAgain`: the weekly on 20 Nov, then the daily on 28 Nov, gives a second alert.
- `costAlertFailsWhenNobodyCanBeAlerted` (unit, mocks; as `VarianceAlertServiceTest`).
- `closeWindowForLockDay16` (unit): the 13th to the 16th.

**React:**
- `ReviewActualManhours.test.tsx`: `BPA65_costVarianceOnCostControllerReview` (cells and the "Alert raised" for a cost-only alert).
- `MyVarianceAlerts.test.tsx`: a daily alert with no week.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest='CostVarianceApiTest,DailyCloseCostCheckTest' test`; `grep -c "void BPA65_"` across both = 13 (11 DoD names + 2, Q10); `npm test`; `gh pr checks <n>` (Actions may not start: billing) |
| Process runs end to end locally | the run below |
| BPMN and DMN match deployed | the deployed `Process_PPC_WeeklyProgressPeriod` and `Decision_CostVariance` equal `models/` (whitespace-normalised hash, as BPA-63/64) |
| Open questions resolved or logged | the Questions below as Jira comments (shown first, posted on your yes) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-65.md` |
| Demoed at sprint review | Human gap |

**End-to-end run in the local stack** (rebuild `bpa-app` and the portal; V6 applied):
1. **Seed:** `cbs_actual_cost` for CBS-139-PIP-01 so the sum up to November is 300000.00, by SQL.
2. **Weekly:** reset the 19 Nov week and start the weekly instance at `ServiceTask_PrepareVarianceCheck`.
   - Mailpit (http://localhost:8025): one cost email to project-manager and one to the PCON address, with the Jira text and today's date.
   - `psql`: `variance_alert` Cost rows (alert_date, period). None for cost-controller.
3. **As project-manager:** `GET /me/variance-alerts` returns the cost alert. **As supervisor:** 200 `[]`.
4. **As cost-controller:** `GET …/cbs-actuals` shows the cost columns and costAlert true. Completing the review with no remark gives the 400; with a remark, 204.
5. **Daily:** the job's clock is the real clock, so it cannot be moved in the stack. Run it with the business date inside the window in one of two ways (Q12):
   - (a) restart `bpa-app` with `bpa.hours.month-lock-day` set so today falls in the window, and call the job through a test-only trigger; or
   - (b) prove it only through `DailyCloseCostCheckTest`.
   - Then check Mailpit and `psql`, and that a second run sends nothing.
6. **Browser:** the review screen's cost columns and the My alerts list.

## Risks
- **Size:** 5 points, but the work is V6, a scheduled job with DMN evaluation, contract changes, security, the screen, PersonaSync and two test classes. Nearer 8.
- **V6 changes BPA-64's guard index:** the man-hour guard is rebuilt as a partial index, and its `ON CONFLICT` must name it, so all of `VarianceApiTest` reruns.
- **Seeded cost rows:** the tests change `cbs_actual_cost`/`cbs_forecast` (a repeatable `R__` seed). They must restore them, or other classes see different figures.
- **No retries for the daily job:** a mail outage during the close loses that day's emails (logged). Accepted unless you choose B.
- **`numeric(7,2)` variance_pct:** a tiny budget overflows, as BPA-64's note.
- **The daily check runs on every node:** the POC has one `bpa-app`, so this is fine. Several would send twice per day, and only the unique index stops the duplicate rows.

## Questions
1. **Daily mechanism.**
   - **(A) recommended:** the `@Scheduled` daily job above.
     - It evaluates `Decision_CostVariance` through `CamundaDecisionClient` (BPA-50's pattern; no copy of the rules) and alerts through the shared sender.
     - No new process. It is testable with the injected clock, and it matches BPA-64's `MonthLockJob`.
     - The cost: no Operate trace and no Camunda retries.
   - **(B)** `Process_PPC_DailyCloseCostCheck` with a timer start `StartEvent_DailyCloseCheck` (cron 06:00), a window worker, the rule task as a multi-instance and the send task.
     - It is visible in Operate and has retries.
     - But a timer-start process cannot be started by hand, so tests need a second none start event or must drive Zeebe time. It is also a new model and a new deployment.
   - **Jira effect:** under A, the BPMN Reference should drop the proposed process and name the job. I'd propose that in a comment; I don't edit the field. Under B, the DoR item is met once the model is approved.
   - I won't add a process without your answer.
2. **Once-per-day guard.** V6 as in §3: `alert_date`, `progress_period_id` nullable for Cost only, and two partial unique indexes. Cost becomes once per CBS, recipient, channel and **day**; man-hour stays once per **period** (BPA-64, unchanged). No migration without your yes.
3. **Which period a daily alert belongs to.**
   - **Recommended:** none. A daily alert is not part of any week's review and shows in My alerts with its date only.
   - If the weekly check finds a same-day daily row, it links the row to its week, so the review shows the alert and needs the remark.
   - `VarianceAlert.weekEnding` becomes optional and `alertDate` is added (a contract change).
4. **The month the cost sum goes up to.**
   - Jira says "months up to the check's month". BPA-64 sums up to the **week ending's** month.
   - The two differ only when a week ending falls late in a month and the check runs in the next month (for example week ending Thu 31 Dec 2026, checked 1 Jan 2027).
   - **Recommended:** weekly = the week ending's month, as built (the progress period's month); daily = today's month.
5. **Which CBS are checked.** Scenario 3's CBS-140-STR-01 has actual cost but no hours and no forecast, so today it is not a check.
   - **Recommended:** extend the set to every CBS with Approved hours, a forecast row **or** a `cbs_actual_cost` row up to the month (ASSUMPTION). Such a CBS shows NO_FORECAST, NO_BUDGET and 0 MH on the review.
   - The test puts CBS-140-STR-01 in `cbs_actual_cost`; the seed is not changed (as BPA-64 Q13).
6. **`/me/variance-alerts` for other personas.**
   - BPA-64 returns 403 for a supervisor (`VarianceApiTest` asserts it). BPA-65's scenario wants 200 with no alerts.
   - **Recommended:** open the endpoint to any authenticated persona (own alerts only) and change that one existing assertion, said in the PR.
   - Jira (newer story) wins over the earlier plan, but this reverses BPA-64.
7. **Permissions for `bpa-service`** (PersonaSync, ASSUMPTION as BPA-16/64): GROUP READ on `project-manager`, and `CREATE_DECISION_INSTANCE` on `Decision_CostVariance` (Q1 A only).
8. **Cost columns on the review:** **recommended** on every CBS row. Without a budget they are blank with result NO_BUDGET, and without cost the cost is blank. The alternative is to show them only where there is a budget.
9. **Month lock day setting:** the property is `bpa.hours.month-lock-day` (Jira calls it `hours.monthLockDay`), read through `AlertSettings.monthLockDay()` (1 or 16, else 1). `MonthLockJob` and the daily check read the same method. Confirm.
10. **Test names:** the DoD lists 11 names for 12 scenarios. Two scenarios have none, "My alerts list shows my alerts only" and "A daily and a weekly check on the same day alert once".
    - **Recommended:** add `BPA65_myAlertsListShowsMyAlertsOnly` and `BPA65_aDailyAndAWeeklyCheckOnTheSameDayAlertOnce` (13 `BPA65_` tests).
    - Keep `BPA65_costAlertSentOncePerPeriod` as named, even though it tests once per day.
11. **Texts and doc fixes (ASSUMPTION):**
    - the email subject "Cost alert: <CBS>, <d MMM yyyy>";
    - the `Decision_CostVariance` description (exactly 10% is sourced by D3; over-run only and SAR only stay ASSUMPTIONs);
    - the `ServiceTask_SendVarianceAlerts` documentation (the cost guard is per day).
12. **The daily job in the local-stack proof:** (a) add a test-only trigger, (b) prove it by the tests only, or (c) temporarily set the lock day so today is in the window and wait for 06:00. **Recommended (b)**, plus showing the job bean's cron in the log. No production endpoint is added for it.
13. **Jira:** BPA-65 is already In Progress. I'll ask before any transition.

## Human-gap log
- None yet.

## Resolutions (22 Nov 2026)
Approved with: Q1 option A, a daily Spring @Scheduled job (06:00 Asia/Riyadh) that evaluates Decision_CostVariance through the decision client, no new BPMN process; Q2 the V6 migration (alert_date, progress_period_id nullable for Cost only, two partial unique indexes: Manhour once per period, Cost once per CBS, recipient, channel and day); Q3 a daily alert belongs to no week and a same-day weekly check links it to its week; Q4 the weekly sum goes up to the week ending month and the daily sum up to the current month; Q5 every CBS with Approved hours, a forecast row or a cbs_actual_cost row is checked; Q6 GET /api/v1/me/variance-alerts open to every logged-in persona (supervisor 200 with an empty list), changing one existing BPA-64 assertion; Q7 GROUP READ on project-manager and CREATE_DECISION_INSTANCE on Decision_CostVariance for bpa-service; Q8 cost columns on every CBS row; Q9 the setting bpa.hours.month-lock-day shared with MonthLockJob; Q10 the two extra BPA65_ test names; Q11 the ASSUMPTION texts and the doc fixes; Q12 the daily job is proved by the tests only, no trigger added to the app; Q13 no Jira transition without asking.
