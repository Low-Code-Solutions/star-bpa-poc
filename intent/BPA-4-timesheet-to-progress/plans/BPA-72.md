# Plan: BPA-72 PPC Team can browse all Work Packs and filter them by status
Jira: BPA-72 (Story, epic BPA-4)
Story last updated: 2026-11-17T11:46:15.410+0400
Status: Approved (Ahsan, 23 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-72-workpack-list, from main e57a4ef

> Written in plan mode and approved by Ahsan on 23 Nov 2026; committed as the branch's first commit.
> Checked read-only: the branch `BPA-72-workpack-list` is at **e57a4ef**, the merge of PR #62 (BPA-69), not at e453cc6 as the brief says. BPA-69's code is on the branch but nothing here depends on it (Q8).

## Context
Today a person reaches a Work Pack only through a task, or by typing its number on the Hour Journal and Time sheet screens. BPA-72 adds a read-only **Work Packs** list under Home in the portal, with filters for status, project and week ending, and a search by number. Clicking a row opens the Work Pack's detail page (BPA-73, not built yet). Every logged-in group may read the list, and it offers no actions.

It needs three things:
1. **A contract-first `GET /api/v1/workpacks`.** Today only POST exists.
2. **The page and a route.**
3. **The first Sidebar navigation.** The Sidebar renders nothing today.

## Readiness check (Definition of Ready, docs/02, against Jira 17 Nov 11:46)
| Check | State |
|---|---|
| Jira status | **Ready** (Faraz, 17 Nov; comment 10563). Ready → In Progress only when you say so (Q7) |
| Template, persona, outcome | Met (PPC Team and any persona, read-only) |
| Gherkin incl. unhappy paths | Met: 6 scenarios (unknown number; read-only persona) |
| BPMN element IDs | n/a: UI only (BPMN Reference says so) |
| Every field has an SOR | Met (SOR Notes): workpack, workpack_schedule, `workpack_progress.approved_pct` of the latest locked week, `activity_progress.earned_manhours`; no new table |
| DMN | none |
| No blocking question | Q1 (project scope) and Q3 (Earned MH per Work Pack) change what is built; Q2, Q4 and Q5 shape it |
| Jira DoR: intent.md and spec.md accepted | **Not met** (as every Epic 2 story) |
| Jira DoR: "GET /api/v1/workpacks list contract in api/openapi.yaml" | **Not met yet**: this story writes it first (contract-first, the first commit after the plan) |
| Estimate ≤ 8 | 3. Includes the first Sidebar navigation and the first route with a path parameter |

## What already exists (code; closed plans BPA-60 and BPA-62 are history only)
**Contract** (`api/openapi.yaml`):
- `/api/v1/workpacks` has only `post` (`startWorkPack`, :175).
- Lists elsewhere return a **bare array** ("No paging in the POC", `listWorkPackPrints` :584).
- The only query parameter is `WorkpackNumberQuery` (:1621).
- Errors are `Problem` (RFC 9457).
- Groups are documented in prose in each operation's description.
- ASSUMPTIONs are marked with `x-assumption`.
- The description (:16) still says "no department or project scope (BPA-44)". That is BPA-79's to change (Q1).

**Security** (`config/SecurityConfig.java`):
- `POST /api/v1/workpacks` is `TIME_METHOD_ANALYST` only (:69), so a GET on the same path already falls to `.anyRequest().authenticated()` (:112).
- There is no "all groups" constant; "any logged-in user" is written as `authenticated()` (e.g. :107).

**Data** (`docs/data-model.md`):
- `workpack`: `workpack_number`, `activity_ref`, `drawing_ref`, `department_ref`, `status` [Draft, Released, In Progress, Completed], `assignment_state` [AwaitingFile, Assigning, Assigned, Replacing] (NULL until printed).
- `workpack_schedule`: `planned_start_date`, `planned_end_date` (one per Work Pack, absent until scheduled).
- **No project column.** The project is `workpack.activity_ref → activity.wbs_ref → wbs.project_ref` (the join in `DigitalTimesheetRepository` :95-96).
- `workpack_progress`: `approved_pct` per (Work Pack, period); `progress_period.status` is CutOff or Locked.
- `activity_progress.earned_manhours` is stored per **activity**, not per Work Pack.

**Reusable reads:**
- `ProgressWeekRepository.lastLockedPct` (:86-91) gives the latest Locked week's `approved_pct` (subquery).
- `ActivityProgressRepository.shown` (:114-124) gives the last locked Earned MH per activity.
- `WorkPackPrintRepository.list` (:108-118) joins `workpack_schedule`.

**Scope:** `user_project` is written by the persona sync (timekeeper and production-supervisor PRJ-139, cost-controller PRJ-140, project-manager both). **Nothing reads it yet** (BPA-76 to 79).

**Frontend** (ADR 0017):
- `Sidebar.tsx` returns `null` ("navigation comes with the visual redesign").
- `AppRoutes.tsx`: `index` and `*` both render `TasksPage`; there is no `/workpacks` route.
- `pages/` holds `LoginPage` and `TasksPage` only.
- No shared table component exists; tables are plain `<table className="sign-table">`.
- `features/workpacks/` has the task screens and `WorkPackPrints`.
- Services use `api.GET` (openapi-fetch); hooks use `useQuery`; types come from `src/types/api.ts` aliases of `schema.d.ts` (`npm run gen:api`).
- `showDate` in `utils/dates.ts`.
- Tests: `fakeBackend()` with `vi.stubGlobal('fetch')`, `tests/test-utils.tsx` render (MemoryRouter without initial entries). No test asserts navigation yet, and `useNavigate` is not used anywhere.

**Test data:** `BookHoursFixture` makes the Work Packs the Gherkin names, but none has a `workpack_schedule` row:
- `reset()`: WP-000457, Released;
- `workPack461()`: WP-000461;
- `completionBackground()`: WP-000457 and WP-000461 In Progress, WP-000480 Released.

## Design
### 1. Contract first: `GET /api/v1/workpacks` (`listWorkPacks`, tag `workpacks`)
**Query parameters** (all optional, combined with AND; invalid values get 400 Problem):

| Name | Type | Meaning |
|---|---|---|
| `status` | array of the 4 statuses, `explode: true` (`?status=Released&status=In%20Progress`) | any of them; the screen sends one |
| `project` | string (`PRJ-139`) | Work Packs whose activity's WBS belongs to the project |
| `weekEnding` | date | Work Packs with a `workpack_progress` row for that week (Q4) |
| `search` | string | Work Pack number contains the text, case-insensitive (ASSUMPTION: partial, so "461" finds WP-000461) |

**Response 200:** a **bare array** of `WorkPackListRow`, as the other lists do. There is no paging ("No paging in the POC", ASSUMPTION). Rows are sorted by Work Pack number, newest first (ASSUMPTION).

**`WorkPackListRow`:**
- `workpackNumber`, `activityRef`, `drawingRef`, `departmentRef`, `projectRef`;
- `status`, `assignmentState` (nullable);
- `plannedStartDate`, `plannedEndDate` (nullable until scheduled);
- `approvedPct` (nullable: the latest **Locked** week's `approved_pct`, Q5) and `approvedWeekEnding` (nullable: that week);
- `earnedManhours` (nullable: per Q3).

**Groups and errors:**
- Any logged-in user (all eleven groups); 401 when not logged in.
- Read-only: the description says so.
- An unknown number is **200 with `[]`**; the screen shows "Work Pack not found" (Gherkin).
- `x-assumption` notes: partial search, sort, no paging, the week filter, the Earned MH meaning.

### 2. Backend
- **`api/WorkPackController.listWorkPacks`** implements the generated method and delegates to a new read repository.
- **`persistence/WorkPackListRepository.list(Filter)`:** one SQL query over:
  - `workpack w`;
  - LEFT JOIN `workpack_schedule s`;
  - JOIN `activity a` and `wbs b` (the project);
  - a lateral or subquery for the latest Locked `approved_pct` and its week (`lastLockedPct`'s condition without the "before this week" limit);
  - the Earned MH per Q3;
  - `EXISTS` on `workpack_progress` for `weekEnding`;
  - `ILIKE` for `search`; `= ANY(?)` for `status`.

  It is read only, with no audit.
- **`SecurityConfig`:** an explicit `requestMatchers(HttpMethod.GET, "/api/v1/workpacks").authenticated()` line with a BPA-72 comment, so the rule reads clearly (it is already the fall-through).
- No `user_project` filter (Q1).

### 3. Frontend
**Navigation (the first Sidebar):** `Sidebar` gets two `NavLink`s:
- **Home** (`/`, the tasks page as today);
- **Work Packs** (`/workpacks`), indented under Home, for every logged-in group.

The visual redesign stays a later task: this is a plain list with the existing CSS.

**Routes** (`AppRoutes.tsx`):
- `workpacks` → `WorkPacksPage`;
- `workpacks/:workpackNumber` → a **placeholder** `WorkPackDetailPlaceholder` ("Work Pack <number>", "Details come with BPA-73"; Q2).

Both go inside the existing `ProtectedRoute` (no groups) and above `*`.

**`pages/WorkPacksPage`:**
- A filter bar:
  - **Status:** select "All" + the 4 statuses;
  - **Project:** select "All" + PRJ-139 and PRJ-140, read from the unfiltered rows' `projectRef`s (ASSUMPTION: no project reference endpoint exists);
  - **Week ending:** date input;
  - **Search:** text input "Work Pack number".
- A table `aria-label="Work Packs"` with the columns: number, activity, drawing, department, status, assignment state, planned start, planned end, approved completion %, Earned MH.
  - Dates use `showDate`; an empty value shows "–".
  - A row (and its number link) navigates to `/workpacks/<number>` with `useNavigate`.
- Messages:
  - empty with a search: **"Work Pack not found"** (Gherkin);
  - empty without a search: "No Work Packs" (ASSUMPTION);
  - an API failure shows `ErrorMessage`.
- **No create, edit or delete buttons.** "Start a Work Pack" stays on the tasks page for time-method-analyst, as today.

**Code layout:**
- `features/workpacks/services/workpackService.ts`: `listWorkPacks(api, filter)`;
- `features/workpacks/hooks/useWorkPackList.ts`: `useQuery`, keyed on the filter;
- a `WorkPackList` component in `features/workpacks/components/`;
- the `WorkPackListRow` type alias in `src/types/api.ts`;
- `npm run gen:api`.

### 4. Docs
- OpenAPI tag text (:36) mentions the list.
- No data model or ADR change (no new table or column).

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml` (GET on `/api/v1/workpacks`, `WorkPackListRow`, the parameters) |
| Backend | `api/WorkPackController.java`, new `persistence/WorkPackListRepository.java`, `config/SecurityConfig.java` (one explicit line) |
| Frontend | `components/layout/Sidebar/Sidebar.tsx` (+ test), `routes/AppRoutes.tsx`, new `pages/WorkPacksPage/` and `pages/WorkPackDetailPlaceholder/`, `features/workpacks/components/WorkPackList/`, `hooks/useWorkPackList.ts`, `services/workpackService.ts`, `src/types/api.ts`, `src/services/schema.d.ts` (generated) |
| Tests | new `backend/.../WorkPackListApiTest.java`; new `WorkPacksPage.test.tsx` (or `WorkPackList.test.tsx`) |
| Docs | plan; REVIEW.md; `intent/README.md` |

## Order of work
1. Approve the plan and commit it (`Approved`).
2. Write the contract and run `gen:api`. Add compile stubs: the controller method throws `UnsupportedOperationException` (or answers 501), and an empty page.
3. Write the tests, backend and React, and run them **red before any production code**. Commit.
4. Build the backend, then the frontend, then green. Check `TaskApiTest` and `WorkPackApiTest` (the same path, POST) and the App integration test (the routes).
5. Frontend lint, `format:check`, `npm test`, build; full `./mvnw -B verify`; the local check; `qa-reviewer`; fixes; REVIEW.md; plan Closed. Push, PR and Jira comments only when you ask.

## Scenario-to-test table
**Backend `WorkPackListApiTest`** (integration stack, per-class Postgres; Camunda is not needed for reads but the app context needs it, as other API tests):
- Background built by SQL: `BookHoursFixture.reset()` + `workPack461()` + `completionBackground()`, then WP-000461 → Completed and WP-000480 stays Released. **The test inserts `workpack_schedule` rows** for the three Work Packs (planned dates, ASSUMPTION data) and `assignment_state` Assigned.
- Project and progress data for the extras: one Locked week with `approved_pct`, and an `activity_progress` row.

**React `WorkPacksPage.test.tsx`** (fakeBackend, MemoryRouter with `initialEntries={['/workpacks']}` and the placeholder route).

| # | Scenario | Test (Java and React, same name) | Asserts | Expected first run |
|---|---|---|---|---|
| 1 | List shows every Work Pack with its current status | `BPA72_listShowsEveryWorkPack` | Java: GET as ppc-team → 3 rows, each with number, status (In Progress, Completed, Released), assignment state, planned start and end. React: the table shows the three rows with those cells | red (Java: 405, no GET; React: no page) |
| 2 | Filter by status | `BPA72_filterByStatus` | Java: `?status=In Progress` → only WP-000457. React: choosing In Progress sends `status=In Progress` and shows only WP-000457 | red |
| 3 | Search by number | `BPA72_searchByNumber` | Java: `?search=WP-000461` → only WP-000461, status Completed. React: typing WP-000461 shows that one row with Completed | red |
| 4 | Unknown number | `BPA72_unknownNumber` | Java: `?search=WP-999999` → 200 `[]`. React: "Work Pack not found" shown, no rows | red |
| 5 | Open a Work Pack from the list | `BPA72_openWorkPackFromList` | **React only:** clicking the WP-000457 row shows the placeholder for WP-000457 (the route `/workpacks/WP-000457`) (Q2) | red |
| 6 | Any persona can read the list but has no actions | `BPA72_anyPersonaReadsWithoutActions` | Java: as timekeeper (and a loop over all eleven personas) GET → 200; a POST as timekeeper stays 403. React: logged in as timekeeper the rows show and **no button, link or menu item named create, edit, new or delete** is on the page; the Sidebar shows "Work Packs" | red |

**Extras (not scenarios):**
- `filterByProject` (PRJ-140 → none of the three; PRJ-139 → all, since A-2201 is PRJ-139).
- `filterByWeekEnding` (Q4).
- `approvedPctIsTheLatestLockedWeek` (a CutOff week's value is not shown; Q5).
- `earnedManhoursColumn` (Q3).
- `notLoggedInIs401`; `unknownStatusIs400`.
- React: the Sidebar test (Home and Work Packs links).

`grep -c "BPA72_"` = 5 Java + 6 React test names.

## Proof (one per Definition of Done line)
| DoD line | Evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=WorkPackListApiTest verify`; `cd frontend && npm test -- WorkPacksPage`; full `./mvnw -B verify` and `npm test`; `gh pr checks <n>` (Actions may not start: billing) |
| Process runs end to end locally | rebuild `bpa-app` and `frontend`; log in at http://localhost:3000 as **timekeeper**, open **Work Packs** in the Sidebar, filter status In Progress, search WP-000001 and WP-999999 ("Work Pack not found"), click a row (placeholder page); check no action buttons; then ppc-team the same |
| BPMN and DMN match deployed | no model change (n/a); state it |
| Open questions resolved or logged | the Jira comments (shown first, posted on your yes) |
| Plan Closed | `grep '^Status: Closed'` |
| Demoed at sprint review | Human gap |

## Risks
- **The first navigation:** a Sidebar with links changes every page's layout slightly. The App integration test and its snapshot-free assertions should hold; check `App.test.tsx`.
- **The first route with a path parameter**, and the catch-all `*` route: `/workpacks/…` must be above `*`.
- **Unscoped list (Q1):** until BPA-79, cost-controller (PRJ-140 only) sees PRJ-139 Work Packs too.
- **One query with subqueries** per row is fine for the POC's few Work Packs; there is no paging.

## Questions
1. **Project scope.** Jira's 12 Nov decision says each user sees only their assigned projects, through BPA-79 (Ready, not built). Nothing reads `user_project` yet.
   - **Recommended:** build BPA-72 **unscoped**, with a `project` filter the user chooses. BPA-79 adds the per-user restriction to this endpoint (and the others) in its own branch.
   - The PR and the Jira comment say that until BPA-79 every user sees every project.
2. **The row click and BPA-73.** The detail page does not exist.
   - **Recommended:** a placeholder route `/workpacks/:workpackNumber` ("Work Pack <number>", "Details come with BPA-73"). Scenario 5 asserts the navigation and the placeholder's number only; BPA-73 replaces the placeholder.
3. **"Earned MH" per Work Pack.** Earned man-hours exist only per activity (BPA-62); the SOR Notes name `activity_progress.earned_manhours`.
   - **Recommended:** show **the activity's Earned MH of the latest Locked week**, labelled "Earned MH (activity)", sourced from the SOR Notes. Work Packs on the same activity show the same number, which the label explains.
   - Alternatives: approved % × the Work Pack's estimated hours (not sourced, ASSUMPTION), or drop the column.
4. **The week-ending filter.** Jira doesn't say what it means.
   - **Recommended:** Work Packs that have a `workpack_progress` row for that week, meaning they were in that week's progress (ASSUMPTION). The % and Earned MH columns stay "latest Locked" either way.
   - Alternative: Work Packs active that week (scheduled dates overlap it).
5. **"Approved completion %".**
   - **Recommended:** the **latest Locked week** (the SOR Notes say "of the latest locked week"), with that week shown in the cell or its title.
   - Alternative: the latest approved week even if not locked yet.
6. **Khitaba's UI design decisions** (U-D29 onwards, in the Claude Project, not in the repo) describe a larger Projects area with path filters.
   - **Recommended:** BPA-72 follows the Jira text only. The gap is noted in the Human-gap log and the Jira comment; the visual redesign stays a later task.
7. **Jira:** BPA-72 is Ready. I'll ask before moving it to In Progress and before any other transition.
8. **Branch base:** the branch is at e57a4ef (with BPA-69), not e453cc6. Nothing here depends on BPA-69. Keep it, or rebuild the branch from e453cc6?
9. **ASSUMPTIONs:**
   - partial, case-insensitive search;
   - newest-first sort;
   - no paging;
   - the project filter's options taken from the rows;
   - "No Work Packs" for an empty unfiltered list;
   - `status` takes several values in the API while the screen sends one.

## Resolutions (Ahsan, 23 Nov 2026, in plan mode)
- **Q1:** unscoped; BPA-79 adds the per-user project restriction on its own branch. The PR and the Jira comment say so.
- **Q2, Q8:** a placeholder route `/workpacks/:workpackNumber`; scenario 5 asserts the navigation only. The branch stays at e57a4ef.
- **Q3:** "Earned MH (activity)": the activity's Earned MH of the latest Locked week.
- **Q4, Q5:**
  - the week filter selects Work Packs with a `workpack_progress` row for that week (ASSUMPTION);
  - the % is the latest Locked week's `approved_pct`, with its week.
- **Q6, Q7, Q9:** as written. I ask before any Jira transition.

## Human-gap log
- **DoR:** intent.md and spec.md not accepted; the Jira DoR item "list contract in api/openapi.yaml" is met by this branch's first commit.
- **Design:** Khitaba's UI decisions (U-D29 onwards) are not in the repo; BPA-72 follows Jira only (Q6).
- **DoD:** "Demoed at sprint review". People.
- **DoD:** "passing in CI". Actions may not start (billing).
