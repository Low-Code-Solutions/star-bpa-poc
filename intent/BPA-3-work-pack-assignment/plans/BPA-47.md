# Plan: BPA-47 Set the React portal structure and add project skills for task screens and job workers
Jira: BPA-47 (Task, epic BPA-3)
Story last updated: 2026-11-04T14:45:21.396+0400
Status: Approved (Ahsan, Build Lead, 4 Nov 2026, with changes, in the Claude Code session)
Approver: Ahsan (Build Lead)
Branch: BPA-47-frontend-structure-and-skills, from main 827a8ba


## Context
BPA-8, 9, 11 and 23 each add a task screen and a job worker. BPA-47 fixes one structure now, so they are not built four
different ways (Jira "Why"). It also writes down how BPA-6 built its screen and worker, as stack rules and two project
skills. BPA-6 is merged (827a8ba), and no other frontend story runs alongside.

## Readiness check (Task)
- Read: Jira BPA-47 (Description, Done when); the brief of 4 Nov; frontend/ as merged; ADR 0004, 0006, 0007, 0013, 0014;
  `TaskService.java`, `SaveWorkPackWorker.java`, `WorkPackRepository.java`, `WorkPackApiTest.java`; Claude Code skill docs
  (`.claude/skills/<name>/SKILL.md`, frontmatter `name` + `description`, body under 500 lines); the cited article
  (Pramod Boda, "Recommended Industry-Standard React.js Folder Structure", dev.to, 21 Feb 2025).
- **Disagreement reported** (Jira → plan precedence): Jira says "no UI library, styling or router", gives a
  `pages/` + `components/` layout and an estimate of 3. The brief adds React Router, TanStack Query, ESLint + Prettier in CI,
  a `features/` layout, ADR 0017 and the ADR 0004 amendment.
  **Ahsan decided, 4 Nov:** one item with the brief's scope, **proposed at 8 points**. I'll draft a Jira comment proposing
  the scope and estimate change (CLAUDE.md: never edit Description; show the text first).
- **Approval changes (Ahsan, 4 Nov):**
  - No visible DOM change: Sidebar renders nothing, and only common components the moved code uses are created.
  - The test change is called "import + render wrapper", not import-only.
  - The Prettier reformat goes in its own format-only commit.
  - The `subst` and `npm.cmd` work-rounds stay out of `backend/CLAUDE.md` (Human-gap log or README instead).
  - A Frontend section is added to `docs/04-conventions.md`.
  - ADR 0017: deciders Ahsan, Faraz informed. It notes that moving the polling into TanStack Query is a later clean-up.
  - In `new-job-worker`, the incident rule is marked temporary until BPA-23.
- **Routing (Ahsan, 4 Nov):** React Router owns the app level (login vs portal). The task list still opens a task screen
  in place, so the URL and every test behave as today. Per-task URLs wait for the visual redesign.
- **Test wrapper (Ahsan, 4 Nov):** tests that render a feature component take `render` from `frontend/tests/test-utils.tsx`
  (Testing Library re-exported with the providers): an "import + render wrapper" change, listed in the PR.

## Target layout (`frontend/`)
```
src/
  main.tsx, App.tsx              App = providers (QueryClient, Auth, Router) + AppRoutes
  assets/                        (.gitkeep)
  components/common/{Button,Field,ErrorMessage,...}/{X.tsx,index.ts}   only those the moved code uses, each rendering
                                 exactly today's element (no Spinner unless today's "Loading…" moves into one)
  components/layout/{Header,Sidebar}/        Header = "Logged in as", Groups list, Log out (moved from App);
                                 Sidebar renders nothing (placeholder for the redesign)
  layouts/MainLayout/            Header + Sidebar + <Outlet/>
  features/auth/                 components/LoginForm, services/authService.ts, hooks/useLogin.ts, auth.types.ts
  features/tasks/                components/TaskList (setRetryMs kept), components/TaskScreenShell,
                                 hooks/useTasks.ts, useTask.ts, useCompleteTask.ts, services/taskService.ts,
                                 taskScreens.ts (elementId → screen; TaskList opens only registered ones), tasks.types.ts
  features/workpacks/            components/CreateWorkPack (+ its test), fieldMessages.ts, services/workpackService.ts,
                                 hooks/useStartWorkPack.ts, workpacks.types.ts
  features/reference-data/       components/{ActivitySelect,DrawingSelect,MaterialSelect,DepartmentSelect},
                                 hooks/useReferenceLists.ts, services/referenceService.ts, reference-data.types.ts
  pages/{LoginPage,TasksPage}/   thin: compose features
  routes/AppRoutes.tsx, routes/ProtectedRoute.tsx   (by Camunda group; hides only, the backend's 403 is the check, ADR 0004)
  hooks/                         global hooks (none yet: .gitkeep)
  context/AuthContext.tsx        /me, login, logout; nothing else in context
  utils/dates.ts (todayInRiyadh, showDate), utils/constants.ts (PHASES)
  types/api.ts                   aliases to the generated components['schemas'] types
  styles/global.css              empty of rules (no visual change), imported once
  config/app.config.ts           business zone Asia/Riyadh, task-list retry 500 ms × 10. No env var needed, so no .env.example
  services/apiClient.ts          the one openapi-fetch client (moved from src/api/client.ts); schema.d.ts generated here
tests/
  setup.ts (from src/test-setup.ts), test-utils.tsx (render with QueryClientProvider + MemoryRouter),
  integration/App.test.tsx, integration/TaskList.test.tsx, mocks/ (none needed yet: .gitkeep)
```
- Naming: PascalCase component folders with an `index.ts` barrel, `use*` hooks, `*Service.ts` modules.
- Path aliases: `@/` → `src/`, `@tests/` → `tests/` (tsconfig `paths` + Vite `resolve.alias`).
- **Same behaviour.** The DOM, labels, texts, order and timing stay as they are: one alert list, the "Complete task" button
  last, 500 ms × 10 polling, and the "not logged in" view rendered in place (no redirect).
