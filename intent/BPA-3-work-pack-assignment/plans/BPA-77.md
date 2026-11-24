# Plan: BPA-77 Portal user can see master data only for their assigned projects
Jira: BPA-77 (Story, epic BPA-3)
Story last updated: 2026-11-24T16:55:28.013+0400
Status: Approved (Ahsan, 24 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-77-master-data-by-project, from main 1ed07bb

> Checked read-only: `BPA-77-master-data-by-project` is at **1ed07bb** (the merge of PR #67, BPA-76), no commits of its own, clean tree (only the untracked `.postman/` and `postman/`, which stay out).

## Context
BPA-76 lets the PPC Team assign projects to users (`user_project`), but nothing reads those rows yet. BPA-77 is the first reader: every portal user sees the master data of **their own** projects (Activities, Cost structure, Drawings, Reserved materials, Cutting sheets) on a new "Projects and master data" screen, so Work Packs can be prepared from the right data before Epic 1 starts. It also builds the **one shared project-scope check** that BPA-78 (Work Pack creation) and BPA-79 (Work Pack list and detail) will reuse.

Decision 11 Nov 2026 (Khitaba for the PO): a user may have several projects; this changes BPA-44. Comment 10514 proposes "all 11 personas" in the SOR Notes (the field already says 11).

## Readiness check (Definition of Ready, docs/02, against Jira 24 Nov 16:55)
| Check | State |
|---|---|
| Jira status | **In Progress** (`statuscategorychangedate` = `updated` = 24 Nov 16:55). The Development panel shows one **merged** PR (lastUpdated 24 Nov 16:55): BPA-76's PR #67, which names BPA-77 in its text; nothing is open for BPA-77. Any transition only when you say so (Q5) |
| Template, persona, outcome | Met (portal user; four personas in the Gherkin) |
| Gherkin incl. unhappy paths | Met: 4 scenarios (a 403 and an empty state) |
| BPMN element IDs | n/a: "portal screen before Process_PPC_WorkPackAssignment starts" |
| DMN | n/a |
| Every field has a named SOR | Met in the SOR Notes. **But** the data model has no route from a drawing to a project (see below); resolved by Q2 (V8) |
| No blocking question | Q2 was blocking; resolved in plan mode (Resolutions) |
| Estimate ≤ 8 | 3 (the V8 migration and five tabs make it a full 3) |
| Epic `intent.md` / `spec.md` accepted | **Met**: Epic 1 spec Accepted (Faraz, 2 Nov 2026) |
| PO approved as Ready | Human gap (the 11 Nov comment says Faraz would move BPA-76 to 79 to Ready; not seen in the history read) |

## What already exists
**How each tab reaches a project** (V1; `docs/data-model.md` §3; SOR per `docs/03-sor-matrix.md`):

| Tab | Table(s) | Join to a project | Real SOR (03 matrix) |
|---|---|---|---|
| Activities | `activity` | `activity.wbs_ref → wbs.project_ref` | P6, read through the D365 PMS database |
| Cost structure | `cbs_activity` (activity → CBS) | `cbs_activity.activity_ref → activity → wbs.project_ref` | D365 F&O Cost Control Report |
| Drawings | `drawing`, `drawing_revision` | **none**: `drawing` is `(drawing_ref, title)` only | D365 F&O Document Control |
| Reserved materials | `material_reservation` (+ `material` for the description) | **none of its own**: `drawing_ref` only | D365 F&O (PPC) |
| Cutting sheets | `cutting_sheet` | **none of its own**: `drawing_ref` only | FMS nesting |

The only existing link from a drawing to a project is a **Work Pack** (`workpack.drawing_ref` + `activity_ref`), which is transactional and does not exist "before Epic 1 starts". → **Q2, resolved: V8 adds `drawing.project_ref`.**

Seed today: PRJ-139 (WBS 11031, 11032; activities A-2201 to A-2205, A-2209, A-2299, A-2301; all six `cbs_activity` rows), PRJ-140 (WBS 14001; activities A-2206 to A-2208; **no** `cbs_activity` row, so its Cost structure tab is empty). Drawings DWG-118, 119, 121, 130, 140, 141, 150, 151; 5 reservations (DWG-118 ×2, 119, 130, 140); 3 cutting sheets (DWG-118, 130, 140). **PRJ-142 does not exist**: the tests add it with its own master data.

**BPA-76 (closed plan, history):** `user_project`, `UserProjectRepository.projectsOf(username)`, `GET /api/v1/projects` (ppc-team only, **exact** path matcher `"/api/v1/projects"` in `SecurityConfig` :105), `Project { projectRef }` schema, the "Users and projects" page, the Sidebar `groups` prop, the persona sync re-seeding (timekeeper PRJ-139, production-supervisor PRJ-139, cost-controller PRJ-140, project-manager PRJ-139 + PRJ-140; the other seven have none).

**Reference lists (BPA-6, `ReferenceController`/`ReferenceRepository`):** `GET /api/v1/activities`, `/drawings`, `/materials`, `/departments`, open to every logged-in user, unscoped, used by the Create Work Pack screen. `Activity` and `Drawing` schemas exist and are reused here.

**Frontend:** BPA-73's `WorkPackDetail` tabs (`role="tablist"`, `aria-controls` on the selected tab) are the tab pattern; BPA-72/76 page tests render `<App />` with a stubbed `fetch`.

## Design
### 1. One shared project-scope check (for BPA-77, 78 and 79)
- **New `domain/ProjectScope`** (`@Component`, over `UserProjectRepository`):
  - `List<String> projectsOf(username)`: the user's projects, by reference (empty allowed).
  - `boolean allows(username, projectRef)`: a `user_project` row (username, projectRef) exists. For BPA-79's list filtering.
  - `void require(username, projectRef)`: throws `ProjectNotAssignedException` when not allowed. Controllers map it to **403** with "Project <ref> is not assigned to you" (**ASSUMPTION**: Jira gives only the 403).
- **One rule for all eleven personas, ppc-team included** (Q3 resolved): no group exception. An unknown project (no row can exist) is the same 403, so the answer does not reveal whether a project exists (ASSUMPTION).
- `UserProjectRepository` gains `exists(username, projectRef)`.

### 2. Contract first (`api/openapi.yaml`), read-only, every logged-in group
| Operation | Path | Answer |
|---|---|---|
| `listMyProjects` | `GET /api/v1/me/projects` | 200 `[Project]` (the session user's; empty allowed) |
| `getProjectMasterData` | `GET /api/v1/projects/{projectRef}/master-data` | 200 `ProjectMasterData`; 403 not assigned |

**One combined endpoint** (Q1 resolved). `ProjectMasterData { projectRef, activities, costStructure, drawings, reservedMaterials, cuttingSheets }`, each tab `{ systemOfRecord, rows }`:
- `activities.rows`: the existing `Activity` schema (by `activityRef`).
- `costStructure.rows`: new `CbsActivity { activityRef, activityName, cbsCodeRef }`.
- `drawings.rows`: new `ProjectDrawing { drawingRef, title, latestRevision, status }` (latest revision by `registered_at`, D42; absent when no revision).
- `reservedMaterials.rows`: new `ReservedMaterial { traceabilityNumber, drawingRef, materialRef, description }`.
- `cuttingSheets.rows`: new `CuttingSheet { cuttingSheetRef, drawingRef }`.
- `systemOfRecord` is the 03 matrix text verbatim, per the table above (the scenario "each tab names its system of record").

**`/api/v1/projects` stays ppc-team only** (Q4): its `SecurityConfig` matcher is the exact path, so `/api/v1/projects/{ref}/master-data` falls to `authenticated()`. An **explicit** `.requestMatchers("/api/v1/projects/*/master-data", "/api/v1/me/projects").authenticated()` is added **before** the ppc-team line, so a later widening of that line to `/**` cannot lock users out.

**The existing reference lists stay as they are** (Q1 resolved): `/api/v1/activities`, `/drawings`, `/materials`, `/departments` are not scoped here. **Scoping Work Pack creation is BPA-78's**, using `ProjectScope`; BPA-77 does not touch `ReferenceController`, `ReferenceRepository`'s existing methods or the Create Work Pack screen.

### 3. V8: `drawing.project_ref` (Q2 resolved)
- `V8__drawing_project.sql`: `ALTER TABLE bpa.drawing ADD COLUMN project_ref text NULL REFERENCES bpa.project_job_code (project_ref)`; `COMMENT ON COLUMN … IS 'D365 F&O Project Management'` (A12: an FK column carries the SOR of the entity it identifies).
- **Nullable**: tests that insert drawings (`Bpa48MigrationTest`, fixtures) keep working; a drawing with no project belongs to no project and shows in no project's tabs (ASSUMPTION).
- `R__seed_reference_data.sql`: the drawing upsert sets `project_ref` (**ASSUMPTION**, no source gives it): DWG-118, 119, 121, 130 → PRJ-139 (DWG-118 is WP-000457's drawing on A-2201, PRJ-139); DWG-140, 141 → PRJ-140 (by number); DWG-150, 151 → none.
- Reservations and cutting sheets reach a project **through their drawing**.
- `docs/data-model.md` §3.7 gains the column; `docs/03-sor-matrix.md` drawing row gets the key field; an "As built by BPA-77" note. `FlywayBaselineTest`'s list gains "drawing project" (existing-test edit, said in the PR).

### 4. Backend
- **New `api/ProjectMasterDataController`** implementing the two operations; reads the session user (D63), calls `ProjectScope.require`, and maps `ProjectNotAssignedException` to the 403.
- **New `persistence/ProjectMasterDataRepository`**: five read queries, each filtered by the project (the joins above), ordered by their reference (ASSUMPTION; byte order `COLLATE "C"`, as BPA-76).
- No change to `ReferenceController` or the existing `ReferenceRepository` methods.

### 5. Screen "Projects and master data" (every persona)
- **New page `pages/ProjectsPage`**, route `projects` in `AppRoutes` (no group restriction), and a Sidebar link "Projects and master data" for **every** logged-in group (beside Work Packs).
- **New feature `features/projects/`** (`components/ProjectMasterData`, `hooks/useMyProjects.ts`, `hooks/useProjectMasterData.ts`, `services/projectService.ts`, `projects.types.ts`, `index.ts`).
- A **Project** picker offering only my projects (the first one selected); five tabs **Activities, Cost structure, Drawings, Reserved materials, Cutting sheets** (the BPA-73 tab pattern), each showing **"System of record: <text>"** and a table, or "No rows" when the project has none.
- No projects → **"No projects assigned. Ask the PPC Team."** (Jira's text).
- A 403 (a stale project) shows the backend's text.

## Files that change
| Area | Files |
|---|---|
| Migration and seed | new `V8__drawing_project.sql`; `R__seed_reference_data.sql` (drawing `project_ref`) |
| Contract | `api/openapi.yaml` (2 operations; `ProjectMasterData`, tab wrappers, `CbsActivity`, `ProjectDrawing`, `ReservedMaterial`, `CuttingSheet`) |
| Backend, new | `domain/ProjectScope.java`, `domain/ProjectNotAssignedException.java`, `api/ProjectMasterDataController.java`, `persistence/ProjectMasterDataRepository.java` |
| Backend, changed | `persistence/UserProjectRepository.java` (`exists`), `config/SecurityConfig.java` (explicit authenticated matcher) |
| Backend tests | new `ProjectMasterDataApiTest.java`; `FlywayBaselineTest.java` (existing-test edit) |
| Frontend, new | `features/projects/**`, `pages/ProjectsPage/**` |
| Frontend, changed | `routes/AppRoutes.tsx`, `components/layout/Sidebar/Sidebar.tsx` (+ a test), `types/api.ts` |
| Docs | `docs/data-model.md` (§3.7 and a note), `docs/03-sor-matrix.md` (drawing row); plan; `REVIEW.md`; `intent/README.md` |

## Order of work
1. Approve and commit the plan (`Approved`).
2. Contract and V8 (both needed to compile and to seed); then the tests, **red before any production code**; commit with the red output.
3. Build: `ProjectScope`, the repository, the controller, the security matcher, then the React feature, page, route and Sidebar link. Green; neighbours (`UserProjectApiTest`, `PersonaSyncTest`, `FlywayBaselineTest`, `Bpa48MigrationTest`, `WorkPackApiTest` (the only test that calls the reference lists), Sidebar, UsersPage, WorkPacksPage).
4. Data model and SOR matrix notes; full `./mvnw -B verify`; full `npm test`, lint, format, build; the local check; `qa-reviewer`; fixes (red first); REVIEW.md; plan Closed. Push, PR and Jira only when you ask.

## Scenario-to-test table
**Backend `ProjectMasterDataApiTest`** (per-class Postgres, shared Camunda for login) and **React `ProjectsPage.test.tsx`** (stubbed `fetch`, `<App />` at `/projects`). The same four names on both sides.

**Background (backend):** the eleven `app_user` rows (`RevisionFlow.seedAppUsers`); `user_project` cleared, then the rows each test needs; **PRJ-142** with its own master data: `project_job_code` PRJ-142, WBS 14201, activity A-4201, `cbs_activity` (A-4201, CBS-142-PIP-01), drawing DWG-1420 (`project_ref` PRJ-142) with revision A, reservation TR-1420 (DWG-1420, MAT-PIPE-06), cutting sheet CS-1420 (DWG-1420).

| # | Scenario | Test | Arrange / act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | User sees only assigned projects | `BPA77_userSeesOnlyAssignedProjects` | project-engineer has PRJ-139 only; GET `/api/v1/me/projects` | `[PRJ-139]`; not PRJ-142 | **red** (404: no path) |
| 2 | User with several projects switches project | `BPA77_userWithSeveralProjectsSwitchesProject` | time-method-analyst has PRJ-139 and PRJ-142; GET `/api/v1/projects/PRJ-142/master-data` | 200; each of the five tabs holds PRJ-142's fixture rows **and none of PRJ-139's**; each tab's `systemOfRecord` is the 03 matrix text | **red** |
| 3 | Unassigned project is refused | `BPA77_unassignedProjectIsRefused` | project-engineer has PRJ-139 only; GET `/api/v1/projects/PRJ-142/master-data` | 403 | **red** (404 now) |
| 4 | User with no projects sees an empty state | `BPA77_userWithNoProjectsSeesEmptyState` | supervisor has none; GET `/api/v1/me/projects` | 200 `[]` | **red** |

**React, the same four names:** 1 the Project picker offers PRJ-139 only; 2 select PRJ-142, each of the five tabs shows PRJ-142's rows and "System of record: …"; 3 a 403 from the backend shows its text and no tab data; 4 "No projects assigned. Ask the PPC Team.".

**Extras:**
- `ppcTeamSeesOnlyItsOwnProjects` (Q3: no group exception; ppc-team with no rows gets `[]` and a 403 for PRJ-139).
- `anUnknownProjectIsRefusedLikeAnUnassignedOne` (PRJ-999 → 403, same text).
- `aDrawingWithNoProjectIsInNoProject` (DWG-150 appears in no project's Drawings tab).
- `theSeededProjectsSplitTheDrawings` (PRJ-139 has DWG-118/119/121/130 with its reservations and cutting sheets; PRJ-140 has DWG-140/141; PRJ-140's Cost structure is empty).
- `theReferenceListsAreUnchanged` (`GET /api/v1/drawings` still lists all eight drawings: a guard for the BPA-78 split).
- `everyPersonaCanReadItsOwnProjects` (all eleven get 200 on `/me/projects`).
- `ProjectScopeTest` (unit: `allows`, `require`).
- React: a Sidebar test (the link for any group); `aStaleProjectShowsTheRefusal`.
- `FlywayBaselineTest` (V8 in the list; existing-test edit).

`grep -c "void BPA77_"` = 4 in Java, 4 `BPA77_` in React; all eight red first.

## Proof (one per Definition of Done line, docs/02)
| DoD line | Evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=ProjectMasterDataApiTest verify`; `npm test`; `grep -c "void BPA77_"` = 4 and React = 4; full `./mvnw -B verify`; `gh pr checks <n>` (Actions may not start: billing) |
| Process runs end to end locally | n/a (no BPMN). Instead, rebuild `bpa-app` and `frontend` (V8 applies at start), then **as cost-controller** (PRJ-140 only, from `personas.yaml`): the Project picker offers PRJ-140 only, not PRJ-139; its tabs show A-2206 to A-2208, DWG-140/141 with TR-5535 and CS-3312, an empty Cost structure; `GET /api/v1/projects/PRJ-139/master-data` answers **403**; as supervisor the empty-state text |
| BPMN and DMN match deployed | no model change (`git diff --stat models/` empty) |
| Open questions resolved or logged | the Jira comment (shown first, posted on your yes) |
| Plan Closed | `grep '^Status: Closed' intent/BPA-3-work-pack-assignment/plans/BPA-77.md` |
| Demoed at sprint review | Human gap |

## Risks
- **V8 changes a V1 table** (`drawing`). The column is nullable, so existing inserts stand; the seed is repeatable, so a running stack picks up `project_ref` at the next start. `FlywayBaselineTest` pins the migration list.
- **The drawing-to-project mapping is invented** (ASSUMPTION): DWG-150/151 belong to no project. The Jira comment names it for Khitaba.
- **The `/api/v1/projects` matcher**: an exact path today. The explicit authenticated matcher for `/api/v1/projects/*/master-data` keeps the new endpoint open if someone widens the ppc-team rule.
- **BPA-78 and BPA-79 depend on `ProjectScope`'s shape**; its three methods are the contract they reuse.
- **Seeded personas**: cost-controller (PRJ-140) and project-manager (both) see data on a fresh stack; the other seven see the empty state until the PPC Team assigns them (BPA-76).

## Questions
1. **New per-project endpoints vs scoping the existing reference lists.** Resolved: new endpoints; `/activities`, `/drawings`, `/materials`, `/departments` unchanged; BPA-78 owns create-time scope with `ProjectScope`.
2. **How each table links to a project.** Resolved: Activities and Cost structure through WBS; **drawings had no path**, so V8 adds `drawing.project_ref` (nullable, FK) with a seeded mapping (ASSUMPTION); reservations and cutting sheets follow their drawing. Flagged: `material` (the master, not the reservation) and `department` have no project and are not tabs.
3. **Does ppc-team see all projects?** Resolved: no, only its own `user_project` rows, like every persona.
4. **Does `/api/v1/projects` stay ppc-team only?** Recommended: yes; `/api/v1/me/projects` is the general read.
5. **Jira:** BPA-77 is In Progress; the linked PR is BPA-76's (merged). I ask before any transition or comment.
6. **The Jira comment** (shown first, posted on your yes): the V8 column and the seeded drawing mapping (ASSUMPTION, DWG-150/151 in no project); the 403 text "Project <ref> is not assigned to you" (ASSUMPTION) and that an unknown project gets the same 403; the SOR texts per tab taken from the 03 matrix; that the existing reference lists stay unscoped until BPA-78; the SOR Notes already say "all 11 personas" (comment 10514).
7. **ASSUMPTIONs:** the 403 text; tab row order (by reference); the seeded drawing mapping; a drawing with no project is in no project; Cost structure rows show the activity with its CBS (one row per `cbs_activity`).

## Resolutions (Ahsan, 24 Nov 2026, in plan mode)
- **Q2 (drawing link):** V8 adds a nullable `drawing.project_ref` (FK to `project_job_code`), seeded by ASSUMPTION; reservations and cutting sheets follow their drawing.
- **Q3 (ppc-team):** own `user_project` rows only; one rule for all eleven personas.
- **Endpoints:** one combined `GET /api/v1/projects/{projectRef}/master-data`, plus `GET /api/v1/me/projects`.
- **Q4:** `GET /api/v1/projects` stays ppc-team only; `GET /api/v1/me/projects` is the general read.
- **Q5 to Q7:** as written; the Jira comment is shown first and posted only on a yes; I ask before any Jira transition.

## Human-gap log
- **DoR:** whether the PO's move to Ready was recorded.
- **DoD:** "Demoed at sprint review". People.
- **DoD:** "passing in CI". Actions may not start (billing).
- **The local check** in the portal (as cost-controller and supervisor) is a person's.
