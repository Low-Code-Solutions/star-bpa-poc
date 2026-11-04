---
name: new-task-screen
description: Adds the React portal screen for a Camunda user task (Confirm Workpack, Sign off release, Schedule Work Pack, or any later one) on the shared TaskScreenShell, with field checks, the backend allow-list and BPA<n>_ tests. Use when a story asks for a task screen or says "the screen shows/blocks …" for a UserTask_* element.
---

# New task screen

Follow `frontend/CLAUDE.md` and ADR 0017. The story's plan comes first (root CLAUDE.md); these steps do not replace it.
Worked example throughout: BPA-6, Create Work Pack (`frontend/src/features/workpacks/components/CreateWorkPack/`).

## 1. Read the sources
- The story in Jira: Acceptance Criteria (every "the screen …" line is a screen condition), BPMN Reference (the
  `UserTask_*` element ID), SOR Notes (field names and SORs).
- The field spec: `docs/form-specs/Form_<Name>.form` (fields, required flags, display conditions). Variable names follow
  `docs/data-model.md` and `api/openapi.yaml` (`CompleteTaskRequest`), which win over form-spec keys
  (BPA-6: the spec says `dateToComplete`, the variable is `completionDate`).
- The task's allow-lists: `bpa.tasks.allow-lists."[<elementId>]"` in `backend/src/main/resources/application.yml` (BPA-26).
  Every variable the screen sends must be on `write`, or the backend answers 400 "Variable <name> is not allowed for this
  task". The screen can prefill only what is on `read`. If a list is wrong, change it in this story's PR and record it in
  the plan (spec.md shared-contract rule).
- Never send `completedBy`: the task API sets it from the session (ADR 0004 amendment).

## 2. Write the tests first (L15, ADR 0014)
- One React test per screen condition, named `BPA<n>_<camelCaseScenarioTitle>` after the Gherkin scenario, beside the
  component (`<Name>.test.tsx`). Render with `render` from `@tests/test-utils`; stub `fetch` with `vi.stubGlobal` as
  `CreateWorkPack.test.tsx` does (a fake backend answering `/api/v1/tasks/{key}`, the reference lists and `.../complete`).
- Outline rows run inside the one test. Dates: `vi.useFakeTimers({ toFake: ['Date'] })` and `vi.setSystemTime(...)`, and
  include a row that only passes in Asia/Riyadh (BPA-6 review: "20 Oct rejected at 20 Oct 21:30 UTC").
- Run `npm test` and show each test failing for the expected reason before writing the screen.

## 3. Build the screen on the shell
- New folder `src/features/<feature>/components/<Name>/` with `<Name>.tsx` and `index.ts`. The props are `TaskScreenProps`
  (`{ api, taskKey, onDone }`, `src/features/tasks/tasks.types.ts`).
- Wrap the fields in `TaskScreenShell` (`src/features/tasks/components/TaskScreenShell`) with:
  - `title`: the screen's heading and form name (BPA-6: "Create Work Pack");
  - `onLoaded(task)`: prefill from `task.variables` (read allow-list), so a task sent back by the save step keeps what
    was entered;
  - `check()`: the field messages, a pure function in the feature (BPA-6: `fieldMessages.ts`), with the save worker's
    exact texts;
  - `variables()`: exactly the write allow-list names.
- Display-only conditions in React (for example D16: a material's phase is offered only from the entered phases). Every
  rule that affects validity is also checked by the save worker (S14, ADR 0006). The worker side uses the
  `new-job-worker` skill.
- Pick lists come from `src/features/reference-data` (`ActivitySelect`, `useActivities`, …). A new list gets a service
  function, a hook and a select there.
- Wrap labelled controls in `Field`, buttons in `Button`, and leave messages to the shell. Style with the class names in
  `src/styles/global.css` (`field`, `line-group`, `line`, `context-panel`, `notice`); the shell already gives the job tag
  and the sheet. No UI library (ADR 0017, amended by BPA-23).
- Fields shared with another screen go in one component and a form hook: BPA-23's Confirm Workpack reuses Create's
  `WorkPackFields` and `useWorkPackForm`. Saved lines keep their `id`, so a correction updates them in place (A4).
- Business dates: `todayInRiyadh()` and `showDate()` from `src/utils/dates.ts` (09 S9).

## 4. Register it
- Add the element ID to `USER_TASKS` in `src/utils/constants.ts`, and the screen to `src/features/tasks/taskScreens.ts`.
  The task list then enables the task and opens the screen in place (no route per task yet, ADR 0017).
- A screen for one group needs nothing more: the task API lists only the user's tasks and answers 403 otherwise (ADR 0004).

## 5. Check
- `npm run lint`, `npm run format:check`, `npm test`, `npm run build`.
- In the browser (`npm run dev`, after `docker compose up -d --build bpa-app`), as the task's group persona: open the
  task, try each blocked case, complete it, and check that a task sent back shows its messages and the entered data.
