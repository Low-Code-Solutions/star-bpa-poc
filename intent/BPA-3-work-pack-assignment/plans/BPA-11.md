# Plan: BPA-11 Production Planner can schedule a Released Work Pack
Jira: BPA-11 (Story, epic BPA-3)
Story last updated: 2026-11-05T15:20:36.118+0400
Status: Closed (2026-11-05)
Approver: Ahsan (Build Lead)
Branch: BPA-11-schedule-work-pack, from main f3207ac


## Context
Since BPA-9, an approved Work Pack is Released and waits at `UserTask_ScheduleWorkPack` (production-planner). That task has
no screen, `save-workpack-schedule` has no worker, and the read API for the Look-Ahead PPC Plan-Dates is not built. BPA-11
builds all three:
- The Schedule Work Pack screen (skill `new-task-screen`). It shows the Work Pack, its date to complete and the three Look-Ahead
  PPC Plan-Dates read-only. The Planner enters the planned start and end. A non-blocking notice appears when the end is after the
  date to complete (D26).
- `GET /api/v1/workpacks/{workpackNumber}/look-ahead`, as already defined in api/openapi.yaml (PO decision 2 Nov, BPA-46 Q4,
  comment 10466).
- The save worker (skill `new-job-worker`):
  - checks the lead time on both dates (today Asia/Riyadh + 7, D9, D28, F10), then end ≥ start (D56);
  - throws SCHEDULE_INVALID on a failure, so the task returns with `validationMessages`;
  - writes one `workpack_schedule` per Work Pack (D27, D65), with `scheduled_by` from `completedBy` (D63).

After the save, the token moves to `ServiceTask_GenerateWorkPackPrint` (BPA-10, no worker yet) and waits there.

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met |
| Gherkin, testable, unhappy paths | Met: 9 scenarios (1 outline, 3 rows) |
| BPMN IDs exist | Met: Gateway_IsSignOffApproved → Gateway_MergeSchedule → UserTask_ScheduleWorkPack (production-planner) → ServiceTask_SaveWorkPackSchedule (`save-workpack-schedule`, retries 3) → BoundaryEvent_ScheduleInvalid (SCHEDULE_INVALID, `validationMessages` out) → Gateway_MergeSchedule |
| SOR per field | Met: workpack_schedule (POC database; V1 has UNIQUE workpack_id and CHECK end ≥ start); lookahead_item (D365 PCON copy); scheduled_by (Camunda identity); lead time is config (§6.2) |
| DMN complete | n/a |
| No blocking question | Met |
| ≤ 8 points | 2. The screen, the worker and the new read API together are more; said here, the estimate is not changed |
| PO approved | Met (Faraz 28 Oct; note: the lead-time rule applies to both dates and is checked before end ≥ start); status In Progress |

Already in place, reused:
- the contract `getLookAhead` (200 with absent dates when no row; 400 InvalidWorkpackNumber; 404 "Work Pack not found"; open to
  every logged-in user, x-assumption) and the schema `LookAhead`;
- the allow-list `[UserTask_ScheduleWorkPack]` (write: plannedStartDate, plannedEndDate; the data-model names, not the form
  spec's plannedStart and plannedEnd, BPA-46 Q2);
- `docs/form-specs/Form_ScheduleWorkPack.form`.

**Reported, not resolved silently: the seed does not match the AC's Givens** (as in BPA-8).

| Scenario | AC says | Seed has |
|---|---|---|
| Scheduled with Look-Ahead dates | DWG-118: Shop Drg Mark 03 Nov, Assembly / Integration 10 Nov, Weld Map 05 Nov | DWG-118: 02 Nov, 04 Nov, 06 Nov |
| No Look-Ahead row | no row for DWG-121 | DWG-121 has a row (DWG-150 and DWG-151 have none); DWG-121's latest revision is IFI, so its Work Pack cannot pass the release check either |

As decided for BPA-8, each test arranges exactly its Given and restores the seed afterwards: the look-ahead row, plus an IFC
revision and a reservation for DWG-121 so its Work Pack reaches the Schedule task. The seed is not changed. The mismatch goes
to Khitaba in the Jira comment.

## Design: look-ahead read API
`api/LookAheadController implements LookAheadApi` (generated) with `persistence/LookAheadRepository`:
- Work Pack by number; none → 404 "Work Pack not found".
- Its drawing's `lookahead_item`. No row → 200 with only `workpackNumber` and `drawingRef`; the dates are left out (the contract,
  and `default-property-inclusion: non_null`).
- A malformed number → 400 "Workpack number must match WP-######", the same handler as `HourJournalHeaderController`.
- Security: `anyRequest().authenticated()` already covers the path; no new rule (the contract says every logged-in user).

