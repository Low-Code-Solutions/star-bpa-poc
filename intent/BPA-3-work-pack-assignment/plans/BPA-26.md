# Plan: BPA-26 Portal user can get, view and complete their tasks through the backend task API
Jira: BPA-26 (Story, epic BPA-3)
Story last updated: 2026-11-02T16:20:42.128+0400 (content; 2026-11-04T12:24 is the Ready → In Progress move only)
Status: Approved (Ahsan, Build Lead, 4 Nov 2026, in the Claude Code session)
Approver: Ahsan (Build Lead); Faraz (Product Owner and Architect) for the questions below
Branch: BPA-26-task-api, from main 83cf6ca


## Context
React must never use Camunda Tasklist (09 S2, S14). BPA-26 builds the backend task API that every Epic 1
user-task story (BPA-6, 9, 11, 23, and the Confirm task for BPA-8) completes its task through: list my tasks,
open one, complete it. Access is by Camunda group only (BPA-44). The backend calls Camunda as `bpa-service`
(S25), assigns the task to the session user and then completes it, so Camunda records the real user.
It is first in the Sprint 2 delivery order (spec.md), against the merged contract (`api/openapi.yaml`, BPA-46:
`listTasks`, `getTask`, `completeTask`) and the REST calls BPA-25 proved on 8.10.1.

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met |
| Gherkin, testable, unhappy paths | Met: 7 scenarios (4 unhappy) |
| BPMN IDs exist | Met: the four user tasks exist in `models/bpmn/Process_PPC_WorkPackAssignment.bpmn` with the G9 candidate groups |
| SOR per field | Met (Camunda for task data; allow-list is config) |
| DMN | None needed |
| No blocking question | Met: BPA-25 Done. Q1 below is not blocking but changes one assertion |
| ≤ 8 points | 5; Jira says "Ahsan to confirm" (Q4) |
| intent.md / spec.md accepted | Met (Faraz, 2 Nov 2026) |
| PO approved | Met (Faraz, 2 Nov 2026); status Ready |

No plan existed, so there is nothing to compare against Jira `updated`.

## Design (what the code does)
`bpa-service` REST calls, exactly those BPA-25 proved (results.md, comment 10420), via a RestClient adapter like
`CamundaIdentityClient` (not the Java `CamundaClient` bean: `AuthApiTest` runs with `camunda.client.enabled=false`
and must keep starting):

| API | Camunda calls |
|---|---|
| GET /api/v1/tasks | `POST /v2/user-tasks/search` filter `state=CREATED`, `processDefinitionId=Process_PPC_WorkPackAssignment`, `candidateGroup` per session group; drop elementIds not in config; read `workpackNumber` per task via variables search |
| GET /api/v1/tasks/{key} | `GET /v2/user-tasks/{key}` (404 → "Task not found") → group check (403) → `POST /v2/user-tasks/{key}/variables/search` → `variables` = read allow-list only; `validationMessages` ([] when absent), `confirmMessage`, `workpackNumber` as their own fields |
| POST …/{key}/complete | get task (404) → group (403) → state COMPLETED (409) → every variable name on the write allow-list (400 "Variable X is not allowed for this task") → `POST /assignment {assignee: <session user>, allowOverride: true}` → `POST /completion {variables}` → 204 |

