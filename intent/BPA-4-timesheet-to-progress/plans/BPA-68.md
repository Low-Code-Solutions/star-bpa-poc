# Plan: BPA-68 PPC Team can see one Work Pack run from creation to locked progress in one demo
Jira: BPA-68 (Story, epic BPA-4)
Story last updated: 2026-11-17T11:46:03.700+0400
Status: Approved (Ahsan, 22 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-68-end-to-end-demo, from main 6f789dc

> Written in plan mode and approved by Ahsan on 22 Nov 2026; committed as the branch's first commit.
> Checked read-only: main 6f789dc is the merge of PR #60 (BPA-67, merged 22 Nov 17:27Z). `BPA-68-end-to-end-demo` is at 6f789dc with no commits of its own.

## Context
BPA-68 is the showcase the POC exists for (00-context question 4). It takes one fresh Work Pack on A-2301 from creation (Epic 1) to a locked week of progress (Epic 2) and delivers two things:
1. **One automated run across the three processes**, as the three `BPA68_` tests in the Definition of Done.
2. **A demo script**, `docs/showcase/epic1-epic2-demo.md`, for the live run.

Every step it uses is already built (BPA-6 to BPA-82, BPA-48 to BPA-67). **BPA-68 adds no production code**: only a test class, a test helper, the script, at most one demo file, and docs.

## Readiness check (Definition of Ready, docs/02, against Jira 17 Nov 11:46)
| Check | State |
|---|---|
| Jira status | **Ready** (Faraz, 17 Nov; comment 10559). Ready → In Progress only when you say so (Q9) |
| Template, persona, outcome | Met (PPC Team; audience: STAR leadership) |
| Gherkin incl. unhappy paths | Met: 3 scenarios. **Scenario 3 cannot pass as written** (Q1, and Q5 for the 17 Nov date) |
| BPMN element IDs exist | Met: all three processes, main paths. No model change |
| Every field has an SOR | Met ("none new") |
| DMN complete | Met (all four Epic 2 decisions as built) |
| No blocking question | Jira says none. Q1 and Q5 change scenario 3's Gherkin. Q6: intent.md has no "Not shown" list to match word for word |
| intent.md / spec.md accepted | **Not met** (both DRAFT 10 Nov; same as BPA-58 to BPA-67) |
| Story-specific: script and demo data agreed with the build team | **Not met**: this plan is the proposal; your approval of it is the agreement (Human-gap log) |
| Estimate ≤ 8 | 3. No production code; most of the work is one long test and the script |

## What already exists (code; closed plans are history only)
**Epic 1** (`models/bpmn/Process_PPC_WorkPackAssignment.bpmn`; allow-lists in `application.yml` `bpa.tasks`). Every task is completed with `POST /api/v1/tasks/{key}/complete` `{"variables":{…}}` → 204.
1. `POST /api/v1/workpacks` (time-method-analyst, empty body) → `UserTask_CreateWorkPack`:
   - fields: `activityRef, drawingRef, departmentRef, completionDate, phases[], materials[]`;
   - the release check is Ready with DWG-118 (revision A, IFC) and MAT-PIPE-06 (TR-5531) (`ReleaseInputsRepository`).
2. `UserTask_ConfirmWorkPack` (the same variables back) → `Decision_WorkpackReleaseCheck`.
3. `UserTask_SignOffRelease` (project-engineer, `signOffDecision: Approved`).
4. `UserTask_ScheduleWorkPack` (production-planner, `plannedStartDate`/`plannedEndDate`):
   - **the planned start must be at least today + 7 days** (`ScheduleValidator`, `app.lead-time-days`).
5. `generate-workpack-print` sets AwaitingFile. There is no print task: `GET /api/v1/workpacks/{n}/print` reads the PDF.
6. `POST /api/v1/assignment-files` (production-team) → `validate-worker-assignment`, `create-hour-journal-lines`, `create-digital-timesheets` → `EndEvent_WorkPackAssigned`.
7. `publish-workpack-assigned` (`TimesheetProcessStarter`) starts `Process_PPC_TimesheetToProgress`, which waits at `UserTask_BookHours`.
8. A fresh database numbers the first Work Pack **WP-000001** (`workpack_number_seq`). The seed has no Work Pack rows.

**Epic 2, hours.**
- The lines API: `POST /api/v1/digital-timesheets/{id}/lines` `{workDate, phase, hours}`, `PUT /api/v1/digital-timesheet-lines/{id}`. The supervisor finds the time sheet ids with `GET /api/v1/digital-timesheets?workpackNumber=`.
- **Rule order** (`HourLineService.add`):
  1. Completed;
  2. Cancelled;
  3. **in approval → 409**;
  4. values;
  5. **future date → 400 "Work date cannot be in the future"**;
  6. month;
  7. week;
  8. phase;
  9. assigned;
  10. duplicate;
  11. budget and daily limit → **400 "Worker E1021 would exceed 16 hours on 17 Nov 2026"** (`HourLineChecks.dailyLimitMessage`; limit `app.daily-hours-limit: 16`).
- The first saved line moves the Work Pack from Released to In Progress.
- Send = complete Book hours with `{}`. Then:
  - `UserTask_ValidateHours` (timekeeper): `hoursDecision` Validated or Returned; `hoursReason` is required for Returned.
  - `UserTask_ApproveHoursProduction` (production-supervisor) and `UserTask_ApproveHoursPpc` (ppc-team): `hoursDecision` Approved.
  - After the PPC decision the instance is back at Book hours.

**Epic 2, the week.**
- `Process_PPC_WeeklyProgressPeriod`; the timer is `0 59 20 * * THU` UTC (Thursday 23:59 Riyadh). `OpenProgressPeriodWorker` opens the latest Thursday on or before *today*. From then on, lines in that week get "Week ending 19 Nov 2026 is closed".
- Steps:
  1. `UserTask_SendHoursToFms` (ppc-team, `fmsHandOffs`). The file holds every Approved line not yet exported; read it with `GET /api/v1/fms-hours-exports/{id}/file`.
  2. `UserTask_UploadCompletionReport` (ppc-team): `POST /api/v1/completion-reports` (multipart `file`, `taskKey`), then `completionReportId`. The columns are Workpack number, Completion %, Actual hours, Source (FMS).
  3. `UserTask_ApproveCompletion` (ppc-team, `completionApprovals`). The approvals must cover **every In Progress Work Pack in the database**. Values above 100 get **400 "Completion % must be between 0 and 100"** (`CompletionApprovalCheck`), and the column has `CHECK (approved_pct BETWEEN 0 AND 100)`.
  4. The progress rule (BPA-61). Its ABOVE_100 text "<WP> completion cannot exceed 100% (approved 120%)" (`ProgressMessages`) is reachable only by starting at `BusinessRuleTask_CheckProgressRule` with an injected `progressChecks` (`ProgressRuleApiTest`).
  5. Earned man-hours: Σ(pct×weight)×budget ÷ (Σweight×100). One Work Pack on A-2301 (budget 1200) at 40% gives **480.00** in `activity_progress.earned_manhours`.
  6. `SendVarianceAlerts` (mail), `UserTask_ReviewActualManhours` (cost-controller, `cbsReviews`), `UserTask_ApproveWeeklyProgress` (project-manager, `weekDecision: Approved`).
  7. `complete-workpacks` (only Work Packs at 100%) → `lock-progress-period` → `EndEvent_PeriodLocked`.

**Seed.**
- A-2301 (`R__seed_reference_data.sql:73-76`): "Synthetic end-to-end demo module M-04", PRJ-139 / JC-139 / WBS 11031, Piping, baseline 16 Nov–31 Dec 2026, budgeted units 1200.00, CBS-139-PIP-01 (shared with A-2201).
- E1021 (Welder, Fabrication) and E1099 (Fitter, Fabrication) (:222-223).
- `personas.yaml`: eleven personas, each username equal to its group; password DEMO_PASSWORD.

**Clock.**
- `ClockConfig` gives `Clock.systemUTC()`, with the business zone Asia/Riyadh (ADR 0013).
- Tests use `AssignmentFileApiTest.TestClock` (`set(Instant)`) via `@Import(TestClockConfig)`.
- **Live stack:** profile `qa` + `BPA_CLOCK_TODAY=yyyy-MM-dd` (BPA-81, `QaClock`: a whole-day offset, so time keeps moving; compose `BPA_PROFILES=local,qa`). BPA-82 made the portal follow the same date. Check it with `GET /api/v1/business-date`.

**Test helpers.**
- `TestBrowser` (session login per persona), `AssignmentFlow` (Epic 1 through the task API, but fixed to A-2201, Cutting 150 h, schedule 10–12 Nov), `CamundaSteps` (`awaitOpenTask`, `poll`, `cancelInstancesWaitingAt`), `AssignmentFileBuilder`, `CompletionReportBuilder`, `Mailpit`.
- `FmsHoursExportApiTest.download` (POI reader).
- `TimesheetProcessStartApiTest` is the one test that already runs Epic 1 into Epic 2, as far as Book hours.

**Demo files.**
- `assign-demo-good.xlsx` = WP-000001, E1021 40 h and E1099 60 h (built by `AssignmentFileBuilder.demoFiles()`). After a fresh database it fits the demo Work Pack exactly.
- `completion-demo-good.xlsx` = WP-000001 40% **and** WP-000002 100%. On a fresh database WP-000002 does not exist, so it shows a rejected row.

## Design
### 1. The automated run: harness
I'll use **the integration stack**: a per-class Testcontainers Postgres, the shared `IntegrationCamunda` 8.10.1, `IntegrationApp.background()`, real job workers, Mailpit, `@DirtiesContext(AFTER_CLASS)`. This is the same as `WeekLockApiTest` and `TimesheetProcessStartApiTest`.

Camunda Process Test is ruled out:
- it has no database, and every step here writes and reads `bpa` rows (Work Pack, lines, FMS file, Earned MH, period);
- it has no task API, so the story's "as each persona, through the same API the portal uses" (security groups, allow-lists, `completedBy`, segregation) would not be tested;
- `ProcessTestApplication` must leave database workers out (backend/CLAUDE.md).

### 2. The test class `EndToEndRunApiTest` and helper `DemoRun` (test sources only)
`DemoRun` drives each step as its persona through `TestBrowser` and the task API, reusing `CamundaSteps`, `AssignmentFileBuilder` and `CompletionReportBuilder`. It has one method per Gherkin step, so the three tests read like the scenarios. `AssignmentFlow` stays unchanged: its Create data and dates are fixed.

**Reset before each test** (`DemoRun.reset()`): the three tests each run the whole week ending 19 Nov, so each starts clean.
- It deletes the POC-mastered rows (03 "POC-mastered data": workpack … audit_log, and the Epic 2 tables, in foreign-key order) and leaves the seed alone.
- It cancels instances of this class still waiting in the three processes (the `cancelInstancesWaitingAt` pattern), so a buffered or correlated message cannot reach another class's WP-000001.

**The data.** Jira gives the activity, phases, workers and dates. The rest is **ASSUMPTION** demo data, taken from the seed so the release check is Ready:

| Item | Value | Source |
|---|---|---|
| Activity, phases | A-2301; Cutting 300 h, Welding 200 h | Jira |
| Drawing, material, department | DWG-118; MAT-PIPE-06 (phase Cutting); Fabrication | seed, as `AssignmentFlow.draft` (ASSUMPTION) |
| Completion date | 2026-12-31 (A-2301 baseline end) | ASSUMPTION |
| Schedule | 23 Nov – 18 Dec 2026 (today 16 Nov + 7-day lead time) | ASSUMPTION |
| Assignment file | WP number of the run, E1021 40 h, E1099 60 h (as `assign-demo-good.xlsx`) | ASSUMPTION |
| Hours | E1021 16 Nov Cutting 8 h; E1099 16 Nov Cutting 7.5 h | Jira |
| Completion report | the WP 40 %, actual hours 15.5, Source FMS | 40 % Jira; hours ASSUMPTION |
| Approvals | approved 40 % (same as reported, so no remark); CBS review remark "Reviewed for the demo" if the task asks for one | ASSUMPTION |
| PM | `weekDecision: Approved` | Jira |

**Clock** (`TestClock`, ADR 0013):
- 16 Nov 09:00 Riyadh (`2026-11-16T06:00:00Z`) from the start through the PPC hours approval;
- then 19 Nov 23:59 Riyadh (`2026-11-19T20:59:00Z`).

The cut-off is fired directly: the weekly instance is started at `ServiceTask_OpenProgressPeriod` (`/v2/process-instances` with `startInstructions`, as `WeekLockApiTest.startAt` does). The test does not wait for the timer.

### 3. The three tests
**`BPA68_workPackRunsFromCreationToLockedProgress`**, step by step, each as its persona:
1. **Epic 1:** create, confirm, sign-off, schedule; read the print (GET, the "printed" step); upload the assignment file; Book hours is open.
2. **Hours:** two lines and send; then Validated, Approved, Approved.
3. **Cut-off:** clock to 19 Nov 23:59; cut-off.
4. **FMS:** the FMS hand-off "Sent to FMS".
5. **Progress:** upload the report; approve 40 %; the rule step; Earned MH.
6. **Close the week:** the CBS review; the PM approves.

Asserts the exact end state:
- the FMS file for the week has **2 rows for the demo Work Pack** (rows read with the `download` reader and filtered on Badge No and Activity A-2301, decision (a); other rows are ignored);
- `GET …/progress-periods/2026-11-19/earned-manhours` gives A-2301 **480.00**;
- the period is **Locked**;
- the Work Pack is **In Progress**, and its time sheet instance is active and waiting at `UserTask_BookHours`.

**`BPA68_returnedHoursRejoinTheRun`:** the same run up to the send. Then:
- the Timekeeper returns the hours with `hoursReason` "Check E1099";
- the instance is back at Book hours and both lines are Returned;
- the supervisor PUTs E1099's line (**ASSUMPTION: 7.5 h → 7 h**, remark "Corrected after Timekeeper check"; Jira says only "corrects") and sends again.

The rest of the run is as in test 1, with the same four end-state asserts.

**`BPA68_rulesAreShownLive`:** the same run up to booking. Then:
- **Daily limit:** the supervisor books E1021 on the demo Work Pack, 17 Nov, Cutting, 17 h → **400 "Worker E1021 would exceed 16 hours on 17 Nov 2026"**, and no line is saved. The clock must be at least 17 Nov for this (Q5).
- **120 %:** the run goes on to `UserTask_ApproveCompletion`, where ppc-team sends `approvedPct` 120. The expected result depends on **Q1**. As recommended: 400 "Completion % must be between 0 and 100", the task still open, `approved_pct` unchanged.

### 4. The demo script `docs/showcase/epic1-epic2-demo.md`
- **Ahead of the demo** (checklist):
  - the stack: `.env` with `BPA_PROFILES=local,qa` and `BPA_CLOCK_TODAY=2026-11-19` (Q2);
  - `docker compose down -v && docker compose up -d` the same day (Q3);
  - `GET /api/v1/business-date` shows 2026-11-19;
  - the seed is loaded (A-2301, E1021, E1099 present; no Work Packs);
  - Operate shows no weekly instance (Q4);
  - Mailpit is open at :8025;
  - one browser profile per persona, or log out between steps;
  - the curl command that starts the cut-off is ready;
  - `assign-demo-good.xlsx` and the completion demo file (Q7) are at hand;
  - the dry run has been done.
- **Steps (a table):** for each step, the persona and login, the screen (task name), the exact data, the expected screen or result, and the BPMN element shown in Operate.
  - The order follows test 1.
  - The two live rules come after the hours are booked: the 17 h line on Book hours, then 120 % on Approve completion.
  - The script also shows the returned-hours loop.
  - With the clock at 19 Nov, the dates in the script are those of test 1: Epic 1 lead time 26 Nov onwards; hours on 16 Nov; the cut-off started by hand for week ending 19 Nov.
- **What the audience sees at the end:**
  - the FMS file download with 2 rows;
  - the Earned MH 480.00 on the PM screen;
  - the period Locked;
  - the Work Pack In Progress, back at Book hours.
- **Not shown** (Q6).
- **What is behind it:** one line per epic, with the test name that proves it.

### 5. Demo data files
- The assignment: `templates/demo/assign-demo-good.xlsx` as it is. On a freshly reset stack the demo Work Pack is WP-000001, which this file names. **No new file.**
- The completion report: **one new file (Q7)**, `templates/demo/completion-demo-run.xlsx`: WP-000001, 40, 15.5, FMS. It is generated like the others (`CompletionReportBuilder` demo list) and listed in `templates/README.md`. The alternative is to use `completion-demo-good.xlsx` and accept that its WP-000002 row is rejected on screen.
- Tests never use demo files (S18): the test builds its own with the builders.

## Files that change
| Area | Files |
|---|---|
| Production code, models, contract | **none** |
| Tests | new `backend/src/test/java/com/lcs/star/bpa/EndToEndRunApiTest.java`, new `DemoRun.java` (helper); `FmsHoursExportApiTest.download` made package-visible if it is private (no behaviour change) |
| Demo | `docs/showcase/epic1-epic2-demo.md` (new); `templates/demo/completion-demo-run.xlsx` and `templates/README.md` row (Q7); the demo-file generator list |
| Docs | plan; REVIEW.md; `intent/README.md` regenerated |

## Order of work
1. Approve the plan and commit it (`Approved`).
2. Write the three tests and `DemoRun`, then run them. As BPA-68 adds no production code, **all three are expected green at the first run**. The red-first rule cannot show a failure here, and the plan and the PR will say so plainly. Any red that does appear is a real gap: it is reported and goes in Questions, and is not fixed here without your yes (unrelated work is a new Jira item).
3. The demo file (Q7) and the script.
4. Full `./mvnw -B verify`; the local dry run following the script, with notes; `qa-reviewer`; fixes; REVIEW.md; plan Closed. Push, PR and Jira comments only when you ask.

## Scenario-to-test table
One class, **`EndToEndRunApiTest`** (integration stack, real workers, Mailpit, `TestClock`, `DemoRun.reset()` before each test).

| # | Scenario | Test | Clock | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | A Work Pack runs from creation to locked progress | `BPA68_workPackRunsFromCreationToLockedProgress` | 16 Nov 09:00 → 19 Nov 23:59 | FMS file: 2 rows for the demo WP; A-2301 Earned MH 480.00; period Locked; WP In Progress, time sheet instance at `UserTask_BookHours` | green (built by BPA-6…67) |
| 2 | Returned hours rejoin the run | `BPA68_returnedHoursRejoinTheRun` | as 1 | after "Check E1099": back at Book hours, lines Returned; after the correction and send: the same four end-state asserts | green |
| 3 | The rules are shown live | `BPA68_rulesAreShownLive` | 16 Nov 09:00, **17 Nov 09:00 for the 17 h line (Q5)**, 19 Nov 23:59 | 400 "Worker E1021 would exceed 16 hours on 17 Nov 2026", no line saved; 120 %: per Q1 (recommended 400 "Completion % must be between 0 and 100", task open) | green once Q1 and Q5 are agreed; **red as Jira reads** (the future-date text, then the BPA-60 400 instead of the send-back) |

`grep -c "void BPA68_"` = 3.

## Proof (one per Definition of Done line)
| DoD line | Evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt` |
| 3 tests passing in CI | `./mvnw -B -Dtest=EndToEndRunApiTest verify` locally, full `./mvnw -B verify`, and `gh pr checks <n>` (Actions may not start: billing, so CI is a Human gap if they don't) |
| Script reviewed by Khitaba; dry run done | dry run by me on the local stack following the script word for word, with notes in the plan (time per step, what differed); Khitaba's review is a Human gap |
| Process runs end to end locally | the dry run: Operate shows the assignment instance ended at `EndEvent_WorkPackAssigned`, the weekly one at `EndEvent_PeriodLocked`, the time sheet one waiting at Book hours |
| BPMN and DMN match deployed | the deployed three processes and four Epic 2 decisions equal `models/` (whitespace-normalised hash, as BPA-67) |
| Open questions resolved or logged | the Jira comments (shown first, posted on your yes) |
| Plan Closed | `grep '^Status: Closed'` |
| Demoed at sprint review | Human gap |

## Risks
- **Run time:** three full runs, each perhaps 2–4 minutes on the shared Camunda. This adds to a suite that already takes about 40 minutes in CI.
- **Shared Camunda:** each class's fresh database starts at WP-000001, so messages correlated by Work Pack number (`AssignmentFileReceived`, `WorkPackCompleted`) could meet another class's instance. The mitigation is the cancel-before-each-test step, plus looking up instances by key, not by number.
- **"Approvals must cover every In Progress Work Pack":** this is safe on a fresh database. On a used stack the PPC would have to approve every old Work Pack too (Q3).
- **Variance alert mail:** CBS-139-PIP-01 has seeded actual cost and forecast, so alerts may be sent. They go to Mailpit and are harmless (they can be shown in the demo).
- **cost-controller is scoped to PRJ-140** in `user_project`, but A-2301's CBS is PRJ-139. BPA-67's local proof completed this review for a PRJ-139 CBS, so the task is by group. I'll check this in the first run.

## Questions
1. **The 120 % rule.**
   - BPA-60 refuses values above 100 at Approve completion (400 "Completion % must be between 0 and 100"), and the database CHECK refuses them too. So BPA-61's "<WP> completion cannot exceed 100% (approved 120%)" can never come from the screen. In the built system it is a backstop that no user path reaches.
   - Options:
     - **(a)** show BPA-60's 400 live; scenario 3's last line and the script say "is refused with 'Completion % must be between 0 and 100'", via a Jira comment asking Khitaba to amend the Gherkin (I never edit Acceptance Criteria).
     - **(b)** show a value the rule does send back through the screen. That needs a real Blocked case, and the only other Blocked reason I know of is not in the story, so it would be a new rule.
     - **(c)** inject `progressChecks` in the test only. This is not what the portal does and cannot be shown live.
   - **Recommended: (a).** It is the real behaviour; the BPA-61 text stays proven by `ProgressRuleApiTest`.
2. **The clock on the live stack** (spec A7).
   - **Recommended:** use what BPA-81 and BPA-82 already built. Profile `qa` + `BPA_CLOCK_TODAY=2026-11-19` for the whole demo (no restart mid-demo, so logins survive), and **start the cut-off by hand** with one documented curl to Camunda REST as demo/demo (`startInstructions: ServiceTask_OpenProgressPeriod`).
   - With today at 19 Nov:
     - hours dated 16 and 17 Nov are in the past, so the 17 h rule shows the daily-limit text;
     - no period exists until the cut-off starts;
     - the cut-off opens week ending 19 Nov;
     - Epic 1's lead time moves the schedule to 26 Nov onwards.
   - No production code changes. The rejected alternatives are a new dev clock endpoint (new code), a pinned-time clock (new code), or shifting the demo to future dates (the dates would differ from Jira's).
3. **Stale data.**
   - **Recommended:** the checklist starts with `docker compose down -v && docker compose up -d` on the demo machine the same day. That gives a fresh seed: no Work Packs (so the demo Work Pack is WP-000001), no periods (so BPA-60's "must be locked first" cannot bite), and empty Camunda. No new script is needed.
   - **Not safe on the QA server:** `down -v` wipes the testers' data. There, old CutOff periods need the BPA-67 one-off SQL, and every In Progress Work Pack would have to be approved in the demo week.
   - **Recommended:** run the showcase on a local or dedicated stack, never on QA.
4. **Real-time jobs during the demo.**
   - **The weekly timer** fires on the Camunda container's real clock, Thursday 23:59 Riyadh. It does not fire during a daytime demo. If the stack stays up over a Thursday night, it opens a period for the business date's latest Thursday, and the hand-started cut-off would then fail with "already has a progress period". Hence the reset the same day and the "no weekly instance in Operate" check.
   - **`MonthLockJob`** (00:10 Riyadh, business clock, lock day 1) only locks October when today is 19 Nov, so it never touches November lines. **`DailyCloseCostCheckJob`** (06:00) only sends mail.
   - Neither fires in working hours.
5. **Scenario 3's 17 Nov line at a 16 Nov clock.** The Background puts today at 16 Nov 09:00, but a 17 Nov line is then refused as "Work date cannot be in the future" (the rule order checks the future date before the daily limit).
   - **Recommended:** the test moves the clock to 17 Nov 09:00 for that step (ASSUMPTION), with a Jira comment for Khitaba. The demo is not affected (today is 19 Nov).
   - Alternative: book the 17 h on 16 Nov. The message would then read "16 Nov 2026", not Jira's text.
6. **"Not shown" list.** intent.md has **no "Not shown" list**, so the script cannot match it word for word. It has "What it does not show: payroll and overtime, billing (Epic 3), live integrations" (L40) and the "Out of scope" paragraph (L89-91), with different wording from Jira (d). For example, Jira's "HSE eligibility" is "Worker eligibility from HSE TFC and medical fitness" there, and Jira's "billing" is "progress billing and the Invoice Certificate (Epic 3, BPA-5)" there.
   - **Recommended:** the script uses **Jira (d)'s eight items word for word**: payroll and overtime, non-production timesheets, billing, live integrations, leave, HSE eligibility, Level 3 cost allocation, reopening a locked week. Each gets its intent.md source (L40 or L89-91). The mismatch goes in the Jira comment, so Khitaba can add a "Not shown" line to intent.md when it is accepted.
   - Alternative: copy the intent.md wording.
7. **The completion report demo file.** `completion-demo-good.xlsx` also names WP-000002, which on a fresh stack is rejected on screen.
   - **Recommended:** one new `completion-demo-run.xlsx` (WP-000001, 40 %, 15.5 h, FMS), generated with the other demo files.
   - Alternative: no new file, and the script explains the rejected row.
8. **ASSUMPTION demo data**, listed in Design §2: DWG-118, MAT-PIPE-06, Fabrication, completion date 31 Dec, schedule 23 Nov–18 Dec (test) or 26 Nov–18 Dec (demo), 40 h and 60 h estimates, actual hours 15.5, the E1099 correction 7.5 → 7 h, the CBS review remark. Confirm or replace.
9. **Jira:** BPA-68 is Ready. I'll ask before moving it to In Progress and before any other transition.

## Resolutions (Ahsan, 22 Nov 2026, in plan mode)
- **Q1:** (a) scenario 3 and the script show BPA-60's 400 "Completion % must be between 0 and 100", with the task still open. A Jira comment asks Khitaba to amend the Gherkin line.
- **Q2, Q3:**
  - profile `qa` + `BPA_CLOCK_TODAY=2026-11-19` for the whole demo;
  - `docker compose down -v && up -d` the same day;
  - the cut-off is started by one documented curl at `ServiceTask_OpenProgressPeriod`;
  - local or dedicated stack only, never QA;
  - no new script, no new code.
- **Q5:** the test moves the clock to 17 Nov 09:00 for the 17 h line (ASSUMPTION), asserts Jira's exact text, and a Jira comment goes to Khitaba.
- **Q6, Q7:**
  - "Not shown" uses Jira (d)'s eight items word for word, each citing its intent.md line, and the mismatch goes in the Jira comment;
  - new `templates/demo/completion-demo-run.xlsx` (WP-000001, 40 %, 15.5 h, FMS).
- **Q4, Q8, Q9:** as written. The Q8 demo data stays ASSUMPTION and goes in the Jira comment. I ask before any Jira transition.

## Human-gap log
- **DoR, story-specific:** "Showcase script and demo data agreed with the build team". The demo data and method are in this plan. Ahsan's approval is the build team's agreement; Khitaba sees them in the PR.
- **DoR:** intent.md and spec.md not accepted by the PO (as for every Epic 2 story).
- **DoD:** "Demo script reviewed by Khitaba; dry run done". I do the dry run and record it; Khitaba's review is hers.
- **DoD:** "Demoed at sprint review". People.
- **DoD:** "passing in CI". GitHub Actions may not start (billing); the local run is the evidence until it does.
- **Jira:** the Gherkin changes for Q1 and Q5 and the "Not shown" mismatch (Q6) are Khitaba's to make. I propose them in a comment, shown to you first.
