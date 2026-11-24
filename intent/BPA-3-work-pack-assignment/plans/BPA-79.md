# Plan: BPA-79 Portal user can see only Work Packs of their assigned projects
Jira: BPA-79 (Story, epic BPA-3)
Story last updated: 2026-11-24T01:10:02.373+0400
Status: Approved (Ahsan, 25 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-79-workpacks-by-project, from main 847bbac (the merge of PR #69, BPA-78; it includes BPA-77, d203f37)

> Checked read-only: the branch is at 847bbac with no commits of its own and a clean tree (only the untracked `.postman/` and `postman/`, which stay out).

## Context
BPA-72 built the Work Packs list and BPA-73 the Work Pack detail, both deliberately **unscoped until BPA-79**: today every logged-in user sees every Work Pack. BPA-76 assigns projects to users, BPA-77 built the shared check `scope/ProjectScope`, and BPA-78 applied it to creating a Work Pack. BPA-79 applies it to **reading** Work Packs: the list shows only Work Packs of the caller's projects, and opening another project's Work Pack by its link is refused with 403.

Decision 11 Nov 2026 (Khitaba for the PO, comment 10522): BPA-76 to BPA-79 approved; a user may have several projects.

## Readiness check (Definition of Ready, docs/02, against Jira 24 Nov 01:10)
| Check | State |
|---|---|
| Jira status | **Ready** (not In Progress). The CLAUDE.md rule is Ready → In Progress when work starts: **I ask before moving it** (Q4) |
| Template, persona, outcome | Met (portal user; workpack-owner in the Gherkin) |
| Gherkin incl. unhappy path | Met: 2 scenarios (the 403) |
| BPMN element IDs | n/a ("portal list and detail") |
| Every field has an SOR | Met, with the same correction as BPA-78: the SOR Notes name **`workpack.project_ref`, which is not a column**; a Work Pack's project is its activity's (`activity.wbs_ref → wbs.project_ref`), which the list already reads |
| No blocking question | Q1 to Q3 resolved in plan mode |
| Estimate ≤ 8 | 2 |
| Epic `intent.md` / `spec.md` accepted | Met (Epic 1 spec Accepted, Faraz, 2 Nov 2026) |
| PO approved as Ready | Met: the status is Ready |

## What exists today
- **List** (`GET /api/v1/workpacks`, BPA-72): `WorkPackController.listWorkPacks` → `WorkPackListRepository.list(Filter(statuses, project, weekEnding, search))`. The SQL already joins activity → WBS and returns `b.project_ref`; the optional `project` filter is `AND b.project_ref = ?`. The contract and the controller javadoc say "not limited to the user's projects until BPA-79". The React `WorkPackList` builds its Project filter options from the rows it receives, so once the backend returns only the caller's Work Packs the filter offers only their projects, with no frontend change.
- **Detail** (BPA-73), three reads, each through `WorkPackController.known(number)` (400 outside WP-######, 404 "Work Pack not found"):
  1. `GET /api/v1/workpacks/{n}`: `getWorkPack`, the summary and the Overview, Workers, Hours and Progress tabs in one call;
  2. `GET /api/v1/workpacks/{n}/history`: `getWorkPackHistory`, the History tab;
  3. `GET /api/v1/workpacks/{n}/open-tasks`: `listWorkPackOpenTasks`, the open-tasks side panel.
  `WorkPackDetailRepository.Header` is `(id, processInstanceKey, completionDate)`: no project yet.
- **Not BPA-73 reads (left alone, Q2):** `/workpacks/{n}/look-ahead` (BPA-11, Schedule screen), `/workpacks/{n}/print` (BPA-10), and the task inbox `GET /api/v1/tasks`.
- **ProjectScope** (BPA-77, hardened in BPA-78): `projectsOf(user)`, `allows(user, ref)` (false for a blank or control-character reference), `require(user, ref)` → `ProjectNotAssignedException` → 403 "Project PRJ-142 is not assigned to you".
- **Personas:** projects today are time-method-analyst and project-manager (PRJ-139, PRJ-140), timekeeper and production-supervisor (PRJ-139), cost-controller (PRJ-140). **Six have none:** project-engineer, production-planner, workpack-owner, ppc-team, supervisor, production-team. The sync seeds a user only while it has no `user_project` row.
- **Tests that read the list or the detail:** `WorkPackListApiTest` and `WorkPackDetailApiTest`, as ppc-team (34 calls), timekeeper, supervisor, production-planner and production-supervisor. Their per-class databases have **no `user_project` rows** (the sync of `IntegrationApp` writes to the shared Camunda's database), so once scoped they would see nothing. The React `WorkPacksPage` and `WorkPackDetailPage` tests use fake backends.

## Design
### 1. List (contract text; `WorkPackController`, `WorkPackListRepository`)
- `Filter` gains `projects` (the caller's `ProjectScope.projectsOf`). The SQL adds `AND b.project_ref = ANY(?)`; **no projects → an empty list without a query**.
- The `project` query parameter stays and narrows within the caller's projects: `?project=PRJ-142` for a user without PRJ-142 gives an **empty list**, not a 403 (**ASSUMPTION**: it is a filter, not a link).
- The contract and javadoc drop "not limited until BPA-79".

### 2. Detail (contract 403s; `WorkPackController`, `WorkPackDetailRepository`)
- `Header` gains `projectRef` (the header query joins activity → WBS).
- A private `readable(number)` in the controller: `known(number)` (400, then **404 "Work Pack not found" still for an unknown number**), then `ProjectScope.require(session user, header.projectRef())` (**403** "Project PRJ-142 is not assigned to you", BPA-77's text, Q3).
- All three BPA-73 reads call it: `getWorkPack`, `getWorkPackHistory`, `listWorkPackOpenTasks`. A handler maps `ProjectNotAssignedException` to the 403 problem.
- **Contract:** each of the three operations gains a `403` response with the example; their descriptions say so.
- **One rule for every persona, ppc-team included** (BPA-77's decision; no exception, Q3 of the brief).

### 3. Screen
- `workpackService.getWorkPack` returns `{ forbidden: detail }` on 403. `WorkPackDetail` shows the backend's text in an alert and **nothing else** (no lifecycle bar, no tabs, no open-tasks panel, so their reads are never made).
- `WorkPacksPage` / `WorkPackList`: **no change** (the Project filter follows the rows); only the "not limited until BPA-79" comments go.

### 4. Personas (Q1 resolved)
- `personas.yaml`: project-engineer, production-planner, workpack-owner, ppc-team, supervisor and production-team get **`projects: [PRJ-139, PRJ-140]`** (**ASSUMPTION**). The out-of-scope cases stay: cost-controller has PRJ-140 only; timekeeper and production-supervisor PRJ-139 only.
- **An existing local database:** those six have no row, so the sync seeds them at the **next start of `bpa-app`**; no reset. A user the PPC Team already gave a project keeps what it has (BPA-76's rule).
- The BPA-68 demo script does not use the list or the detail: **no change**.

### 5. Docs
- Data model §4.1: an "As built by BPA-79" bullet (who sees which Work Packs; the project is the activity's).
- No ADR change, no migration, no BPMN change.

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml` (`listWorkPacks` text; 403 on `getWorkPack`, `getWorkPackHistory`, `listWorkPackOpenTasks`) |
| Backend | `api/WorkPackController.java` (projects into the list, `readable()`, the 403 handler), `persistence/WorkPackListRepository.java` (`Filter.projects`), `persistence/WorkPackDetailRepository.java` (`Header.projectRef`), `resources/personas.yaml` |
| Backend tests | new `WorkPackReadScopeApiTest.java`; `WorkPackListApiTest`, `WorkPackDetailApiTest` (fixtures give the personas they log in as PRJ-139 and PRJ-140; existing-test edits), `PersonaSyncTest` (7 → 19 rows; existing-test edit) |
| Frontend | `features/workpacks/services/workpackService.ts`, `workpacks.types.ts` (`WorkPackResult`), `components/WorkPackDetail/WorkPackDetail.tsx`; tests `pages/WorkPacksPage/WorkPacksPage.test.tsx`, `pages/WorkPackDetailPage/WorkPackDetailPage.test.tsx` |
| Docs | `docs/data-model.md`; plan; `REVIEW.md`; `intent/README.md` |

## Order of work
1. Approve and commit the plan (`Approved`).
2. Contract first; then the tests **red before production code** (compile stubs only); commit with the red output.
3. Build: the list filter, `Header.projectRef`, `readable()`, the handler, the personas; then the React detail 403. Green.
4. Data model note; the **targeted** proof (below; not the whole suite, Ahsan's rule for this story); the local check; `qa-reviewer`; fixes (red first); REVIEW.md; plan Closed. Push, PR and Jira only when you ask.

## Scenario-to-test table
**Backend `WorkPackReadScopeApiTest`** (per-class Postgres, shared Camunda for login and the open-tasks read). Background: the eleven `app_user` rows (`RevisionFlow.seedAppUsers`); `user_project` cleared, then **workpack-owner has PRJ-139 only**; **PRJ-142** with WBS 14201 and activity A-4201 (fixture); two Work Pack rows inserted by SQL: one on A-2201 (PRJ-139) and one on A-4201 (PRJ-142).

| # | Scenario | Test | Arrange / act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | List filtered by project | `BPA79_listFilteredByProject` | workpack-owner GETs `/api/v1/workpacks` | only the PRJ-139 Work Pack is listed; the PRJ-142 one is not | **red** (both listed) |
| 2 | Direct link to another project's Work Pack is refused | `BPA79_directLinkToOtherProjectIsRefused` | workpack-owner GETs `/api/v1/workpacks/<PRJ-142 WP>` | **403**, detail "Project PRJ-142 is not assigned to you" | **red** (200) |

**Extras, one per BPA-73 read and the edges:**
- `theHistoryOfAnotherProjectsWorkPackIsRefused` (403; red).
- `theOpenTasksOfAnotherProjectsWorkPackAreRefused` (403; red).
- `anUnknownNumberIsStillNotFound` (WP-999999 → 404 "Work Pack not found", not 403; a malformed number → 400; guards).
- `myProjectsWorkPackOpensWithItsTabs` (the PRJ-139 Work Pack: detail, history and open-tasks all 200; a guard).
- `aUserWithNoProjectsSeesAnEmptyList` (supervisor, no rows → `[]`; red).
- `theProjectFilterCannotWidenTheScope` (`?project=PRJ-142` as workpack-owner → `[]`; red).
- `thePpcTeamHasNoException` (ppc-team with no rows → `[]` and 403 on the PRJ-139 Work Pack; red).

**React:**
- `WorkPacksPage.test.tsx` `BPA79_listFilteredByProject`: the backend returns only PRJ-139 rows; the table shows them and the Project filter offers PRJ-139 only. **Passes before code** (the filter already follows the rows; a guard, said so).
- `WorkPackDetailPage.test.tsx` `BPA79_directLinkToOtherProjectIsRefused`: the backend answers 403 → the page shows "Project PRJ-142 is not assigned to you" and no tabs; and the history and open-tasks reads are not made (**red**).

`grep -c "void BPA79_"` = 2 in Java, 2 `BPA79_` in React.

## Proof (one per Definition of Done line, docs/02) — targeted, Ahsan's rule for this story
| DoD line | Evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt` |
| One test per scenario, passing in CI | **Locally, only:** `cd backend && ./mvnw -B -Dtest='WorkPackReadScopeApiTest,WorkPackListApiTest,WorkPackDetailApiTest,PersonaSyncTest,ProjectScopeTest' test` and `cd frontend && npm test -- WorkPacksPage WorkPackDetailPage`, plus `npm run lint`, `npm run format:check` and `npx tsc --noEmit`; `grep -c "void BPA79_"` = 2 and the React count = 2. **The whole backend and frontend suites are not run locally; CI runs them on the PR** (`gh pr checks <n>`; if Actions does not start, billing, the whole suite stays unrun: a human gap, said in the PR) |
| Process runs end to end locally | n/a (no BPMN). Instead: rebuild `bpa-app` and `frontend`; **as cost-controller (PRJ-140)** the Work Packs list shows no PRJ-139 Work Pack and `GET /api/v1/workpacks/WP-000001` (PRJ-139) answers **403**. The local database has an extra cost-controller PRJ-139 row from earlier testing: it is taken away through the BPA-76 API for the check and given back |
| BPMN and DMN match deployed | no model change (`git diff --stat models/` empty) |
| Open questions resolved or logged | the Jira comment (shown first, posted on your yes) |
| Plan Closed | `grep '^Status: Closed' intent/BPA-3-work-pack-assignment/plans/BPA-79.md` |
| Demoed at sprint review | Human gap |

## Risks
- **Targeted local runs only:** a regression in a class outside the list above (for example a test that reads `/api/v1/workpacks` indirectly) would show only in CI. The grep of the test sources finds only `WorkPackListApiTest`, `WorkPackDetailApiTest`, and the `/print` reads in `PrintApiTest` and `DemoRun`, which this story does not scope.
- **The 403 names the project** of a Work Pack the user cannot open (Q3: BPA-77's text, accepted); the number is already known to exist, since 404 stays for an unknown one.
- **Personas:** a database where the PPC Team already changed a user's projects keeps them; the six new rows appear only for users with none.
- **Not scoped (Q2):** the task inbox, `/print` and `/look-ahead` still show other projects' work to a user whose group owns the task.

## Questions
1. **Personas without projects.** Resolved: the six get PRJ-139 and PRJ-140 (ASSUMPTION); the demo script needs no change.
2. **The open-tasks panel and the task inbox.** Resolved: the open-tasks **panel** is a BPA-73 read and is scoped here (403); the **task inbox** `GET /api/v1/tasks`, `/print` and `/look-ahead` are **out of scope**, to propose as a new story in the Jira comment (on your yes).
3. **ppc-team exception?** No: one rule for every persona (BPA-77).
4. **Jira:** BPA-79 is **Ready**. I ask before moving it to In Progress, before any other transition and before any comment.
5. **The Jira comment** (shown first, posted on your yes): the list is limited to the caller's projects and `?project=` narrows within them (an unassigned project gives an empty list, ASSUMPTION); the three BPA-73 reads answer 403 with BPA-77's text and 404 stays for an unknown number; the six personas' projects (ASSUMPTION); the SOR Notes' `workpack.project_ref` is not a column; the proposed follow-up story for the task inbox, `/print` and `/look-ahead`.
6. **ASSUMPTIONs:** the 403 text (BPA-77's); `?project=` for an unassigned project is an empty list; the persona projects.

## Resolutions (Ahsan, 25 Nov 2026, in plan mode)
- **Q1:** project-engineer, production-planner, workpack-owner, ppc-team, supervisor and production-team get PRJ-139 and PRJ-140.
- **Q2:** the task inbox, `/print` and `/look-ahead` are out; a new story is proposed.
- **403 text:** BPA-77's "Project PRJ-142 is not assigned to you".

## Progress and departures (25 Nov 2026)
- **Contract first:** a shared response `WorkPackProjectNotAssigned` (403, BPA-77's text) is on `getWorkPack`, `getWorkPackHistory` and `listWorkPackOpenTasks`; the list and detail descriptions say "since BPA-79" and the `?project=` narrowing (an unassigned project gives an empty list, ASSUMPTION). `npm run gen:api` parses it. (My first scripted edit of the contract matched `getWorkPackHistory` and `getWorkPackPrint` as well, inserted the 403 before every line and corrupted the file; I restored it from git before anything was committed and redid it with exact `awk` anchors, `git diff --stat`: 23 insertions, 6 deletions.)
- **No compile stubs were needed:** the new tests are HTTP-only, so no new class or method is referenced.
- **Existing tests edited (they pass before code, by design):** `AssignmentFlow` gains `assignPersonasTheSeededProjects(jdbc)` (every persona's `app_user` row and PRJ-139 and PRJ-140 in the class's own database); `WorkPackListApiTest` and `WorkPackDetailApiTest` call it in their setup; `PersonaSyncTest` expects 19 rows; the React `WorkPacksPage.test.tsx` fake backend can limit the list to a user's projects and `WorkPackDetailPage.test.tsx` answers a 403 for WP-000500.
- **Red first:** `WorkPackReadScopeApiTest` 7 of 9 fail (the PRJ-142 Work Pack is listed and opens: 200 where a 403 is expected; the supervisor, ppc-team and `?project=PRJ-142` lists are not empty); `PersonaSyncTest` 2 fail (7 rows, not 19); React `BPA79_directLinkToOtherProjectIsRefused` fails (the page says "The Work Pack could not be loaded", not the backend's text). Green before code, by design (guards): `anUnknownNumberIsStillNotFound`, `myProjectsWorkPackOpensWithItsTabs`, and the React `BPA79_listFilteredByProject` (the Project filter already follows the rows the backend returns; `WorkPackReadScopeApiTest.BPA79_listFilteredByProject` is the real test of the limit). `WorkPackListApiTest` 14 and `WorkPackDetailApiTest` 20 pass with the new fixtures.

## Human-gap log
- **DoR:** none open (the status is Ready).
- **DoD:** "passing in CI": the whole suite runs only in CI for this story; Actions may not start (billing).
- **DoD:** "Demoed at sprint review". People.
- **The local check** in the portal (as cost-controller) is a person's.

## Green (25 Nov 2026)
- Built as planned: `Filter.projects` (null = not scoped, used only by the detail's own one-number lookup after the check; empty = no rows, no query; `b.project_ref = ANY (?)`), `Header.projectRef` (activity to WBS join), `readable()` for the three BPA-73 reads, the six personas' projects, the React 403 (`{ forbidden }`, alert text only), the data-model note.
- Targeted proof only (Ahsan's rule): backend `WorkPackReadScopeApiTest` 9, `PersonaSyncTest` 4, `WorkPackListApiTest` 14, `WorkPackDetailApiTest` 20, `ProjectScopeTest` 7 = 54, 0 failures; frontend `WorkPacksPage` and `WorkPackDetailPage` 20 of 20; `tsc`, lint, format check clean. The whole suites run only in CI.
