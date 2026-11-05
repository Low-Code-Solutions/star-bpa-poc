# Plan: BPA-9 Project Engineer can sign off a Work Pack release
Jira: BPA-9 (Story, epic BPA-3)
Story last updated: 2026-11-05T12:24:53.058+0400
Status: Approved (Ahsan, Build Lead, 2026-11-05)
Approver: Ahsan (Build Lead)
Branch: BPA-9-release-signoff, from main fdb64ac


## Context
Since BPA-8, a Work Pack that passes the release check waits at `UserTask_SignOffRelease` (project-engineer). It has no screen,
and `record-release-signoff` has no worker. BPA-9 builds both:
- The Sign off release screen: approve, or decline with a reason. The screen checks the reason and the backend re-checks it
  before completing the task (09 S14).
- The worker `ServiceTask_RecordSignOff`. It writes `release_signoff`, idempotent on the task key (09 S7, D65), with
  `signed_off_by` from `completedBy` (D63). On approval it sets the Work Pack to Released and locks the Header (D8). On decline
  it sets `confirmMessage` to "Declined by Project Engineer: <reason>". The Confirm Workpack screen already shows that under
  "Returned:", as it does BPA-8's reason (D51).

The BPA-7 and BPA-23 tests still stand in for this sign-off; they move to the real path.

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met |
| Gherkin, testable, unhappy paths | Met: 6 scenarios |
| BPMN IDs exist | Met: UserTask_SignOffRelease (project-engineer), ServiceTask_RecordSignOff (`record-release-signoff`, retries 3, no boundary error), Gateway_IsSignOffApproved (`=signOffDecision = "Approved"`, default Declined → Gateway_MergeConfirm) |
| SOR per field | Met: release_signoff (POC database; V1 has UNIQUE task_key and the decline-reason CHECK), signed_off_by (Camunda identity), workpack.status |
| DMN complete | n/a (none) |
| No blocking question | Met |
| ≤ 8 points | 2. The screen, the task-API check and the worker together may be more than 2; said here, the estimate is not changed |
| PO approved | Met (Faraz 28 Oct); status In Progress |

Already in place, reused: the allow-list `[UserTask_SignOffRelease]` (write: signOffDecision, declineReason); the openapi
400 example "Reason is required"; `docs/form-specs/Form_SignOffRelease.form`; the Confirm screen's "Returned:" notice.

## Decisions (Ahsan, 5 Nov)
- **Task key → worker:** on every completion, the task API adds the server-set variable `completedTaskKey` (the user task
  key). It works like `completedBy` (D63): set from the engine's task, never from the request, on no allow-list (a request that
  sends it gets the existing 400). The worker reads it for `release_signoff.task_key`. Recorded in the ADR 0004 amendment and
  the openapi `CompleteTaskRequest` text (no schema change).
- **Screen data:** the sign-off READ list gains activityRef, drawingRef, departmentRef, completionDate, phases and materials, so
  the Project Engineer sees what they sign (form spec). WRITE stays signOffDecision, declineReason. Recorded in application.yml.
- **Merge:** I open the PR and wait for all CI checks to go green; Ahsan approves and merges.

## Design: backend re-check (task API)
`TaskService.complete` runs the per-task validity check after the allow-list check (order 404, 403, 409, 400; BPA-26).
- A new `task/TaskValidity` (pure, unit-testable), keyed by element ID; only `UserTask_SignOffRelease` has rules today:
  - missing `signOffDecision` → 400 "Decision is required" (ASSUMPTION text). This matters because the gateway's default
    flow is Declined, so a completion without a decision would otherwise decline without a reason;
  - a value other than Approved or Declined → 400 "Decision must be Approved or Declined" (ASSUMPTION text);
  - Declined with a blank or whitespace-only `declineReason` → 400 "Reason is required" (AC; whitespace matches the V1 CHECK).
- On a 400 nothing is assigned or completed, so the task stays open.
- An Approved completion that also carries a declineReason is accepted; the worker stores no reason for Approved (ASSUMPTION).

## Design: worker (skill `new-job-worker`)
`worker/RecordSignOffWorker` (`@JobWorker(type = "record-release-signoff", autoComplete = false)`) with
`persistence/ReleaseSignOffRepository`, in one transaction:
1. Read `completedTaskKey`, `completedBy`, `signOffDecision` and `declineReason`. If `completedTaskKey` or `completedBy` is
   missing, throw: retries, then an incident (S7). The `signed_off_by` column must be the Project Engineer, never bpa-service.
