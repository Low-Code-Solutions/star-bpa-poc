# Plan: BPA-51 Supervisor can only book hours within the phase budget and the daily limit
Jira: BPA-51 (Story, epic BPA-4)
Story last updated: 2026-11-17T16:35:39.000+0400
Status: Approved (Ahsan, Build Lead, 17 Nov 2026)
Approver: Ahsan (Build Lead)
Branch: BPA-51-hours-budget-and-daily-limit, from main 1cfb42c

> Written in plan mode and approved before this commit (first commit on the branch). The local branch `BPA-51-hours-budget-and-daily-limit` already existed at main 1cfb42c (the BPA-50 merge, = origin/main) and is used (CLAUDE.md). Q1 (daily limit) and Q5 (send-time messages) are open: no DMN or model change until they are answered.

## Context
BPA-50 already evaluates `Decision_HoursWithinBudget` at every save (cbsClosed and workerOnLeave false) and refuses a Blocked line with the
data-model texts; its two non-scenario tests prove one daily and one phase block. BPA-51 owns the rule scenarios (8), makes the daily limit a
setting (Jira decision a), and builds the **send-time re-check**: `ServiceTask_PrepareHoursSubmission` (job `prepare-hours-submission`, no worker
yet) builds `lineChecks`, `BusinessRuleTask_CheckHoursWithinBudget` evaluates each line, and a Blocked line returns the instance to
`UserTask_BookHours` with `validationMessages`, the lines staying Draft. The happy path past `Gateway_AreHoursWithinBudget` (`ServiceTask_SubmitHours`,
job `submit-hours`) is E2-05 per the BPMN documentation; its Jira key is not confirmed (BPA-53? Q6). BPA-51 does not build it.

## Readiness check (Definition of Ready, docs/02, against Jira 17 Nov 16:35)
| Check | State |
|---|---|
| Jira status | **In Progress** since 17 Nov 16:35 (Ready at 11:45, comment 10544). Not moved by me. Its "Development" panel shows one **merged** PR: that is PR #45 (BPA-50), whose description mentions BPA-51; no BPA-51 PR exists (`gh pr list`). Q4 |
| Template, persona, outcome | Met |
| Gherkin incl. unhappy paths | Met: 8 scenarios (2 Scenario Outlines) |
| BPMN element IDs exist | Met: `UserTask_BookHours`, `ServiceTask_PrepareHoursSubmission`, `BusinessRuleTask_CheckHoursWithinBudget`, `Gateway_AreHoursWithinBudget`, `Flow_HoursBlocked` |
| Every field has an SOR | Met (SOR Notes) |
| DMN complete | **Stale text**: Jira's table has 3 rules; the model has 5 (CBS, leave, daily, phase, allowed; FIRST) since BPA-69/BPA-75 were added. With cbsClosed and workerOnLeave false the extra two never fire, so the 3-rule behaviour holds |
| No blocking question | **Not met in substance**: decision (a) (daily limit as configuration) conflicts with the hard-coded `> 16` in the DMN (Q1); how the send-time messages reach the task is not in the model (Q5) |
| Estimate ≤ 8 | Met on paper (3); the send-time worker and the model mappings make it larger (Risks) |
| intent.md / spec.md accepted | **Not met in the repo** (both DRAFT; Jira DoR box unchecked) |
| PO approved | Met (comment 10544) |

