# Plan: BPA-6 Time & Method Analyst can create a Work Pack with estimates
Jira: BPA-6 (Story, epic BPA-3)
Story last updated: 2026-11-02T20:51:49.871+0400 (the PO's Q4 comment 10465; no field change found since approval)
Status: Approved (Ahsan, Build Lead, 4 Nov 2026, in the Claude Code session)
Approver: Ahsan (Build Lead); Faraz (Product Owner and Architect) for the questions below
Branch: BPA-6-create-work-pack, from main efcf784


## Context
BPA-6 is the first business step of Epic 1 and second in the Sprint 2 order (spec.md): the Time & Method Analyst starts a
Work Pack request from the portal and completes the Create Work Pack task, and `ServiceTask_SaveWorkPack` validates and
saves the Work Pack (number WP-######, status Draft, phases with man hours, materials per phase). It builds on the merged
task API (BPA-26) and the contract's `POST /api/v1/workpacks` with no body (BPA-46 Q1), and also builds the four reference
lists `GET /api/v1/activities`, `drawings`, `materials`, `departments` (BPA-46 Q4; Jira comment 10465).

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met |
| Gherkin, testable, unhappy paths | Met: 11 scenarios (3 outlines) |
| BPMN IDs exist | Met: StartEvent_WorkPackRequested, Gateway_MergeCreate, UserTask_CreateWorkPack, ServiceTask_SaveWorkPack, BoundaryEvent_WorkPackInvalid (error `Error_WorkPackInvalid`, output `validationMessages`) |
| SOR per field | Met (SOR Notes; data model v0.5 §4.1–4.3) |
| DMN | None |
| No blocking question | Met; Q1–Q3 below are build decisions, answered by Ahsan 4 Nov |
| ≤ 8 points | 3 (PO 29 Oct). The React screen makes it larger than 3 in practice (Q4) |
| intent.md / spec.md accepted | Met (2 Nov) |
| PO approved | Met (Faraz 28 Oct; D74 scenario 29 Oct); status Ready |

Seed rows the Background needs all exist in `R__seed_reference_data.sql`: PRJ-139/JC-139, WBS 11031, A-2201 (with plan
data), A-2299 (none), DWG-118, MAT-PIPE-06, MAT-FLG-02, Fabrication.

## Decisions taken in the session (Ahsan, 4 Nov)
- **Scope:** backend **and** the React screen (S14: one React test and one backend test per screen condition).
- **Who saved (D63):** the task API (BPA-26) adds a server-set variable `completedBy` = session username to every
  completion. Clients cannot send it (on no allow-list). `save-workpack` writes it to `created_by`/`updated_by` and
  `audit_log.username`. Extends the BPA-26 contract code, recorded here (spec.md shared-contract rule).
- **Corrections jobs:** `save-workpack` jobs from `ServiceTask_SaveWorkPackCorrections` (BPA-23) are failed with no retries
  and the message "Save corrections is built by BPA-23" (an incident in Operate). Nothing is saved.

## Design
**`POST /api/v1/workpacks`** (WorkPackController, replaces the 501): starts `Process_PPC_WorkPackAssignment` as
`bpa-service` with no variables through REST `POST /v2/process-instances` (BPA-25 proved it); 201 `{processInstanceKey}`.
Only `time-method-analyst` (SecurityConfig rule already there, 403 otherwise). 503 when Camunda is down.

**Reference lists** (new `ReferenceController` + JDBC `ReferenceRepository`, read-only, every logged-in user per the
contract's x-assumption): activities join `wbs` and `project_job_code` (`projectRef`, `jobCodeRef`), plan fields absent
when null (Jackson non_null, BPA-26); drawings, materials, departments as in the contract. Ordered by ref (ASSUMPTION).

**`save-workpack` worker** (`worker/SaveWorkPackWorker`, `@JobWorker(type = "save-workpack", autoComplete = false)`):
1. elementId ≠ `ServiceTask_SaveWorkPack` → fail job, retries 0, message above.
2. Read variables `activityRef, drawingRef, departmentRef, completionDate, phases[{phase, manhours}],
   materials[{materialRef, phase}], completedBy` (BPA-26 allow-list names).
3. `WorkPackValidator` (pure, unit-sized) collects **all** messages in this order (ASSUMPTION), exact texts from the AC:
   required (activity, drawing, ≥1 material, phase per material, man hours per phase, department, date); man hours
   `> 0`, ≤ 2 decimals, ≤ 99,999.99; "Phase <p> is listed twice"; "Phase <p> is not selected for this Work Pack";
   "Material <m> is listed twice for phase <p>"; "Date to complete cannot be in the past" against today in Asia/Riyadh
   from the injectable `Clock` (S9, ADR 0013). Unknown activity/drawing/material/department refs → "<Field> is not
   known" (ASSUMPTION; the FK is the backstop).
4. Any message → throw BPMN error `WORKPACK_INVALID` with variable `validationMessages` (list). The boundary event loops
   back; the new Create task shows them through `GET /api/v1/tasks/{key}` (BPA-26).
5. Valid → `WorkPackService.save(processInstanceKey, input, username)` in one transaction: if a `workpack` row with this
   `process_instance_key` exists, return it unchanged (D65, S7); else number `'WP-' || lpad(nextval(seq),6,'0')`
   (data model A2), insert `workpack` (Draft), `workpack_phase`, `workpack_material`, and `audit_log` rows per business
   column (old null, canonical value format §6.1). Complete the job with `workpackId`, `workpackNumber`,
   `validationMessages: []` (BPA-23 clears them).
- "Total Activity hours is 400" = sum of the phases' man hours (ASSUMPTION: no column holds it; BPA-7's header carries
  the estimate). Asserted from `workpack_phase`.

**Clock:** a `Clock` bean (UTC system clock) in `config`; validation converts to `ZoneId.of("Asia/Riyadh")`. Tests replace
it with a settable test clock.

**React** (`frontend/src`): after login, a Time & Method Analyst sees "Start Work Pack request" (calls POST workpacks)
and "My tasks" (GET tasks). Opening a Create Work Pack task shows the Create screen: activity select with a read-only
context panel (project, Job Code, WBS, activity name, FAC CRPO/Type/Name, discipline, baseline dates, budgeted units;
blank when absent), drawing, department, date to complete, phases with man hours, materials with a phase chosen only
from the entered phases. Field checks block submission (required, man hours range/decimals, no past date in
Asia/Riyadh). `validationMessages` from the task are shown. Other groups see their task list only.

## Files that change
- `backend/.../api/WorkPackController.java` (body), new `api/ReferenceController.java`
- new `backend/.../adapter/CamundaProcessClient.java` (start instance as bpa-service) — or a method on `CamundaTaskClient`
- new `backend/.../worker/SaveWorkPackWorker.java`, `domain/WorkPackInput.java`, `domain/WorkPackValidator.java`,
  `persistence/WorkPackRepository.java`, `persistence/ReferenceRepository.java`
- new `backend/.../config/ClockConfig.java`
- `backend/.../task/TaskService.java`: add `completedBy` to completion variables (BPA-26 extension)
- `backend/src/test/.../bpatest/ProcessTestApplication.java` + `SkeletonWorkerProcessTest.java`: exclude
  `SaveWorkPackWorker` (it needs the database this test app has none of), so BPA43's test still completes the job by hand
- tests: `backend/src/test/.../WorkPackApiTest.java` (11 BPA6 tests), `frontend/src/CreateWorkPack.test.tsx` (screen tests)
- `frontend/src/App.tsx`, new `frontend/src/TaskList.tsx`, `frontend/src/CreateWorkPack.tsx`
- `REVIEW.md`, this plan. No BPMN, DMN, migration or contract change expected.

**As built (4 Nov):**
- The start client is its own class `adapter/CamundaProcessClient.java`, wired in new `config/CamundaClientsConfig.java`.
- The save step is `persistence/WorkPackRepository.save` (the plan's "WorkPackService"); no separate service class.
- Test helpers: new `IntegrationApp.java` (the Camunda background, moved out of `TaskApiTest`, which now uses it) and
  `IntegrationCamunda.grpcUrl()`, so the app's job worker can reach the integration Camunda.
- `AuthApiTest.BPA43_startWorkPackReturns501ForTimeMethodAnalyst` asserted the skeleton's 501 stub, which BPA-6 replaces.
  Renamed `BPA43_startWorkPackIsAllowedForTimeMethodAnalyst` and changed to expect 201 with the Camunda start stubbed
  (said in the PR, L15). `SkeletonWorkerProcessTest` itself is unchanged; only its test app leaves the worker out.
- Extra validator messages for input the AC does not cover (ASSUMPTION texts, Q5): "Select a phase for each man-hour
  line", "Phase <p> is not a valid phase", "Select a material for each material line", "<Field> <ref> is not known",
  "Date to complete must be a date (YYYY-MM-DD)".

## Order of work
1. Commit this plan (Approved). Ask before Ready → In Progress.
2. Write the 11 backend tests and the React tests; run them; show each fails for the expected reason.
3. Backend: clock, reference lists, POST workpacks, `completedBy`, validator, repository, worker; adjust the BPA43
   skeleton test. Re-run until green.
4. React screen; `npm test`, `npm run build`.
5. `./mvnw -B verify`; local stack end to end; Operate check of the loop-back.
6. qa-reviewer → REVIEW.md → fixes → plan Closed → PR → Jira linkage comment.

## Scenario-to-test table
Backend tests run the app against the integration Camunda with job workers on (as `bpa-service`) and a settable clock.
React tests use a stubbed fetch, as `App.test.tsx` does.

| Scenario | Backend test (JUnit) | React test (Vitest) |
|---|---|---|
| Work Pack is created with estimates | BPA6_workPackIsCreatedWithEstimates: WP-######, Draft, phases sum 400, created_by time-method-analyst | — |
| Context panel shows the activity read-only | BPA6_contextPanelShowsActivityReadOnly: GET activities returns A-2201's joined fields | same name: panel shows the read-only fields |
| Activity without plan data | BPA6_activityWithoutPlanDataCanBeUsed: fields absent; save of A-2299 succeeds | same name: plan fields blank |
| Required fields (7 rows) | BPA6_requiredFields: each row sent to complete → task returns with the message, no row saved | same name: submission blocked per field |
| Material phase must be a Work Pack phase | BPA6_materialPhaseMustBeWorkPackPhase | same name: phase options limited to entered phases |
| Phase listed twice | BPA6_duplicatePhaseIsRejected | — |
| Material twice in one phase (2 rows) | BPA6_duplicateMaterialInOnePhaseIsRejected | — |
| Date to complete boundary (3 rows) | BPA6_dateToCompleteBoundary (settable clock) | same name: past date blocked |
| Man hours boundary (5 rows) | BPA6_manHoursBoundary | same name: 0, 0.001, 100000 blocked |
| Retried save | BPA6_rerunDoesNotCreateSecondWorkPack: `WorkPackRepository.save` twice for one instance key → one row, same number and estimates. As built: key 4503599627370497 (partition 2's range) instead of the AC's illustrative 2251799813685501, which the single-partition test Camunda could generate itself | — |
| Other roles cannot create | BPA6_otherRolesCannotCreateWorkPack: production-team POST → 403; its task list has no Create task | — |

Outlines run their rows inside the one test (one test per scenario).

## Risks
- **Worker in tests:** the job worker must run against the integration Camunda (gRPC port 26500 exposed) with the
  Micrometer exclusion intact (BPA-43). If activation misbehaves, fall back to driving the worker method directly.
- **Eventual consistency:** after a BPMN error, the returned Create task appears in search after a delay; tests poll.
- **Scope vs estimate:** React screen + 4 endpoints + worker is more than 3 points (Q4).

## Proof
`./mvnw -B verify` and `npm test` output (red first, then green); local stack: TMA starts a request, completes the
screen, the Work Pack row exists; a rejected save shows messages on the returned task; Operate shows the loop-back.

## Questions
- Q1–Q3 answered by Ahsan 4 Nov (above).
- Q4 (Faraz): estimate stays 3 with the React screen in scope?
- Q5 (Khitaba): ASSUMPTIONs — message order, "<Field> is not known" for unknown refs, reference-list order, Total
  Activity hours = sum of phases. For the Jira comment after the developer's yes.

## Human-gap log
- Local Maven build uses the `subst S:` work-round (BPA-26 Human-gap log) until the pom is fixed.
- 4 Nov, test-first (L15). Backend red run: 10 of 11 BPA6 tests failed for the expected reason. 9 failed on
  `POST /api/v1/workpacks` 501 or `GET /api/v1/activities` 404; BPA6_rerunDoesNotCreateSecondWorkPack failed on the
  stub's "not built yet" (a compile-only stub of `WorkPackRepository.save`, said in the PR). BPA6_otherRolesCannotCreateWorkPack
  already passed: its 403 rule came with BPA-43 and its task list with BPA-26, so BPA-6 adds nothing there.
  React red run: the test file failed to import `./CreateWorkPack` (not written yet).
- 4 Nov, green: 11/11 BPA6 backend tests pass with the real worker on the integration Camunda; `./mvnw -B verify` 49
  tests; React 6/6 BPA6 tests and 3/3 BPA43, `npm run build` clean. BPA6_manHoursBoundary (React) runs five full form
  fills and needed a 20 s timeout instead of Vitest's 5 s default (timeout only; assertions unchanged).
