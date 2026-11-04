# Portal (frontend/): rules for Claude Code

Read with the root CLAUDE.md, which comes first. Architecture: docs/adr/0017-frontend-architecture.md. To add a task
screen, use the `new-task-screen` skill.

## Where code goes

- `src/features/<feature>/`: `components/` (PascalCase folder + `index.ts`), `hooks/` (`use*.ts`), `services/`
  (`*Service.ts`), `<feature>.types.ts`, tests beside the code. Features today: `auth`, `tasks`, `workpacks`, `reference-data`.
- `src/components/common/` and `src/components/layout/`: shared UI only. Create a common component only when code uses it.
- `src/pages/`: thin pages that compose features. `src/routes/`: `AppRoutes` and `ProtectedRoute`.
- `src/context/`: auth only. `src/utils/`: helpers, constants, the Asia/Riyadh date helpers. `src/types/api.ts`: names for the
  generated contract types. `src/config/`: app config. `src/styles/`: global CSS only.
- `tests/`: integration tests, `setup.ts`, `test-utils.tsx`, mocks.
- Imports use `@/` (src) and `@tests/` (tests).

## Rules

- **All API calls go through `src/services/apiClient.ts`** (openapi-fetch) with the types generated into
  `src/services/schema.d.ts` by `npm run gen:api` from `api/openapi.yaml` (S21). Never edit `schema.d.ts`. No axios, no
  fetch calls of your own, no store (ADR 0017).
- **React calls only the backend, never Camunda** (09 S2, ADR 0004).
- **Task screens are built on `TaskScreenShell`** (`src/features/tasks/components/TaskScreenShell`) and registered in
  `src/features/tasks/taskScreens.ts` by BPMN element ID. The shell loads the task, shows `validationMessages`, completes it
  and shows the contract's error text; the screen supplies its fields, `check()` and `variables()`.
- **Variables a screen sends must be on the task's write allow-list** (`bpa.tasks` in
  `backend/src/main/resources/application.yml`, BPA-26), or the backend answers 400. Never send `completedBy`: the
  backend sets it (ADR 0004 amendment).
- **Display-only conditions live in React; every rule that affects validity is re-checked on the server** (09 S14,
  ADR 0004, ADR 0006). The screen's checks use the same texts as the save worker.
- **Field specs** for each screen are in `docs/form-specs/` (field list, required flags, display conditions). Variable
  names follow `docs/data-model.md` and the contract, which win over the form-spec keys.
- **`ProtectedRoute` only hides screens.** The backend's 403 is the real check (ADR 0004).
- **Business dates are Asia/Riyadh** (`src/utils/dates.ts`, 09 S9).
- **Tests:** one React test per screen condition (ADR 0014, S14). A scenario test is named `BPA<n>_<camelCaseScenario>` and
  is shown failing before the code (L15). Feature components are rendered with `render` from `@tests/test-utils`.
- **Before a PR:** `npm run lint`, `npm run format:check`, `npm test`, `npm run build` (CI runs the same).
- **Visual design** (ADR 0017, amended by BPA-23):
  - Use the tokens and class names in `src/styles/global.css` (plain CSS, Barlow); no UI library.
  - New visuals follow the frontend-design plugin, which covers visual design only. The rules in this file and the root
    CLAUDE.md come first.
  - Styling adds class names; it never changes an element, role, label or text that a story or test states.
  - The marking-paint yellow is kept for the job tag and keyboard focus.

## Running locally

`npm ci`, then `npm run dev` (http://localhost:5173, proxies `/api` to bpa-app on :8081). See README.md.
