# Plan: BPA-23 Time & Method Analyst can correct a Draft Work Pack before confirming it
Jira: BPA-23 (Story, epic BPA-3)
Story last updated: 2026-11-04T17:46:15.286+0400 (status In Progress and assignee only; content last changed 2 Nov)
Status: Approved (Ahsan, Build Lead, 4 Nov 2026, in the Claude Code session; design brief confirmed with the plan)
Approver: Ahsan (Build Lead)
Branch: BPA-23-correct-draft-work-pack, from main 604eb8c


## Context
After BPA-6 saves a Work Pack and BPA-7 creates its header, the process waits at **Confirm Workpack**. Today that task has
no screen, and completing it raises an incident: `save-workpack` refuses jobs from `ServiceTask_SaveWorkPackCorrections`
(the BPA-6 decision, marked temporary until BPA-23). BPA-23 builds three things:
- the Confirm Workpack screen (skill `new-task-screen`);
- the corrections save (skill `new-job-worker`), with in-place updates and audit rows;
- the re-check of `BPA7_headerFollowsCorrectionsWhileDraft` against the real corrections step.

Faraz agreed to build BPA-23 before BPA-8 (Ahsan, 4 Nov). BPA-23 is also **where the portal's visual design starts**
(Ahsan's decision in this session, below).

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met |
| Gherkin, testable, unhappy paths | Met: 7 scenarios (1 outline, 3 rows) |
| BPMN IDs exist | Met: Gateway_MergeConfirm, UserTask_ConfirmWorkPack, ServiceTask_SaveWorkPackCorrections, BoundaryEvent_CorrectionInvalid (`Error_WorkPackInvalid`), ServiceTask_UpdateHourJournalHeader |
| SOR per field | Met (as BPA-6; audit_log D64) |
| DMN | None |
| No blocking question | Met |
| ≤ 8 points | 2 (PO). The visual design decision makes it larger in practice; Ahsan keeps the estimate (see BPA-47) |
| intent.md / spec.md accepted | Met. The delivery order changed (BPA-23 before BPA-8), agreed by Faraz |
| PO approved | Met (Faraz 28 Oct; D74 row 29 Oct); status Ready |

Seed rows exist for MAT-PIPE-06, MAT-FLG-02 and MAT-PLATE-12. The Confirm write allow-list (`application.yml`) already
holds `materials`, where each material line's `id` sits (data model §6.3).

## Decisions taken in the session (Ahsan, 4 Nov)
- **Visual design starts now.** The Confirm screen and the rest of the portal (login, task list, Create Work Pack) get a
  designed look, following the frontend-design skill (plugin 1.1.1, read from its SKILL.md; it loads as a skill in the
  next session). This amends ADR 0017 and `frontend/CLAUDE.md` ("no theme or visual change until the redesign"). Still no
  UI library. Texts, labels and roles do not change, because the AC and every existing test depend on them.
- **Line ids reach the screen through the save step.** `save-workpack` (create and corrections) completes with the saved
  `phases` and `materials`, each material with its `id`, as process variables. The Confirm task's read allow-list returns
  them, and the screen round-trips the ids (data model A4, F13). No new endpoint, no contract path change.
- **A corrections job for a Work Pack that is no longer Draft** fails with no retries and the message
  "WP-###### is <status>; corrections are closed (D8)" (ASSUMPTION text). Operate shows an incident, and nothing is saved.

## Design: backend (skill `new-job-worker`)
`SaveWorkPackWorker` keeps job type `save-workpack` and dispatches on elementId. The temporary incident for corrections
is removed.

**Corrections path** (`ServiceTask_SaveWorkPackCorrections`):
1. Find the Work Pack by process instance key; a missing one throws (Camunda default retries, then an incident).
2. Status not Draft → fail with no retries (decision above).
3. Read the variables as at create, plus `materials[].id`. Validate with the same `WorkPackValidator` (D14, D16, D20 in
   Asia/Riyadh, D74, with the exact AC texts). One extra check: an `id` that is not one of this Work Pack's lines answers
   "Material line <id> is not part of this Work Pack" (ASSUMPTION).
4. Any message → BPMN error `WORKPACK_INVALID` with `validationMessages`. `BoundaryEvent_CorrectionInvalid` returns a
   new Confirm task, and nothing is written.
5. Valid → `WorkPackRepository.correct(workpackId, input, username)` in one transaction, updating in place (F13):
   - `workpack` columns: update the changed ones.
   - Phases by name: update changed man hours, insert new phases, delete removed ones.
   - Material lines by `id`: update changed `material_ref` or `phase`. A line without an `id` is matched to an unclaimed
     existing line with the same (material, phase), else inserted. Unclaimed old lines are deleted.
   - The FKs and UNIQUE are DEFERRABLE INITIALLY DEFERRED, so statement order does not matter.
   - Audit (D64, §6.1): one row per changed business column, old → new, with an insert's old value null and a delete's
     new value null. Times come from the injected clock; the user is the Confirm completer (`completedBy`, D63).
6. Complete with `workpackId`, `workpackNumber`, `validationMessages: []`, and the saved `phases` and `materials` (ids).
   `ServiceTask_UpdateHourJournalHeader` (the BPA-7 worker) then recalculates the header.

**Idempotent by construction.** A retried job applies the same target state. The diff is then empty and there are no new
audit rows; an id-less line is matched by its natural key rather than inserted again.

**Create path:** unchanged, except that it now also outputs the saved `phases` and `materials` (ids).

## Design: frontend (skill `new-task-screen`, then frontend-design)
**Confirm Workpack screen** (`features/workpacks/components/ConfirmWorkPack/`):
- Built on `TaskScreenShell`, with title "Confirm Workpack".
- The editable fields are shared with Create Work Pack: a new `WorkPackFields` component plus a form-state hook, both
  extracted from `CreateWorkPack`. CreateWorkPack behaves as before, and its tests stay unchanged.
- Extras:
  - a read-only Workpack number (form spec);
  - the latest return reason `confirmMessage` (D51), shown read-only when present (set by BPA-8 or BPA-9 later);
  - material ids round-tripped in `variables()`.
- The field checks are the same as Create (`fieldMessages`), so a Cutting of 0 is blocked on the screen (S14).
- Registered in `taskScreens.ts` with `USER_TASKS.confirmWorkPack`. The task list's existing "sent back" watch already
  reopens a returned Confirm task.

**Visual design (frontend-design skill, two passes).**
- **Brief, for you to confirm** (the skill asks the client to confirm the subject):
  - Subject: STAR's production planning at a steel fabrication yard (piping modules, cutting, welding).
  - Audience: Time & Method Analysts, planners and PPC staff at a desk, entering and checking Work Packs.
  - Primary job: complete a task correctly, quickly, with errors impossible to miss.
- **Draft tokens** (to be reviewed against the skill's list of defaults before building):
  - Colour, cool and industrial, not cream:
    - steel ink `#1E2933` (text);
    - mill-scale grey `#5B6670` (secondary);
    - plate `#ECEEEF` (page);
    - sheet `#FFFFFF` (form surface);
    - marking-paint yellow `#F2B705`, used in **one place**: the Work Pack tag and the focus ring;
    - signal red `#B3261E` (errors).
  - Type: one family, Barlow (industrial-signage roots), self-hosted with `@fontsource/barlow` (a new dependency; no CDN).
    A clear scale, sentence case, no all-caps labels.
  - Layout:
    - left-aligned, single column, forms at most about 72ch;
    - labels above controls;
    - fieldsets as grouped sheets with a left rule;
    - the task list as a register (rows, not cards).
  - The one memorable thing: the task heading set as a fabrication **job tag**. As built, the tag carries the screen title; the Workpack number sits beside it on a read-only plate.
  - Quality floor: visible keyboard focus, reduced motion respected, contrast AA, works down to mobile width.
- **Constraints:**
  - plain CSS (tokens as custom properties in `styles/global.css`, class names on the existing elements);
  - no UI library;
  - the **DOM roles, labels and texts the tests use stay exactly as they are**;
  - the skill's copywriting advice applies only to new text that no AC or test states.
- **Check:** `npm test` must stay green unchanged. I'll take screenshots in a real browser to critique the design if the
  environment allows; otherwise you check it with `npm.cmd run dev`.

## Scenario-to-test table
Backend class `ConfirmWorkPackApiTest` follows the `WorkPackApiTest` / `HourJournalHeaderApiTest` pattern. Background: a
Work Pack with Cutting 150, Welding 250 and MAT-PIPE-06 in Cutting, created through the real flow, with its Confirm task
open.

| Scenario | Backend test | React test |
|---|---|---|
| Correcting man hours recalculates the Header | BPA23_correctManHoursRecalculatesHeader: complete Confirm with Welding 300 (ids from the task's variables) → phase 300; GET header 450 | same name: the screen sends Welding 300 with the line ids |
| Correcting materials and phases | BPA23_correctMaterialsAndPhases: add MAT-PLATE-12 in Cutting → listed; the existing line keeps its id | same name: added line sent without id, existing line with its id |
| An invalid correction is rejected (3 rows) | BPA23_invalidCorrectionIsRejected: each row → a new Confirm task with the exact message; the Work Pack still has Cutting 150 and no Finishing material | same name: Cutting 0 blocked on the screen |
| Validation messages are cleared after a successful save | BPA23_validationMessagesClearedAfterSave: Finishing rejected, then FLG moved to Welding → saved; process variable `validationMessages` is `[]` | — |
| Other roles cannot correct | BPA23_otherRolesCannotCorrect: production-planner does not see the task; POST complete → 403 | — |
| Every correction is audited | BPA23_correctionIsAudited: exactly one `workpack_phase`/`manhours` row 250 → 300 by time-method-analyst at the test clock time | — |
| No correction after release | BPA23_noCorrectionAfterRelease: **stand-in for BPA-9**, below | — |

Non-scenario tests:
- backend `correctionOfNonDraftWorkPackRaisesAnIncident` (the decision above);
- React `confirmMessage` shown (D51).

### Release scenario: tested now, and the re-check when BPA-9 lands
- **Now:**
  1. After a real correction (token at `ServiceTask_EvaluateReleaseInputs`; BPA-8 not built), the test stands in for
     BPA-9's sign-off: it sets Released and locks the header in the database, then moves the token past Confirm with the
     terminate-whatever-is-active modification from BPA-7.
  2. Assert no Confirm task exists, and the header is locked.
  3. BPA-23's own part: a corrections job for the Released Work Pack fails with the incident (mocked `JobClient`), and
     nothing changes.
- **When BPA-9 lands:** replace step 1 with the real sign-off path. The release check must be Ready (reserved materials and
  an approved drawing, D1, D10). Keep the assertions. Marked `// BPA-9:` in the test.
- **When BPA-8 lands:** run the class. The modification already copes with BPA-8 moving the token on (`// BPA-8:`).

## Changes to existing code and tests (said in the PR, L15)
- **BPA-7 re-check:** in `HourJournalHeaderApiTest`, `correctWeldingTo300` now completes Confirm Workpack through the task
  API with Welding 300 and the line ids, instead of the SQL update plus modification. It is used by
  `BPA7_headerFollowsCorrectionsWhileDraft` and `BPA7_headerIsLockedOnceReleased`. The assertions are unchanged: one
  header, 450, and the audit row 400 → 450. The `// BPA-23:` comments are removed.
- **BPA-6:** `WorkPackApiTest.correctionsJobRaisesAnIncident` asserted the temporary rule, which this story ends. It is
  replaced by the BPA-23 tests.
- **Skill `new-job-worker`:** the temporary-incident rule is marked ended (BPA-23). An "in-place correction" note is added
  with its sources (F13, A4).
- **ADR 0017:** amendment for visual design (Ahsan, 4 Nov; frontend-design skill; plain CSS tokens; `@fontsource/barlow`).
  The decision text before it stays.
- **`frontend/CLAUDE.md`:** the visual-design rule is updated to the tokens and the skill. The other rules stay.

## Files that change
- **Backend:**
  - `worker/SaveWorkPackWorker.java` (corrections path, line outputs);
  - `persistence/WorkPackRepository.java` (`correct`, `lines`);
  - `domain/WorkPackInput.java` (material `id`);
  - tests `ConfirmWorkPackApiTest.java` (new), `HourJournalHeaderApiTest.java` and `WorkPackApiTest.java` (as above).
- **Frontend:**
  - new `features/workpacks/components/ConfirmWorkPack/` and `WorkPackFields/`, plus a `useWorkPackForm` hook;
  - `CreateWorkPack.tsx` (uses them);
  - `taskScreens.ts`, `utils/constants.ts`, `styles/global.css`;
  - class names on existing components;
  - `package.json` (`@fontsource/barlow`);
  - tests `ConfirmWorkPack.test.tsx` (new).
- **Docs:** ADR 0017, `frontend/CLAUDE.md`, the `new-job-worker` skill, `REVIEW.md`, this plan.
- **No BPMN, DMN, migration or contract-path change.** The contract's `CompleteTaskRequest` text already names the
  material line `id` for Confirm.

## Order of work
1. Commit this plan. You move statuses.
2. Write the 7 backend tests and the React tests; run them; show each failing for the expected reason (today's incident;
   no screen).
3. Backend: input id, `correct` with audit, worker corrections path, line outputs. Remove the incident and its BPA-6 test.
   BPA-7 re-check. Green, then `./mvnw -B verify`.
4. Frontend function first:
   - extract `WorkPackFields` (CreateWorkPack tests stay green unchanged);
   - add `ConfirmWorkPack` and register it;
   - React tests green.
5. Frontend design, per the skill:
   - token plan → review against its defaults (note what changed) → build in `global.css`;
   - `npm test` unchanged and green; lint, format and build clean.
6. Docs: ADR 0017 amendment, `frontend/CLAUDE.md`, skill.
7. Local stack end to end:
   - create → Confirm → correct Welding to 300 → header 450, audit row;
   - a rejected correction returns with its message;
   - Operate shows the corrections path.
8. qa-reviewer → REVIEW.md → fixes → plan Closed → PR → Jira comment (text shown first).

## Risks
- **The visual pass can break tests** that depend on DOM structure: keep the elements, add classes and wrappers only where
  no role or label changes. Run `npm test` after each step.
- **Diff logic edge cases** (a material moved to a removed phase, two id-less lines): the deferred constraints are the
  backstop; each case is covered by the invalid-row tests or a validator message.
- **Scope:** larger than 2 points with the design pass (Ahsan's call, estimate kept).

## Proof
- `./mvnw -B verify` and `npm test` (red first, then green), lint, format and build.
- The local-stack run and Operate.
- Screenshots of the designed screens if possible; otherwise you check them in the browser.

## Questions
- Q1 (Khitaba, ASSUMPTIONs for the comment):
  - "Material line <id> is not part of this Work Pack";
  - the not-Draft incident text;
- Q2 (you): confirm the design brief above (subject, audience, primary job), as the frontend-design skill asks.

## Human-gap log
- Local Maven build uses the `subst S:` work-round; `npm.cmd` in PowerShell.
- frontend-design plugin installed mid-session: not in the skill list until a new session, so it is read from its SKILL.md.
- 5 Nov, test-first (L15), backend:
  - **Red:** 7 of 8 tests failed for the expected reason. Every corrections job hit the temporary incident ("Save
    corrections is built by BPA-23", 9 times in the log), so nothing was saved or returned. BPA23_otherRolesCannotCorrect
    passed already: its group check is BPA-26's.
  - **Green:** 8/8 BPA-23; BPA-7's class 8/8 on the real corrections step; `./mvnw -B verify` 67 tests.
- 5 Nov, one flaky run: in a combined run (ConfirmWorkPackApiTest, then HourJournalHeaderApiTest, then WorkPackApiTest),
  BPA6_duplicatePhaseIsRejected timed out. Its save-workpack job was never taken within 60 s, and the worker logged nothing
  for the instance. The same test passed alone (13/13) and in the full verify (67/67). Likely cause, not proven: a job
  held by a just-closed workers-on context. Watch it in CI. If it recurs, propose a test-infrastructure Task (for example,
  turn job streaming off in tests or give each class a longer wait).
- 5 Nov, frontend function (skill new-task-screen):
  - **Red:** the Confirm test file failed to import `./ConfirmWorkPack`, which did not exist yet.
  - **Extraction:** `useWorkPackForm` and `WorkPackFields` came out of CreateWorkPack. Its 6 tests and the 6
    integration tests stay green, unchanged.
  - **Green:** ConfirmWorkPack registered in `taskScreens.ts`; `npm test` 18/18; lint, format and build clean.
- 5 Nov, design pass 1 (frontend-design skill). The draft tokens were reviewed against the skill's five default looks:
  - **Cream, serif and terracotta:** not used. The page is cool plate grey and the type is a sans (Barlow).
  - **Near-black with one acid accent:** avoided. The page is light; the yellow appears only on the job tag and the focus
    ring, on light ground.
  - **Broadsheet hairlines and zero radius:** **revised.** Thin rules with square corners everywhere read as that default.
    Instead, line groups get a solid 4 px left edge (a plate edge), and controls get a 3 px radius.
  - **SaaS card kit:** avoided. The task list is a register of rows with a rule between them, not cards. There is one sheet
    per screen, not a card per section.
  - **Template chrome:**
    - **revised:** the Workpack number first got a monospace "data label" look; it now uses Barlow's tabular figures;
    - no all-caps eyebrows, no middle-dot meta strings, no arrows on buttons;
    - labels stay sentence case.
  - **The one memorable thing:** the task heading as a fabrication job tag (marking-paint yellow, a punched hole). Everything
    else stays quiet.
  - **No motion:** restraint, and reduced motion holds by default.
- Screenshots: no headless browser here (Playwright is not installed, and the artifact emulator is cloud-only). The visual
  critique is Ahsan's, in the browser (`npm.cmd run dev`).

## Review fixes (qa-reviewer at de8af72; REVIEW.md)
- **Material matching:** lines with an id now claim their rows first (two passes), and a duplicate id is refused with
  "Material line <id> is listed twice" (ASSUMPTION). A crafted or out-of-order request can no longer lose a line.
- **Retry rule (Ahsan, 5 Nov):** a correction that equals what is saved (`WorkPackRepository.alreadySaved`) completes
  without re-checking. A retried job after Riyadh midnight therefore no longer returns the task. A plain confirm of
  unchanged data also skips the server checks; the screen still blocks a past date.
- **New non-scenario tests:**
  - `multiChangeCorrectionIsAppliedInPlace`: every branch of `correct()` and the exact audit set.
  - `correctionRetryAfterMidnightCompletesWithoutRechecking`.
- **Release scenario:** the non-Draft incident is asserted inside BPA23_noCorrectionAfterRelease, as the plan said, and
  the separate test is folded in. The stand-in for BPA-9 is said in the PR.
- **Frontend:** the CSS specificity bug is fixed (a `:not()` selector kept the task list and sheet out of full width), and
  the return reason has a visible "Returned:" label (form spec).
- **Wording:** material lines are ordered by `created_at, id`; "deferrable" now covers only the material FK and UNIQUE; the
  CamundaSteps Javadoc is reworded; Story last updated is bumped; the plate hex is corrected; the date re-check is dropped
  from Q1 (it is sourced in the Description); the job-tag wording matches the build.
- **Files that change, as built (adds to the list above):**
  - backend test helper `CamundaSteps.java`;
  - skill `new-task-screen`;
  - `frontend/src/main.tsx` (fonts), `package-lock.json`;
  - `features/workpacks/index.ts`, `workpacks.types.ts` (line `id`);
  - `Button`, `Field`, `ErrorMessage` (a `className` prop).
- **Not done here (Ahsan):**
  - the spec.md order change and a BPA-3 comment: Faraz's agreement stays recorded in this plan;
  - the CLAUDE.md proposal on posting ASSUMPTION comments before the PR.
- **No remove-line control** on Create or Confirm (as before). The backend's delete branches are reachable through the API
  and are tested; noted for the PO in the Jira comment.
- **After the fixes:** `./mvnw -B verify` 68 tests (ConfirmWorkPackApiTest 9); `npm test` 18/18; lint, format and build
  clean.