## Save time: BPA-50 against Jira (no change needed except the limit)
| Jira says | BPA-50 does (`timesheet/HourLineService.checkBudget`, `persistence/DigitalTimesheetLineRepository`) | Verdict |
|---|---|---|
| dailyHoursAfter = the Worker's hours on that date across all Work Packs, lines not Rejected, including this line | `workerHoursOn(workerId, date, exceptLine)` sums `digital_timesheet_line.hours` over every time sheet of the Worker (all Work Packs), `status <> 'Rejected'`, then `+ hours` | Matches |
| phaseHoursAfter = all Workers' hours on that phase of the Work Pack, not Rejected, including this line | `phaseHours(workpackId, phase, exceptLine)` over every time sheet of the Work Pack, `status <> 'Rejected'`, then `+ hours` | Matches |
| phaseBudgetHours = workpack_phase.manhours | `phaseBudget(workpackId, phase)` | Matches |
| On a change, old hours replaced, not added | both sums exclude the line being changed (`l.id IS DISTINCT FROM exceptLine`), then add the new hours | Matches |
| Draft, Submitted, Returned, Approved count; only Rejected does not (decision c) | `status <> 'Rejected'` | Matches |
| Messages: "Worker <badge> would exceed 16 hours on <date>", "Phase <phase> budget of <budget> h would be exceeded" | same texts; date as "17 Nov 2026"; budget as a plain number (300, not 300.00) | Matches; **"16" is hard-coded in the message** (fixed with Q1) |
| cbsClosed, workerOnLeave | sent as `false` (BPA-69, BPA-75 own them) | Kept |
Missing at save time: only the configurable limit (Q1). The rule-order scenario holds today: DMN FIRST evaluates daily before phase (Q3).