2. **Idempotent, checked first:** `INSERT … ON CONFLICT (task_key) DO NOTHING`. If the row was already there, change nothing
   and complete with the same outputs.
3. If the row is new:
   - Approved: workpack Draft → Released; Header `locked` false → true. That needs a new `HourJournalHeaderRepository.lock`;
     the BPA-7 upsert still never changes a locked header.
   - Declined: no workpack change.
   - Audit rows (D64, §6.1, canonical values; the username is `completedBy`): the release_signoff columns on insert;
     `workpack.status`; `hour_journal_header.locked`.
   - `signed_off_at` = the injected clock (S9).
4. Complete the job. On Declined, set `confirmMessage` = "Declined by Project Engineer: " + the trimmed reason. On Approved,
   leave it as it is (the release check left it null).

The gateway and the loop-back already exist in the model: no BPMN change. After a decline, Confirm → corrections (unchanged:
nothing saved) → header → evaluate → DMN runs the release check again, as the AC says.

## Design: screen (skill `new-task-screen`)
`frontend/src/features/workpacks/components/SignOffRelease/` (SignOffRelease.tsx, index.ts, fieldMessages or inline check)
on `TaskScreenShell`, titled "Sign off release":
- Read-only: the Workpack number, and the Work Pack's activity, drawing, department, date to complete, phases and materials, in
  the existing `context-panel` / `line-group` styles.
- Decision: radio "Approve release" (Approved) / "Decline" (Declined). Reason textarea "Reason for declining", shown only when
  Declined (form spec).
- `check()`: no decision → "Decision is required"; Declined with a blank reason → "Reason is required". Same texts as the
  backend.
- `variables()`: `{signOffDecision}` when Approved, `{signOffDecision, declineReason}` when Declined.
- Register it in `USER_TASKS` (signOffRelease) and `taskScreens.ts`.
- Visual: the existing tokens and classes (frontend-design guidance already applied in BPA-23); no new styling beyond the radio
  group, if needed.
- Confirm Workpack: no code change. The decline reason arrives as `confirmMessage`, and the screen shows it under "Returned:",
  as it does BPA-8's.

## Scenario-to-test table
Backend: a new `SignOffApiTest` on the BPA-8 pattern (app on the integration Camunda, workers on, the TestClock,
`@DirtiesContext`, `CamundaSteps`). The Background builds a Work Pack on the seed (DWG-118 A IFC, MAT-PIPE-06 reserved by
TR-5531): create → complete Confirm unchanged → release check Ready → sign-off task open. React: tests beside the new screen.

