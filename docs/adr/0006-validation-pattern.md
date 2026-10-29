# 0006 Validation pattern

- Status: Accepted
- Date: 2026-10-27 (D56 and 09 S14; 09 LOCKED); written 2026-10-29 (BPA-37)
- Deciders: D56 by Claude under PO delegation, confirmed by Khitaba (BA); S14 by Ahsan (Build Lead) and
  Usama (Lead Developer)
- Informed: Faraz (Product Owner and Architect)
- 09 rows: S14 (conditions). G2 is not a row of 09 §1: it is design-pack question 07 §11 G2, decided as
  06 **D56** and cited by S14.

## Context

Several rules span more than one field or need server data: the phase of each material must be one of the
Work Pack's phases (D16), the 7-day lead time (D9), planned end before start, and the business date in
Asia/Riyadh (D20, ADR 0013). Camunda Tasklist forms could not run these checks (07 G2). S14 then moved task
screens to React (ADR 0004), but a rule checked only in the browser can be bypassed by calling the API.

## Options considered

1. **All rules in the task form.** Not possible with Tasklist forms for cross-field rules (07 G2).
2. **A Camunda 8.8+ completing task listener that denies completion** (07 G2 option B, "to be checked on
   8.10"). Not adopted; D56 kept it as a switch-over only if a check showed it worked, and that check was
   closed when 09 S14/S15 removed Camunda forms (06 follow-ups).
3. **Save workers validate and throw a BPMN error; the task returns showing `validationMessages`**
   (07 G2 proposal, D56). Chosen, and kept by S14 for React screens.

## Decision

- **Cross-field and server rules stay in the save workers**, which throw a BPMN error carrying
  `validationMessages` (G2 / D56, kept by S14). The error returns the process to the user task:

  | Error code | Thrown by | Caught by |
  |---|---|---|
  | `WORKPACK_INVALID` | `save-workpack` | BoundaryEvent_WorkPackInvalid, BoundaryEvent_CorrectionInvalid |
  | `SCHEDULE_INVALID` | `save-workpack-schedule` | BoundaryEvent_ScheduleInvalid |

  `validationMessages` is a list of strings with the exact texts from the stories (07 §5).
- **Conditions on screens** (S14): display-only conditions live in React. Any condition that affects
  validity (for example, decline reason required when Declined) is re-checked by the backend.
- The backend task API rejects variables outside the task's allow-list with 400 (S14; ADR 0004).
- Business/validation errors are BPMN errors and are **not retried** (S7; ADR 0007).

## Consequences

- Affected scenarios read "the task returns showing <message>" rather than "the form shows …" (D56); the
  story rework of 27 Oct applied this (07 §12).
- **One React test and one backend test per condition** (S14; ADR 0014).
- BPA-25 confirms that a BPMN error from a save worker returns the task with `validationMessages` (S15).
- D56's split "field rules in the Tasklist forms" is superseded by S14 (06 superseded list): field rules
  are now on the React screens, and those that affect validity are re-checked by the backend.

## Sources

09 S7, S14, S15; 07 §5, §11 G2, §12; 06 D9, D16, D20, D56 and the superseded list.
