# Plan: BPA-73 PPC Team can open a Work Pack and see its status, workers, hours and progress in one place
Jira: BPA-73 (Story, epic BPA-4)
Story last updated: 2026-11-23T13:51:11.506+0400
Status: Closed (23 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-73-workpack-detail, from main 8fc497d

> Written in plan mode and approved by Ahsan on 23 Nov 2026; committed as the branch's first commit.
> Checked read-only: main 8fc497d is the merge of PR #63 (BPA-72, the Work Packs list). `BPA-73-workpack-detail` is at 8fc497d with no commits of its own.

## Context
BPA-72 lists the Work Packs, and a row opens `/workpacks/{workpackNumber}`, which today is a placeholder. BPA-73 replaces it with a **read-only case view**:
- a summary panel and a lifecycle bar;
- five tabs: Overview, Workers, Hours, Progress, History;
- a side panel of the Work Pack's **open tasks from every group**. A task opens only for its own group; the others are listed but disabled.

Every logged-in group may read the page. Nothing on it changes anything. Actions stay on each group's task screens.

## Readiness check (Definition of Ready, docs/02, against Jira 23 Nov 13:51)
| Check | State |
|---|---|
| Jira status | **In Progress** (moved by Ahsan on 23 Nov 13:51; no content field changed since 17 Nov). Any other transition only when you say so (Q7) |
| Template, persona, outcome | Met (PPC Team and any persona, read-only) |
| Gherkin incl. unhappy paths | Met: 7 scenarios (Draft with nothing yet; unknown Work Pack; a task of another group) |
| BPMN element IDs | UI only; the side panel reads the open user tasks of the existing processes |
| Every field has an SOR | Met (SOR Notes: the 14 tables, no new table) |
| No blocking question | Q1 (the cross-group task read) and Q2 (the API shape) change what is built |
| Jira DoR: intent.md and spec.md accepted | **Not met** (as every Epic 2 story) |
| Jira DoR: "GET /api/v1/workpacks/{number} contract" | **Not met yet**: written first in this branch (contract-first) |
| Estimate ≤ 8 | 5. The page is large (five tabs and a side panel), and the task read is new |

## What already exists (code; closed plans are history only)
**BPA-72** (closed plan; the same answers are used here):
- **Q1:** unscoped until BPA-79 adds the per-user project restriction.
- **Q3:** "Earned MH (activity)", the activity's Earned MH, since Earned MH exists only per activity (BPA-62).
- **Q5:** the approved % is the **latest Locked week** in which the Work Pack has an approved value.
- **Q2:** `/workpacks/:workpackNumber` is the placeholder this story replaces.
- `WorkPackListRepository` gives the summary row: planned dates, project through activity and WBS, `approvedPct`, `approvedWeekEnding`, `earnedManhours`.

**Data** (`docs/data-model.md`, V1/V2):
- **Work Pack:** `workpack` (status, assignment_state, process_instance_key); `workpack_phase` (phase, manhours); `workpack_material` (material_ref, phase); `workpack_schedule` (planned dates, scheduled_by); `workpack_print` (generated_at).
- **Workers:** `worker_assignment` (estimated_hours, `flag` = `HoursLoadedNotRemoved` or NULL, BPA-13); `hour_journal_line` (role, designation, estimated_hours, status Open/Closed); `digital_timesheet` (cbs_code_ref, submission_status).
- **Hours:**
  - `digital_timesheet_line.status`: Draft, Submitted, Approved, Returned, Rejected;
  - the approval step is `hours_submission.stage` (Timekeeper, ProductionSupervisor, Ppc, Closed);
  - `hours_decision` (step, decision, reason, decided_by/at).
- **Progress:** `workpack_progress` (per week: reported, approved, rule_result, override_remark); `progress_period` (week_ending, CutOff/Locked); `progress_retraction` (requested %, reason, decision); `activity_progress` (Earned MH per activity and week).
- **History sources:**
  - `audit_log` (table_name, record_id, column_name, old/new, username, changed_at) has **no workpack_id**, so the history must collect the record ids of the Work Pack and its children;
  - `notification` (workpack_id, drawing_ref, revision, recipient, channel, sent_at).

**Reads to reuse** (`backend/.../persistence`):
- `WorkPackRepository.findByNumber` / `lines`;
- `WorkPackPrintRepository.exists`;
- `WorkerReplacementRepository.assignments(workpackId)` (badge, hours, flag);
- `HourJournalLineRepository.findByWorkpackNumber`, `DigitalTimesheetRepository.findByWorkpackNumber`;
- `ProgressRetractionRepository`, `ActivityProgressRepository`.

Missing reads:
- any read of `audit_log`;
- the hours of a Work Pack with their step;
- all weeks of a Work Pack's progress.

Existing endpoints are group-limited (time sheets: supervisor; submissions: three groups), so the detail endpoint reads the repositories directly.

**Tasks:**
- `GET /api/v1/tasks` lists only the user's own groups' tasks (`TaskService.list`: a search per group, then one variables call per task for `workpackNumber`).
- `GET /api/v1/tasks/{key}` already answers **403** "You are not allowed to work on this task" to a user outside the task's candidate groups.
- All Camunda calls run as **bpa-service**, which has `READ_USER_TASK` on the five processes (`PersonaSync` :37-66). A user-task search **without a candidate-group filter** is not proven yet (backend/CLAUDE.md: "only calls BPA-25 proved or a later story checked").

**How tasks relate to a Work Pack:**
- Epic 1: `workpack.process_instance_key`, with Create, Confirm, Sign off and Schedule.
- The time sheet process: one per Work Pack, carrying the variable `workpackNumber`, with Book hours, Validate, Approve (Production) and Approve (PPC). Its instance key is **not stored** until the first send.
- The weekly process: one per **week**, through `progress_period.process_instance_key`. Its tasks (Approve completion, Approve retraction, Approve weekly progress, ...) concern every Work Pack of the week (Q1).

**Frontend:**
- Tasks open **only in place** from the Home task list (`TaskList` local state); there is no deep link.
- `screenFor(elementId)` (taskScreens.ts) maps elements to screens.
- No tab component exists; `.context-panel dl` (global.css) fits a summary.
- `SubmissionLines` is read-only but shows no status.
- `useAuth().me.groups`.

## Design
### 1. API, contract-first (Q2): one detail read plus two side reads
All three are read only, open to any logged-in user (no project scope until BPA-79, as BPA-72), and answer **404 "Work Pack not found"** (the existing `WorkPackNotFound` response) or 400 for a malformed number.

1. **`GET /api/v1/workpacks/{workpackNumber}` → `WorkPackDetail`**, everything the summary and four tabs show, from the POC database only:
   - **`summary`:** number, status, assignment state, activity, drawing, department, project, completion date, planned start and end, the latest Locked `approvedPct` and its week, `earnedManhours` (activity), as BPA-72.
   - **`overview`:**
     - `phases[{phase, manhours}]`;
     - `materials[{materialRef, phase}]`;
     - `schedule{plannedStartDate, plannedEndDate, scheduledBy}` or absent;
     - `print{generatedAt}` or absent.
   - **`workers[]`:** one per assigned Worker (badge, name, role, designation; `estimatedHours`; `hoursLoadedNotRemoved` true from `flag`; the Hour Journal line's estimated hours and status; the time sheet's CBS and status).
   - **`hours`:**
     - `lines[]`: badge, work date, phase, hours, status, `step` = the stage of the line's submission while it waits (Timekeeper, Production Supervisor, PPC), entry source;
     - `totalHours` = the sum of every line that is **not Rejected** (Jira).
   - **`progress[]`:** one per week the Work Pack has a `workpack_progress` row, newest first:
     - week ending, period status (CutOff or Locked), reported %, approved %, rule result;
     - `earnedManhours` of the Work Pack's **activity** that week (BPA-72 Q3);
     - `retractions[{requestedPct, reason, decision, decidedBy, decidedAt}]`.
2. **`GET /api/v1/workpacks/{workpackNumber}/history?page=&size=` → `WorkPackHistoryPage {items, total}`**, newest first, default size 50 (Q4). Items:
   - `audit_log` rows of the Work Pack and its children;
   - the drawing-revision `notification` rows (as items of kind "Notice").

   A separate read, because it can be long and it pages.
3. **`GET /api/v1/workpacks/{workpackNumber}/open-tasks` → `OpenTask[] {key, name, elementId, candidateGroups, creationDate, canOpen}`**. `canOpen` = the task's groups share one with the user's. **No task variables or data.** It is separate because it calls Camunda: if Camunda is down only the side panel says so (503), and the page still shows.

### 2. The cross-group task read (Q1)
`WorkPackOpenTaskService`, as **bpa-service** (the existing `CamundaTaskClient` identity):
1. Find the process instances of the Work Pack:
   - (a) `workpack.process_instance_key` (Epic 1);
   - (b) the time sheet instance: `POST /v2/user-tasks/search` with `{processDefinitionId: Process_PPC_TimesheetToProgress, state: CREATED}` and **no candidate group**, then keep the tasks whose `workpackNumber` (the existing `variables(task)` call, as `TaskService.list` does) is this Work Pack's;
   - (c) **weekly tasks, per Q1:** the open tasks of the weekly instance of any period in which the Work Pack has a `workpack_progress` row and that period is not Locked.
2. Search the open user tasks of the instances from (a) and (c) with `{processInstanceKey, state: CREATED}`, no candidate group.
3. Map each to `OpenTask`, with `canOpen` from the user's groups. Only allow-listed elements are listed, as `TaskService` does.

- **Permissions:** `READ_USER_TASK` on the process definitions, which bpa-service already has. **No new permission** is expected. The plan proves it with a probe test (`WorkPackOpenTasksApiTest.searchWithoutAGroupIsAllowedForTheServiceUser`), as BPA-25 did. If Camunda answers 403, the fallback is to search per group (the five groups that own user tasks) and merge, which uses only proven calls. That fallback is in the Risks.
- **Opening a task:** an enabled item navigates to `/` with `state: {taskKey}`. `TaskList` opens that task when it is in the user's list, which is a small change to `TaskList` (`useLocation`). A disabled item is a non-interactive line with "(for <group>)". The backend's 403 on `GET /api/v1/tasks/{key}` stays the real check.

### 3. The page (replaces the placeholder)
`pages/WorkPackDetailPage` (route `workpacks/:workpackNumber`) composes `features/workpacks/components/WorkPackDetail/`:
- **Summary panel** (`.context-panel dl`): the summary fields.
- **Lifecycle bar:** an ordered list of the four statuses. The current one has `aria-current="step"`, earlier ones carry "done" (class and visually hidden text), later ones "not reached".
- **Tabs** (`role="tablist"`, one tab per section, `aria-selected`, plain buttons, the first selected):
  - **Overview:** phases and man-hours table (with the total), materials, schedule ("Not scheduled yet" when absent, ASSUMPTION), print ("Printed <date>"; no PDF button, so the page stays action-free, ASSUMPTION).
  - **Workers:** one row per Worker: badge, name, estimated hours, Hour Journal status, time sheet status, and **"Hours loaded; not removed"** when flagged (Jira text).
  - **Hours:** every line with its status and step; **"Total: 27.50 h"** (not Rejected). Draft Work Pack: **"No hours booked yet"** (ASSUMPTION, Q3).
  - **Progress:** one row per week: week ending, status, reported %, approved %, Earned MH (activity), retractions. Draft: **"No progress recorded yet"** (ASSUMPTION, Q3).
  - **History:** the first page of the history read, with "Show more" (paging). It reads, it does not change anything: a button that loads more is not an action on the Work Pack.
- **Side panel "Open tasks":**
  - a list of name, group and age ("open 2 days");
  - enabled items are buttons "Open <task>";
  - disabled ones are plain text with "for <group>";
  - "No open tasks" when the list is empty.
- **No create, edit or delete** anywhere.
- An unknown number shows **"Work Pack not found"**, with the "Back to the Work Packs list" link.

Code layout:
- `features/workpacks/services/workpackService.ts`: `getWorkPack`, `getWorkPackHistory`, `listWorkPackOpenTasks`;
- hooks `useWorkPack`, `useWorkPackHistory`, `useWorkPackOpenTasks`;
- types in `src/types/api.ts`;
- `TaskList` reads `location.state.taskKey`.

`WorkPackDetailPlaceholder` is deleted.

### 4. Docs
- OpenAPI (the three operations and their schemas, `x-assumption` on the texts and the history scope).
- No data model change.
- `PersonaSync` only if the probe shows a permission is missing.

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml` (3 operations, `WorkPackDetail`, `WorkPackHistoryPage`, `OpenTask`) |
| Backend | `api/WorkPackController` (3 methods) or a new `WorkPackDetailController`; new `persistence/WorkPackDetailRepository` (summary reuse, phases, materials, workers, hours with step, progress with retractions), `persistence/WorkPackHistoryRepository`; new `task/WorkPackOpenTaskService`; `adapter/CamundaTaskClient` (a search by process instance key or by process without a group); `SecurityConfig` (explicit authenticated GET rules) |
| Frontend | new `pages/WorkPackDetailPage/`, `features/workpacks/components/WorkPackDetail/` (summary, lifecycle bar, tabs, side panel), hooks, services, `types/api.ts`, `routes/AppRoutes.tsx`, `features/tasks/components/TaskList/TaskList.tsx` (open a task from navigation state), CSS; delete `pages/WorkPackDetailPlaceholder/` |
| Tests | new `WorkPackDetailApiTest` (and the probe), new `WorkPackDetailPage.test.tsx`; `WorkPacksPage.test.tsx` scenario 5 now checks the real page heading (said in the PR) |
| Docs | plan; REVIEW.md; `intent/README.md` |

## Order of work
1. Approve the plan and commit it (`Approved`).
2. Write the contract and run `gen:api`. Add compile stubs: the three controller methods answer 501, and an empty page.
3. Write the tests, backend (with the probe) and React, and run them **red before any code**. Commit.
4. Build the backend, then the task read, then the frontend, then green. Run the neighbours: `TaskApiTest`, `WorkPackListApiTest`, `WorkerReplacementApiTest` and the React task tests.
5. Full `./mvnw -B verify`, `npm test`, lint, format and build; the local check; `qa-reviewer`; fixes; REVIEW.md; plan Closed. Push, PR and Jira comments only when you ask.

## Scenario-to-test table
**Backend `WorkPackDetailApiTest`** (integration stack, per-class Postgres; Camunda for scenario 7). The test builds its data with SQL:
- WP-000457 In Progress with Cutting 150 and Welding 300 (Gherkin), schedule, E1021 and E1099 assigned, E1099 flagged `HoursLoadedNotRemoved`;
- lines of 27.50 h Approved and 4.00 h Rejected;
- week 19 Nov Locked with approved 35.00 and an `activity_progress` row;
- WP-000490 Draft with no hours or progress.

**React `WorkPackDetailPage.test.tsx`** (fakeBackend, `<App />` at `/workpacks/WP-000457`).

| # | Scenario | Test (Java and React, same name) | Asserts | Expected first run |
|---|---|---|---|---|
| 1 | Overview shows the Work Pack and its status | `BPA73_overviewShowsStatusAndPhases` | Java: status In Progress; phases Cutting 150 and Welding 300; planned dates. React: lifecycle bar has In Progress `aria-current="step"`, Draft and Released "done"; Overview shows both phases and the planned dates | red (501 / placeholder) |
| 2 | Workers tab shows a kept Worker with booked hours | `BPA73_workersTabShowsKeptWorker` | Java: E1099 `hoursLoadedNotRemoved` true. React: Workers tab, E1099 row shows "Hours loaded; not removed" | red |
| 3 | Hours tab shows Rejected lines but does not count them | `BPA73_hoursTabExcludesRejectedFromTotal` | Java: all lines with status; `totalHours` 27.50. React: Hours tab lists every line with its status; "Total: 27.50 h" | red |
| 4 | Progress tab shows a locked week | `BPA73_progressTabShowsLockedWeek` | Java: week 2026-11-19, Locked, 35.00, Earned MH (activity) value. React: the row shows Locked, 35.00 and the Earned MH | red |
| 5 | Draft Work Pack has no hours or progress | `BPA73_draftWorkPackHasNothingYet` | Java: WP-000490 → `hours.lines` empty, `progress` empty. React: Hours "No hours booked yet", Progress "No progress recorded yet" (Q3) | red |
| 6 | Unknown Work Pack | `BPA73_unknownWorkPack` | Java: 404 "Work Pack not found" on all three reads. React: the screen shows "Work Pack not found" | red |
| 7 | Open task is offered only to its own group | `BPA73_openTaskOnlyForOwnGroup` | Java (real Camunda): WP-000457's time sheet instance started at `UserTask_ApproveHoursProduction`; as timekeeper, `/open-tasks` lists it with `canOpen` false, and `GET /api/v1/tasks/{key}` as timekeeper is 403. React: as timekeeper the task is listed and not a button; as production-supervisor it is a button "Open …" | red |

**Extras:**
- the probe `searchWithoutAGroupIsAllowedForTheServiceUser`;
- History: paging (size 2, total, newest first), includes child rows and notices;
- the step of a Submitted line;
- Epic 1 and weekly tasks in the side panel (Q1);
- read by all eleven personas (200);
- 401 when not logged in;
- the side panel is 503 while the page still loads (React);
- a task opened from the side panel opens on Home (React, `TaskList`).

## Proof (one per Definition of Done line)
| DoD line | Evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=WorkPackDetailApiTest verify`; `npm test -- WorkPackDetailPage`; full `./mvnw -B verify`, `npm test`; `gh pr checks <n>` (Actions may not start) |
| Process runs end to end locally | rebuild both images; as ppc-team open the BPA-68 demo Work Pack (WP-000001) from the list and walk the five tabs (Overview phases Cutting 300 and Welding 200, Workers E1021 and E1099, Hours, Progress week 19 Nov Locked 40.00 and 480.00, History); open as supervisor and see Book hours as "Open"; as timekeeper see it disabled |
| BPMN and DMN match deployed | no model change (n/a) |
| Open questions resolved or logged | the Jira comments (shown first, posted on your yes) |
| Plan Closed | `grep '^Status: Closed'` |
| Demoed at sprint review | Human gap |

## Risks
- **The cross-group search is unproven.** If bpa-service gets 403, fall back to one search per task-owning group (proven calls).
- **The time sheet instance key is not stored:** finding it costs one search of the process's open tasks plus one variables call per task. That is fine for the POC's few tasks, and the BPA-26 list does the same.
- **A page of reads:** one detail query set and two side reads; no paging except the history.
- **The first tabs and the first deep-ish task opening** (navigation state into `TaskList`).
- **Unscoped until BPA-79.**

## Questions
1. **The cross-group task read.** As in Design 2:
   - a backend service as **bpa-service** searches the open user tasks of the Work Pack's Epic 1 instance (by key) and of its time sheet instance (by the `workpackNumber` variable);
   - it returns `{key, name, elementId, candidateGroups, creationDate, canOpen}` with **no task data**;
   - no new permission (`READ_USER_TASK` already granted; proven by a probe test, with a per-group fallback if Camunda refuses).

   Open sub-question, **weekly tasks:** they concern every Work Pack of the week. Recommended: list the open weekly tasks of a **not-Locked week in which the Work Pack has a progress row** (ASSUMPTION). Alternative: Work Pack processes only.
2. **One endpoint or one per tab.**
   - **Recommended:** one detail read (summary and four tabs, database only), plus **history** (paged) and **open tasks** (Camunda; can fail alone) as separate reads.
   - Alternatives: one endpoint for everything, or one per tab.
3. **"Nothing yet" texts** (not in Jira, ASSUMPTION): Hours "No hours booked yet", Progress "No progress recorded yet". Also "Not scheduled yet", "No open tasks" and "Printed <date>".
4. **History scope and paging.**
   - **Recommended:** `audit_log` rows of the Work Pack and of its children (phases, materials, schedule, assignments, Hour Journal header and lines, time sheets and lines, submissions, progress rows and retractions), plus the drawing-revision notices.
   - Newest first, `page` and `size` (default 50), with the total.
   - Alternative: only the `workpack` rows (short, but misses the hours and progress trail).
5. **Project scope:** as BPA-72, unscoped; BPA-79 adds it later. Confirm.
6. **The print on the Overview:** text only ("Printed <date>"), no PDF button, so the page has no actions (the print endpoint is limited to three groups anyway). Confirm.
7. **Jira:** BPA-73 is In Progress. I'll ask before any transition.

## Resolutions (Ahsan, 23 Nov 2026, in plan mode)
- **Q1:** the side panel lists the Epic 1 and time sheet instance tasks, plus the open weekly tasks of a not-Locked week in which the Work Pack has a progress row (ASSUMPTION). It is read as bpa-service with no new permission; a probe test proves the read, and a per-group search is the fallback.
- **Q2:** three reads: the detail (summary and four tabs), the history (paged) and the open tasks (Camunda; it can fail alone).
- **Q4:** the history holds the audit rows of the Work Pack and its children plus the drawing notices, newest first, paged (default 50) with a total.
- **Q3, Q5, Q6:**
  - the "nothing yet" texts as proposed (ASSUMPTION);
  - the print shown as text only, with no PDF button;
  - unscoped until BPA-79.
- **Q7:** I ask before any Jira transition.

## Progress and departures (23 Nov 2026)
- **Contract first (65b4808):** `GET /api/v1/workpacks/{n}` (`getWorkPack`), `.../history` and `.../open-tasks` with their schemas in `api/openapi.yaml`; `npm run gen:api`; compile stubs in the controller (501) so the tests could run red.
- **Red first (65b4808):** `WorkPackDetailApiTest` **13 of 14 red** (501 from the stubs; `aMalformedNumberIs400` too, because the generated `@Pattern` reaches the generic handler as a 500) with `notLoggedInIs401` green; `WorkPackDetailPage.test.tsx` **all 10 red** (the placeholder page, no tabs or side panel).
- **Built (d0b984b):** `WorkPackController` (three methods and a 400 handler for the number and the page and size), `workpack/WorkPackDetailService`, `workpack/WorkPackListMapper` (the list controller now uses it too), `persistence/WorkPackDetailRepository`, `task/WorkPackOpenTaskService`, `CamundaTaskClient.searchOpenAnyGroup`; the page `WorkPackDetailPage` (replaces `WorkPackDetailPlaceholder`, deleted), `features/workpacks` `WorkPackDetail` (tabs), `LifecycleBar`, `OpenTasksPanel`, three hooks, the service functions, the CSS, and `TaskList` opening a task from navigation state.
- **After the review:** the open-task read searches the Epic 1 and weekly instances by key and reads every page; the history sorts by row id last, shows Riyadh dates and states its deleted-records limit; `TaskList` clears the navigation state it used; tests added (see below).
- **Departures from the plan:**
  - `WorkPackHistoryRepository` was not made: the history reads are in `WorkPackDetailRepository`.
  - `SecurityConfig` needed no change: `anyRequest().authenticated()` already covers the three reads.
  - New `workpack/WorkPackDetailService` and `WorkPackListMapper`; `WorkPacksPage.test.tsx` scenario 5 now has a fake backend that answers the detail (the placeholder is gone).
  - The named probe test `searchWithoutAGroupIsAllowedForTheServiceUser` was not written separately: scenario 7 and the Epic 1 and weekly task tests prove the search without a candidate group against the real engine, with the service user's existing `READ_USER_TASK` permission. **No new permission was needed.**
  - `src/services/schema.d.ts` is generated and gitignored (not in the diff); `intent/README.md` is regenerated in the closing commit.
- **Green:** `WorkPackDetailApiTest` 19/19 (the 14 of the red run, and five added after the review), `WorkPackListApiTest` 14/14, `TaskApiTest` 8/8, `WorkPackApiTest` 13/13; frontend `WorkPackDetailPage` 12/12 (the 10 and two added after the review), `TaskList.navigation` 2/2, `WorkPacksPage` 6/6 and `Sidebar` 1/1; lint, `format:check` and `build` clean. Full `./mvnw -B verify` at d0b984b (before the review fixes): **739 tests, 0 failures**; after them the three classes the fixes touch were rerun (above). Full `npm test` after the fixes: 168 of 169; the one failure is the known `CreateWorkPack` `BPA6_dateToCompleteBoundary` 5 s timeout under load (passes alone).
- **No model, migration or data model change.**
- **Local-stack check (23 Nov, over the API; the portal screens were not clicked by me):** on WP-000001 the detail returned the summary (A-2301, PRJ-139, In Progress, 26 Nov to 18 Dec, 40.00, 480.00), phases Cutting 300 and Welding 200, Workers E1021 (40 h) and E1099 (60 h), three hour lines (two Approved on 16 Nov, one Submitted waiting at ProductionSupervisor, total 25.50 h) and the Locked week 19 Nov; the history returned the changes (including the Timekeeper's Validated decision); the open tasks listed **Approve hours (Production)** for `production-supervisor`, with `canOpen` false for `ppc-team` and `supervisor` and true for `production-supervisor`; an unknown number answered 404 "Work Pack not found". The portal served `/workpacks/WP-000001`.

## After the `qa-reviewer` pass (23 Nov 2026; REVIEW.md "BPA-73 at d0b984b")
- **Fixed after the review (Important 1 to 5):** the open-task search by instance key with every page read; the stable history order; the history limit stated in the contract and the child-table branches tested; the missing tests (TaskList navigation, "Show more", another Work Pack's task, an Epic 1 task, the 400s, the empty assertion removed); Riyadh dates for the history and the print.
- **Fixed (Nits):** `aria-controls` on the selected tab only; the navigation state cleared after use; the duplicated helpers; element-specific task assertions and a cleanup of the engine before each test.
- **Accepted for the POC:** no roving tabindex or arrow keys on the tabs; a task that completes between the search and its variables read fails the panel (as the task list does); the history query does not use the `audit_log` index; the 400 handler's wording; two weak keyword regexes in React tests.
- **For Ahsan and Jira (shown first, posted only on a yes):**
  1. **Q1:** the open-tasks read as the service user, listing the tasks of the Work Pack's Epic 1 instance, its timesheet process and (ASSUMPTION) the weekly process of a not-Locked week in which the Work Pack has a progress row; no task data; `canOpen` from the user's groups; no new permission.
  2. **Q2:** three reads (detail, history, open tasks).
  3. **Q3 (ASSUMPTIONs):** "No hours booked yet", "No progress recorded yet", "Not scheduled yet", "No open tasks", "Not printed yet", "No Workers assigned yet"; the print as a date only (no PDF button).
  4. **Q4:** the history is the audit log of the Work Pack and its **live** child records plus the drawing notices, paged; the rows of deleted child records are not in it.
  5. **Q5:** unscoped until BPA-79 (every user reads every Work Pack).
  6. **For the PO to confirm:** the history shows raw old and new values and usernames of all child tables to all eleven groups.
  7. **Jira status:** Ahsan moved BPA-73 to In Progress on 23 Nov 13:51; no content field changed since 17 Nov.
- **Recorded (plan vs diff):** the diff holds the contract, `WorkPackController`, `WorkPackDetailService`, `WorkPackListMapper`, `WorkPackDetailRepository`, `WorkPackOpenTaskService`, `CamundaTaskClient`, `WorkPackDetailApiTest`, the page, the `features/workpacks` detail, lifecycle bar, side panel, hooks, service and barrel, `TaskList` and its navigation test, `utils/dates.ts`, the CSS, `WorkPackDetailPage.test.tsx`, `WorkPacksPage.test.tsx`, the plan, `REVIEW.md` and `intent/README.md`; nothing else.

## Human-gap log
- **DoR:** intent.md and spec.md not accepted; the DoR contract item is met by this branch.
- **DoD, local run:** done over the API; nobody has clicked the detail page in a browser yet (the five tabs, the lifecycle bar, the open-tasks panel and opening a task from it). Ahsan or Khitaba can do one click-through.
- **For the PO:** the History tab shows raw audit values and usernames of all child tables to every group.
- **DoD:** "Demoed at sprint review". People.
- **DoD:** "passing in CI". Actions may not start (billing).
