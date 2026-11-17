# Plan: BPA-49 System can start a Work Pack's timesheet process when its assignment completes
Jira: BPA-49 (Story, epic BPA-4)
Story last updated: 2026-11-17T11:39:44.136+0400
Status: Approved (Ahsan, Build Lead, 17 Nov 2026)
Approver: Ahsan (Build Lead)
Branch: BPA-49, from main 398fd45

A local branch `BPA-49` already existed at main 398fd45 (the BPA-48 merge, = origin/main); CLAUDE.md says "if a branch for the key already exists, use it", so it is used instead of `BPA-49-start-timesheet-process` (Q9).

## Context
Epic 1 ends each Work Pack at `EndEvent_WorkPackAssigned` with Draft Digital Time sheets and nothing that lets anyone book hours
on them. BPA-49 joins the two epics: the end of a Work Pack's assignment instance starts exactly one
`Process_PPC_TimesheetToProgress` instance for it (E2-D18), and a one-off endpoint starts it for Work Packs assigned before Epic 2
was deployed. BPA-48 already delivered V2 and deploys both Epic 2 processes (app `@Deployment` of `classpath*:bpmn/*.bpmn`); BPA-49
changes one Epic 1 element, adds one worker, one endpoint, and the test plumbing that the shared test Camunda needs.

## Readiness check (Definition of Ready, docs/02, against Jira 17 Nov)
| Check | State |
|---|---|
| Template, persona, outcome | Met ("As the System …") |
| Gherkin incl. unhappy path | Met: 6 scenarios |
| BPMN element IDs exist | Met: `EndEvent_WorkPackAssigned`, `StartEvent_WorkPackAssigned`, `Gateway_MergeBooking`, `UserTask_BookHours` (models on main) |
| Every field has an SOR | Met (SOR Notes) |
| DMN complete | Met: none needed |
| No blocking question | **Not met**: "the build team (Ahsan) to agree the Epic 1 change … and the test-helper update" (Q1) |
| Estimate ≤ 8 | Met: 3 |
| intent.md / spec.md accepted by the PO | **Not met in the repo**: Faraz accepted (reported by Ahsan, 13 Nov) but both files still say DRAFT (Q2) |
| PO approved / Ready | **Not met**: Jira status is Backlog; Faraz moves it to Ready |
Planned anyway, as asked.

## Files that change
| Area | Files |
|---|---|
| Model (E2-D18) | `models/bpmn/Process_PPC_WorkPackAssignment.bpmn`: `EndEvent_WorkPackAssigned` gets `<bpmn:messageEventDefinition>` and `<zeebe:taskDefinition type="publish-workpack-assigned" retries="3" />`; its documentation names BPA-49/E2-D18. Same ID, same incoming flow, same DI; no other element touched; the draft pack's `user_project` note is **not** copied (BPA-78). Whether lint wants a `bpmn:message` root element: see Q8 |
| Worker | new `backend/.../worker/PublishWorkPackAssignedWorker.java` (job type `publish-workpack-assigned`), built like `SaveWorkPackWorker` / `CreateDigitalTimesheetsWorker`: Work Pack found by process instance key (`WorkPackRepository.find`), idempotent before any work, `autoComplete = false`, Camunda default retries (S7, ADR 0007) |
| Shared start logic | new `backend/.../timesheet/TimesheetProcessStarter.java` (or in `persistence`/`adapter` per 09 S23 layout): the duplicate guard and the publish, used by the worker and the endpoint |
| Guard persistence | `WorkPackRepository` or a small new repository: `published(WorkPackAssigned, number)` / `record(...)` on `published_message` (sha256 = `'WorkPackAssigned'`, Jira SOR Notes) — no table change |
| API | `api/openapi.yaml`: `POST /api/v1/timesheet-processes`, tag `timesheet-processes`, request `{workpackNumber}` (required); 201; 404 "Work Pack not found"; 409 "Timesheet process already running for <WP>" / "<WP> is not assigned yet"; 403; 401; 400 for a missing field and 503 engine down (both ASSUMPTION, existing pattern of `DrawingRevisionController`). New `api/TimesheetProcessController.java` implementing the generated interface; `config/SecurityConfig.java`: `POST /api/v1/timesheet-processes` → `hasAuthority(PPC_TEAM)` (ASSUMPTION group, Jira) |
| Camunda permission | `config/PersonaSync.java` `SERVICE_PERMISSIONS`: add `PROCESS_DEFINITION:Process_PPC_TimesheetToProgress` with the same set as the Epic 1 processes, so `bpa-service` may start it by message (ASSUMPTION: proven by the tests; not proven by BPA-25) |
| Docs | `docs/adr/0008-messaging-and-instance-state.md`: Messages table row `WorkPackAssigned` (key `workpackNumber`, TTL 0, messageId `workpackNumber`, published by `publish-workpack-assigned` and the one-off endpoint) as an amendment; `docs/data-model.md` §5.12 one line: `published_message` also records `WorkPackAssigned` (sha256 = the literal name) |
| Tests | see "Test updates" and the scenario table |
| Review | `REVIEW.md` pass, this plan, `intent/README.md` |