## Send time (new)
1. **`PrepareHoursSubmissionWorker`** (job `prepare-hours-submission`; new-job-worker skill; `autoComplete=false`, Camunda default retries, ADR 0007):
   - Reads `workpackId` and `workpackNumber` (process variables set by BPA-49's message); collects the Work Pack's **Draft and Returned** lines (BPMN doc).
   - None → BPMN error `HOURS_INVALID` with `validationMessages` ["No Draft or Returned hours to send for <WP>"] (text is the BPMN documentation's example, **ASSUMPTION**; `BoundaryEvent_NothingToSubmit` already maps it back to Book hours). Not a BPA-51 scenario; one non-scenario test.
   - Otherwise completes with `lineChecks` (one element per line, the BPMN doc's fields): `workDate`, `phase`, `badgeNo`, `phaseBudgetHours`, `phaseHoursAfter` (the phase total of non-Rejected lines, which already includes this saved line), `dailyHoursAfter` (the Worker's day total over all Work Packs, likewise), `cbsCodeRef`, `cbsClosed` false, `workerOnLeave` false; plus `dailyHoursLimit` (Q1) and the two message texts (Q5); and `validationMessages: []` (clears an earlier send's messages, BPA-23 pattern).
   - Idempotent: read-only (no rows written), so a retried job recomputes the same `lineChecks`.
   - The sums reuse `DigitalTimesheetLineRepository.workerHoursOn` / `phaseHours` (with no line excluded) and the message builders move from `HourLineService` into `domain/HourLineChecks` so save and send say the same text.
2. **`BusinessRuleTask_CheckHoursWithinBudget`**: evaluates each `lineCheck` (multi-instance, existing). **Model mappings change (Q5)**, no element added.
3. **`Gateway_AreHoursWithinBudget`**: unchanged; any `budgetResult = "Blocked"` takes `Flow_HoursBlocked` back through `Gateway_MergeBooking` to `UserTask_BookHours`. Lines are not touched, so they stay Draft.
4. **Messages on the returned task (Q5, recommended option 1):** `BusinessRuleTask_CheckHoursWithinBudget` gets `resultVariable="budgetCheck"` (today `lineCheck`, which overwrites the input element and loses the line's data) and `outputElement="={budgetResult: budgetCheck.budgetResult, budgetReason: budgetCheck.budgetReason, message: <the line's text for that reason, or null>}"`; the gateway condition `r.budgetResult` is unchanged. The task API (`TaskService.get` for `UserTask_BookHours`) shows `validationMessages` plus the `message` of every Blocked entry of `lineCheckResults` (a multi-instance output mapping runs per line, so the list cannot be built in the model without a new element). `BoundaryEvent_NothingToSubmit` gets a second output `lineCheckResults` ← `=[]` so a "nothing to send" never shows an older send's budget messages.
5. **The happy path** (`ServiceTask_SubmitHours`, `submit-hours`) is not built: an Allowed send waits there with no worker (Risks). The Book hours screen keeps `canComplete=false` (BPA-50 Q8); the send-time test and the local proof complete the task through the task API.

## Daily limit setting (Q1) — options and recommendation
| Option | What changes | For | Against |
|---|---|---|---|
| **A (recommended)** New DMN input `dailyHoursLimit`; Rule_DailyLimitExceeded input expression `dailyHoursAfter - dailyHoursLimit`, test `> 0` (as the phase rule); setting `app.daily-hours-limit: 16` next to `app.lead-time-days` (the pattern Jira names, data model §6.2) | DMN (1 input, 1 rule expression), `application.yml`, `HourLineService` (passes the limit, message uses it), worker (puts it in `lineChecks`), `Epic2DecisionTablesProcessTest` helper passes 16 (test edit, said in the PR) | Honours decision (a); one value for save, send and message; the rule stays in the DMN | DMN change; a BPA-48 test helper changes |
| B Keep `> 16` in the DMN, no setting | nothing; message keeps "16" | No model change | Departs from decision (a); the message text and the DMN can drift; changing it needs a model deploy |
| C Setting in Java, Java pre-check before the DMN | `HourLineService`, worker | No DMN change | Two places hold the rule; the send-time BRT would still use 16 |
Key name: Jira says `app.daily-hours-limit`; the repo already has `app.lead-time-days` (the pattern Jira cites), while BPA-48's Epic 2 settings sit under `bpa.` (ASSUMPTION there). Recommend `app.daily-hours-limit` as Jira writes it. **No DMN change until you answer.**

## Files that change (with Q1 = A and Q5 = 1)
| Area | Files |
|---|---|
| Model | `models/dmn/Decision_HoursWithinBudget.dmn` (input `dailyHoursLimit`, daily rule expression); `models/bpmn/Process_PPC_TimesheetToProgress.bpmn` (BRT `resultVariable`, `outputElement`; `BoundaryEvent_NothingToSubmit` second output); `npm run lint:bpmn` |
| Config | `backend/src/main/resources/application.yml`: `app.daily-hours-limit: 16` |
| Worker | new `worker/PrepareHoursSubmissionWorker.java`; `ProcessTestApplication` excludes it (needs the DB) |
| Reuse | `domain/HourLineChecks` (message builders, limit); `timesheet/HourLineService` (passes the limit; uses the builders); `persistence/DigitalTimesheetLineRepository` (lines to send, by Work Pack) |
| Task API | `task/TaskService` (Book hours: add Blocked `lineCheckResults` messages to `validationMessages`) |
| Docs | ADR or data model §6.2 line for `app.daily-hours-limit`; data model §13 note on send-time messages; plan; REVIEW.md; `intent/README.md` |
| Tests | new `HoursBudgetApiTest` (8 BPA51_ tests, reuses `BookHoursFixture`), fixture method for a second Work Pack (WP-000461); a worker test for "nothing to send"; `Epic2DecisionTablesProcessTest` helper (Q1 = A) |

## Order of work
1. Approve; commit this plan (`Approved`) on the existing branch; regenerate `intent/README.md`. No Jira transition without asking (Q4).
2. Tests first: `HoursBudgetApiTest` (8), fixture extension; run, show red. Expected red: scenarios 1-7 mostly **green already** (BPA-50 computes the inputs correctly) — those are proven, not built, and the plan will say so; `BPA51_sendRechecksTheBudget` red (no worker: the instance waits at `ServiceTask_PrepareHoursSubmission`).
3. After your Q1/Q5 answers: DMN + setting; worker; model mappings; task API; lint.
4. Green: the class, `BookHoursApiTest`, `Epic2DecisionTablesProcessTest`, `TaskApiTest`; then full `./mvnw -B verify`.
5. Local stack: a blocked send end to end (Proof). `qa-reviewer`, fixes, REVIEW.md, plan Closed, push, PR, Jira comment shown first.

## Scenario-to-test table (`HoursBudgetApiTest`; app against `IntegrationCamunda`, clock 18 Nov 2026 Asia/Riyadh; Background by `BookHoursFixture`: WP-000457 Cutting 300 h, Welding 200 h, E1021/E1099 time sheets; lines in a Given inserted by SQL)
| # | Scenario | Test | How | Expected first run |
|---|---|---|---|---|
| 1 | Outline: phase budget is a hard limit (8 / 8.01 on 292 h) | `BPA51_phaseBudgetReachedExactlyIsAllowed` | E1099 Draft lines totalling 292 h on Cutting; E1021 +8 → 201; +8.01 (after deleting) → 400 "Phase Cutting budget of 300 h would be exceeded" | green (BPA-50 already does it) |
| 1 | (same outline, second name in the DoD) | `BPA51_phaseBudgetExceededIsBlocked` | 292 h, E1021 +8.01 → 400 text, no row | green |
| 2 | Other Workers' hours count | `BPA51_otherWorkersHoursCountTowardsBudget` | E1099 295 h on Cutting; E1021 +6 → 400 text | green |
| 3 | Rejected lines do not count | `BPA51_rejectedLinesDoNotCount` | 285 h + 10 h Rejected; E1021 +15 → 201 | green |
| 4 | Outline: 16 h a day (8 / 8.25 on 8 h) | `BPA51_dailyLimitReachedExactlyIsAllowed` | E1021 8 h on 17 Nov (Cutting); +8 Welding → 201; +8.25 → 400 "Worker E1021 would exceed 16 hours on 17 Nov 2026" | green |
| 5 | Daily limit counts all Work Packs | `BPA51_dailyLimitCountsAllWorkPacks` | fixture: WP-000461 with an E1021 time sheet, 10 h on 17 Nov; +7 on WP-000457 → 400 text | green |
| 6 | Daily limit before phase budget (rule order) | `BPA51_dailyLimitIsCheckedBeforeThePhaseBudget` (proposed; no DoD name, Q7) | E1021 10 h that day (Welding) and Cutting at 299 h; +7 Cutting → the daily text, not the phase text | green |
| 7 | A changed line replaces its old hours | `BPA51_changedLineReplacesOldHours` | Cutting 300 h incl. E1021 8 h line; PUT 7 → 200, 7 h | green |
| 8 | Sending re-checks the budget | `BPA51_sendRechecksTheBudget` | start `Process_PPC_TimesheetToProgress` at `UserTask_BookHours` (start instructions, `workpackId`/`workpackNumber` of WP-000457); E1021 Draft 8 h Cutting; `UPDATE workpack_phase SET manhours = 5` (SOR Notes); complete the task as supervisor via `/api/v1/tasks/{key}/complete`; poll: a new Book hours task on the same instance; its `validationMessages` contain "Phase Cutting budget of 5 h would be exceeded"; the line is still Draft | **red**: the instance waits at `ServiceTask_PrepareHoursSubmission` (no worker) |
**Note on the counts:** Jira has 8 scenarios but the DoD lists 8 names that do not map one to one: the phase outline has two names (`...ReachedExactlyIsAllowed`, `...ExceededIsBlocked`) and the rule-order scenario has **no** name. Plan: the outline's two rows are split over the two DoD names, and the rule-order scenario is a ninth test `BPA51_dailyLimitIsCheckedBeforeThePhaseBudget` (Q7).
**Note on red-first:** 7 of 8 test BPA-50's save-time code and will pass on the first run; that is evidence BPA-50 met BPA-51's save rules, not a test edited to pass. The PR will show the first run.

## Progress and departures (17 Nov 2026)
Steps 1-4 done; answers taken from Ahsan (17 Nov): **Q1 = A** (new DMN input `dailyHoursLimit` plus `app.daily-hours-limit: 16`), **Q5 = 1** (model mappings plus the task API).
Q2, Q3 confirmed as planned (the tests reuse `BookHoursFixture`; the DMN order gives the daily text first). Q4 (Jira transition), Q6 (owner of `submit-hours`), Q7 (the
ninth test name, built as `BPA51_dailyLimitIsCheckedBeforeThePhaseBudget`), Q8, Q9 are still open.
- **Red first:** `HoursBudgetApiTest` first run: 8 of 9 green at once (BPA-50's save-time code already does what BPA-51 asks, as the plan said) and `BPA51_sendRechecksTheBudget`
  red (timed out: no `prepare-hours-submission` worker, the instance waited at `ServiceTask_PrepareHoursSubmission`). No test was edited to pass.
- **Finding, a model change the plan did not list:** the business rule task had **no input mappings**. The decision reads `cbsClosed`, `dailyHoursAfter`... as flat variables but in
  the multi-instance task they sit inside `lineCheck`, so every line would have read as Allowed and the Blocked path could never run (first green attempt: the "nothing to send" test
  passed and the blocked send still timed out). Fixed by six `zeebe:input` mappings (`=lineCheck.<field>` to the flat name) on `BusinessRuleTask_CheckHoursWithinBudget`; no element added.
- **Built:** `PrepareHoursSubmissionWorker` (`prepare-hours-submission`; read-only, so a retry builds the same checks); BRT `resultVariable="budgetCheck"` and `outputElement` carrying
  the message; `BoundaryEvent_NothingToSubmit` clears `lineCheckResults`; `TaskService.get` adds each distinct Blocked `message` of `lineCheckResults` to `validationMessages` for
  `UserTask_BookHours`; DMN: `dailyHoursAfter - dailyHoursLimit` with `> 0`; `app.daily-hours-limit: 16`; `HourLineChecks.dailyLimitMessage` and `phaseBudgetMessage` used at save and at send.
- **Tests:** `HoursBudgetApiTest` 10 (the 9 planned and `sendingWithNoDraftOrReturnedLineReturnsToBookHoursWithAMessage`), all green; `Epic2DecisionTablesProcessTest` helper passes
  `dailyHoursLimit` 16 (the one edit to a BPA-48 test, said in the PR) and a new test `BPA51_dailyLimitFollowsTheDailyHoursLimitInput` (limit 10); `ProcessTestApplication` excludes the
  worker; `lint:bpmn` clean.
- **Local stack (17 Nov; image rebuilt with plain `docker build`; version 2 of the process and the decision deployed):** on WP-000031 a booking of 8 h was refused at save with "Phase
  Engineering budget of 20 h would be exceeded" (the Work Pack's 18 Draft hours already booked in the portal); with the budget cut to 5 h (psql, test-only), a **fresh Epic 2 instance
  started by hand at `UserTask_BookHours`** (the existing one runs version 1, which has no BPA-51 mappings) was completed as supervisor through the portal API (204); a **new Book hours task
  on the same instance** appeared and `GET /api/v1/tasks/{key}` returned `validationMessages: ["Phase Engineering budget of 5 h would be exceeded"]`; the two Draft lines stayed Draft.
  Budget restored to 20 h, the manual instance cancelled. Not done by Claude: a look at Operate.
- **Existing instances keep their model version:** Epic 2 instances started before this deploy (WP-000011, WP-000030, WP-000031) run version 1 and a send there skips the budget check.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=HoursBudgetApiTest test` (9 run, 0 failures); `gh pr checks <n>` |
| Process runs end to end in the local stack | rebuild `bpa-app` (`docker build -f backend/Dockerfile ...`, `docker compose up -d --no-build bpa-app`); on WP-000031 (In Progress, Engineering): book a Draft line in the portal; `psql -c "UPDATE bpa.workpack_phase SET manhours = 1 WHERE ..."` (test-only, as the SOR Notes say); complete its Book hours task as supervisor with curl (`POST /api/v1/tasks/{key}/complete`, the screen has no Complete); then `GET /api/v1/tasks/{newKey}` shows the phase message; Operate shows the instance back at `UserTask_BookHours` via `Flow_HoursBlocked`; the line is still Draft; restore manhours |
| BPMN and DMN match the deployed models | `curl -u demo:demo localhost:8080/v2/process-definitions/<key>/xml` and `/v2/decision-definitions/<key>/xml` diffed with the repo files; `npm run lint:bpmn` |
| Open questions resolved or logged | Questions below, in Jira comments (shown first) or new items (with confirmation) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-51.md` |
| Demoed at sprint review | Human gap |

## Risks
- **An Allowed send gets stuck**: past the gateway `ServiceTask_SubmitHours` has no worker (E2-05), so any send that passes the budget waits there; the Book hours task is gone until that story ships. Keep the portal's Complete hidden; only tests and the proof send.
- **FEEL in `outputElement`** (Q5 option 1): proven by a Camunda Process Test on the model before the integration test; fallback is option 2.
- **DMN change** (Q1 = A) touches a BPA-48 test helper and every caller of the decision (save time, send time).
- **Concurrency** (from BPA-50): two simultaneous saves can both pass the limits; the send-time re-check is the backstop for anything that slips through.
- **Size**: 3 points against a new worker, model mappings, a DMN input and 9 tests.

## Questions
1. **Daily limit setting vs the hard-coded DMN.** Jira decision (a): `app.daily-hours-limit`, default 16, configuration. The DMN has `> 16`. Recommend **A**: new DMN input `dailyHoursLimit` (rule `dailyHoursAfter - dailyHoursLimit > 0`), setting `app.daily-hours-limit: 16` next to `app.lead-time-days`, passed by the save path and the send worker; the message takes the same value. Agree? (No DMN change until you answer.)
2. **Background and fixtures.** Jira's Background has no "week ending 12 Nov is cut off"; the tests reuse BPA-50's `BookHoursFixture`, which also inserts that row. It does not affect BPA-51's dates (17 Nov). Confirm the reuse (plus one fixture method for WP-000461).
3. **Rule order.** The DMN is FIRST with rules CBS, leave, daily, phase, allowed. With cbsClosed and workerOnLeave false, a line that breaks both limits hits `Rule_DailyLimitExceeded` first, so the scenario expects the daily text. Confirmed by reading the model; the test proves it.
4. **Jira state.** BPA-51 is In Progress (moved 16:35) and its Development panel shows a merged PR, which is PR #45 (BPA-50) because its description mentions BPA-51. No BPA-51 PR exists. I will ask before any transition.
5. **How send-time messages reach the task.** The model returns to Book hours on `Flow_HoursBlocked` but nothing turns `lineCheckResults` into `validationMessages`, and `resultVariable="lineCheck"` overwrites the line's data. Options: **1 (recommended)** model mappings only (BRT `resultVariable="budgetCheck"`, an `outputElement` carrying the message; one extra output on `BoundaryEvent_NothingToSubmit`) plus the task API adding Blocked messages to `validationMessages`; keeps `Flow_HoursBlocked`. **2** no model change: the worker evaluates the decision in Java and throws `HOURS_INVALID` with the messages, so the block returns through `BoundaryEvent_NothingToSubmit` ("Nothing to send") instead of `Flow_HoursBlocked`, and the BRT only re-checks what passed. Which?
6. **Owner of the happy path** (`ServiceTask_SubmitHours`, E2-05): BPA-53? Until it ships, an Allowed send waits at `submit-hours`.
7. **Test names.** The DoD's 8 names give the phase outline two names and the rule-order scenario none. Plan: split the outline's rows over the two names and add `BPA51_dailyLimitIsCheckedBeforeThePhaseBudget` (9 tests). Or should the rule-order assertion live inside `BPA51_dailyLimitCountsAllWorkPacks` to keep 8?
8. **"Nothing to send" text** "No Draft or Returned hours to send for <WP>" is the BPMN documentation's example, not a story's: ASSUMPTION, to comment in Jira.
9. **ASSUMPTIONs to comment in Jira** (after a yes): the setting key and placement; option chosen in Q5; the nothing-to-send text; that send-time sums include the saved line itself.

## Human-gap log
- Plan written in plan mode: no file outside this plan is changed yet.
- Faraz: intent/spec acceptance; Q1/Q5/Q6 with Khitaba if they change the model contract. Ahsan: Q1, Q5, Q7, merge.