## Design: save worker (skill `new-job-worker`)
`worker/SaveWorkPackScheduleWorker` (`@JobWorker(type = "save-workpack-schedule", autoComplete = false)`) with
`persistence/WorkPackScheduleRepository`, and a pure `domain/ScheduleValidator`:
1. Find the Work Pack by instance key (else throw). **Idempotent, checked first** (D65, S7): if a schedule already exists, change
   nothing and complete (dates are fixed once saved, D27).
2. Read `plannedStartDate` and `plannedEndDate` (ISO dates).
3. Validate, all messages collected and de-duplicated, then BPMN error SCHEDULE_INVALID with `validationMessages`
   (G2, D56, as SaveWorkPackWorker does):
   - missing dates → "Planned start is required" / "Planned end is required" (ASSUMPTION texts; the form spec marks both
     required);
   - each date earlier than today (Asia/Riyadh, from the injected `Clock` and `ZoneId`, S9) + `app.lead-time-days` (new config,
     default 7, §6.2) → "Date cannot be earlier than 28 Oct 2026" (date as `dd MMM yyyy`, English);
   - only when both dates pass the lead-time check: end before start → "Planned end cannot be before planned start".
4. Valid:
   - the Work Pack must be Released (the release gate, D25; anything else is a fault, so the job retries and ends as an incident);
   - `INSERT … ON CONFLICT (workpack_id) DO NOTHING`, with `scheduled_by` = `completedBy` (missing → fault; never the service user);
   - audit rows for workpack_id, planned_start_date, planned_end_date and scheduled_by (D64, §6.1, ISO dates);
   - complete with `validationMessages: []`, which clears a returned task's messages, as BPA-23 does.
- The date-to-complete notice is not the worker's: the save succeeds when the end is after the date to complete (D26).

## Design: screen (skill `new-task-screen`)
`frontend/src/features/workpacks/components/ScheduleWorkPack/` on `TaskScreenShell`, titled "Schedule Work Pack":
- Read-only:
  - the Workpack number;
  - the Work Pack's activity, drawing, department and **date to complete** (`showDate`);
  - "Look-Ahead PPC plan dates": Shop Drg Mark, Assembly / Integration and Weld Map Drawing, from a new `getLookAhead` service
    and `useLookAhead` hook (via `apiClient`, TanStack Query). When the API returns no dates: "No Look-Ahead plan dates for
    <drawing>".
- Inputs: "Planned start" and "Planned end" (type date), prefilled from the task (a returned task keeps what was entered).
- A non-blocking notice "Planned end is after the date to complete (06 Nov 2026)" when end > date to complete; none when equal.
- `check()`: only the two required messages (same ASSUMPTION texts). The lead-time and order rules are the save step's, so the
  task returns showing them, as the AC says.
- `variables()`: `{plannedStartDate, plannedEndDate}`.
- Register it in `USER_TASKS` and `taskScreens.ts`.
- Read allow-list for the Schedule task gains activityRef, drawingRef, departmentRef and completionDate (write unchanged). This
  is the same choice as BPA-9's sign-off screen.
- Left out (form spec only, not AC): the "Earliest allowed" hint (the frontend does not know the lead time) and the live end ≥
  start hint.

## Scenario-to-test table
Backend: a new `ScheduleApiTest` on the BPA-8/9 pattern:
- the app on the integration Camunda with workers on, the TestClock at 21 Oct, `@DirtiesContext`;
- arrange/restore of seed rows as in ReleaseCheckApiTest;
- a Background helper that takes a Work Pack the real way to the Schedule task: create → confirm → Ready → the Project Engineer
  approves.

React: tests beside the new screen.

