# 0004 UI channel and task API

- Status: Accepted
- Date: 2026-10-27 (decided, 09 LOCKED); written 2026-10-29 (BPA-37)
- Deciders: Ahsan (Build Lead), Usama (Lead Developer)
- Informed: Faraz (Product Owner and Architect), Khitaba (BA)
- 09 rows: S2, S4, S14; S15 (rejected option)

## Context

The UI channel was undecided (08 R6): the compose file had a commented React `frontend` service, while the
design pack used Camunda Tasklist with linked forms (07 §8). Tasklist forms (form-js) cannot fetch data
or run cross-field checks, which had forced two extra "loading" service tasks into the model (07 G12)
and a server-side validation workaround (07 G2). The Work Pack print also has to be viewable (D29).

## Options considered

1. **Camunda Tasklist with linked forms for Epic 1; React deferred** (07 §8; 08 Gate 1, proposal for
   ADR 0007). Rejected by S2 and S14.
2. **React for all task handling, through a backend task API** (S2, S14). Chosen.
3. **A forms-capability Spike** to test how far Camunda forms go. Cancelled (S15): superseded by S14; its
   remaining checks moved into BPA-25.
4. **Loading steps** `ServiceTask_LoadReferenceData` and `ServiceTask_LoadScheduleContext` (07 G12).
   Dropped (S14): React fetches lists from the backend.
5. For the print: the browser's built-in viewer, or `react-pdf` (S4). The browser viewer is chosen;
   `react-pdf` only if a story needs in-app controls.

## Decision

- **React front end for all task handling**, one portal per persona. React calls **only** the Spring Boot
  backend; the backend calls Camunda. Camunda Tasklist is not used by end users (S2).
- **No Camunda forms.** `zeebe:formDefinition` is removed from the four user tasks, which keep their
  candidate groups, so the BPMN deploys without `.form` files. React screens are written in code; the
  drafted `.form` files are kept only as the field spec (S14; ADR 0003). The G12 loading steps are dropped.
- **Backend task API** (S14): `GET /api/v1/tasks`, `GET /api/v1/tasks/{key}`,
  `POST /api/v1/tasks/{key}/complete`, with a group/scope check (403), a per-task variable allow-list in
  config (400 otherwise), and assign-to-user-then-complete so Camunda records the real user.
- **Conditions** (S14): display-only conditions live in React; any condition that affects validity is
  re-checked by the backend (see ADR 0006).
- **Viewing the print** (S4): React fetches `GET /api/v1/workpacks/{workpackNumber}/print` as a blob and
  shows it in an iframe with the browser viewer. The backend sends
  `Content-Disposition: inline; filename="<WP>.pdf"`, and 404 "Workpack print not available" before
  scheduling.

## Consequences

- The models carry no form links (done under BPA-36); `docs/form-specs/` holds the field specs.
- BPA-25 (Spike) confirms the 8.10.1 API calls the task API needs: search user tasks by candidate group,
  assign then complete with variables and see the real user in Operate, read the user's groups at login,
  BPMN error from a save worker returning the task with `validationMessages` (S15; a fifth check for the
  service identity comes from S25, ADR 0005). BPA-26 builds the task API.
- Process start moves from Tasklist (07 G5, D52 mechanism) to `POST /api/v1/workpacks` (S20, ADR 0011);
  the actor and start permission of D52 stand.
- Stories BPA-6, 8, 9, 11, 23 refer to React screens instead of Tasklist forms (09 §4 item 7).
- One React test and one backend test per condition (S14; ADR 0014).

## Sources

09 S2, S4, S14, S15, S20, §3, §4 item 7; 07 §8, §11 G2, G5, G12; 08 R6 and Gate 1; 06 D29, D52.
