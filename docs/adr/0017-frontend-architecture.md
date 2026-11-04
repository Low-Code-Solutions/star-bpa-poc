# 0017 Frontend architecture

- Status: Accepted
- Date: 2026-11-04
- Deciders: Ahsan (Build Lead)
- Informed: Faraz (Product Owner and Architect)
- Jira: BPA-47
- Builds on: 09 S2, S14 (ADR 0004), S21 (ADR 0011), S9 (ADR 0013)

## Context

The portal (BPA-43, BPA-6) had five files in `frontend/src/`. BPA-8, 9, 11 and 23 each add a task screen. Without one
agreed structure they would be built four different ways, and restructuring later costs more than now (BPA-47 "Why").

## Options considered

1. **A layout by file type** (`components/`, `hooks/`, `services/` at the top only). Rejected: every story touches every
   folder, and the task-screen code for one task ends up scattered.
2. **A layout by feature**, after Pramod Boda, "Recommended Industry-Standard React.js Folder Structure", dev.to,
   21 Feb 2025. Chosen, with the three adaptations below.
3. **The article's state and HTTP choices**: Redux or Zustand in `store/`, an Axios instance in `services/api.js`.
   Rejected. The only shared client state is the session user, which a context holds. Server data is TanStack Query's
   cache. HTTP stays openapi-fetch, typed from the contract (S21).

## Decision

- **Layout** (`frontend/`):
  - `src/assets/`: static files.
  - `src/components/common/` (Button, Field, ErrorMessage, Spinner) and `src/components/layout/` (Header, Sidebar): one
    PascalCase folder each, with an `index.ts` barrel.
  - `src/layouts/MainLayout`: the logged-in portal.
  - `src/features/{auth,tasks,workpacks,reference-data}/`: each with `components/`, `hooks/` (`use*`), `services/`
    (`*Service.ts`) and a `*.types.ts`. Tests sit beside their code. `tasks/` holds the task list, the shared
    **TaskScreenShell** (load a task, show `validationMessages`, complete it, show the contract's error text) and
    `taskScreens.ts`. `reference-data/` holds the pick lists.
  - `src/pages/`: thin route-level pages that compose features.
  - `src/routes/`: `AppRoutes` and `ProtectedRoute`.
  - `src/hooks/`: global hooks.
  - `src/context/`: auth only.
  - `src/utils/`: helpers, constants, the Asia/Riyadh date helpers.
  - `src/types/`: names for the generated contract types.
  - `src/styles/`: global CSS only.
  - `src/config/`: app configuration.
  - `src/services/`: the one openapi-fetch client and the generated `schema.d.ts` (never edited by hand).
  - `frontend/tests/`: integration tests, test setup and mocks.
  - Path aliases `@/` → `src/` and `@tests/` → `tests/`.
- **New dependencies:**
  - React Router (v7): app-level routes.
  - TanStack Query (v5): server data, with retries and refetch-on-focus off. A task completion refetches the task list.
  - ESLint 9 (flat config with typescript-eslint and react-hooks) and Prettier 3, both run in CI.
  - No UI library, theme or visual change; the visual redesign is a later task.
- **Adaptations of the article:**
  1. **No store.** One React context (auth) and TanStack Query; no Redux or Zustand.
  2. **openapi-fetch instead of axios.** All calls go through `src/services/apiClient.ts` with the types generated from
     `api/openapi.yaml` (S21).
  3. **ProtectedRoute only hides screens.** The backend's group check (403) stays the real control (ADR 0004, 09 S6).
- **Screens open in place.** The task list opens a task's screen in place, through `taskScreens.ts` (BPMN element ID →
  screen), so URLs do not change. Per-task routes come with the visual redesign. Features take the API client as a prop,
  so a test can render one with a stubbed `fetch`.

## Consequences

- A new task screen is a feature component on TaskScreenShell plus one entry in `taskScreens.ts` (skill
  `new-task-screen`; `frontend/CLAUDE.md`).
- Tests that render a feature component take `render` from `@tests/test-utils`, which adds the providers. Tests of `<App />`
  use Testing Library directly.
- CI's `frontend-tests` job runs `npm run lint` and `npm run format:check` before the tests.
- **Later clean-up:** the task list still polls Camunda's eventually consistent search with its own loop (500 ms × 10,
  BPA-6). That polling moves into TanStack Query later; BPA-47 kept the loop to keep behaviour unchanged.
- `docs/04-conventions.md` has a Frontend section that points here.
- **Amended 5 Nov 2026 (BPA-23; decided by Ahsan, Build Lead, 4 Nov):** the visual design starts with BPA-23, not a later
  task. "No theme or visual change" above held until then. The design follows the frontend-design skill (plugin 1.1.1),
  with the brief and the review against its default looks recorded in plan BPA-23. It uses:
  - plain CSS: tokens as custom properties and class names in `src/styles/global.css`;
  - one self-hosted typeface, `@fontsource/barlow` (no CDN);
  - still **no UI library**.

  Styling adds class names only. Elements, roles, labels and texts stay as the stories and tests state them.

## Sources

BPA-47 (Jira, and the brief of 4 Nov 2026); Pramod Boda, dev.to, 21 Feb 2025; 09 S2, S6, S9, S14, S21; ADR 0004, 0011, 0013,
0014.