Not changed: database (no V3), every other BPMN element, Epic 2 models, React, BPA-15 behaviour, `Process_PPC_WorkerReassignment`.

## Duplicate guard: recommendation (Jira marks the mechanism ASSUMPTION; spec A4)
**Recommended: the database (`published_message` row), not a Camunda search, with publish-then-record order.**
1. If a `published_message` row (`'WorkPackAssigned'`, workpackNumber) exists → already started: the worker completes the job and
   publishes nothing; the endpoint answers 409 "Timesheet process already running for <WP>".
2. Otherwise publish `WorkPackAssigned`: correlationKey = messageId = workpackNumber, variables `workpackId`, `workpackNumber`,
   **TTL 0** (ASSUMPTION; same as every message in ADR 0008).
3. Then insert the row (`ON CONFLICT (sha256, workpack_number) DO NOTHING`, `created_by` = `bpa-service` for the worker, the
   username for the endpoint), then complete the job / answer 201.

Why, against ADR 0007: with TTL 0 the messageId does not stop a second publish (ADR 0007 open point; data model B2/B3), so the
database is the guard. A Camunda search reads secondary storage (the RDBMS exporter), which lags the engine, so a just-started
instance can be missed and a retry could start a second one; it also needs a search call and permission no story has proved.
Order: publish first, record second. A crash between the two leaves no row, so the retry publishes again; Camunda does not start
a second message-start instance while one with the same correlation key is active, so the duplicate is dropped (proved by
`BPA49_retriedPublishStartsNoSecondInstance`). Recording first (the `AssignmentFileService` claim pattern) would turn the same
crash into a Work Pack that never gets a process and is refused by the one-off endpoint (409) forever. The row is what stops a
start **after** the Epic 2 instance has ended, which Camunda's correlation-key rule does not cover. Listed as Q3.

## Test updates (Epic 1 tests that run to the end)
The five classes that call `AssignmentFlow.awaitEnd` — `AssignmentFileApiTest`, `DigitalTimesheetApiTest` (BPA-15),
`HourJournalLineApiTest`, `WorkerAssignmentApiTest`, `WorkerReplacementApiTest` — run the app with every worker in
`com.lcs.star.bpa.worker`, so the new worker runs in them without a change. What must change:
- `IntegrationApp.background()` deploys only the three Epic 1 processes and one DMN: add `Process_PPC_TimesheetToProgress.bpmn`
  and `Decision_HoursWithinBudget.dmn` (not the weekly process: no timer in tests). Without it the message has no start event.
- **Shared Camunda, repeated Work Pack numbers** (the existing note in `DigitalTimesheetApiTest`): an Epic 2 instance another
  class left running for, say, WP-000002 would absorb this class's publish. Each of the five classes (and the new BPA-49 class)
  adds `CamundaSteps.cancelInstancesWaitingAt("UserTask_BookHours")` to its `@BeforeAll`, next to the existing catch-event
  clean-up; a constant `AssignmentFlow.BOOK_HOURS` names it. Epic 2 instances are otherwise ignored: the task list is filtered
  by `bpa.tasks.process-id` (Process_PPC_WorkPackAssignment), so they do not show in Epic 1 task tests.
- `awaitEnd` itself is unchanged: the element ID is the same and the instance completes once the worker has published.
- `ProcessTestApplication` (Camunda Process Test, no database): exclude `PublishWorkPackAssignedWorker`, as for every DB worker.
- `PersonaSyncTest.expectedServicePermissions()`: add the new process permission (edit stated in the PR).

