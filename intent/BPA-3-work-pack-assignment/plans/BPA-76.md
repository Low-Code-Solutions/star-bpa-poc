# Plan: BPA-76 PPC Team can assign projects to a user
Jira: BPA-76 (Story, epic BPA-3)
Story last updated: 2026-11-24T14:25:25.588+0400
Status: Approved (Ahsan, 24 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-76-assign-projects-to-user, from main 6f25090

> Checked read-only: the branch `BPA-76-assign-projects-to-user` is at **6f25090** (the merge of PR #66, BPA-75), with no commits of its own and a clean tree (only the untracked `.postman/` and `postman/`, which stay out).

## Context
`user_project` was built in BPA-48 and is written today by one writer only: the S22 persona sync, from a `projects:` list in `personas.yaml` (timekeeper PRJ-139, production-supervisor PRJ-139, cost-controller PRJ-140, project-manager PRJ-139 and PRJ-140; the other seven personas, `project-engineer` among them, have none). Nothing reads it yet and nobody can change it: there is no `/api/v1/users`, no `/api/v1/projects` and no screen. BPA-76 makes the PPC Team the second writer, through the portal, as ADR 0005 and `docs/03-sor-matrix.md` already foresee. BPA-77 to BPA-79 then use the rows to scope what a user sees; **BPA-76 scopes nothing** (BPA-72 and BPA-73 are deliberately unscoped until BPA-79).

Decision 11 Nov 2026 (Khitaba for the PO, Jira comment 10519): a user may have several projects; this changes BPA-44.

## Readiness check (Definition of Ready, docs/02, against Jira 24 Nov 14:25)
| Check | State |
|---|---|
| Jira status | **In Progress** (`statuscategorychangedate` = `updated` = 24 Nov 14:25). The one comment (11 Nov) says "Faraz to move them to Ready"; whether that Ready step was recorded is a human gap. Any other transition only when you say so (Q5) |
| Template, persona, outcome | Met (PPC Team member; supervisor and the other personas as the 403 case) |
| Gherkin incl. unhappy paths | Met: 5 scenarios, 3 of them unhappy |
| BPMN element IDs exist | n/a: BPMN Reference says "n/a (portal administration, no BPMN element)". No model change |
| DMN tables complete | n/a: no decision |
| Every field has a named SOR | Met (SOR Notes: `user_project` POC database ASSUMPTION; `project_ref` D365 F&O Project Management; `username` Camunda identity) |
| No open question or contradiction blocks | Met: Q1 to Q3 are design choices, now resolved; Q4 and Q5 are write-backs |
| Estimate ≤ 8 | 3 |
| Epic `intent.md` and `spec.md` accepted by the PO | **Met** — BPA-76's epic is **Epic 1 (BPA-3)**, whose `spec.md` is Accepted (Faraz, 2 Nov 2026). (Epic 2 stories carry this as a gap; this one does not.) |
| PO approved it as Ready | Human gap, see the status row |

## What already exists
**Data** (`V2__epic2_timesheet_to_progress.sql` :472-490; `docs/data-model.md` §13.7):
- `user_project (id uuid PK, username text NOT NULL FK → app_user(username), project_ref text NOT NULL FK → project_job_code(project_ref), + 4 audit columns, UNIQUE (username, project_ref))`. **No table change.**
- `app_user (username PK, employee_ref, email, + audit)` holds exactly the **11 personas**; `bpa-service` is a Camunda user and is **not** an `app_user`, so no technical user has to be hidden.
- `project_job_code` has `UNIQUE (project_ref)` and the seed holds **PRJ-139 and PRJ-140 only**. **PRJ-142 does not exist anywhere in the repo**, so the FK would refuse it: the tests insert it as a fixture.

**The sync** (`config/PersonaSync.syncUserProjects` :147-164): writes a persona's projects **only while that user has no `user_project` row**, so a project the PPC Team removes in the portal is not brought back; if *every* project of a user is removed, the next start seeds that user again (ADR 0005, ASSUMPTION; `PersonaSyncTest.BPA48_personaSyncDoesNotBringBackAProjectRemovedInThePortal` pins it).

**Security**: group-restricted paths are declared in `config/SecurityConfig` with `.requestMatchers("…").hasAuthority(PPC_TEAM)` for **every** method (the BPA-10 lesson), e.g. `/api/v1/fms-hours-exports/**` :103 and `/api/v1/completion-reports/**` :105. The 403 body is a problem detail, "Your groups do not allow this call, or the CSRF token is missing".

**Groups**: `adapter/CamundaGroupClient.usernames(groupId)` lists a group's members and needs **GROUP READ** per group in `PersonaSync.SERVICE_PERMISSIONS`, or Camunda answers an empty list. Seven of the eleven groups have it today (workpack-owner, project-engineer, cost-controller, project-manager, timekeeper, production-supervisor, ppc-team).

**Controllers**: `api/WorkPackController` (BPA-72) is the read-plus-write pattern: a generated interface, a record mapper, and refusals thrown inline as `ErrorResponseException` with a `ProblemDetail`. `api/DigitalTimesheetLineController` adds POST 201 and DELETE 204 and reads the session user with `SecurityContextHolder.getContext().getAuthentication().getName()` (D63). `persistence/CbsReviewRepository` is the house pattern for a **user-initiated** write with `audit_log` rows carrying that username (D64). A duplicate is refused with **400** in this repo (`HourLineService.duplicate`), not 409.

**Frontend**: `routes/ProtectedRoute` already accepts `groups={[…]}` but **no route uses it yet**; `components/layout/Sidebar` has no group check and takes no props; group gating elsewhere is **prop-based** (`AssignmentFileUpload({ groups })`, `TaskList`), with `useAuth()` read once in the page or layout. `useAuth()` **throws** outside an `AuthProvider`, and `Sidebar.test.tsx` renders `<Sidebar />` without one — so the Sidebar must receive `groups` as a prop, not call `useAuth` (see Risks). Services call `apiClient` (openapi-fetch) and map a refusal with `failed(status, error)` → `{ ok: false, status, detail }`, shown as `result.detail ?? 'fallback (status)'`.

## Design
### 1. Contract first (`api/openapi.yaml`), tag `users` → generated `UsersApi`
| Operation | Path | Answer |
|---|---|---|
| `listPortalUsers` | `GET /api/v1/users` | 200 `[PortalUser]`, by username |
| `listProjects` | `GET /api/v1/projects` | 200 `[Project]`, by projectRef |
| `listUserProjects` | `GET /api/v1/users/{username}/projects` | 200 `[Project]`; 404 |
| `assignProject` | `POST /api/v1/users/{username}/projects` | 201 `UserProject`; 400; 404 |
| `unassignProject` | `DELETE /api/v1/users/{username}/projects/{projectRef}` | 204; 404 |

Schemas: `PortalUser { username, employeeRef, email, groups[] }`, `Project { projectRef }`, `NewUserProject { projectRef }` (request), `UserProject { username, projectRef }`.

**Exact messages** (Jira):
- unknown project → **400** `"Project PRJ-999 does not exist"`, nothing saved;
- duplicate → **400** `"PRJ-139 is already assigned to project-engineer"` (400, not 409, follows `HourLineService.duplicate`);
- unknown user → **404** `"User not found"` (**ASSUMPTION**, Jira gives no text);
- unassigning a row that is not there → **404** `"PRJ-140 is not assigned to project-engineer"` (**ASSUMPTION**, Q2);
- `GET /api/v1/users` when Camunda cannot answer the group reads → **503** (the existing `EngineUnavailableException` path).

All five paths are **ppc-team only**: two `SecurityConfig` matchers, `"/api/v1/users", "/api/v1/users/**"` and `"/api/v1/projects"`, `hasAuthority(PPC_TEAM)`, every method, declared before the broader rules.

### 2. Backend (no new package; `api` + `persistence`, 09 S23)
- **New `api/UserController implements UsersApi`.** Holds the two existence checks and the message texts, throwing `ErrorResponseException` as `WorkPackController` does; reads the session user for the audit rows.
- **New `persistence/UserProjectRepository`**: `projectsOf(username)`, `assign(username, projectRef, by, at)` (insert `ON CONFLICT (username, project_ref) DO NOTHING`; a conflict means the duplicate 400, which is also the database backstop for two clicks at once), `unassign(...)` returning whether a row went, and `exists(...)`. `@Transactional`; **one `audit_log` row per business column** (`username`, `project_ref`) per assignment and per unassignment, `table_name 'user_project'`, `username` = the ppc-team session user, `old_value` null on insert and `new_value` null on delete (D64, data model §6.1).
- **New `persistence/AppUserRepository`**: `all()` (username, employeeRef, email) and `exists(username)`.
- **Changed `persistence/ReferenceRepository`**: one new `projects()` reading `project_job_code` (reuse, no new repository for one query).
- **Groups, as decided:** `UserController` reads the members of each of the eleven Camunda groups through `CamundaGroupClient.usernames` and inverts them onto the users. **`PersonaSync` gains GROUP READ on the four groups that lack it** — `time-method-analyst`, `production-planner`, `supervisor`, `production-team` — so no group silently comes back empty. `PersonaSyncTest.expectedServicePermissions` gains the same four (an existing-test edit, said in the PR).

### 3. Screen (ppc-team only)
- **New page `frontend/src/pages/UsersPage`** ("Users and projects"), route `users` in `routes/AppRoutes.tsx` wrapped in `<ProtectedRoute groups={['ppc-team']}>` — the first use of that existing mechanism.
- **New feature folder `frontend/src/features/user-projects/`** with `components/UserProjects`, `hooks/useUsers.ts`, `hooks/useUserProjects.ts`, `services/userProjectService.ts`, `user-projects.types.ts` and an `index.ts` barrel (docs/04 Frontend conventions). The page picks a user, lists their projects, and offers a project drop-down plus an Assign button and an Unassign button per row, built from `Field`, `Button`, `ErrorMessage` and `Spinner`, following `BookHours` and `WorkPackList`.
- **Refusals** are shown with the backend's `detail` (`result.detail ?? 'The project could not be assigned (<status>)'`), as `BookHours` does.
- **Last-project note (Q1, resolved):** when the user being edited has exactly one project, the screen says that removing it will let a restart seed that user's `personas.yaml` projects again (ADR 0005). It is a note, not a rule: the unassign still succeeds.
- **Sidebar**: `MainLayout` reads `useAuth()` and passes `groups` to `Sidebar`; `Sidebar` shows "Users and projects" only for ppc-team. `Sidebar.test.tsx` gains the prop (an existing-test edit, said in the PR). The Sidebar must **not** call `useAuth` itself (Risks).

### 4. Docs
- `docs/data-model.md` §13.7: an "As built by BPA-76" note (who writes the rows now, the two messages, the audit rows, the restart behaviour).
- No ADR change: ADR 0005 already records the portal as a writer and the restart behaviour.
- No migration, no model change, no seed change.

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml` (tag `users`: 5 operations, 4 schemas) |
| Backend, new | `api/UserController.java`, `persistence/UserProjectRepository.java`, `persistence/AppUserRepository.java` |
| Backend, changed | `persistence/ReferenceRepository.java` (`projects()`), `config/SecurityConfig.java` (2 matchers), `config/PersonaSync.java` (4 GROUP READ) |
| Backend tests | new `UserProjectApiTest.java`; `PersonaSyncTest.java` (existing-test edit) |
| Frontend, new | `features/user-projects/**`, `pages/UsersPage/**` |
| Frontend, changed | `routes/AppRoutes.tsx`, `layouts/MainLayout/MainLayout.tsx`, `components/layout/Sidebar/Sidebar.tsx`, `components/layout/Sidebar/Sidebar.test.tsx` (existing-test edit), `types/api.ts` |
| Docs | `docs/data-model.md`; this plan; `REVIEW.md`; `intent/README.md` regenerated |

## Order of work
1. Approve the plan and commit it (`Approved`) as the branch's first commit.
2. Contract first: the five operations and four schemas in `api/openapi.yaml` (needed to compile the generated interface and to generate `schema.d.ts`).
3. Write the tests, run them **red before any production code** (compile stubs only), commit with the red output in the message.
4. Build: repositories, controller, `SecurityConfig`, `PersonaSync`, then the React feature, page, route and Sidebar. Green, then the neighbours (`PersonaSyncTest`, `WorkPackListApiTest`, `AuthApiTest`, `Sidebar`, `WorkPacksPage`, `ProtectedRoute`).
5. Data model note; full `./mvnw -B verify`; `npm test`, `npm run lint`, `npm run format:check` in `frontend/`; the local check; `qa-reviewer`; fixes (red first where behaviour changes); REVIEW.md; plan Closed. Push, PR and Jira comments only when you ask.

## Scenario-to-test table
**Backend `UserProjectApiTest`** (integration: per-class Postgres, shared Camunda for login and the group reads, `AssignmentFlow.loggedInAs`), and **React `UsersPage.test.tsx`** (stubbed `fetch`, the `fakeBackend(groups)` shape of `WorkPacksPage.test.tsx`). The same five names on both sides, as BPA-73 did.

**Background (each backend test):** `project_job_code` gains **PRJ-142** (not seeded); `user_project` rows of `project-engineer` cleared; the seeded rows of the four personas left alone and restored by `@AfterEach`.

| # | Scenario | Test | Arrange / act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | Assign two projects to a user | `BPA76_assignTwoProjectsToUser` | ppc-team POSTs PRJ-139, then PRJ-142, to project-engineer | 201 twice; `GET /users/project-engineer/projects` = PRJ-139, PRJ-142; an `audit_log` row per business column for each assignment, `username` = ppc-team | **red** (404: no such path) |
| 2 | Unassign a project | `BPA76_unassignAProject` | both assigned, then DELETE PRJ-142 | 204; the list is PRJ-139 only; audit rows for the delete (`new_value` null) | **red** |
| 3 | Unknown project is rejected | `BPA76_unknownProjectIsRejected` | ppc-team POSTs PRJ-999 | 400, detail "Project PRJ-999 does not exist"; project-engineer still has no row | **red** |
| 4 | Duplicate assignment is rejected | `BPA76_duplicateAssignmentIsRejected` | PRJ-139 assigned, POST PRJ-139 again | 400, detail "PRJ-139 is already assigned to project-engineer"; still exactly one row | **red** |
| 5 | Other personas cannot assign projects | `BPA76_otherPersonasCannotAssignProjects` | supervisor POSTs PRJ-139 to project-engineer; supervisor GETs `/api/v1/users` | 403 both times; nothing saved | **red** (no path, so not a 403 yet) |

**React, the same five names:** 1 pick project-engineer, assign both, the list shows both and the POST bodies are asserted; 2 unassign removes the row; 3 and 4 the backend's 400 `detail` is shown on the screen (the drop-down only offers real projects, so these stub the refusal); 5 as `supervisor` the Sidebar has no "Users and projects" link and `/users` renders nothing.

**Extras (not scenarios):**
- `anUnknownUserIsNotFound` (GET, POST and DELETE for a username with no `app_user` row → 404 "User not found").
- `unassigningAProjectThatIsNotAssignedIsNotFound` (Q2 text, ASSUMPTION).
- `ppcTeamCanManageItsOwnProjects` (resolved Q3: no special case).
- `theUsersListGivesTheElevenPersonasWithTheirGroups` (groups read from Camunda; guards the four new GROUP READ grants).
- `theProjectsListGivesTheProjectsThatCanBeAssigned` (PRJ-139, PRJ-140 and the fixture's PRJ-142).
- React: `theLastProjectWarningIsShown`.
- `PersonaSyncTest.expectedServicePermissions` gains the four grants (existing-test edit).

`grep -c "void BPA76_"` = 5 in Java and 5 in React; all ten are red first.

## Proof (one per Definition of Done line, docs/02)
| DoD line | Evidence |
|---|---|
| Merged to main through a reviewed PR referencing the key | `gh pr view <n> --json state,mergedAt` |
| One automated test per Gherkin scenario, all passing in CI | `./mvnw -B -Dtest=UserProjectApiTest verify`; `npm test` in `frontend/`; `grep -c "void BPA76_"` = 5 and the React count = 5; full `./mvnw -B verify`; `gh pr checks <n>` (Actions may not start: billing) |
| Process runs end to end in the local Camunda stack | n/a: no BPMN. Instead, rebuild `bpa-app` and `frontend`, then **in the portal as ppc-team**: (1) assign **PRJ-140 to project-engineer**, `docker compose restart bpa-app`, log in again and confirm the assignment is still there; (2) unassign **PRJ-139 from timekeeper** (their only project), restart, and confirm PRJ-139 **is back** — the documented ADR 0005 behaviour the screen warns about; restore both afterwards |
| BPMN and DMN files match deployed | No model change (`git diff --stat models/` empty) |
| Open questions resolved or logged | the Jira comment (shown first, posted on your yes) |
| Plan matches the merged diff and is `Closed` | `grep '^Status: Closed' intent/BPA-3-work-pack-assignment/plans/BPA-76.md` |
| Demoed at sprint review | Human gap |

## Risks
- **`useAuth()` throws outside an `AuthProvider`**, and `Sidebar.test.tsx` renders the Sidebar bare. Putting `useAuth` in the Sidebar would break that test; the Sidebar therefore takes `groups` as a prop from `MainLayout`, as `AssignmentFileUpload` does. The test edit is one line.
- **Eleven Camunda group reads per `GET /api/v1/users`.** Acceptable for a POC admin screen with eleven users; if a group read fails the call answers 503 rather than showing a user with no groups.
- **Four new GROUP READ grants** widen what `bpa-service` may read. They are the same grant seven groups already carry, and `PersonaSyncTest` pins the full list.
- **`project-engineer` has no `projects:` in `personas.yaml`**, so part 1 of the local check cannot show re-seeding; part 2 (timekeeper) does.
- **First use of `ProtectedRoute groups={…}`**: a user outside ppc-team gets a blank page at `/users`, not a message (existing behaviour, `ProtectedRoute` returns `null`). The Sidebar simply does not offer the link.

## Questions
1. **Epic 1 `spec.md` shared-contract row** says `personas.yaml` and the sync are the "Only writer of Camunda users, groups and `app_user`… seven personas, no scope (BPA-44)". That is stale: there are eleven personas, and from this story the portal is a second writer of `user_project`. ADR 0005 and `docs/03-sor-matrix.md` already record it.
   - **Recommended:** leave `spec.md` alone in this story (it is PO-accepted; the change is recorded in the ADR and the SOR matrix) and note the staleness in the Jira comment.
   - Alternative: update that row here, in this PR.
2. **Unassigning a row that is not there.** Jira is silent.
   - **Recommended:** 404 "PRJ-140 is not assigned to project-engineer" (ASSUMPTION), so a stale screen says something useful.
   - Alternative: 204, idempotent.
3. **The POST answer.** Recommended: 201 with `{ username, projectRef }`. Jira does not say; the screen reloads the list either way.
4. **The Jira comment** (brief Q3): Jira carries no Definition of Ready or Done and no API or screen spec (the prototype screens are in Pega, not the repo), so the API shape, the screen and five texts are ours. The comment lists them for Khitaba and Faraz: the five paths and the ppc-team-only rule; the two Jira texts used verbatim; the three ASSUMPTION texts ("User not found", the not-assigned 404, the 503); the audit rows per business column; the last-project behaviour (ADR 0005) and the screen's note; that BPA-76 scopes nothing until BPA-79; and the stale `spec.md` row from Q1. Shown to you first, posted on your yes.
5. **Jira:** BPA-76 is In Progress. I ask before any transition or comment.
6. **ASSUMPTIONs:** the three texts above; PRJ-142 is a test fixture, not a seed; `GET /api/v1/projects` is ppc-team only for now (BPA-77 to BPA-79 may widen it); the users list shows all eleven `app_user` rows, ppc-team included.

## Resolutions (Ahsan, 24 Nov 2026, in plan mode)
- **Groups in the users list:** read from Camunda, with the four missing GROUP READ grants added to `PersonaSync` (not derived from the username).
- **Last project:** keep the documented ADR 0005 behaviour and warn on the screen; no backend rule, no ADR change.
- **ppc-team itself:** no special case; every `app_user` is listed and editable.
- **Q1 (Epic 1 `spec.md`):** leave the PO-accepted file alone in this story; the Jira comment notes the stale row.
- **Q2 (unassigning a row that is not there):** 404 "PRJ-140 is not assigned to project-engineer" (ASSUMPTION).
- **Q3 (the POST answer):** 201 with `{ username, projectRef }`.
- **Q4, Q5:** the Jira comment is shown first and posted only on a yes; I ask before any Jira transition.

## Human-gap log
- **DoR:** whether the PO's move to Ready was recorded (the 11 Nov comment says Faraz would do it; the item is In Progress since 24 Nov).
- **DoD:** "Demoed at sprint review". People.
- **DoD:** "passing in CI". Actions may not start (billing).
- **The local check** is a person clicking the portal as ppc-team (assign, restart, unassign the last project, restart, restore).