| # | Scenario | Backend test | React test | Red first? |
|---|---|---|---|---|
| 1 | Release is signed off | BPA9_releaseIsSignedOff: approve via the task API as project-engineer → status Released; one release_signoff row (Approved, signed_off_by project-engineer, signed_off_at = TODAY, task_key = the task's key); Header locked; audit rows | BPA9_releaseIsSignedOff: choose Approve → completes with `{signOffDecision: 'Approved'}` | Backend: yes (no worker). React: yes (no screen) |
| 2 | Project Engineer declines | BPA9_declineReturnsToAnalyst: decline "Wrong drawing revision" → still Draft; a Confirm task for time-method-analyst with `confirmMessage` "Declined by Project Engineer: Wrong drawing revision"; complete it unchanged → evaluate runs again, a new sign-off task opens | BPA9_declineReturnsToAnalyst: Decline + reason → sends both; and Confirm shows the decline text under "Returned:" | Backend: yes. React: yes (screen part) |
| 3 | A decline needs a reason | BPA9_declineNeedsReason: POST complete `{signOffDecision: Declined}` (and a whitespace-only reason) → 400 "Reason is required"; the same task still open; no release_signoff row | BPA9_declineNeedsReason: Decline, no reason → "Reason is required", no completion call | Backend: yes (today it completes). React: yes |
| 4 | A passed Work Pack waits for sign-off | BPA9_workPackWaitsForSignOff: after Ready → Draft, waits at UserTask_SignOffRelease, no UserTask_ScheduleWorkPack task | — | **Passes already** (BPA-8 built it); said in the PR |
| 5 | Other roles cannot sign off | BPA9_otherRolesCannotSignOff: as time-method-analyst, GET /tasks doesn't list it; complete → 403 | — | **Passes already** (BPA-26); said in the PR |
| 6 | A retried job records once | BPA9_rerunRecordsSignOffOnce: after a real approval, `handle` twice with a mocked JobClient and the same completedTaskKey → one row; Released; no new audit rows | — | Yes (stub worker throws) |

Non-scenario tests: `TaskValidityTest` (unit: the three messages); the React reason field shown only when Declined; the
`completedTaskKey` variable is set and a request that sends it gets 400 (in TaskApiTest or SignOffApiTest).

A stub `RecordSignOffWorker` with no `@JobWorker` keeps the test class compiling for the red run, as in BPA-8.

## Re-checks from earlier plans
- **BPA7_headerIsLockedOnceReleased** (BPA-7 plan, "When BPA-9 lands"): replace the stand-in (the SQL Released and lock, and
  the token move) with the real path. The correction to 450 → release check Ready (DWG-118, PIPE-06 and FLG-02 reserved in the
  seed) → the Project Engineer approves through the task API → `record-release-signoff` locks the Header. Same assertions:
  locked true, 450, no Confirm task; it now means something. Keep BPA-7's locked re-run unchanged.
- **BPA23_noCorrectionAfterRelease** (BPA-23 plan): the same replacement; its D8 incident part is unchanged.
- A shared `CamundaSteps.signOff(browser, instance, decision, reason)` helper for both, and for SignOffApiTest.
- `ProcessTestApplication` excludes `RecordSignOffWorker` (it needs the database).
- Record the results in this plan. Jira re-check notes go in the BPA-9 comment (shown first).

## Files that change
- Backend: new `worker/RecordSignOffWorker.java`, `persistence/ReleaseSignOffRepository.java`, `task/TaskValidity.java`;
  changed `TaskService.java` (validity check and `completedTaskKey`), `HourJournalHeaderRepository.java` (`lock`),
  `application.yml` (sign-off read list).
- Tests: new `SignOffApiTest`, `TaskValidityTest`; changed `HourJournalHeaderApiTest`, `ConfirmWorkPackApiTest`,
  `CamundaSteps`, `ProcessTestApplication`.
- Frontend: new `features/workpacks/components/SignOffRelease/*` and its test; changed `utils/constants.ts`,
  `features/tasks/taskScreens.ts`; one Confirm test for the decline text.
- Docs: `api/openapi.yaml` (`CompleteTaskRequest` description: completedTaskKey); `docs/adr/0004-…md` (amendment line);
  `REVIEW.md`; this plan. No BPMN, DMN, migration or seed change.

## Order of work
1. Commit this plan (Approved). You move statuses.
2. Tests first: the backend scenario tests, TaskValidityTest and the React tests, with the stub worker. Run them, and show the
   failures are for the expected reason (4 and 5 pass already).
3. Backend: TaskValidity and TaskService, then the repository and worker, then `lock`, then the allow-list. Green; the BPA-7 and
   BPA-23 re-checks; `./mvnw -B verify`.
4. Frontend: the screen, registry and constants (frontend-design guidance). `npm test`, lint, format, build.
5. Local stack (rebuild bpa-app), in the portal as project-engineer, then time-method-analyst:
   - approve → Released, Header locked, Operate at Schedule;
   - decline → Confirm shows the reason;
   - decline without a reason is blocked.
6. qa-reviewer → REVIEW.md → fixes → plan Closed → push → PR → wait for all CI checks green → tell Ahsan → Jira comments (text
   shown first). Ahsan merges.

## Risks
- `completedTaskKey` is a new server-set variable on every task. It is harmless to the other workers, but the contract text
  and ADR must say so.
- The task list's 5 s watch shows "the Work Pack is saved" after a sign-off completion, since no task returns to the Project
  Engineer. The wording is BPA-26's; I leave it and note it.
- The combined-run Confirm flake (BPA-23, BPA-8): watch it.

## Proof
`./mvnw -B verify` (red first, then green), `npm test`, lint, build, the local-stack run in the portal, and Operate.

## Questions
- Q1 (Khitaba): the decision messages "Decision is required" and "Decision must be Approved or Declined" are ASSUMPTION texts.
  The AC states only "Reason is required".
- Q2 (Khitaba): an Approved completion that carries a declineReason is accepted, and the reason is not stored (ASSUMPTION).

## Human-gap log
- Local Maven build uses the `subst S:` work-round and JAVA_HOME per command; `npm.cmd` in PowerShell; Docker Desktop must be
  running.
