# Plan: BPA-56 Production Supervisor can approve, return or reject a Work Pack's validated hours
Jira: BPA-56 (Story, epic BPA-4)
Story last updated: 2026-11-17T11:45:25.546+0400
Status: Approved (Ahsan, Build Lead, 18 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-56-record-hours-decision, from main fabbc27


## Context
BPA-53 sends hours and records the Timekeeper's decision. After Validated, the instance waits at `UserTask_ApproveHoursProduction`, which has no screen. The
shared job `record-hours-decision` also fails any step other than Timekeeper with no retries. BPA-56 builds the Production Supervisor's step:
- **Approved**: the submission moves to stage Ppc, and the instance waits at `UserTask_ApproveHoursPpc` (BPA-57).
- **Returned** (reason required): the lines go back to the Supervisor, and the submission is Closed with outcome Returned.
- **Rejected** (reason required): the lines become Rejected for good, and the submission is Closed with outcome Rejected. The Supervisor may book a new line
  for the same Worker, date and phase.

Segregation of duties is coded: the person who decides must not be the person who sent the hours. No BPMN, DMN or schema change.

## Readiness check (Definition of Ready, docs/02, against Jira 17 Nov 11:45)
| Check | State |
|---|---|
| Jira status | **Ready** (comment 10547). The development panel shows one merged PR, which is probably PR #48 (BPA-53); see Q5. No transition without asking |
| Template, persona, outcome | Met |
| Gherkin incl. unhappy paths | Met: 6 scenarios (one Scenario Outline with 2 rows) |
| BPMN element IDs exist | Met. `UserTask_ApproveHoursProduction` has candidate group `production-supervisor`. `ServiceTask_RecordProductionDecision` is `record-hours-decision` with header step=ProductionSupervisor. On `Gateway_IsProductionApproved`, `Flow_ProductionApproved` has `=hoursDecision = "Approved"` and goes to `UserTask_ApproveHoursPpc` (ppc-team); the default `Flow_ProductionNotApproved` goes to `Gateway_MergeBooking` and then Book hours |
| Every field has an SOR | Met (SOR Notes; `hours_submission`, `hours_decision` in V2) |
| DMN | None needed |
| No blocking question | Q1 (the segregation message and status) changes the build; see Questions |
| Persona `production-supervisor` | Met (BPA-48) |
| intent.md / spec.md accepted | **Not met** (Jira DoR box unchecked; still open from BPA-53) |
| Estimate ≤ 8 | Met (3). One worker step, a task-API check, a screen and a notice; most of it reuses BPA-53 |

## What already exists (closed plans BPA-48 and BPA-53; history only)
- **`RecordHoursDecisionWorker`** (`record-hours-decision`) reads the `step` header.
  - Timekeeper is built. Any other step fails with `retries(0)` and the message "Step … is not built yet (BPA-56, BPA-57)".
  - Re-run key: `hours_decision.task_key` (UNIQUE, `ON CONFLICT DO NOTHING`).
  - Who decided: `completedBy`.
  - Inputs: `hoursSubmissionId`, `hoursDecision`, `hoursReason`, `completedTaskKey`.
- **`HoursSubmissionRepository`** has `send` (round rule: 1 + the instance's **Closed** submissions), `decideAsTimekeeper` (Validated → ProductionSupervisor;
  Returned → lines Returned, Closed/Returned), `find`, `stageOfLine` and `inApproval`. `inApproval` covers the stages Timekeeper, ProductionSupervisor and Ppc.
- **`SubmitHoursWorker`** sends only Draft and Returned lines and clears `hoursDecision` and `hoursReason`.
- **`TaskValidity`**:
  - SignOff and ValidateHours have their own checks.
  - "Decision is required" and "Reason is required" are existing texts.
  - "Decision must be Validated or Returned" is an ASSUMPTION text.
- **`GET /api/v1/hours-submissions/{id}`** is timekeeper only. `SecurityConfig` has one `requestMatchers` that holds both the amend path and the read path.
- **Validate hours screen**: `ValidateHours.tsx`, with `hoursDecisionMessages.ts` and `hoursSubmissionService.ts`.
- **Book hours** shows "Hours returned: <reason>" only when `hoursDecision === 'Returned'`.
- **Rejected lines (BPA-48, BPA-51)**:
  - The partial unique index `(digital_timesheet_id, work_date, phase) WHERE status <> 'Rejected'` lets a Rejected line coexist with a new one.
  - `duplicate`, `workerHoursOn` and `phaseHours` ignore Rejected lines.
  - `sendable` takes only Draft and Returned.
  - The Book hours grid's `lineOf` skips Rejected lines, so the cell shows "+" and can be booked again.

## Design
### 1. Worker: extend `record-hours-decision` for step=ProductionSupervisor (no second worker)
- The `step` header picks the allowed decisions:
  - Timekeeper: {Validated, Returned}.
  - ProductionSupervisor: {Approved, Returned, Rejected}.
  - **Ppc stays refused**, as before (BPA-57); its message text becomes "Step Ppc of record-hours-decision is not built yet (BPA-57)".
- A decision that is unknown for the step, or a Returned or Rejected decision without a reason, fails the job with no retries. The task API refuses both first.
- The repository method `decideAsTimekeeper` becomes **`decide(step, submissionId, taskKey, decision, reason, username)`**. It is one transaction with the
  same re-run key. After the `hours_decision` insert (`ON CONFLICT (task_key) DO NOTHING`; when it is already recorded, nothing else happens):
  | Step / decision | Lines | Submission |
  |---|---|---|
  | Timekeeper Validated | stay Submitted | stage → ProductionSupervisor |
  | ProductionSupervisor Approved | stay Submitted | stage → Ppc |
  | Returned (either step) | Submitted → Returned | stage → Closed, outcome Returned |
  | ProductionSupervisor Rejected | Submitted → Rejected | stage → Closed, outcome Rejected |
- Audit rows go to every changed column (D64), with the old stage taken from the row.
- **Defensive:** every `UPDATE … SET stage` also carries `WHERE stage = <step>`. A decision for a submission that is not at that step fails the job (no
  retries, a plain message) instead of moving a stage it should not.
- The worker completes with no output, as in BPA-53. `Gateway_IsProductionApproved` reads the `hoursDecision` that the completion already set.

### 2. Segregation of duties (data model §13.2.2: "worker-checked"; FRD-21 §21.8.1 spirit; **ASSUMPTION** for hours)
- **Worker (as the data model says):** if `completedBy` equals the submission's `submitted_by`, the job fails with no retries.
  - The message is "Segregation of duties: <user> sent these hours and cannot decide on them" (**ASSUMPTION** text).
  - It applies to step ProductionSupervisor, and to Ppc later. It does not apply to the Timekeeper, because the data model names only Production Supervisor
    and PPC.
- **Task API pre-check (recommended, Q1):** without it, a violation would complete the user task and leave the instance in an incident.
  - With it, `TaskService.complete` refuses before completing. A new `HoursSegregationCheck` reads `hoursSubmissionId` from the task's variables and looks up
    `submitted_by`.
  - The task stays open. The answer is **403** with the same text (ASSUMPTION status and text).
  - It applies only to `UserTask_ApproveHoursProduction`. `UserTask_ApproveHoursPpc` joins in BPA-57.
- It always holds today, because `supervisor` sends and `production-supervisor` decides. Extra tests force it by setting `submitted_by` to
  `production-supervisor` in SQL.

### 3. Task API for `UserTask_ApproveHoursProduction`
- `application.yml` allow-list: write `[hoursDecision, hoursReason]`, read `[hoursSubmissionId]`.
  - The read list deliberately leaves out `hoursDecision`, so the Timekeeper's "Validated" is not offered back.
- `TaskSummary.elementId` gains `UserTask_ApproveHoursProduction` in `api/openapi.yaml`.
- **`TaskValidity`**:
  - No decision → "Decision is required".
  - A decision outside Approved, Returned or Rejected → "Decision must be Approved, Returned or Rejected" (**ASSUMPTION** text, the pattern of BPA-9 and BPA-53).
  - Returned or Rejected with a blank reason → "Reason is required" (Gherkin).
- **403** for any other group comes from the candidate group `production-supervisor` (BPA-26). Scenario 6 tests timekeeper.

### 4. Read
- `GET /api/v1/hours-submissions/{id}` gains group **production-supervisor**. `SecurityConfig` splits the BPA-53 matcher:
  - `/api/v1/hours-submissions/**` → timekeeper and production-supervisor.
  - `/api/v1/digital-timesheet-lines/*/amendments` → **timekeeper only**: the Production Supervisor does not edit hours (Jira, Q4).

### 5. Rejected lines and the round rule (checks, no new code expected)
- Rejected lines stay Rejected. They are never resent (`sendable`: Draft and Returned only), do not count towards the budget or the daily limit, and a new line for
  the same Worker, date and phase can be booked thanks to the partial index.
- **Extra test:** `aRejectedLineCanBeBookedAgainAndIsNotResent`. After a reject, POST the same Worker, date and phase gives 201. Send again: the new
  submission holds only the new line, and the Rejected line keeps its old `hours_submission_id` and status.
- **The round rule:** Returned and Rejected both close the round (stage Closed), so the next send gets `round_no` + 1. The same extra test asserts round 2.
  Approved does not close it, so no new send can happen while the submission is at Ppc (the in-approval rule; see Risks).

### 6. Frontend
- **New screen `ApproveHoursProduction`** (new-task-screen skill; `features/timesheets/components/ApproveHoursProduction`), registered for
  `UserTask_ApproveHoursProduction`:
  - The Work Pack, the round, who sent it, and the lines **read-only** (no Amend).
  - A decision: **Approve**, **Return to Supervisor**, **Reject** (ASSUMPTION labels).
  - A reason for the last two, checked before the call, and "Complete task" through the shell.
- **Shared read-only table:** the lines table moves out of `ValidateHours.tsx` into `features/timesheets/components/SubmissionLines` with an optional Amend
  column. ValidateHours uses it with Amend, ApproveHoursProduction without. This is a refactor of BPA-53 code, and its tests must stay green unchanged.
- **Decision checks:** `hoursDecisionMessages` takes the decision and the reason. "Reason is required" applies to Returned and Rejected, and the type widens to
  `'' | 'Validated' | 'Approved' | 'Returned' | 'Rejected'`.
- **Book hours notice:** "Hours returned: <reason>" stays as it is. When `hoursDecision` is **Rejected** it shows "Hours rejected: <reason>" (**ASSUMPTION**,
  Q2; Jira states the reason only for Returned).
- **`TaskList`:** "Decision recorded" also after an ApproveHoursProduction completion.
- `taskScreens.ts`, `utils/constants.ts` (`approveHoursProduction`).

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml`: `TaskSummary.elementId` + `UserTask_ApproveHoursProduction`; the `GET /api/v1/hours-submissions/{id}` description (groups) |
| Worker | `worker/RecordHoursDecisionWorker.java` (step table, segregation, Ppc message) |
| Persistence | `persistence/HoursSubmissionRepository.java` (`decideAsTimekeeper` → `decide`, Approved and Rejected, stage guard, `submittedBy(id)`) |
| Task API | `task/TaskValidity.java` (ApproveHoursProduction); new `task/HoursSegregationCheck.java`; `task/TaskService.java` (calls it before completing) |
| Security, config | `config/SecurityConfig.java` (`PRODUCTION_SUPERVISOR` constant if absent; split matcher); `application.yml` (allow-list) |
| Frontend | new `features/timesheets/components/ApproveHoursProduction/*` (+ test), new `components/SubmissionLines/*`; `ValidateHours.tsx` (uses it); `hoursDecisionMessages.ts`; `BookHours.tsx` (Rejected notice); `TaskList.tsx`; `taskScreens.ts`; `utils/constants.ts` |
| Tests | new `HoursProductionApprovalApiTest` (6 BPA56_ + extras); `task/TaskValidityTest` (a row); React tests; `BookHours.test.tsx` (one BPA56 test added) |
| Docs | data model §13.2.1/13.2.2 "as built" notes; plan; REVIEW.md; `intent/README.md` |

Not changed: BPMN, DMN, database, `SubmitHoursWorker`, `PrepareHoursSubmissionWorker`, `HourLineService`.

## Order of work
1. Approve; commit this plan (`Approved`) on `BPA-56-record-hours-decision`; regenerate `intent/README.md`. No Jira transition without asking (Q5).
2. Contract; the 6 BPA56_ tests (and extras); run and show red. Expected: all 6 red:
   - 1 to 3: the job fails at step ProductionSupervisor, so there is no Ppc task and no stage change.
   - 4 and 6: the task is not served, so the API answers 404 instead of 400 or 403.
   - 5: the worker refuses the step.
3. Backend: repository `decide`, the worker steps, segregation (worker and task API), TaskValidity, allow-list, security. Green: the class, then
   `HoursValidationApiTest` (BPA-53 must stay green after the rename), `BookHoursApiTest`, `TaskApiTest`, `TaskValidityTest`.
4. Frontend tests first (red), then `SubmissionLines`, the screen, the decision checks, the Book hours notice and TaskList; lint, format, test, build.
5. Docs; full `./mvnw -B verify`; local stack (Proof); `qa-reviewer`; fixes; REVIEW.md; plan Closed; push, PR and Jira comment only when asked.

## Scenario-to-test table
`HoursProductionApprovalApiTest` setup:
- Clock 18 Nov 2026, Asia/Riyadh.
- Background: `BookHoursFixture` (WP-000457), with Draft lines E1021 16 Nov Cutting 8 and E1099 16 Nov Cutting 7 made by SQL.
- An instance started at `UserTask_BookHours`, sent by `supervisor` and validated by `timekeeper` through the task API, so it waits at
  `UserTask_ApproveHoursProduction`.
- The workers run in the app.

| # | Scenario | Test | Asserts | Expected first run |
|---|---|---|---|---|
| 1 | The Production Supervisor approves | `BPA56_productionSupervisorApproves` | complete as production-supervisor with `Approved` → 204; stage `Ppc`; a `UserTask_ApproveHoursPpc` task (group ppc-team); both lines Submitted; `hours_decision` step ProductionSupervisor, Approved, decided_by production-supervisor | red: incident at step ProductionSupervisor |
| 2 | The Production Supervisor returns | `BPA56_productionSupervisorReturns` | `Returned` + "E1099 was on Welding" → both lines Returned; Closed/Returned; a Book hours task whose `variables.hoursReason` is the reason | red |
| 3 | The Production Supervisor rejects | `BPA56_productionSupervisorRejects` | `Rejected` + "Hours not worked on this Work Pack" → both lines Rejected; Closed/Rejected; a Book hours task again | red |
| 4 | Return and reject need a reason (Outline, 2 rows, one test) | `BPA56_returnOrRejectNeedsAReason` | for Returned and Rejected with an empty reason → 400 "Reason is required"; the task stays open; no `hours_decision` | red (404, task not served) |
| 5 | A re-run records one decision | `BPA56_rerunRecordsOneDecision` | approve through the API, then call `RecordHoursDecisionWorker.handle` again with a mocked `ActivatedJob` (same variables, header step=ProductionSupervisor, same `completedTaskKey`) and a deep-stub `JobClient` (new-job-worker skill) → exactly one `hours_decision` for that task key; stage still Ppc; no new audit rows | red (step refused) |
| 6 | Other personas cannot approve | `BPA56_otherPersonasCannotApproveProduction` | timekeeper completes → 403; the task stays open | red (404) |

Extra tests (not scenarios):
- `aRejectedLineCanBeBookedAgainAndIsNotResent`: re-book 201; round 2 holds only the new line; the Rejected line is untouched.
- `segregationRefusesTheSenderAtTheTaskApi`: `submitted_by` set to production-supervisor gives 403 with the ASSUMPTION text, and the task stays open.
- `segregationAlsoFailsTheJob`: the worker called directly with that state makes a fail command with retries 0, and no decision is written.
- `aDecisionForASubmissionAtAnotherStageFailsTheJob`.
- The read endpoint for production-supervisor: 200; amend as production-supervisor: 403.
- `TaskValidityTest` rows.
- React:
  - ApproveHoursProduction shows the lines read-only (no Amend).
  - It blocks Return or Reject without a reason, and blocks no decision.
  - It sends the right variables for each decision and shows the server's refusal.
  - Book hours shows "Hours rejected: <reason>".
  - ValidateHours tests unchanged after the `SubmissionLines` extraction.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=HoursProductionApprovalApiTest test` (6 scenario tests, 0 failures); `gh pr checks <n>` |
| Task screen; allow-list updated | `cd frontend && npm test -- ApproveHoursProduction && npm run build`; `grep -n "UserTask_ApproveHoursProduction" backend/src/main/resources/application.yml` |
| Process runs end to end in the local stack | see the run below |
| BPMN and DMN match deployed | no model change; `curl -u demo:demo localhost:8080/v2/process-definitions/<key>/xml` diff unchanged |
| Open questions resolved or logged | Questions below, as Jira comments (shown first) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-56.md` |
| Demoed at sprint review | Human gap |

**End-to-end run in the local stack:**
1. Rebuild `bpa-app` and `frontend`.
2. Reset WP-000011 to Draft lines and start a fresh instance at Book hours, as in the BPA-53 proof.
3. In the portal:
   - As supervisor: book and **Send**.
   - As timekeeper: **Validate**.
   - As production-supervisor: **Reject** with a reason.
   - As supervisor: see "Hours rejected: …", **re-book** the same Worker, date and phase (201), then **Send** (round 2).
   - As timekeeper: **Validate**.
   - As production-supervisor: **Approve**.
4. `psql` should show:
   - round 1 Closed/Rejected, with its lines Rejected;
   - round 2 at stage Ppc, with its lines Submitted;
   - decisions Validated, Rejected, Validated, Approved.
5. Operate should show the instance waiting at `UserTask_ApproveHoursPpc`.

## Risks
- **After Approved the instance waits at `UserTask_ApproveHoursPpc`**, which has no screen or worker (BPA-57).
  - The submission stays at stage Ppc, so the Work Pack stays "in approval" and **takes no new hours** (form or upload) until BPA-57.
  - The `record-hours-decision` job for step Ppc still fails with no retries, so Operate shows an incident once a PPC task is completed outside the portal.
  - Demo with a Work Pack you can spare (WP-000011 after a reset).
- **Shared variable names:** both steps write `hoursDecision` and `hoursReason`. The Production read list leaves out `hoursDecision`, and `SubmitHoursWorker`
  clears both on the next send, so an old decision is never shown as the new one.
- **Segregation pre-check in the task API** is new behaviour for a generic service. It is limited to one element ID, and it reads the database through the
  repository.
- **The `SubmissionLines` refactor** touches BPA-53's screen. Its tests must pass unchanged.
- **Older instances** (model version 1, started before BPA-51) reach this step the same way; no model change.

## Questions
1. **Segregation of duties: where, which message, which status?** The data model says "worker-checked" but gives no text.
   - Recommendation: check in the worker (fail with no retries; that is the data model's rule) **and** in the task API before completion (**403**, so the task stays
     open instead of an incident).
   - Text for both: "Segregation of duties: <user> sent these hours and cannot decide on them" (**ASSUMPTION**).
   - Alternative: the worker only, which leaves an incident on a violation.
2. **Book hours after a reject:** Jira states the reason only for Returned ("showing …"). Recommendation: show "Hours rejected: <reason>" (**ASSUMPTION**), since
   Book hours is where the Supervisor re-books. Or show nothing for Rejected?
3. **After Approved the Work Pack takes no new hours until BPA-57** (see Risks). Recorded; nothing to decide unless you want BPA-57 built before this is demoed.
4. **Amend stays timekeeper-only:** Jira says "the Production Supervisor does not edit hours". The matcher split keeps the amend path timekeeper-only, and a test
   asserts 403 for production-supervisor. Confirm.
5. **Jira:** BPA-56 is Ready. Its "merged PR" is probably PR #48 (BPA-53 mentions BPA-56 in the worker's message). I will ask before any transition.
6. **Texts not in Jira (ASSUMPTION):**
   - "Decision must be Approved, Returned or Rejected".
   - The segregation text.
   - "Hours rejected: <reason>".
   - The labels "Approve", "Return to Supervisor", "Reject".
   - The Ppc refusal text.
   - To comment in Jira after a yes.

## Human-gap log
- Plan written in plan mode; the branch existed already (empty at main fabbc27). Approved by Ahsan on 18 Nov 2026 without answers to Q1-Q6 one by one, so the recommended options are applied and recorded as ASSUMPTIONs (Jira comment after a yes).
