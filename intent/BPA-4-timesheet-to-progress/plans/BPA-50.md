# Plan: BPA-50 Supervisor can book daily hours for each Worker on a Work Pack by phase
Jira: BPA-50 (Story, epic BPA-4)
Story last updated: 2026-11-17T14:28:03.756+0400
Status: Approved (Ahsan, Build Lead, 17 Nov 2026)
Approver: Ahsan (Build Lead)
Branch: BPA-50-book-daily-hours, from main 30f6685

> Written in plan mode and approved before this commit (first commit on the branch). The existing empty local branch `BPA-50-Supervisor` (at main 30f6685) was renamed to `BPA-50-book-daily-hours` (Q0, as asked). PR #44 (BPA-49) is **MERGED** (17 Nov 10:24 UTC, merge 30f6685 = origin/main); its `backend-tests` check still showed *pending* at merge, so main's CI is checked before the build starts.

## Context
Epic 1 ends with Draft Digital Time sheets, and BPA-49 now starts one `Process_PPC_TimesheetToProgress` per Work Pack, which waits at
`UserTask_BookHours` (supervisor). Nothing yet lets anyone put hours on those time sheets. BPA-50 adds the line API (add, change, delete,
list) with every line rule, the save-time call to `Decision_HoursWithinBudget`, the Released → In Progress move, and a grid screen for
`UserTask_BookHours`. Completing the task ("Send to Timekeeper") is **not** in BPA-50: its job `prepare-hours-submission` has no worker yet.

## Readiness check (Definition of Ready, docs/02, against Jira 17 Nov 14:28)
| Check | State |
|---|---|
| Jira status | **In Progress**, not Ready (moved 17 Nov 14:28; comment 10543: Ready at 11:45, approved by Faraz). Not moved by me. |
| Template, persona, outcome | Met |
| Gherkin incl. unhappy paths | Met: 19 scenarios (3 Scenario Outlines) |
| BPMN element IDs exist | Met: `Gateway_MergeBooking`, `UserTask_BookHours` (models on main) |
| Every field has an SOR | Met (SOR Notes) |
| DMN complete | **Stale text**: DoR says "3 rules"; the model has 5 (`Rule_CbsClosed`, `Rule_WorkerOnLeave`, `Rule_DailyLimitExceeded`, `Rule_PhaseBudgetExceeded`, `Rule_Allowed`, FIRST). Q2 |
| No blocking question | **Not met in substance**: Q6 (Worker-not-assigned scenario cannot happen with the per-time-sheet path) and Q7 (production-team cannot see the task) need answers before the build |
| Estimate ≤ 8 | Met on paper (5); this plan sizes it above 5 (Q5) |
| intent.md / spec.md accepted by PO | **Not met in the repo**: both still say DRAFT, "Not accepted" (spec L3, intent L5); Jira DoR box unchecked |
| PO approved | Met (comment 10543) |
| Contradictions in Jira | SOR Notes "403 outside supervisor" vs scenario "A Production Team member can also book hours" (C-E2-5): the Gherkin wins (precedence). Scenario 2 title still "(E2-D8, ASSUMPTION)" vs comment 10499 and Description D1 "sourced"; Source Reference still lists "ASSUMPTION: In Progress trigger" (Q3) |

## Design
### API (contract-first: `api/openapi.yaml` before any Java)
Tag `timesheet-lines`. Groups **supervisor and production-team** (C-E2-5) on all four.
| Call | Success | Errors (exact Gherkin/SOR text) |
|---|---|---|
| `GET /api/v1/digital-timesheets/{id}/lines` | 200 `TimesheetLines` | 404 "Time sheet not found", 403, 401 |
| `POST /api/v1/digital-timesheets/{id}/lines` body `{workDate, phase, hours, remarks?, reportedCompletionPct?}` | 201 `DigitalTimesheetLine` | 400 (messages below), 404 "Time sheet not found", 409 "Time sheet for <badge> on <WP> is Cancelled", 403, 401, 503 |
| `PUT /api/v1/digital-timesheet-lines/{id}` body `{hours, remarks?, reportedCompletionPct?}` | 200 `DigitalTimesheetLine` | 400, 404 "Line not found", 409 "Line is not editable (status <status>)", 403, 401, 503 |
| `DELETE /api/v1/digital-timesheet-lines/{id}` | 204 | 404 "Line not found", 409 "Line is not editable (status <status>)", 403, 401 |