| # | Scenario | Backend | React | Red first? |
|---|---|---|---|---|
| 1 | Scheduled with Look-Ahead dates | BPA11_workPackIsScheduledWithLookAheadDates: GET look-ahead gives the three arranged dates; complete 03 / 06 Nov as production-planner → workpack_schedule row with those dates and scheduled_by; audit rows | BPA11_workPackIsScheduledWithLookAheadDates: three labelled dates and 06 Nov 2026 shown read-only; completes with `{plannedStartDate, plannedEndDate}` | yes (no API, no worker, no screen) |
| 2 | Before lead time rejected (3 rows) | BPA11_plannedDateBeforeLeadTimeIsRejected: start 27 Oct → task returns "Date cannot be earlier than 28 Oct 2026"; start 28 Oct → accepted; end 27 Oct → returns with the same text | — | yes |
| 3 | End before start rejected | BPA11_plannedEndBeforeStartIsRejected: 05 / 04 Nov → returns "Planned end cannot be before planned start"; no schedule row | — | yes |
| 4 | Late end shows notice | BPA11_lateEndShowsNotice: 03 / 12 Nov is saved | BPA11_lateEndShowsNotice: the notice "Planned end is after the date to complete (06 Nov 2026)", and the completion still goes through | yes |
| 5 | End equal to date to complete | — | BPA11_endEqualToDateToCompleteShowsNoNotice | yes |
| 6 | No Look-Ahead row | BPA11_noLookAheadRowForDrawing: DWG-121 with its row removed → GET 200 with no dates; the schedule still saves | BPA11_noLookAheadRowForDrawing: "No Look-Ahead plan dates for DWG-121"; completion works | yes |
| 7 | Unknown Work Pack | BPA11_lookAheadForUnknownWorkPackReturnsNotFound: WP-999999 → 404 "Work Pack not found" | — | yes (no endpoint) |
| 8 | Other roles cannot schedule | BPA11_otherRolesCannotSchedule: as time-method-analyst the task is not listed; complete → 403 | — | **passes already** (BPA-26); said in the PR |
| 9 | Cannot be rescheduled | BPA11_scheduledWorkPackCannotBeRescheduled: after the schedule, no Schedule task for the instance; dates unchanged; a re-run job with other dates changes nothing | — | yes |

Non-scenario tests:
- a retried job saves once (mocked JobClient, no new audit rows);
- missing dates are refused on the screen and by the worker;
- missing `completedBy` fails the job;
- the lead time comes from `app.lead-time-days`.

A stub worker and a stub endpoint keep the classes compiling for the red run, as in BPA-8 and BPA-9.

## Files that change
- Backend:
  - new `api/LookAheadController.java`, `persistence/LookAheadRepository.java`, `worker/SaveWorkPackScheduleWorker.java`,
    `persistence/WorkPackScheduleRepository.java`, `domain/ScheduleValidator.java`;
  - `application.yml`: `app.lead-time-days: 7`, and the Schedule read list.
- Tests: new `ScheduleApiTest`, `ScheduleValidatorTest`; `ProcessTestApplication` (exclude the worker); possibly `CamundaSteps`.
- Frontend:
  - new `features/workpacks/components/ScheduleWorkPack/*` and its test;
  - `features/workpacks/services` (look-ahead) and a `useLookAhead` hook;
  - `utils/constants.ts`, `features/tasks/taskScreens.ts`, `features/workpacks/index.ts`;
  - `global.css` only if needed.
- Docs: `REVIEW.md`, this plan. No BPMN, DMN, migration, seed or contract change.

## Order of work
1. Commit this plan (Approved). You move statuses.
2. Tests first, with stubs; run them and show they fail for the expected reason (8 passes already).
3. Backend: validator, repositories, worker, controller, config. Green, then `./mvnw -B verify`.
4. Frontend: service, hook, screen, registry (frontend-design guidance). `npm test`, lint, format, build.
5. Local stack (rebuild bpa-app), through the API:
   - look-ahead GET;
   - an invalid date returns the task with its message;
   - a valid schedule is saved and the instance waits at Generate Workpack print.
6. qa-reviewer → REVIEW.md → fixes → plan Closed → push → PR → wait for all CI checks green → tell you → Jira comments (text
   shown first). You merge.

## Risks
- The Background path is long (create, confirm, release check, sign-off) for each test: longer runs, and more exposure to
  timing. The test-only 10 s job lock (BPA-9) is in place.
- Dates: the "today + lead time" boundary is computed in Asia/Riyadh from the TestClock (09:00 Riyadh on 21 Oct). A row that
  passes only in Riyadh is added, per the new-task-screen skill's rule for dates.

## Proof
`./mvnw -B verify` (red first, then green), `npm test`, lint, build, the local-stack run, CI.

## Questions (for Khitaba, ASSUMPTIONs)
- Q1: the seed vs AC look-ahead rows (above).
- Q2: the texts "Planned start is required", "Planned end is required", "Planned start is not a valid date" and "Planned end is not a valid date" (the AC states none).
- Q3: one message when both dates fail the lead-time check (de-duplicated), and end ≥ start is checked only when both pass
  (PO note of 28 Oct).

## Human-gap log
- Local Maven build uses the `subst S:` work-round and JAVA_HOME per command; `npm.cmd` in PowerShell; Docker Desktop must be
  running.