- **Query defaults:** QueryClient with `retry: false` and `refetchOnWindowFocus: false`, so fetch counts and timing match
  today. A completion refetches the `['tasks']` query.
- **TaskScreenShell** renders the screen's existing frame: form `aria-label`, `h2`, the alert list, its children, and the
  submit button. The screen supplies `check()` (its field messages) and `variables()`; the shell does the complete call and
  shows the contract's `detail` or the fallback text, as `CreateWorkPack` does today.
- **Features take `api` as a prop**, as today: the tests pass `apiClient()`, and pages pass the single app client.

## Tooling
- New dependencies: `react-router` (v7), `@tanstack/react-query` (v5). New dev dependencies: `eslint` (v9, flat config),
  `@eslint/js`, `typescript-eslint`, `eslint-plugin-react-hooks`, `globals`, `eslint-config-prettier`, `prettier` (v3).
- Exact versions are resolved by `npm install` and pinned in `package-lock.json`.
- Scripts: `lint`, `format`, `format:check`; `gen:api` now writes `src/services/schema.d.ts` (`.gitignore` updated).
- Prettier is set close to the current style (single quotes, semicolons, printWidth 130) to keep reformat noise small. I'll
  run lint and format over all code and fix the findings in this PR.
- CI: the `frontend-tests` job in `.github/workflows/backend.yml` gains `npm run lint` and `npm run format:check`.

## Docs and skills
- `docs/adr/0017-frontend-architecture.md` (MADR short form, as 0001–0016), plus a row in `docs/adr/README.md`. It covers
  the layout and the new dependencies, with the article as the source. Rejected: Redux/Zustand, axios, a type-based layout.
  Adaptations: no store; openapi-fetch instead of axios (S21); ProtectedRoute only hides screens (ADR 0004).
  Also: task screens open in place until the redesign; features take the client as a prop.
- `docs/adr/0004`: the amendment bullet under Consequences, worded as the brief gives it. Each statement is checked against
  `TaskService.java:106-109` (set from the session; no allow-list holds it, so the 400 text applies),
  `SaveWorkPackWorker.java` (`completedBy`, else `bpa-service`) and `WorkPackRepository.java` (`created_by`, `updated_by`,
  audit username). The Decision section stays unchanged.
- `frontend/CLAUDE.md` covers:
  - the layout and where new code goes;
  - calls only through `src/services/` with the generated types;
  - task screens built on TaskScreenShell and registered in `taskScreens.ts`;
  - display-only conditions in React, with validity rules re-checked on the server (ADR 0004, S14);
  - field specs in `docs/form-specs/`;
  - one React test per condition (ADR 0014);
  - the frontend-design plugin, if installed, covers visual design only, and these rules come first.
- `backend/CLAUDE.md`: package layout (09 S23), worker rules in short (pointing to the skill), the Micrometer ban (pom
  enforcer), the test helpers (`IntegrationApp`, `IntegrationCamunda`). No local work-rounds: those go in the Human-gap log
  and the READMEs.
- `docs/04-conventions.md`: new Frontend section (layout, naming, aliases, services, tests; points to ADR 0017).
- `new-job-worker`: the incident rule is marked **temporary until BPA-23** builds the corrections path.
- ADR 0017: deciders Ahsan (Build Lead), Faraz informed. It notes that moving the task-list polling into TanStack Query is a
  later clean-up.
- `.claude/skills/new-task-screen/SKILL.md` gives the steps, with BPA-6's Create Work Pack as the worked example:
  1. Read the story (AC, BPMN ref) and its form-spec.
  2. Set the variables against `bpa.tasks` in `application.yml` (BPA-26).
  3. Write the feature component on TaskScreenShell.
  4. Register it in `taskScreens.ts`. This replaces the brief's "page and route" step: screens open in place (decision above).
  5. Add the reference pick lists.
  6. Write tests named `BPA<n>_*`, one per condition, failing first (L15).