- `{id}` is `digital_timesheet.id`. `DigitalTimesheet` (BPA-15 schema) gets `id` (additive) so the screen can find it.
- `TimesheetLines` (ASSUMPTION shape; Jira gives only "200"): `{timesheetId, employeeRef, workpackNumber, submissionStatus, phases[], openWeekStart, today, lines[]}`, so the grid needs one call per Worker and no extra endpoint for phases or the week.
- `DigitalTimesheetLine`: `{id, workDate, phase, hours, status, entrySource, remarks?, reportedCompletionPct?, createdBy}`.
- PUT changes hours, remarks and completion % only; date and phase are fixed (move = delete + add). **ASSUMPTION**.
- Dates in messages as `d MMM yyyy` ("17 Nov 2026"), as the Gherkin writes them.
- 503 when Camunda cannot evaluate the decision (existing pattern, ASSUMPTION text "The process engine is not available right now; try again later").

### Line rules: where each is enforced
API = `HourLineService` (one transaction per call). DB = V1/V2 constraint (backstop). No job worker is involved: lines are saved
through the API while the task is open (BPMN doc on `UserTask_BookHours`).
| Rule | Where | Message | Source |
|---|---|---|---|
| Group supervisor or production-team | SecurityConfig (all methods, both path patterns) | 403 | C-E2-5, 09 S6 |
| Time sheet / line exists | API | 404 "Time sheet not found" / "Line not found" | SOR Notes |
| Cancelled time sheet takes no lines | API (POST) | 409 "Time sheet for E1099 on WP-000457 is Cancelled" | BPA-13 D7, data model §13.1 |
| Change/delete only while Draft or Returned | API (PUT, DELETE) | 409 "Line is not editable (status Submitted)" | FRD-5-12 §5.12.8.1 |
| Remarks ≤ 250 | API + DB CHECK | 400 "Remarks can be at most 250 characters" | Gherkin |
| Hours > 0 | API (DB only `>= 0`) | 400 "Hours must be greater than 0" | Gherkin, Q-DM16 |
| Hours ≤ 2 decimals | API (DB numeric(7,2) would round) | 400 "Hours can have at most 2 decimal places" | Gherkin |
| Work date not in the future, Asia/Riyadh | API, injected `Clock` + `businessZone` (ADR 0013) | 400 "Work date cannot be in the future" | Gherkin |
| Cut-off week closed | API: week ending = the Thursday on/after the work date (E2-D15); closed if a `progress_period` row has that `week_ending` (any status) | 400 "Week ending 12 Nov 2026 is closed" | E2-D15; **ASSUMPTION** "row exists = cut off" (Q4). The Returned-line exception (BPA-67 a) is left to BPA-67 |
| Phase on the Work Pack | API (`workpack_phase`) | 400 "Phase Finishing is not on this Work Pack" | Gherkin |
| Worker assigned, flag not `HoursLoadedNotRemoved` | API (`worker_assignment` for the time sheet's Worker and Work Pack) | 400 "Worker E1150 is not assigned to WP-000457"; the same text for the flag (**ASSUMPTION**, no text in Jira) | Gherkin, E2-D16; Q6 |
| One line per Worker, date, phase (non-Rejected) | API pre-check + DB partial unique index (unique violation mapped to the same 400) | 400 "Hours already booked for E1021 on 17 Nov 2026 for phase Cutting" | E2-D4; Q1 |
| Reported completion 0-100, 2 dp | API + DB CHECK | 400 text **ASSUMPTION** "Reported completion must be between 0 and 100" | C-E2-10 |
| Budget decision (below) | API → Camunda | 400 per reason (below) | E2-D5, E2-D6 |
| First saved line: Released → In Progress | API, same transaction: `UPDATE workpack SET status='In Progress' WHERE id=? AND status='Released'` + audit row | none | D1 (FRD-7 §7.6 s13-14, §7.7.1) |
| Audit, one row per changed business column | API, same transaction, `clock.instant()`, username from the session | none | D64, §6.1 |

- Check order (**ASSUMPTION**; one message per response, as every existing endpoint): 404 → 409 → remarks → hours → decimals → future → cut-off → phase → assigned → duplicate → decision.
- Status on change: unchanged (a Returned line stays Returned) — **ASSUMPTION**, the resubmit flow is BPA-52/BPA-67.
- Audit on insert: every non-null business column (old null), as BPA-6. On delete: one row per business column with new value null — **ASSUMPTION** (§6.1 names inserts and updates only).
- `entry_source` = Form; `created_by`/`updated_by` = session username (the production-team scenario checks it).
- No check against a Completed Work Pack (the BPMN boundary `BoundaryEvent_WorkPackCompleted` closes the task; E2-16).

### `Decision_HoursWithinBudget` at every save (what BPA-50 calls, what it leaves)
- BPA-50 evaluates the decision on every POST and PUT, through Camunda REST `POST /v2/decision-definitions/evaluation` as `bpa-service` (new `adapter/CamundaDecisionClient`). No Java copy of the rules.
- Inputs BPA-50 computes: `dailyHoursAfter` (the Worker's non-Rejected hours on that date across all Work Packs, plus this line, minus the line's old hours on PUT); `phaseHoursAfter` and `phaseBudgetHours` (the Work Pack's non-Rejected hours for the phase, plus this line; `workpack_phase.manhours`).
- Inputs BPA-50 sends as constant `false`: `cbsClosed` (BPA-69 replaces it with a `cbs_block` read) and `workerOnLeave` (BPA-75, `worker_leave`). This matches the Description ("Not checked: leave days").
- So BPA-50 saves can hit only `Rule_DailyLimitExceeded`, `Rule_PhaseBudgetExceeded` and `Rule_Allowed`. On `Blocked` BPA-50 answers 400 with the data model §13.2.3 texts (marked (A) there): "Worker <badge> would exceed 16 hours on <date>" / "Phase <phase> budget of <budget> h would be exceeded". Their scenarios and tests belong to BPA-51; BPA-50 adds one non-scenario test that a Blocked answer stops the save.
- Left to BPA-51 (E2-04): the daily/phase scenarios and the send-time business rule task. BPA-69: CBS closed. BPA-75: leave.
- Camunda permission: `bpa-service` gets `DECISION_DEFINITION:Decision_HoursWithinBudget` `CREATE_DECISION_INSTANCE` (**ASSUMPTION**, proven by the first green test, as BPA-49's process permission was).

### Serving the task (needed for the screen, found while planning)
- `TaskService` serves only `bpa.tasks.process-id: Process_PPC_WorkPackAssignment`, only allow-listed elements, and `TaskSummary.elementId` is a 4-value enum: `UserTask_BookHours` never shows today.
- Change: `process-id` becomes `process-ids` (list: Epic 1 + `Process_PPC_TimesheetToProgress`), allow-list `UserTask_BookHours` read `[workpackNumber]`, write `[]`, enum gains `UserTask_BookHours`.
- `UserTask_BookHours` candidate group is `supervisor` only (BPMN l.66), so production-team cannot open the screen (Q7).
- The screen does not complete the task (Q8): `TaskScreenShell` gets an optional `canComplete` (default true); Book hours passes false.

### Frontend: Book hours screen (new-task-screen skill)
- `frontend/src/features/timesheets/` (`components/BookHours/`, `hooks/useTimesheetLines.ts`, `services/timesheetService.ts`, `timesheets.types.ts`); registered in `taskScreens.ts`, `USER_TASKS.bookHours` in `utils/constants.ts`.
- On `TaskScreenShell` (title "Book daily hours", `canComplete={false}`): reads `workpackNumber` from the task, lists the time sheets (`GET /api/v1/digital-timesheets?workpackNumber=`), then `GET .../{id}/lines` per Worker.
- Grid: one row per Worker, one column per day from `openWeekStart` to `today` (from the API; `utils/dates.ts` gets a `daysFrom(start, end)` helper), each cell one input per Work Pack phase. Cancelled time sheets are shown read-only.
- Selecting a cell opens a line panel below the grid: hours, remark (max 250), reported completion % (optional), Save (POST or PUT) and, on Draft lines only, Delete with an inline confirmation ("Delete this line? Yes / No"). Server messages shown in `ErrorMessage`. No UI library (skill).
- `GET /api/v1/digital-timesheets` gains production-team (today supervisor only) — needed for C-E2-5; changes BPA-15's rule (Q7).

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml`: 3 paths, schemas `TimesheetLines`, `DigitalTimesheetLine`, `NewDigitalTimesheetLine`, `DigitalTimesheetLineChange`; `DigitalTimesheet.id`; `TaskSummary.elementId` + `UserTask_BookHours`; tag |
| Backend API | new `api/DigitalTimesheetLineController.java`; `api/DigitalTimesheetController.java` (id) |
| Rules | new `timesheet/HourLineService.java` (transaction, order, decision call, In Progress); new `domain/HourLineChecks.java` (pure: hours, decimals, remarks, completion %, future date, week ending) |
| Persistence | new `persistence/DigitalTimesheetLineRepository.java` (insert/update/delete with audit, sums, cut-off, assignment, phase, status move); `DigitalTimesheetRepository` (id) |
| Camunda | new `adapter/CamundaDecisionClient.java`; `config/CamundaClientsConfig.java` (bean); `config/PersonaSync.java` + `PersonaSyncTest` (decision permission) |
| Security | `config/SecurityConfig.java`: `/api/v1/digital-timesheets/*/lines`, `/api/v1/digital-timesheet-lines/*` → supervisor, production-team; `/api/v1/digital-timesheets` + production-team (Q7) |
| Tasks | `task/TaskAllowList.java`, `task/TaskService.java`, `adapter/CamundaTaskClient.java` (process list); `application.yml` (process-ids, allow-list) |
| Model | only if Q7 = yes: `UserTask_BookHours` candidateGroups `supervisor, production-team` |
| Frontend | `features/timesheets/**`, `features/tasks/components/TaskScreenShell` (`canComplete`), `features/tasks/taskScreens.ts`, `utils/constants.ts`, `utils/dates.ts`, `types/api.ts`, `features/tasks/components/TaskList` (no Work-Pack "saved" wording for Book hours) |
| Tests | new `BookHoursApiTest` (19), `BookHoursFixture` (SQL Background), `HourLineChecksTest` (unit), `TaskApiTest` (Book hours listed for supervisor); `frontend/src/features/timesheets/components/BookHours/BookHours.test.tsx`, `TaskScreenShell.test.tsx` |
| Docs | `docs/data-model.md` §13.1 one line: where each line rule is enforced; plan; `REVIEW.md`; `intent/README.md` (generated) |
Not changed: database (no V3), DMN, Epic 1 models, workers.

## Order of work
1. Approve this plan; `git switch main && git pull`; branch (Q0); commit the plan as `Approved`; regenerate `intent/README.md`. Ask before any Jira move (it is already In Progress).
2. Contract: `api/openapi.yaml` (+ `npx --yes @redocly/cli@2.62.1 lint api/openapi.yaml` (CI's api-lint) / Redocly), `npm run gen:api`, backend generate-sources.
3. Tests first: `BookHoursFixture`, the 19 `BPA50_` tests and the decision/permission test; run, show red (expected: 404 "No static resource" on the new paths). Commit nothing until red is shown.
4. Backend: repository, checks, decision client, service, controller, security, permission, task serving. Green: `BookHoursApiTest`, `PersonaSyncTest`, `TaskApiTest`, then the BPA-15 and BPA-49 classes.
5. Frontend tests first (red), then the shell prop, grid, panel, registration; `npm run lint`, `format:check`, `test`, `build`.
6. Full `./mvnw -B verify` (background); docs line.
7. Local stack end-to-end (Proof); `qa-reviewer`; fixes; `REVIEW.md`; plan Closed; push; PR; Jira comments shown first.

## Scenario-to-test table (`BookHoursApiTest`; app against `IntegrationCamunda`, settable clock 2026-11-18 Asia/Riyadh, `@DirtiesContext` after class)
Background by SQL fixture, not by running Epic 1 (fast, and the Gherkin numbers can be used as written): WP-000457 Released, phases
Cutting 300 h and Welding 200 h; E1021 and E1099 assigned with Draft time sheets; a `progress_period` row week ending 2026-11-12, status
CutOff, a test-only process instance key (Q4). Lines in a Given are inserted by SQL with the stated status. Calls go through the API as the
persona (TestBrowser). Expected red for all: 404 "No static resource api/v1/digital-timesheets/…" (no path yet).
| # | Scenario | Test | Asserts |
|---|---|---|---|
| 1 | Supervisor books a line | `BPA50_supervisorBooksALine` | 201; row Draft, entry_source Form; GET lines shows 8 h for 17 Nov, Cutting |
| 2 | The first line sets the Work Pack In Progress | `BPA50_firstLineSetsWorkPackInProgress` | workpack.status In Progress; one audit row status Released → In Progress |
| 3 | Earlier day of the open week with a remark | `BPA50_earlierDayOfOpenWeekWithRemark` | E1099 16 Nov Welding 7.5, remark "Night shift", Draft |
| 4 | Remark at most 250 (boundary) | `BPA50_remarkAtMost250Characters` | 251 chars → 400 text; no row (250 → saved, extra row in the same test) |
| 5 | Supervisor changes a Draft line | `BPA50_supervisorChangesADraftLine` | PUT 9.5 → 200, 9.5; exactly one audit row `digital_timesheet_line.hours` 8 → 9.5 |
| 6 | Supervisor deletes a Draft line | `BPA50_supervisorDeletesADraftLine` | 204; no line for 17 Nov, Cutting |
| 7 | Second line same Worker, date, phase (E2-D4) | `BPA50_duplicateLineIsRejected` | 400 text; still one line |
| 8 | Outline: hours above 0 (0, 0.01, -1) | `BPA50_hoursMustBeAboveZero` | rows 0 and -1 → 400 text; 0.01 → 201 |
| 9 | Hours at most 2 decimals | `BPA50_hoursTwoDecimalsOnly` | 7.125 → 400 text |
| 10 | Outline: work date not in the future (18, 19 Nov) | `BPA50_futureDateIsRejected` | 18 Nov → 201; 19 Nov → 400 text |
| 11 | Outline: cut-off week closed (12, 13 Nov) | `BPA50_cutOffWeekIsClosed` | 12 Nov → 400 "Week ending 12 Nov 2026 is closed"; 13 Nov → 201 |
| 12 | Phase must be on the Work Pack | `BPA50_phaseNotOnWorkPackIsRejected` | Finishing → 400 text |
| 13 | Worker must be assigned | `BPA50_workerNotAssignedIsRejected` | per Q6: E1150 time sheet on WP-000457 with no assignment row → 400 text |
| 14 | A Submitted line cannot be changed | `BPA50_submittedLineCannotChange` | PUT → 409 text; hours unchanged |
| 15 | A Cancelled time sheet takes no lines | `BPA50_cancelledTimesheetTakesNoLines` | 409 text; no row |
| 16 | Other personas cannot book hours | `BPA50_otherPersonasCannotBookHours` | timekeeper POST → 403; no row |
| 17 | A Production Team member can also book hours | `BPA50_aProductionTeamMemberCanAlsoBookHours` | production-team POST → 201; Draft, Form, created_by production-team |
| 18 | Optional reported completion % | `BPA50_anOptionalReportedCompletionIsStoredForInformation` | stored 40.00; no `workpack_progress` row / approved % unchanged |
| 19 | A Submitted or approved line cannot be deleted | `BPA50_aSubmittedOrApprovedLineCannotBeDeleted` | DELETE → 409 text; line still exists |
Outline rows run inside their one test. Extra tests (not scenarios, named as sentences): decision Blocked stops a save; unique-index race
maps to the same 400; `HourLineChecksTest` week-ending and decimals; React: grid shows open week to today, Delete only on Draft with
confirmation, server message shown, no Complete button.

## Progress and departures (17 Nov 2026)
Steps 1-4 done (contract, tests first, backend, task serving). Questions answered by taking the plan's recommendation (Ahsan: "do your recommended
approach"): Q0 branch renamed; Q1 partial index stands; Q2 `cbsClosed`/`workerOnLeave` sent as false; Q4 "a `progress_period` row exists = cut off";
Q6 assignment checked on `worker_assignment` (the test gives E1150 a time sheet and no assignment row); Q8 no completion in BPA-50. Not applied:
Q7 (below). Q5: the split was **not** made; the frontend is built inside BPA-50 (the Jira Definition of Done requires the screen) and the story stays 5 points on paper.
Step 5 (frontend) done, see the last bullets.
- **Red first:** all 21 tests of `BookHoursApiTest` (the 19 scenario tests and 2 extras: `decisionBlockedAnswerStopsTheSave`, `phaseBudgetBlocksTheSave`)
  failed with 404 "No static resource" on the new paths; then 21/21 green on the first run of the new code. The fixture's SQL Background ran in every red test.
- **Q7 not applied (open, needs Khitaba or Faraz):** adding `production-team` to the time sheet list and to `UserTask_BookHours` would break two merged
  scenarios: BPA-15 ("other roles cannot read time sheets": `BPA15_otherRolesCannotReadTimesheets` logs in as production-team and expects 403) and BPA-49
  ("it waits at UserTask_BookHours for group supervisor": `BPA49_assignedWorkPackStartsTimesheetProcess` asserts exactly `supervisor`). Editing those tests
  would resolve a contradiction silently. So: the lines endpoints allow supervisor and production-team (scenario 17 passes), the list endpoint and the task stay
  the Supervisor's, the model is unchanged. Consequence: a Production Team member can book through the API but cannot list time sheets (to find the id) or open
  the task in the portal. Jira C-E2-5 and the BPA-15/BPA-49 scenarios disagree; precedence says the Jira stories, and both are Jira.
- **Decision permission proven:** `DECISION_DEFINITION:Decision_HoursWithinBudget` with `CREATE_DECISION_INSTANCE` was enough; both blocked tests passed.
- **ASSUMPTION added while building:** PUT and DELETE of a **Draft** line in a cut-off week are refused with "Week ending ... is closed" (data model §13.1:
  "editable only while Draft or Returned and its week is not cut off"); a **Returned** line is exempt (Description: "except correcting a Returned line, BPA-67 a").
- **Task serving:** `bpa.tasks.process-id` became `process-ids` (a list, Epic 1 + `Process_PPC_TimesheetToProgress`); `UserTask_BookHours` has empty read and write lists.
  New test `TaskApiTest.supervisorSeesTheBookHoursTask` (not a scenario); no existing test was edited.
- **Contract lint:** the repo has no `npm run lint:api`; CI runs `npx --yes @redocly/cli@2.62.1 lint api/openapi.yaml` (valid).
- **Also added:** `TestBrowser.put` and `delete` (test helper), `HourLineChecksTest` (unit), the data model §13.1 enforcement note.
- **PUT replaces hours, remark and completion (ASSUMPTION, found on the live stack):** a PUT without `remarks` clears the remark (and audits old value > null). The screen always
  sends the remark it shows, so it is safe there; the contract text says "Change the hours, remark or reported completion".
- **Frontend (step 5):** `features/timesheets/` (`BookHours` screen, `useBookHoursBoard`, `timesheetService`, `lineMessages`), `TaskScreenShell` `canComplete` (default true; no Complete
  button and no submit when false), `TaskScreenProps.onClose` with a "Back to my tasks" button (the screen has no completion to leave by), `daysFrom` in `utils/dates.ts`,
  `USER_TASKS.bookHours`, registered in `taskScreens.ts`, types in `types/api.ts`. 10 React tests `BPA50_...` in `BookHours.test.tsx`, red first (`Failed to resolve import
  "./BookHours"`), then green; `npm run lint`, `format:check`, `test` (66 tests) and `build` clean. One earlier full run had 1 failure in an existing file (a timing flake
  under load; the re-run was 66/66). The screen has **no** step to complete the task (plan Q8).
- **Local stack (17 Nov, API as the personas through the portal proxy, images built with plain `docker build`):** the supervisor's list shows "Book hours" for WP-000030, WP-000011
  and WP-000031; for WP-000031 (Released, phase Engineering only): a 8 h line on 16 Nov booked 201 (Draft, Form), the Work Pack went Released to In Progress with one audit row, the
  PUT to 9.5 h wrote one audit row (8 > 9.5), a duplicate gave 400 "Hours already booked for E1021 on 16 Nov 2026 for phase Engineering", Cutting gave 400 "Phase Cutting is not on this
  Work Pack", a date on 25 Nov 400 "Work date cannot be in the future", 7.125 h 400 "Hours can have at most 2 decimal places", the timekeeper 403, a production-team line on 15 Nov 201,
  and both Draft lines deleted (204, audit rows with a null new value). **Not done by Claude: a click-through of the screen in a browser** (human gap; the screen's calls are the ones above).
  The lines of WP-000031 are deleted again; its status stays In Progress.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| 19 tests passing in CI | `./mvnw -B -Dtest=BookHoursApiTest test` (19 run, 0 failures); `gh pr checks <n>` |
| Endpoints in openapi; React screen on TaskScreenShell | `npx --yes @redocly/cli@2.62.1 lint api/openapi.yaml` (CI's api-lint); `grep -n "digital-timesheet-lines" api/openapi.yaml`; `cd frontend && npm test -- BookHours && npm run build` |
| Process runs end to end in the local stack | `docker build -f backend/Dockerfile -t star-bpa-poc-bpa-app . && docker compose up -d --no-build bpa-app frontend` (bake grant issue after the folder rename); log in to http://localhost:3000 as supervisor, open "Book daily hours" for an Assigned Work Pack (e.g. WP-000031), book one cell → `psql … "select status from bpa.workpack where workpack_number='WP-000031'"` = In Progress, one Draft line, audit rows; then the same as production-team (if Q7 = yes) |
| BPMN and DMN match deployed | `curl -u demo:demo localhost:8080/v2/process-definitions/<key>/xml` diffed with the repo file (only if the model changes, Q7); DMN unchanged |
| Open questions resolved or logged | Questions below answered in Jira comments (shown first) or new items (with confirmation) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-50.md` in the final commit |
| Demoed at sprint review | Human gap |

## Risks
- **Size** (Q5): 19 API scenarios, a decision call with a new permission, a multi-process task list, a shell change and an editable grid. Likely 8, not 5.
- **Task list change** reaches every persona: Epic 2 tasks appear for supervisor (and production-team if Q7). Existing tests that assert a supervisor's list (BPA-26 `TaskApiTest`) may change; any such edit is stated in the PR.
- **Leftover Epic 2 instances** in the shared test Camunda now show as tasks: the BPA-49 clean-up (`cancelInstancesWaitingAt(BOOK_HOURS)`) keeps them down; the fixture uses its own Work Pack number.
- **Decision permission** unproven; if Camunda refuses, every save answers 503 until fixed.
- **No cut-off on the local stack**: no `progress_period` rows exist and the weekly worker is not built (BPA-67), so the API accepts any past date there; only the grid limits to the open week.
- `backend-tests` on PR #44 was pending when it merged: if main is red, BPA-50 starts from red (checked at step 1).

## Questions
0. **Branch.** A local branch `BPA-50-Supervisor` already exists at 30f6685 (no commits, not pushed). CLAUDE.md says to use an existing branch; you asked for `BPA-50-book-daily-hours`. Recommend: rename it to `BPA-50-book-daily-hours`. Which?
1. **Unique key.** SOR Notes say `UNIQUE (timesheet, date, phase)`; BPA-48 built `... WHERE status <> 'Rejected'` (Q5 resolution, Q-DM25, "Khitaba and Faraz to confirm"). The duplicate scenario still holds: the existing line is Draft, so the index rejects a second one. Difference only for Rejected lines (a new line is allowed), which no BPA-50 scenario covers. Confirm the partial index stands and Jira's SOR Notes should be corrected by comment.
2. **DMN rules vs Description.** Description: leave "not checked"; DoR: "3 rules". The model has 5 (BPA-69 CBS and BPA-75 leave added since). Proposed above: BPA-50 sends `cbsClosed = false` and `workerOnLeave = false`, so its saves hit only daily, phase and Allowed; BPA-69 and BPA-75 replace the two constants. Blocked texts from data model §13.2.3 (marked (A) there). Agree, or should BPA-50 already read `cbs_block` and `worker_leave`?
3. **Scenario 2 title.** Still "(E2-D8, ASSUMPTION)"; comment 10499 and the Description say D1 is sourced (FRD-7 §7.6 s13-14; FLOW-FMS-D365 p1), but comment 10482 says it stays ASSUMPTION "until STAR confirms" and Source Reference still lists it. Propose a Jira comment asking Khitaba to update the title and Source Reference; the test name does not change.
4. **Cut-off and progress_period.** No `progress_period` rows are seeded; the table has `week_ending`, `status IN ('CutOff','Locked')`, no week start and no Open state; rows appear only when the weekly process runs (BPA-67, not built). Proposed: a week is closed when a row exists for its `week_ending` (Thursday, E2-D15); the open week is Friday-Thursday containing today (13-19 Nov for 18 Nov). Tests insert one CutOff row for 12 Nov with a test-only `process_instance_key`. Agree? (Also: with no rows, the API accepts any past date; only the grid shows the open week.)
5. **Size.** This plan reads as ~8 points against 5. Proposed split (not done): BPA-50 = contract, API, rules, decision call, task serving, 19 tests; a new story "Supervisor can book hours on a grid screen" = `TaskScreenShell` change, grid, panel, React tests. Split, or keep one story?
6. **"Worker must be assigned" scenario.** Lines are posted to a time sheet (`/digital-timesheets/{id}/lines`), and a time sheet exists only for an assigned Worker, so E1150 has no time sheet to post to (404, not 400). The case is real only for a Worker removed by a replacement but kept with `HoursLoadedNotRemoved` (E2-D16). Proposed: the check reads `worker_assignment` for the time sheet's Worker (missing row or flag set → 400 "Worker <badge> is not assigned to <WP>"), and the test gives E1150 a time sheet on WP-000457 with no assignment row. Agree, or change the path to name the Worker (`POST /api/v1/workpacks/{n}/lines` with badge)?
7. **Production team access (C-E2-5).** `UserTask_BookHours` has candidate group `supervisor` only, and `GET /api/v1/digital-timesheets` is supervisor only (BPA-15). Without changes production-team can book only by API. Proposed: add `production-team` to the task's candidate groups (a model change in BPA-50) and to the list endpoint (BPA-15's rule widens). Agree?
8. **No completion in BPA-50.** Completing `UserTask_BookHours` means "Send to Timekeeper" and runs `prepare-hours-submission`, which has no worker (an incident). Proposed: the screen hides Complete (`canComplete=false`) until the story that builds the submit step (BPA-52?) turns it on. Which story owns it?
9. **ASSUMPTIONs to comment in Jira** (after a yes): `TimesheetLines` shape; PUT fields; check order; Returned stays Returned on change; audit on delete; completion % message; 503 text; the decision permission; the "row exists = cut off" rule.

## Human-gap log
- Plan written in plan mode: git switch/pull and the branch wait for approval.
- Jira BPA-50 was already In Progress when planning started (who moved it is in Jira's history; not by me).
- Faraz: intent/spec acceptance in the repo; Q2/Q3/Q4/Q6/Q7 decisions with Khitaba. Ahsan: Q0, Q5, Q8, merge.
