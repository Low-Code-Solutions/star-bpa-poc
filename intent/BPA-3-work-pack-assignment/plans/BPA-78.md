# Plan: BPA-78 Time & Method Analyst can create Work Packs only for assigned projects
Jira: BPA-78 (Story, epic BPA-3)
Story last updated: 2026-11-24T18:54:16.424+0400
Status: Approved (Ahsan, 24 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-78-create-only-assigned-projects, from main d203f37 (the merge of PR #68, BPA-77)

> **Base:** PR #68 (BPA-77) is merged; the branch was rebased onto main d203f37 (empty branch, clean tree) before this plan was committed. BPA-78 reuses `scope/ProjectScope` and `GET /api/v1/me/projects`.

## Context
BPA-76 lets the PPC Team assign projects; BPA-77 lets each user read the master data of their own projects and built the shared check `ProjectScope`. BPA-78 applies that check where a Work Pack is **created**: the Time & Method Analyst may start a Work Pack only for a project assigned to them, the picker offers only those projects, and the API refuses any other with 403 before Camunda is called, so no `Process_PPC_WorkPackAssignment` instance starts.

Decision 11 Nov 2026 (Khitaba for the PO, comment 10521): BPA-76 to BPA-79 approved; a user may have several projects.

## Readiness check (Definition of Ready, docs/02, against Jira 24 Nov 18:54)
| Check | State |
|---|---|
| Jira status | **In Progress** (`statuscategorychangedate` = `updated` = 24 Nov 18:54). Any transition only when you say so (Q5) |
| Template, persona, outcome | Met (Time & Method Analyst) |
| Gherkin incl. unhappy path | Met: 2 scenarios (the 403) |
| BPMN element IDs exist | Met: `Process_PPC_WorkPackAssignment / StartEvent_WorkPackRequested`. **No model change** (a start variable needs none) |
| Every field has an SOR | Met, with one correction: the SOR Notes name **`workpack.project_ref`, which does not exist**; a Work Pack's project is its activity's (`activity.wbs_ref → wbs.project_ref`). No column is added (Q1) |
| No blocking question | Q1 to Q3 resolved in plan mode; **BPA-77 not yet on main** (base check above) |
| Estimate ≤ 8 | 2 (the start change touches ten test classes, so a full 2) |
| Epic `intent.md` / `spec.md` accepted | Met (Epic 1 spec Accepted, Faraz, 2 Nov 2026) |
| PO approved as Ready | Human gap (the 11 Nov comment says Faraz would move it) |

## What exists today
- **The start carries no project.** `POST /api/v1/workpacks` (`startWorkPack`, `WorkPackController` :143) has no body: group `time-method-analyst` only (`SecurityConfig`, 403 otherwise), then `CamundaProcessClient.start(PROCESS)` with **empty variables**. The contract says "The call carries no Work Pack data".
- **The project comes later**, from the **Activity** picked in the Create Work Pack task (`UserTask_CreateWorkPack`, completed through the task API; write and read allow-list `bpa.tasks` in `application.yml`: activityRef, drawingRef, departmentRef, completionDate, phases, materials). `ActivitySelect` lists every activity of `GET /api/v1/activities` (unscoped; each row carries `projectRef`). The save step `SaveWorkPackWorker` → `WorkPackValidator` refuses "Project activity is required" and "Project activity X is not known".
- **`workpack` has no project column**; the project is the activity's (activity → WBS).
- **The Start button** is in `TaskList` (`groups.includes('time-method-analyst')`, `startRequest` → `useStartWorkPack` → `workpackService.startWorkPack`, `api.POST('/api/v1/workpacks')`).
- **BPA-77 (branch, history):** `ProjectScope.projectsOf/allows/require`, `ProjectNotAssignedException` → 403 "Project PRJ-142 is not assigned to you" (an unknown project gets the same answer), `GET /api/v1/me/projects`, `useMyProjects`, the reference lists left unscoped with the guard `theReferenceListsAreUnchanged`.
- **Personas:** `time-method-analyst` has **no** `projects:` in `personas.yaml`; the sync seeds a user only while it has no `user_project` row.
- **Every test that starts a Work Pack** posts an empty body as time-method-analyst, and all use PRJ-139 activities (A-2201, A-2299, A-2301): `AssignmentFlow`, `WorkPackApiTest`, `ConfirmWorkPackApiTest`, `SignOffApiTest`, `ReleaseCheckApiTest`, `ScheduleApiTest`, `PrintApiTest`, `HourJournalHeaderApiTest`, `DemoRun` (BPA-68), and `AuthApiTest` (`BPA43_startWorkPackIsAllowedForTimeMethodAnalyst` mocks `processes.start(...)` with "{}"). `DrawingRevisionApiTest` and `WorkPackListApiTest` only check a refusal or the GET.

## Design
### 1. The start carries the project (Q1 resolved)
- **Contract:** `POST /api/v1/workpacks` gains a required body **`StartWorkPackRequest { projectRef }`**; responses add **403 "Project PRJ-142 is not assigned to you"** (BPA-77's text, the same for an unknown project) beside the existing group 403, and **400 "Project is required"** (**ASSUMPTION**) for a missing or blank `projectRef`. The description changes from "carries no Work Pack data" to "carries the project".
- **Order in `WorkPackController.startWorkPack`:** the group (SecurityConfig, unchanged) → the body (400) → **`ProjectScope.require(session user, projectRef)`** (403) → `camunda.start(PROCESS, { projectRef })`. A refusal returns before Camunda is called, so **no instance starts**.
- **`CamundaProcessClient.start(processDefinitionId, variables)`**: an overload that sends the variables (the existing one calls it with none).
- **The instance carries `projectRef`** (a process variable, camelCase per docs/04). No BPMN change; the `StartEvent_WorkPackRequested` documentation is not edited (no AC requires it).
- **No `workpack.project_ref` column**: the Work Pack's project stays its activity's, and the save step keeps them equal (design 3).

### 2. Screen
- **`TaskList`** (time-method-analyst only, as today): a **Project** picker beside "Start Work Pack request", offering `GET /api/v1/me/projects` (BPA-77's `useMyProjects`), first project selected. The Start posts `{ projectRef }`. A user with no project sees "No projects assigned. Ask the PPC Team." (BPA-77's text) and no Start button. A refusal shows the backend's `detail` in the existing notice.
- **`CreateWorkPack`**: the task's `projectRef` is read through the task API (`projectRef` added to the **read** list of `[UserTask_CreateWorkPack]`, not the write list), shown read-only, and the **Activity** list offers only that project's activities (`ActivitySelect` gets an optional `projectRef` filter on the rows `GET /api/v1/activities` already returns; the endpoint stays unscoped, so BPA-77's guard holds). An instance started before BPA-78 (no `projectRef`) shows every activity, as today.
- **Drawings, materials and departments stay unfiltered** (Q3).

### 3. The save backstop (Q3 resolved)
- `WorkPackValidator`: when the instance carries a `projectRef`, an activity of another project is refused with **"Project activity A-2206 is not in project PRJ-139"** (**ASSUMPTION**, next to "Project activity X is not known"); the BPMN error returns `validationMessages` and the task reopens (BPA-6 path). `KnownRefs` gains `activityProject(activityRef)`; `SaveWorkPackWorker` passes the variable. An instance with no `projectRef` (started before BPA-78) is not checked.
- **No later re-check** (Q4): a project unassigned after the start does not stop the instance.

### 4. Personas, fixtures and the demo (Q2 resolved)
- **`personas.yaml`:** `time-method-analyst` gets `projects: [PRJ-139, PRJ-140]` (**ASSUMPTION**, like project-manager). `PersonaSyncTest`'s expected rows gain the two (existing-test edit; 5 → 7 rows).
- **Existing local databases:** time-method-analyst has no row, so the sync seeds it at the **next start of `bpa-app`**; no reset. If the PPC Team already gave it a project on the Users and projects page, the sync leaves it alone, and the PPC Team adds the rest there.
- **Test fixtures:** a helper (in `AssignmentFlow`) inserts the `app_user` row of time-method-analyst if absent and its PRJ-139 `user_project` row, and the start posts `{"projectRef":"PRJ-139"}`. Files that change: `AssignmentFlow`, `WorkPackApiTest`, `ConfirmWorkPackApiTest`, `SignOffApiTest`, `ReleaseCheckApiTest`, `ScheduleApiTest`, `PrintApiTest`, `HourJournalHeaderApiTest`, `DemoRun`, `AuthApiTest` (the mock and the body). All are existing-test edits, said in the PR.
- **Frontend tests:** `tests/integration/TaskList.test.tsx` and the `CreateWorkPack` tests answer `/api/v1/me/projects` and the task's `projectRef` in their fake backends.
- **Demo script** (`docs/showcase/epic1-epic2-demo.md`, step 1): "pick PRJ-139, then Start Work Pack request".

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml` (`StartWorkPackRequest`; the start's 400 and 403; description) |
| Backend | `api/WorkPackController.java`, `adapter/CamundaProcessClient.java` (overload), `domain/WorkPackValidator.java`, `domain/WorkPackInput.java` (projectRef), `worker/SaveWorkPackWorker.java`, `persistence/ReferenceRepository.java` (the activity's project for `KnownRefs`), `resources/application.yml` (read list), `resources/personas.yaml` |
| Backend tests | new `WorkPackProjectScopeApiTest.java`; new `domain/WorkPackValidatorTest.java` (none exists today); the ten existing classes above; `PersonaSyncTest` |
| Frontend | `features/tasks/components/TaskList/TaskList.tsx`, `features/workpacks/services/workpackService.ts`, `features/workpacks/hooks/useStartWorkPack.ts`, `features/workpacks/components/CreateWorkPack/CreateWorkPack.tsx`, `features/reference-data/components/ActivitySelect` (filter); tests `TaskList.test.tsx`, `CreateWorkPack.test.tsx` |
| Docs | `docs/showcase/epic1-epic2-demo.md`; data model "As built by BPA-78" note (no column; the project is the activity's); plan; `REVIEW.md`; `intent/README.md` |

## Order of work
0. Done: PR #68 (BPA-77) merged; main pulled (d203f37) and this branch rebased onto it.
1. Commit the plan (`Approved`).
2. Contract first; then the tests, **red before production code** (compile stubs only); commit with the red output.
3. Build: controller, client overload, validator and worker, allow-list, personas; then the screens. Green; then the ten edited classes.
4. Docs; full `./mvnw -B verify` (**no 403 regressions** across every test that starts a Work Pack); full `npm test`, lint, format, build; the BPA-68 dry run of the create step; `qa-reviewer`; fixes; REVIEW.md; plan Closed. Push, PR and Jira only when you ask.

## Scenario-to-test table
**Backend `WorkPackProjectScopeApiTest`** (per-class Postgres, shared Camunda) and **React** (`TaskList.test.tsx`). Background: the eleven `app_user` rows; `user_project` cleared, then time-method-analyst has **PRJ-139 only**; **PRJ-142** inserted as a fixture (not seeded).

| # | Scenario | Test | Arrange / act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | Picker lists only assigned projects | `BPA78_pickerListsOnlyAssignedProjects` | React: time-method-analyst, `/me/projects` = [PRJ-139]; open the task list. Backend: GET `/api/v1/me/projects` as time-method-analyst | React: the Project picker beside Start offers PRJ-139 only. Backend: `[PRJ-139]` (the picker's source) | **red** (React: no picker). The backend twin passes before code, as BPA-77 built the endpoint (a guard; said so) |
| 2 | API rejects an unassigned project | `BPA78_apiRejectsUnassignedProject` | Backend: time-method-analyst POSTs `{"projectRef":"PRJ-142"}`. React: the backend answers 403 to the Start | Backend: **403**, detail "Project PRJ-142 is not assigned to you"; the count of `Process_PPC_WorkPackAssignment` instances is unchanged (Camunda search before and after). React: the notice shows the detail; no task opens | **red** (today 201 and an instance starts) |

**Extras:**
- `startWithAnAssignedProjectCarriesIt` (201; the instance's `projectRef` = PRJ-139; the Create task's read shows it).
- `startWithoutAProjectIsRefused` (no body and a blank one → 400 "Project is required"; no instance).
- `anUnknownProjectIsRefusedLikeAnUnassignedOne` (PRJ-999 → the same 403).
- `otherGroupsAreStillRefusedForTheirGroup` (production-team → the group 403, before the body is read).
- `theSaveRefusesAnActivityOfAnotherProject` (started for PRJ-139; the Create task completed with A-2206 of PRJ-140 → back at Create with "Project activity A-2206 is not in project PRJ-139"; no Work Pack row).
- `WorkPackValidatorTest`: the new message; no check without a `projectRef` (an instance started before BPA-78).
- React: `theActivityListShowsOnlyTheTasksProject` (CreateWorkPack); `noProjectMeansNoStart` (TaskList, the empty-state text).

`grep -c "void BPA78_"` = 2 in Java, 2 `BPA78_` in React.

## Proof (one per Definition of Done line, docs/02)
| DoD line | Evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=WorkPackProjectScopeApiTest verify`; `npm test`; `grep -c "void BPA78_"` = 2 and React = 2; **full `./mvnw -B verify` (every test that starts a Work Pack passes: no 403 regressions)**; `gh pr checks <n>` (Actions may not start: billing) |
| Process runs end to end locally | rebuild `bpa-app` and `frontend`; the persona sync seeds time-method-analyst PRJ-139 and PRJ-140 at start; **BPA-68 dry run of the create step**: as time-method-analyst pick PRJ-139, Start, the Create task offers only PRJ-139 activities, save A-2301 → WP created (`docs/showcase/epic1-epic2-demo.md` step 1); then with PRJ-140 taken away through the BPA-76 API, POST `{"projectRef":"PRJ-140"}` → 403 and the instance count unchanged; restore |
| BPMN and DMN match deployed | no model change (`git diff --stat models/` empty) |
| Open questions resolved or logged | the Jira comment (shown first, posted on your yes) |
| Plan Closed | `grep '^Status: Closed' intent/BPA-3-work-pack-assignment/plans/BPA-78.md` |
| Demoed at sprint review | Human gap |

## Risks
- **BPA-77 is not merged.** The branch is rebased once #68 merges; if #68 changes `ProjectScope`, this plan follows it.
- **The start's contract changes** (a required body). Every caller is listed and edited; the full suite proves none was missed. A running stack with an older portal would get 400 until the frontend is rebuilt with it.
- **An instance started before BPA-78** has no `projectRef`: the Create screen shows every activity and the save step skips the check (said in the PR as a deploy note).
- **The persona sync** seeds time-method-analyst only while it has no row; a database where the PPC Team already gave it a project keeps that.

## Questions
1. **How the Create screen identifies the project today, and the change.** Resolved: today it does not; the project follows the Activity, after the instance has started. The start now carries `projectRef` (picker beside Start), checked with `ProjectScope` before Camunda is called; the Create task's Activity list is limited to that project; the save step refuses an activity of another project.
2. **Which projects time-method-analyst gets.** Resolved: PRJ-139 and PRJ-140 (ASSUMPTION, like project-manager).
3. **Filter the other lists (drawings, materials, departments)?** Recommended: no. Jira asks for the picker and the API check; the Activity list is filtered only because it carries the project. Drawings could follow later (V8 gives them a project).
4. **Re-check later (a project unassigned after the start)?** Recommended: no; Jira is silent.
5. **Jira:** BPA-78 is In Progress. I ask before any transition or comment.
6. **The Jira comment** (shown first, posted on your yes): the start now carries the project (the picker sits beside Start, not inside the Create task, because the 403 must come before an instance exists); "Project is required" (400) and the save message "Project activity A-2206 is not in project PRJ-139" are ASSUMPTIONs; the SOR Notes' `workpack.project_ref` is not a column (the project is the activity's); time-method-analyst's projects in `personas.yaml` (ASSUMPTION).
7. **ASSUMPTIONs:** the two texts above; the persona projects; the first project preselected in the picker; no re-check after the start.

## Resolutions (Ahsan, 24 Nov 2026, in plan mode)
- **Q1:** the start carries the project; `ProjectScope.require` before Camunda; the Create task's activities limited to it; the save step refuses another project's activity.
- **Q2:** time-method-analyst gets PRJ-139 and PRJ-140.
- **Save backstop:** yes, in `WorkPackValidator`, skipped for an instance with no `projectRef`.

## Progress and departures (24 Nov 2026)
- **Contract first:** `StartWorkPackRequest { projectRef }` is the required body of `POST /api/v1/workpacks`; the operation gains the 400 "Project is required" and an inline 403 with two examples (the group text and "Project PRJ-142 is not assigned to you"); `npm run gen:api` parses it.
- **Compile stubs:** `WorkPackInput` gains `projectRef` as its last component (the old six-argument constructor stays), `KnownRefs.activityProject` is a default method returning null, `CamundaProcessClient.start(id, variables)` throws "BPA-78 stub", and `WorkPackController.startWorkPack` takes the body and still ignores it.
- **Existing tests edited (they pass before code, by design):** `AssignmentFlow` gains `assignAnalystProject` and `startWorkPack(analyst, jdbc)` (the analyst's `app_user` row and a PRJ-139 `user_project` row in the class's own database, then a start for PRJ-139); the nine classes that start a Work Pack call it (`ConfirmWorkPackApiTest`, `SignOffApiTest`, `ReleaseCheckApiTest`, `ScheduleApiTest`, `PrintApiTest`, `HourJournalHeaderApiTest`, `WorkPackApiTest`, `DemoRun`, and `AssignmentFlow` itself); `AuthApiTest` mocks the two-argument start and posts the project; `PersonaSyncTest` expects the analyst's two rows (7, any order, because the query's order depends on the database collation); the React `TaskList.test.tsx` and `CreateWorkPack.test.tsx` fake backends answer `/api/v1/me/projects`, the project-carrying start and a PRJ-140 activity.
- **Red first:** 9 of 26 backend tests fail: `WorkPackProjectScopeApiTest` 4 of 7 (`BPA78_apiRejectsUnassignedProject` is 201 where a 403 is expected, an unknown project 201, no project "Failed to read request" instead of "Project is required", the instance has no `projectRef`), `AuthApiTest` 1, `PersonaSyncTest` 2 (5 rows, not 7), `WorkPackApiTest.theSaveRefusesAnActivityOfAnotherProject` 1, `WorkPackValidatorTest` 1 of 4. React: `TaskList` 3 (`BPA78_pickerListsOnlyAssignedProjects`, `BPA78_apiRejectsUnassignedProject`, `noProjectMeansNoStart`) and `CreateWorkPack.theActivityListShowsOnlyTheTasksProject`. Green before code, by design (guards): `BPA78_pickerListsOnlyAssignedProjects` in Java (BPA-77 built the endpoint), `otherGroupsAreStillRefusedForTheirGroup`, `anAnalystWithSeveralProjectsMayStartForEither`, three `WorkPackValidatorTest` cases, `aTaskWithNoProjectShowsEveryActivity` and `otherGroupsSeeNoStartAndNoProjectCall`.
- **Departure 1:** the save-backstop test `theSaveRefusesAnActivityOfAnotherProject` is in `WorkPackApiTest`, beside `submit` and `validWorkPack`, not in the new class.
- **Departure 2:** `WorkPackValidatorTest` is in `domain/` (none existed); it holds four cases.

## Human-gap log
- **Base:** PR #68 (BPA-77) merged; the branch is on main d203f37.
- **DoR:** whether the PO's move to Ready was recorded.
- **DoD:** "Demoed at sprint review". People.
- **DoD:** "passing in CI". Actions may not start (billing).
- **The local dry run** in the portal is a person's.