- `.claude/skills/new-job-worker/SKILL.md` gives the steps as `save-workpack` was built, each with its source:
  - idempotency on the process instance key before any check (D65, S7, ADR 0007; `SaveWorkPackWorker`);
  - BPMN error with `validationMessages` (G2, D56, ADR 0006);
  - an incident (retries 0) for a job type a later story builds (Ahsan 4 Nov; BPA-6 plan);
  - `created_by` from `completedBy`, else `bpa-service` (D63, data model §1.2 B12, ADR 0004 amendment);
  - audit rows per business column (D64, §6.1);
  - Riyadh business dates from the injected `Clock` and `app.business-zone` (S9, ADR 0013);
  - the test pattern (`WorkPackApiTest`: app on the integration Camunda with workers on, `@DirtiesContext`, settable clock,
    the retry run through the worker with a mocked `JobClient`; `ProcessTestApplication` leaves out database workers).
- `frontend/README.md` updated for the layout and scripts.

Not changed: `.claude/settings.json`, hooks, deny rules, any backend code, the contract, closed plans.

## Files that change
- Moved (history kept with `git mv`):
  - `src/App.test.tsx` → `tests/integration/App.test.tsx`
  - `src/TaskList.test.tsx` → `tests/integration/TaskList.test.tsx`
  - `src/CreateWorkPack.test.tsx` → `src/features/workpacks/components/CreateWorkPack/CreateWorkPack.test.tsx`
  - `src/CreateWorkPack.tsx`, `src/TaskList.tsx` → into their feature folders
  - `src/api/client.ts` → `src/services/apiClient.ts`
  - `src/test-setup.ts` → `tests/setup.ts`
- `App.tsx` is split into AuthContext, LoginForm, MainLayout/Header and routes.
- New files per the layout above.
- `frontend/package.json`, `package-lock.json`, `tsconfig.json`, `vite.config.ts`, `.gitignore`, `eslint.config.js`,
  `.prettierrc.json`, `.prettierignore`, `README.md`, `CLAUDE.md`.
- `.github/workflows/backend.yml` (two steps).
- `docs/adr/0017-frontend-architecture.md`, `docs/adr/README.md`, `docs/adr/0004-ui-channel-and-task-api.md`,
  `docs/04-conventions.md`.
- `backend/CLAUDE.md`, `.claude/skills/new-task-screen/SKILL.md`, `.claude/skills/new-job-worker/SKILL.md`.
- `REVIEW.md` and this plan.

## Expected test changes ("import + render wrapper"; listed in the PR, no assertion changed)
- `App.test.tsx`: import `App` from `@/App`.
- `TaskList.test.tsx`: import `apiClient` from `@/services/apiClient` and `setRetryMs, TaskList` from `@/features/tasks`;
  `render` from `@tests/test-utils`.
- `CreateWorkPack.test.tsx`: import `apiClient` from `@/services/apiClient`; `render` from `@tests/test-utils`.
- Prettier may re-wrap lines in test files. Any such change is formatting only and will be listed separately.
- New test (not moved): `ProtectedRoute.test.tsx`: a logged-out user sees the login form, and a user outside the group
  does not see the screen.

## Order of work
1. Commit this plan (Approved). Show the Jira scope comment; post it after your yes.
2. Tooling: dependencies, aliases, ESLint and Prettier; fix the lint findings in today's code. The Prettier reformat goes in
   **its own format-only commit**. Tests stay green.
3. Move and split by feature (git mv first, then the edits). Run `npm test` after each feature: 10/10 must keep passing.
4. Router, AuthContext, ProtectedRoute and its test; TanStack Query in the task and reference hooks; TaskScreenShell.
5. CI steps; `npm run lint`, `format:check`, `build`, `test`.
6. Docs: ADR 0017, ADR 0004 amendment, both CLAUDE.md files, both skills, README.
7. `./mvnw -B verify` (52 tests; no backend code changes, run to prove nothing moved).
8. qa-reviewer → REVIEW.md → fixes → plan Closed → PR (moved files, new dependencies, import + render wrapper test changes, test output)
   → Jira linkage comment, text shown first.

## Risks
- **Hidden behaviour change through TanStack Query** (extra fetches, retries, StrictMode double effects): retries off and
  no focus refetch. The existing tests check fetch counts (`completions()`), and they must stay green unchanged.
- **Prettier churn** in test files: kept low with a matching config; anything left is listed as formatting-only.
- **Scope:** 8 points proposed against Jira's 3. Agreed by Ahsan; the Jira comment proposes the change.
- **Windows paths** (space in `D:\LCS CLaude`): Vite and Vitest handled it in BPA-6. ESLint and Prettier are checked early.

## Proof
`npm test` (the same 10 tests plus the ProtectedRoute test), `npm run lint`, `npm run format:check`, `npm run build`,
`./mvnw -B verify`. A manual run in the browser (`npm.cmd run dev`): login, /me, logout, the task list, Create Work Pack
including a rejected save (Ahsan).

## Questions
- Q1 (Faraz, via the Jira comment): accept the scope and estimate change agreed by Ahsan.

## Human-gap log
- Local Maven build uses the `subst S:` work-round. In PowerShell, npm runs as `npm.cmd` (script policy).