Check order 404 → 403 → 409 → 400 is an ASSUMPTION (no source orders them). Camunda unreachable → 503
`EngineUnavailable` text from the contract. A task outside the configured four elementIds answers 404 (ASSUMPTION).
No validity conditions are added here (none in BPA-26; BPA-9's "Reason is required" is the first, built by BPA-9).

Allow-list in `application.yml` (`bpa.tasks.<elementId>.read` / `.write`), bound to a `@ConfigurationProperties` record.
Write lists from the `CompleteTaskRequest` description in the contract:
- CreateWorkPack: activityRef, drawingRef, departmentRef, completionDate, phases, materials
- ConfirmWorkPack: same (material line `id` sits inside `materials`)
- SignOffRelease: signOffDecision, declineReason
- ScheduleWorkPack: plannedStartDate, plannedEndDate

Read lists = the write list of the task (prefill). ASSUMPTION: no source names the read list.

## Files that change
- `backend/src/main/java/com/lcs/star/bpa/adapter/CamundaTaskClient.java` (new): the five REST calls; maps 404, 409
  and connection errors to small exceptions; parses variable values (JSON strings) and fetches the full value with
  `GET /v2/variables/{key}` when `isTruncated`.
- `backend/src/main/java/com/lcs/star/bpa/task/TaskService.java` (new): the checks and assign-then-complete.
- `backend/src/main/java/com/lcs/star/bpa/task/TaskAllowList.java` (new): config record + bean wiring for the client.
- `backend/src/main/java/com/lcs/star/bpa/api/TaskController.java` (new): implements generated `TasksApi`; session
  user and groups from the `SecurityContext` as `MeController` does.
- `backend/src/main/resources/application.yml`: `bpa.tasks` allow-lists.
- `backend/src/test/java/com/lcs/star/bpa/TaskApiTest.java` (new): the seven scenario tests.
- `backend/src/test/java/com/lcs/star/bpa/api/TestBrowser.java`: made `public` so the new test can reuse it.
- `api/openapi.yaml`: no change expected. Any change found necessary is made in this PR and recorded here (spec.md rule).
- `REVIEW.md`: the qa-reviewer pass.

No BPMN, DMN, migration or React change.

## Order of work
1. Ask before moving BPA-26 Ready → In Progress. Commit this plan (Approved) as the first commit.
2. Write `TaskApiTest` (all 7 tests), `@Tag("integration")`, using `IntegrationCamunda`, `CamundaRest` and the
   persona sync exactly as `Bpa25CamundaChecksTest` does; the app is a `@SpringBootTest(RANDOM_PORT)` with
   `camunda.client.enabled=false` and `bpa.camunda.rest-url` / `bpa-service` password pointed at the integration Camunda.
   Background: the admin deploys the models and starts `Process_PPC_WorkPackAssignment` with `startInstructions` at
   `UserTask_SignOffRelease` (variable `workpackNumber=WP-000457`; the Background's project PRJ-139 is not set: no source names that variable and the task API never reads it) and at
   `UserTask_ConfirmWorkPack` (`WP-000460`), then polls until the tasks are searchable. Login through `TestBrowser`
   with the real `CamundaIdentityClient`.
3. Run them; show each fails for the expected reason (404 from the missing controller), output kept for the PR.
4. Build adapter → allow-list config → service → controller, re-running the tests until green.
5. `./mvnw -B verify` (all backend tests, enforcer, generator), Redocly lint unchanged.
6. End to end on the local stack (Proof). Update this plan where the build departed from it.
7. qa-reviewer → REVIEW.md → fixes → plan `Closed` → PR → Jira comment (PR link, plan path, plan SHA); ask before In Review.

## Scenario-to-test table
| Scenario | Test | Key assertion |
|---|---|---|
| The task list shows only my tasks | BPA26_listsOnlyMyTasks | project-engineer's list holds the WP-000457 sign-off key, not the WP-000460 Confirm key |
| I can open one of my tasks | BPA26_getsOneTask | 200; name "Sign off release", workpackNumber WP-000457, `variables` object present |
| Completing a task records the real user | BPA26_completeRecordsRealUser | 204; Camunda task (read as admin, polled) is COMPLETED with assignee project-engineer. Operate: see Q1 |
| Wrong group is forbidden (outline, 1 row) | BPA26_wrongGroupIsForbidden | time-method-analyst gets 403 "You are not allowed to work on this task"; task still CREATED |
| Variable not on the allow-list | BPA26_variableNotAllowedIsRejected | 400 "Variable workpackStatus is not allowed for this task"; task still CREATED |
| Unknown task | BPA26_unknownTaskIsNotFound | GET /api/v1/tasks/9999999999 → 404 "Task not found" |
| Completed task cannot be completed again | BPA26_completedTaskCannotBeCompletedAgain | second complete → 409 "Task is already completed" |

All error bodies asserted as `application/problem+json` with the exact `detail`.

## Risks
- **Eventual consistency** (BPA-25): searches and `GET /v2/user-tasks/{key}` read secondary storage. Right after a
  completion the task can still read CREATED, so the 409 also has to come from the engine's rejection of the assign
  or complete. The status Camunda returns for that (404 or 409) is unknown; I confirm it in step 3–4 and record it.
  Tests poll for setup; the portal may list a just-completed task for about a second (React concern, noted for BPA-6).
- **Completer in Operate** not confirmed by BPA-25 (2, 5b): see Q1.
- **N+1 reads** for `workpackNumber` in the list: one variables search per task. Fine at POC sizes.
- **Shared integration Camunda** across test classes in one JVM: tests assert on their own task keys only.

## Proof
- `./mvnw -B verify` output: the 7 BPA26 tests passing, failing-first output from step 3.
- Local stack: `docker compose up -d`, app with profile `local`; start a WP-000457 instance at the sign-off task
  (admin, `startInstructions`), then as project-engineer: login, GET tasks, GET task, POST complete; responses in the PR.
- Operate screenshot of the completed task (human step, L19).

## Questions
- Q1 (Faraz). The AC says "Operate shows project-engineer as the user who completed the task". BPA-25 did not confirm
  this (comment 10420: completer when `bpa-service` completes is unknown; D63 keeps the real user in our own columns).
  The automated test can prove the task was assigned to project-engineer and then completed; it cannot read Operate's
  screen. Proposal: the test asserts assignee + COMPLETED, and the Operate check is a manual screenshot in the PR. If
  Operate shows `bpa-service` as the actor, that is a contradiction to log, not to work round. To be posted as a
  Jira comment after your yes.
- Q2 (Khitaba). Read allow-lists, the 404/403/409/400 order, and 404 for non-Epic-1 tasks are ASSUMPTIONs; listed for the comment.
- Q3. Spec.md names BPA-8 among the task-API users; Jira (comment 10376) lists BPA-6, 9, 11, 23. BPA-8's Confirm task
  is served either way; reported, no change.
- Q4 (Ahsan). Confirm the 5-point estimate (Jira DoR line).

## Human-gap log
- 4 Nov: the repo's `atlassian` MCP server needs sign-in; Jira was read through the claude.ai Atlassian connector instead.
- The Operate check and the local-stack run are human steps (L19).