## Scenario-to-test table (new class `TimesheetProcessStartApiTest`, app against `IntegrationCamunda`, settable clock 2026-11-18 Asia/Riyadh, `@DirtiesContext` after class; written and run red before the code)
| # | Gherkin scenario | Test | How | Expected red reason |
|---|---|---|---|---|
| 1 | An assigned Work Pack starts its timesheet process | `BPA49_assignedWorkPackStartsTimesheetProcess` | `flow.assigned(...)` (runs to the end), then poll: exactly one `Process_PPC_TimesheetToProgress` instance with variable `workpackNumber` = the WP, active at `UserTask_BookHours`, task candidate group `supervisor` | no instance (plain end event) |
| 2 | A retried publish starts no second instance | `BPA49_retriedPublishStartsNoSecondInstance` | after 1: call the starter again (row present: nothing published), then delete the row and call it again (Camunda's correlation-key rule): still exactly one instance | no starter class / no instance |
| 3 | A replacement starts no timesheet process | `BPA49_replacementStartsNoTimesheetProcess` | after 1: upload a replacement file (WorkerReplacementApiTest's helpers), `awaitReassignmentEnd`; still exactly one instance | no instance to count |
| 4 | The Epic 1 instance still ends at EndEvent_WorkPackAssigned | `BPA49_epic1InstanceStillEndsAtWorkPackAssigned` | instance completed at `EndEvent_WorkPackAssigned`, the `publish-workpack-assigned` job completed, `assignment_state` = Assigned, one `published_message` row | no job of that type |
| 5 | A Work Pack assigned before Epic 2 can be started once | `BPA49_alreadyAssignedWorkPackCanBeStartedOnce` | make the pre-BPA-49 state: run to the end, cancel its Epic 2 instance, delete its row; POST as `ppc-team` → 201, one instance; POST again → 409 "Timesheet process already running for <WP>" | 404 (no path) |
| 6 | The one-off start is refused for a Work Pack that is not assigned | `BPA49_oneOffStartRefusedForUnassignedWorkPack` | a Draft Work Pack (`flow` create only) → 409 "<WP> is not assigned yet"; also, as the SOR Notes contract (not a scenario): unknown number → 404 "Work Pack not found", `supervisor` → 403 | 404 (no path) |
Work Pack numbers are whatever the flow issues (the scenarios' WP-000457 and WP-000480 are examples). Test 2 and the endpoint
call the starter as a Spring bean; the worker itself is proven by 1 and 4.

## Order of work
1. Commit this plan (Approved) and `intent/README.md` on `BPA-49`. Ask before moving BPA-49 to In Progress.
2. Tests first: the new class (6 tests) and the `IntegrationApp` deploy line; run, show red for the reasons above.
3. `api/openapi.yaml`, then the BPMN edit, worker, starter, controller, security rule, permission; lint (`npm run lint:bpmn`, 0 warnings).
4. Epic 1 test clean-up lines; `ProcessTestApplication` exclusion; `PersonaSyncTest` permissions.
5. Docs (ADR 0008 row, data model §5.12 line).
6. Green: the new class, the five Epic 1 classes, then full `./mvnw -B verify` in the background.
7. Local stack end-to-end run (Proof). `qa-reviewer`, fixes, `REVIEW.md`, plan Closed, push, PR, read CI, draft Jira comments
   (PR link, plan path, plan SHA; a comment on BPA-15 that its end event now publishes), shown first.

## Progress and departures (17 Nov 2026)
Steps 1-5 done; step 6: the new class (6 tests), the five Epic 1 classes and `PersonaSyncTest` pass (100 tests, 0 failures); `lint:bpmn` 0 warnings.
Departures from the plan above, found while building:
- **Publish call.** `CamundaProcessClient.publish` uses `POST /v2/messages/publication` (messageId = workpackNumber, TTL 0), not the correlate call
  other stories use: correlate answers 404 both for "nothing waiting" and for a duplicate dropped while an instance with the key is active, so a
  retried publish (test 2) was read as "engine down". Publication does not say whether anything received it; a missing deployment or permission
  is caught by test 1 instead.
- **Test edit (said here as the test-first rule requires):** `TimesheetProcessStartApiTest.timesheetInstances` counts only `ACTIVE` instances. The
  first version also counted a cancelled instance (its variables stay searchable), so `BPA49_alreadyAssignedWorkPackCanBeStartedOnce` failed
  on its own set-up, not on the code. No assertion was weakened.
- **No new repository.** The guard uses `AssignmentFileRepository.published/claim/workPack` (same table); only `WorkPackRepository.findByNumber` is new.
- **Q8 answered by lint:** no `bpmn:message` root element is needed for the message end event. **Q11 answered by test 1:** `bpa-service` needs
  `PROCESS_DEFINITION:Process_PPC_TimesheetToProgress` only as the plan's permission line adds it; `MESSAGE:*` CREATE already existed.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR referencing BPA-49 | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=TimesheetProcessStartApiTest test` (6 run, 0 failures); then `gh pr checks <n>` (backend-tests pass) |
| BPA-15 tests pass with the worker running | `./mvnw -B -Dtest='DigitalTimesheetApiTest,AssignmentFileApiTest,HourJournalLineApiTest,WorkerAssignmentApiTest,WorkerReplacementApiTest' test` |
| Process runs end to end in the local stack | `docker compose up -d --build bpa-app`; take one Work Pack through create, confirm, sign-off, schedule, print and assignment upload via the API as the personas (curl, the same calls the tests make); then `curl -u demo:demo -X POST localhost:8080/v2/process-instances/search` with `processDefinitionId` filters: the Epic 1 instance is COMPLETED and one `Process_PPC_TimesheetToProgress` instance is ACTIVE at `UserTask_BookHours`; then `POST /api/v1/timesheet-processes` for it as ppc-team → 409. Screenshot of Operate optional (human gap) |
| BPMN and DMN files match the deployed models | `curl -u demo:demo localhost:8080/v2/process-definitions/<key>/xml` for Process_PPC_WorkPackAssignment (latest) diffed against `models/bpmn/Process_PPC_WorkPackAssignment.bpmn`; `ModelDeployTest` |
| Open questions resolved or logged | Questions below, answered in Jira comments (shown first) or logged as items with confirmation |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-49.md` in the final commit |
| Demoed at sprint review | Human gap (Faraz/Ahsan) |
Also: `npm run lint:bpmn` (0 warnings).

## Risks
- **Worker down**: Epic 1 instances wait at `EndEvent_WorkPackAssigned` and raise an incident after 3 retries (S7), as for any worker. Work Packs are still Assigned in the database; Operate shows the incident; resolving it re-runs the job, and the guard makes the re-run safe. The one-off endpoint is for Work Packs that never had the job, not for these (it would publish too, harmlessly, but the incident must still be resolved).
- **Weekly timer (from BPA-48)**: `Process_PPC_WeeklyProgressPeriod` is deployed with a Thursday 20:59 timer; each firing creates an instance whose first job (`open-progress-period`) has no worker until its story, piling up incidents/open jobs in every running stack and on the QA server. Not BPA-49's change; noted because the end-to-end run will show them in Operate.
- **Shared test Camunda**: repeated Work Pack numbers across classes (handled by the clean-up above); a class that forgets it gets a flaky count.
- **Permission**: if `bpa-service` lacks the right to start `Process_PPC_TimesheetToProgress`, the publish is accepted but no instance starts (silent). Test 1 catches it.
- **Existing QA data**: Work Packs already Assigned on the QA server get no process until someone calls the one-off endpoint for each (no bulk start; Q6).
- **Estimate 3** covers a model change, worker, endpoint, contract and six test classes; likely tight.

## Questions
1. **DoR, build-team agreement (Ahsan):** agree the Epic 1 change (message end event, job type `publish-workpack-assigned`) and the test-helper update above? (Open in Jira's DoR.)
2. **intent.md / spec.md** still say DRAFT although Faraz accepted (reported 13 Nov). Faraz to record it in Jira and move BPA-49 to Ready; the status lines are not changed by this story.
3. **Duplicate guard (ASSUMPTION in Jira, spec A4):** recommended the `published_message` row with publish-then-record (above), not a Camunda search. Agree?
4. **TTL 0** for `WorkPackAssigned` (not stated in Jira or the spec; ADR 0008 uses 0 for every message; a TTL > 0 would buffer a duplicate and start a second instance once the first ends). Agree?
5. **"Assigned" for the one-off start:** only `assignment_state = 'Assigned'` passes. A Work Pack in `Replacing` (it was Assigned) would get "<WP> is not assigned yet", which reads oddly. Allow `Replacing` too, or keep strict?
6. **No bulk start** for Work Packs already Assigned on the QA server: one call per Work Pack. Enough for the POC?
7. **201 body:** Jira states only the status. Proposed `{ "workpackNumber": … }` (ASSUMPTION; the instance key is not known after a publish).
8. **Lint and the message reference:** a message end event in Camunda 8 is a job worker and needs no `bpmn:message`; if `bpmnlint` (camunda-compat 8.10) asks for one, the existing `Message_WorkPackAssigned` name is reused by adding a root `bpmn:message` to the Epic 1 file. That is a root element, not a flow element; acceptable under "nothing else in the model changes"?
9. **Branch name:** the existing local branch `BPA-49` is used (CLAUDE.md), not `BPA-49-start-timesheet-process`. Rename it before the first push?
10. **sha256 column holds a name:** Jira's fallback stores `'WorkPackAssigned'` in `published_message.sha256`. Works with the unique key, but the column name no longer says what it holds; documented in the data model, no V3. Fine?
11. **Camunda permission:** adding `PROCESS_DEFINITION:Process_PPC_TimesheetToProgress` to `bpa-service` (ASSUMPTION; Camunda may require it to start an instance by message). Proven by test 1.

## Human-gap log
- The end-to-end run in the local stack is driven through the API by me; a look at Operate/Tasklist by a person is optional.
- Faraz: Ready move and the intent/spec acceptance record; demo at sprint review.
- Ahsan: DoR agreement (Q1) and merge.
- Repo path had a space; the OpenAPI generator failed. Ahsan renamed the folder to D:\LCS-Claude on 17 Nov 2026.