## Build notes (5 Nov 2026, as built)
- Built as planned, with these details:
  - `ScheduleValidator.validate(start, end, today, leadTimeDays)` returns the messages in the plan order: required texts, then the lead-time text once
    ("Date cannot be earlier than 28 Oct 2026"), then end before start only when both dates are present and pass the lead time.
  - `SaveWorkPackScheduleWorker` order: find the Work Pack, `completedBy` (a fault if missing), an existing schedule (completes, no change),
    Released (a fault otherwise), then the dates. A date that is not a date gives "Planned start is not a valid date" or "Planned end is not a valid
    date" (ASSUMPTION texts, added with the worker: its test, `unreadableDatesAreRefusedByTheSaveStep`, was written with the worker, not before it).
  - `app.lead-time-days: 7` in application.yml; the worker reads it with `@Value` (no default in code); a test asserts it is 7.
  - Look-ahead: `LookAheadController` and `LookAheadRepository` (a LEFT JOIN from the Work Pack, so a drawing with no row gives 200 with no dates).
  - Screen: a "Work Pack details" panel, a "Look-Ahead PPC plan dates" panel (shown only when a row has at least one date), a one-line "No Look-Ahead
    plan dates for DWG-121" when it has none, two date inputs, and the late-end notice. The screen never prefills the dates from a first visit, only from a
    returned task. A failed look-ahead call shows nothing (not "no dates").
- Red run (41e8b1d): ScheduleApiTest 14 of 15 failed (the schedule never saved: no worker; the look-ahead path gave a Spring 404 with its own text, not the
  contract; the lead-time property was missing), ScheduleValidatorTest 6 of 7, React 6 of 6 (the stub rendered nothing). BPA11_otherRolesCannotSchedule
  passed already (BPA-26).
- Green (fa776c6): ScheduleApiTest 16/16 and ScheduleValidatorTest 7/7 alone; full `./mvnw -B verify` 117 tests, 0 failures, with no combined-run flake
  (the BPA-9 test-only job lock is in place); `npm test` 33/33, lint, Prettier and build clean.
- Local stack (rebuilt bpa-app, through the API, real clock: today 5 Nov Asia/Riyadh, earliest allowed 12 Nov): a drawing with no row gives 200 with no dates;
  unknown Work Pack 404 "Work Pack not found"; malformed number 400; the planner sees the Work Pack and its date to complete; the Analyst does not list the task
  and gets 403; a start of 11 Nov returns "Date cannot be earlier than 12 Nov 2026"; end before start returns "Planned end cannot be before planned start";
  12 Nov to 15 Nov is saved (`scheduled_by` production-planner) and the instance waits at Generate Workpack print (BPA-10). Not checked in a browser.
- The task list says "Task completed; the Work Pack is saved" after a valid completion, and shows the returned task after a rejected one (BPA-26/BPA-23 wording
  and watch, left as they are).
- Not done on purpose: the "Earliest allowed" hint and the live end-before-start hint of the form spec (the frontend does not know the lead time).
- Review fixes (qa-reviewer, 5 Nov):
  - A test that the lead time counts from the Riyadh day, not the UTC day (the clock at 22:00 UTC on 20 Oct, which is 01:00 on 21 Oct in Riyadh: 27 Oct is
    rejected); the Risks line of this plan promised it and it was missing.
  - The save step now runs every rule on what can be read: an unreadable date adds its own text and no duplicate "required" text, and the lead-time and order
    rules still run (several messages at once, D56); tests for three combinations.
  - React: a test for a failed look-ahead call (nothing shown, no claim of "no dates"), a one-day-late end, and "Not set" for a plan date the row lacks.
  - The unreadable-date texts were written with the worker, not before it (said in the PR); they are listed in Q2 above and go to Khitaba in the Jira comment.
- Files that changed beyond the "Files that change" list: `frontend/src/features/workpacks/scheduleMessages.ts` (the screen field checks),
  `frontend/src/features/workpacks/hooks/useLookAhead.ts`, `frontend/src/features/workpacks/services/workpackService.ts` (getLookAhead),
  `frontend/src/types/api.ts` (the LookAhead type), `frontend/src/features/tasks/taskScreens.ts` and `frontend/src/utils/constants.ts` (the registry entry),
  and `backend/src/main/java/com/lcs/star/bpa/domain/ScheduleValidator.java` constants made public for the worker. `CamundaSteps` was not changed.
- CI fix (5 Nov, after the first CI run of PR #30): `BPA11_scheduledWorkPackCannotBeRescheduled` failed in CI because its "no Schedule Work Pack task" check ran while the
  just-completed task was still listed as open (the task search is eventually consistent, BPA-25; the listed task had the planner as assignee). The test now waits for the
  completed task to leave the open list, then checks that none comes back. The check itself is unchanged; it passed locally in every earlier run, and `ScheduleApiTest` is 17/17 again.
