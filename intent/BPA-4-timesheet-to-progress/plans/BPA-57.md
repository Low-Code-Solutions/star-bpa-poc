# Plan: BPA-57 PPC Team can approve, return or reject production hours as the final step
Jira: BPA-57 (Story, epic BPA-4)
Story last updated: 2026-11-18T17:17:52.494+0400
Status: Approved (Ahsan, Build Lead, 18 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-57-ppc-approve-hours, from main 3ad3f25


## Context
After BPA-56 a Production-approved submission waits at `UserTask_ApproveHoursPpc`, which has no screen. Its job (`record-hours-decision`, step Ppc) fails with
"Step Ppc of record-hours-decision is not built yet (BPA-57)", and the Work Pack stays "in approval".

BPA-57 builds the last step:
- **Approved:** the lines become Approved and final (FRD-5-12 §5.12.8.1; decision (a): no correction in the POC). The submission is Closed with outcome
  Approved, and the instance loops back to Book hours for the next round (decision (b)).
- **Returned and Rejected:** as BPA-56.

The segregation rule widens: the PPC decider must be neither the sender nor an earlier decider of the same submission. No BPMN, DMN or schema change.

## Readiness check (Definition of Ready, docs/02, against Jira 18 Nov 17:17)
| Check | State |
|---|---|
| Jira status | **Ready** (comment 10548). The development panel shows 2 merged PRs, probably #48 and #49, which name BPA-57 in the worker's Ppc message. No transition without asking (Q5) |
| Template, persona, outcome | Met |
| Gherkin incl. unhappy paths | Met: 7 scenarios |
| BPMN element IDs exist | Met. `UserTask_ApproveHoursPpc` has candidate group `ppc-team`. `ServiceTask_RecordPpcDecision` is `record-hours-decision` with header step=Ppc. `Flow_PpcDecided` goes to `Gateway_MergeBooking` and then Book hours for every decision. `BoundaryEvent_WorkPackCompleted` (E2-16) sits on Book hours and is not touched (Q4) |
| Every field has an SOR | Met ("as BPA-56 with step Ppc"; `digital_timesheet_line.status` → Approved) |
| DMN | None needed |
| No blocking question | **Q1** (segregation status and text: BPA-56 says 403 with its own text, while this Gherkin says 409 "You already decided these hours at an earlier step") and **Q2** (does "earlier decider" include the Timekeeper?) change the build |
| intent.md / spec.md accepted | **Not met** (Jira DoR box unchecked; open since BPA-53) |
| Estimate ≤ 8 | Met (3). One step in the existing worker, a widened check, a validity rule and a second screen on the BPA-56 one |

## What already exists (closed plans BPA-50, BPA-53, BPA-56; history only)
- **`RecordHoursDecisionWorker`** keeps a decision table per step (Timekeeper, ProductionSupervisor). Ppc is not in the table, so the job fails with no retries.
  Unknown decision, missing reason, segregation and wrong stage also fail with no retries.
- **Segregation (BPA-56):**
  - The worker fails with `segregationMessage(user)` = "Segregation of duties: <user> sent these hours and cannot decide on them" when `completedBy` equals
    `submitted_by`, for every step except Timekeeper.
  - The task API's `HoursSegregationCheck` (elements: `UserTask_ApproveHoursProduction`) answers **403** with the same text. `TaskService.complete` calls it
    after `TaskValidity` and before assigning.
- **`HoursSubmissionRepository.decide(step, ...)`**:
  - The decision row is inserted first (`ON CONFLICT (task_key)`).
  - Moves on approval: Timekeeper Validated → ProductionSupervisor; any other non-closing decision → Ppc.
  - Returned and Rejected close the submission with that outcome and move the lines.
  - `moveStage` guards with `WHERE stage = <step>` (`WrongStage`). There are also `submittedBy`, `find` and `inApproval`.
- **Round rule (BPA-53):** round = 1 + the instance's Closed submissions. `inApproval` covers the stages Timekeeper, ProductionSupervisor and Ppc.
- **Line rules (BPA-50, BPA-53):**
  - `HourLineService.change` and `delete` refuse any status other than Draft or Returned: 409 "Line is not editable (status <status>)".
  - `amend` (Timekeeper) needs Submitted and stage Timekeeper, else the same 409.
  - The Book hours screen does not edit or delete an Approved line.
- **`TaskValidity`** for `UserTask_ApproveHoursProduction`:
  - "Decision is required".
  - "Decision must be Approved, Returned or Rejected" (ASSUMPTION).
  - "Reason is required" for Returned and Rejected.
- **Read endpoint:** `GET /api/v1/hours-submissions/**` is timekeeper and production-supervisor. The amend endpoint is timekeeper only.
- **Approve hours (Production) screen** (`ApproveHoursProduction.tsx`) uses `SubmissionLines`, `hoursDecisionMessages` and the three choices.
- **`TaskList`** shows "Decision recorded" after a decision task.
- Nothing in the backend publishes `WorkPackCompleted` (grep: no Java file names it).

## Design
### 1. Worker: build step Ppc in `record-hours-decision`
- The decision table gains `"Ppc" → {Approved, Returned, Rejected}`. The "not built yet" branch stays only for an unknown step.
- `HoursSubmissionRepository.decide` gets an explicit transition per step and decision:
  | Step / decision | Lines | Submission |
  |---|---|---|
  | Timekeeper Validated | stay Submitted | → ProductionSupervisor |
  | ProductionSupervisor Approved | stay Submitted | → Ppc |
  | **Ppc Approved** | **Submitted → Approved** | **→ Closed, outcome Approved** |
  | Returned (any step) | Submitted → Returned | → Closed, outcome Returned |
  | Rejected (ProductionSupervisor, Ppc) | Submitted → Rejected | → Closed, outcome Rejected |
- The same re-run key applies (one `hours_decision` per `task_key`), with the same stage guard and audit rows per changed column (D64).
- The `Outcome` log enum is unchanged (APPROVED serves both steps).
- `Flow_PpcDecided` goes to `Gateway_MergeBooking` and then `UserTask_BookHours` for every decision: Approved is the next round (decision (b)); Returned and
  Rejected are corrections. The worker completes with no output, as before.
- **Next round, no new code:**
  - An Approved submission is Closed, so `inApproval` clears and the Supervisor can book again (scenario 5).
  - The next send gets `round_no` = 1 + Closed count, that is round + 1. An extra test asserts round 2.
- Book hours after a PPC approval shows no notice: the notice covers Returned and Rejected only, and `hoursDecision` is Approved.

### 2. Segregation of duties for PPC (Jira widens BPA-56's rule; ASSUMPTION as BPA-56)
Recommended (Q1 option A): **keep BPA-56's rule and text for "sent"; add the Jira rule and text for "earlier decider"**.
- **Sent:** for Ppc as for Production: **403** "Segregation of duties: <user> sent these hours and cannot decide on them". BPA-56's behaviour is unchanged.
- **Earlier decider:** at step Ppc, the user who completes must not be the `decided_by` of an earlier `hours_decision` of the same submission. The answer is
  **409** "You already decided these hours at an earlier step" (Gherkin scenario 7: status and text from Jira).
- **Which earlier steps** (Q2): recommended **any earlier decision on the submission**, Timekeeper included, as Jira says "an earlier decider".
- **Not widened:** the Production step keeps BPA-56's sender-only check. Widening it would change BPA-56's behaviour, which waits for your answer.
- **Order:** the sender check runs first, so a user who both sent and decided earlier gets the 403.
- **Where:**
  - `HoursSegregationCheck` covers `UserTask_ApproveHoursPpc` too and returns a status with its text, instead of always 403. `TaskService` answers with that
    status, and the task stays open.
  - The worker makes the same two checks for step Ppc and fails the job with no retries and the same text (the backstop, as in BPA-56).
  - The repository gains `earlierDeciders(submissionId)`, the `decided_by` values of the submission's decisions.

### 3. Task API for `UserTask_ApproveHoursPpc`
- `application.yml` allow-list: write `[hoursDecision, hoursReason]`, read `[hoursSubmissionId]`, the same as Production (the old decision is not offered back).
- `TaskSummary.elementId` gains `UserTask_ApproveHoursPpc` in `api/openapi.yaml`.
- On `POST /tasks/{key}/complete`, the 409 gains an example `earlierDecider`, with the Jira text.
- **`TaskValidity`:** `UserTask_ApproveHoursPpc` uses the Production check, unchanged: "Decision is required", "Decision must be Approved, Returned or Rejected"
  (ASSUMPTION, BPA-56), and "Reason is required" unless Approved.
- **403** for any group other than ppc-team comes from the candidate group (BPA-26). Scenario 6 tests production-supervisor.

### 4. Read
- `GET /api/v1/hours-submissions/{id}` gains **ppc-team** (`SecurityConfig`: `hasAnyAuthority(TIMEKEEPER, PRODUCTION_SUPERVISOR, PPC_TEAM)`).
- The amend endpoint stays timekeeper only.

### 5. Approved lines are final (decision (a); FRD-5-12 §5.12.8.1)
- **PUT and DELETE** (Supervisor): BPA-50's `checkEditable` already answers 409 "Line is not editable (status Approved)". Scenario 2 is the exact Jira text.
- **Amend** (Timekeeper, Q3): `amend` already needs Submitted at stage Timekeeper, so it also refuses an Approved line with 409 "Line is not editable (status
  Approved)". An extra test covers it.
- **Red-run note:** these rules exist, so scenario 2 only fails because its Given ("PPC approved the hours") cannot be reached: the Ppc job fails today. Once
  step Ppc is built it goes green with no change to the line rules. The red note will say so.

### 6. Frontend
- **Reuse BPA-56's screen:** only the title differs (the group is the backend's candidate group).
  - `ApproveHoursProduction.tsx` becomes a shared `ApproveHours` component with a `title` prop.
  - `ApproveHoursProduction` ("Approve hours (Production)") and the new `ApproveHoursPpc` ("Approve hours (PPC)") are thin wrappers.
  - The BPA-56 tests stay unchanged and green.
- `taskScreens.ts` registers `UserTask_ApproveHoursPpc`. `utils/constants.ts` gains `approveHoursPpc`.
- `TaskList` shows "Decision recorded" after a PPC completion too.
- A React test for the PPC wrapper: its title, the lines read-only, and that Approve completes with `{hoursDecision: 'Approved'}`. A second shows the server's 409
  text.

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml`: `TaskSummary.elementId` + `UserTask_ApproveHoursPpc`; read endpoint description (groups); complete 409 example `earlierDecider` |
| Worker | `worker/RecordHoursDecisionWorker.java` (Ppc in the table; sender and earlier-decider checks for Ppc) |
| Persistence | `persistence/HoursSubmissionRepository.java` (transition table with Ppc Approved; `earlierDeciders`) |
| Task API | `task/HoursSegregationCheck.java` (both elements; status and text; earlier decider for Ppc); `task/TaskService.java` (uses the returned status); `task/TaskValidity.java` (Ppc shares the Production check) |
| Security, config | `config/SecurityConfig.java` (read: + ppc-team); `application.yml` (allow-list) |
| Frontend | `ApproveHoursProduction/*` → shared `ApproveHours` + wrappers; new `ApproveHoursPpc/*` (+ test); `taskScreens.ts`; `utils/constants.ts`; `TaskList.tsx` |
| Tests | new `HoursPpcApprovalApiTest` (7 BPA57_ + extras); `task/TaskValidityTest` (a row) |
| Docs | data model §13.2.1/13.2.2 "as built" notes (Ppc, earlier-decider rule); plan; REVIEW.md; `intent/README.md` |

Not changed: BPMN, DMN, database, `HourLineService`, `SubmitHoursWorker`, the Book hours screen.

## Order of work
1. Approve; commit this plan (`Approved`); regenerate `intent/README.md`. No Jira transition without asking (Q5).
2. Contract; the 7 BPA57_ tests and extras. Run them, and show red **before any production code**, committed separately. The frontend red run gets its own commit
   too (the BPA-56 review finding). Expected:
   - All 7 red. Scenarios 1–5 fail because the Ppc job fails, so there is no Approved, Returned or Rejected and no Book hours task.
   - Scenarios 6 and 7 get 404: the PPC task is not served.
3. Backend: repository transitions, worker step Ppc, segregation (task API and worker), TaskValidity, allow-list, security. Green: the class, then
   `HoursProductionApprovalApiTest`, `HoursValidationApiTest`, `TaskApiTest`, `TaskValidityTest`, `BookHoursApiTest`.
4. Frontend: tests (red, committed), then the `ApproveHours` extraction and the PPC wrapper; lint, format, test, build.
5. Docs; full `./mvnw -B verify`; local stack (Proof); `qa-reviewer`; fixes; REVIEW.md; plan Closed. Push, PR and Jira comment only when asked.

## Scenario-to-test table
`HoursPpcApprovalApiTest` setup:
- Clock 18 Nov 2026, Asia/Riyadh.
- Background: `BookHoursFixture` (WP-000457), with Draft lines E1021 16 Nov Cutting 8 and E1099 16 Nov Cutting 7 by SQL.
- An instance started at Book hours, then through the task API: sent by supervisor, validated by timekeeper, approved by production-supervisor. It waits at
  `UserTask_ApproveHoursPpc`.
- The workers run in the app.

| # | Scenario | Test | Asserts | Expected first run |
|---|---|---|---|---|
| 1 | PPC approves the hours | `BPA57_ppcApprovesHours` | ppc-team completes with `Approved` → 204; both lines Approved; submission Closed/Approved; `hours_decision` step Ppc, Approved, decided_by ppc-team | red: the Ppc job fails |
| 2 | Approved lines cannot change | `BPA57_approvedLinesCannotChange` | after the PPC approval, supervisor PUT E1021's 16 Nov line to 9 → 409 "Line is not editable (status Approved)"; hours still 8 | red, from its Given only (see Design 5) |
| 3 | PPC returns the hours | `BPA57_ppcReturnsHours` | `Returned` + "Check phase" → both lines Returned; the instance waits at `UserTask_BookHours` | red |
| 4 | PPC rejects the hours | `BPA57_ppcRejectsHours` | `Rejected` + "Duplicate of WP-000461" → both lines Rejected; Closed/Rejected | red |
| 5 | After approval the next round starts | `BPA57_nextRoundAfterApproval` | after Approved, a `UserTask_BookHours` task for group supervisor (candidate groups); supervisor POSTs a line for E1021 on 18 Nov 2026 → 201 | red |
| 6 | Other personas cannot approve | `BPA57_otherPersonasCannotApprovePpc` | production-supervisor completes → 403 "You are not allowed to work on this task"; the task stays open | red (404) |
| 7 | The PPC decider is not an earlier decider (ASSUMPTION) | `BPA57_ppcDeciderMustDifferFromEarlierDeciders` | SQL sets the ProductionSupervisor decision's `decided_by` to ppc-team; ppc-team completes `Approved` → 409 "You already decided these hours at an earlier step"; the task stays open; no Ppc decision | red (404) |

Extra tests (not scenarios):
- `aSecondRoundAfterApprovalIsRoundTwo`: book, send, and round 2 is at stage Timekeeper while round 1 stays Closed/Approved.
- `theTimekeeperCannotAmendAnApprovedLine`: 409 "Line is not editable (status Approved)" (Q3).
- `anApprovedLineCannotBeDeleted`: 409.
- `theSenderIsRefusedAtPpcWithBpa56sText`: 403 with "Segregation of duties: ppc-team sent these hours …".
- `anEarlierTimekeeperDeciderIsRefusedAtPpc` (Q2): 409.
- `aRetriedPpcJobRecordsOneDecision`: the worker is called twice with mocks.
- `thePpcJobFailsForAnEarlierDecider`: fail with retries 0 and the Jira text.
- The read endpoint for ppc-team: 200.
- `TaskValidityTest` row.
- React: the PPC wrapper (title, read-only, Approve completes, server 409 text shown).

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=HoursPpcApprovalApiTest test` (7 scenario tests, 0 failures); `gh pr checks <n>` |
| Task screen | `cd frontend && npm test -- ApproveHoursPpc && npm run build`; `grep -n "UserTask_ApproveHoursPpc" backend/src/main/resources/application.yml frontend/src/features/tasks/taskScreens.ts` |
| Process runs end to end in the local stack | see the run below |
| BPMN and DMN match deployed | no model change; `curl -u demo:demo localhost:8080/v2/process-definitions/<key>/xml` diff unchanged |
| Open questions resolved or logged | Questions below, as Jira comments (shown first) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-57.md` |
| Demoed at sprint review | Human gap |

**End-to-end run in the local stack:**
1. Rebuild `bpa-app` and `frontend`.
2. Reset WP-000011 and start a fresh instance at Book hours, as in the BPA-56 proof.
3. In the portal:
   - As supervisor: book and **Send**.
   - As timekeeper: **Validate**.
   - As production-supervisor: **Approve**.
   - As ppc-team: open **Approve hours (PPC)** and **Approve**.
   - As supervisor: the next Book hours task is open. The approved cells are read-only ("Line is not editable (status Approved)"). **Book a new line** (201) and
     **Send** (round 2).
4. `psql` should show:
   - round 1 Closed/Approved, with its lines Approved;
   - decisions Validated, Approved (ProductionSupervisor), Approved (Ppc);
   - round 2 at stage Timekeeper.
5. Operate should show the instance at `UserTask_ValidateHours` (round 2).

## Risks
- **Approved lines cannot be corrected** in the POC (decision (a)). A wrong approval stays wrong until the backlog story for corrections exists.
- **The instance never ends** in BPA-57. It loops to Book hours after every approval until `WorkPackCompleted` arrives (E2-16, not built), so every
  Epic 2 instance stays active.
- **Reminder timers:** `BoundaryEvent_HoursWaitingPpc` (BPA-74) fires every 24 h while the PPC task is open, and `send-hours-reminder` has no worker yet. A PPC
  task left open for a day gets a job no worker takes. This is the same as on the other two steps, so it is not new.
- **The segregation status differs by case** (403 sent, 409 earlier decider) if Q1 option A is chosen. One rule for both would change BPA-56's answer.
- **Shared screen refactor** touches BPA-56's screen; its tests must pass unchanged.

## Questions
1. **Segregation for PPC: status and text.** BPA-56 answers 403 "Segregation of duties: <user> sent these hours and cannot decide on them". BPA-57's Gherkin
   wants 409 "You already decided these hours at an earlier step" for an earlier decider.
   - **Recommended (A):** keep BPA-56's 403 and text for "sent" (at Production and Ppc); add 409 with the Jira text for "earlier decider" (Ppc only). Both
     texts then come from a source, and BPA-56 is unchanged.
   - Alternative (B): one rule for both cases with the Jira 409 text, which changes BPA-56's sender answer at Production (its tests and its Jira
     ASSUMPTION comment).
   - Alternative (C): one rule with 403 and BPA-56's style of text, which contradicts scenario 7.
2. **Does "earlier decider" include the Timekeeper's Validated?** Jira says "the sender or an earlier decider of the same submission". Recommended: **yes**,
   any earlier decision on the submission (an extra test). BPA-56's Production check stays sender-only unless you say to widen it too.
3. **Amend on an Approved line:** the Timekeeper's amend already refuses it (Submitted at stage Timekeeper only), with 409 "Line is not editable (status
   Approved)". An extra test proves it. Confirm.
4. **`WorkPackCompleted`:** nothing in BPA-57 publishes it. `BoundaryEvent_WorkPackCompleted` belongs to E2-16. Confirmed by grep: no backend code names it.
5. **Jira:** BPA-57 is Ready; its 2 merged PRs are #48 and #49. I will ask before any transition.
6. **Texts not in Jira (ASSUMPTION):** the title "Approve hours (PPC)" (the BPMN name); the segregation 403 text reused at Ppc; "Decision recorded" after a
   PPC completion. To comment in Jira after a yes.

## Human-gap log
- Plan written in plan mode; the branch existed already (empty at main 3ad3f25). Approved by Ahsan on 18 Nov 2026 without answers to Q1-Q6 one by one, so the recommended options are applied (Q1 option A: 403 for the sender, 409 with the Jira text for an earlier decider; Q2: earlier deciders include the Timekeeper) and recorded as ASSUMPTIONs (Jira comment after a yes).

## Progress and departures (as built, 18 Nov 2026)
- **Red first:** `HoursPpcApprovalApiTest` before any production code: 15 run, 15 failed (commit d9db07c, tests and contract only). Scenarios 1 to 7 and most extras got 404 "Task not found" on the ppc-team completion, not the failing Ppc job the plan expected: `UserTask_ApproveHoursPpc` was not on the task allow-list, so the task was not served at all. `BPA57_approvedLinesCannotChange` failed only on its Given (the PPC approval), as planned: the line rule needed no change and the test is green now without touching `HourLineService`. The React tests were committed red separately (e3194a2: the `ApproveHoursPpc` module did not exist).
- **Design correction found by the tests:** `earlierDeciders` as first written counted every decision of the submission, so a retried Ppc job (same task key) saw its own decision and refused itself, breaking idempotency (`aRetriedPpcJobRecordsOneDecision` failed). It now takes the step and leaves out that step's own decisions (`step <> ?`), which is also what "at an earlier step" means. The plan's Design 2 said "any earlier decision on the submission"; this refines it.
- **Segregation:** as planned, option A: 403 with BPA-56's text for the sender (Production and Ppc); 409 "You already decided these hours at an earlier step" for an earlier decider (Ppc only, the Timekeeper's decision included). `HoursSegregationCheck` now returns a status with its text (`Refusal`); `TaskService` answers with it. The worker's "not built yet" message for an unknown step is now "Unknown step <step> of record-hours-decision".
- **Frontend:** BPA-56's `ApproveHoursProduction.tsx` moved to a shared `ApproveHours` (a `title` prop); `ApproveHoursProduction` and the new `ApproveHoursPpc` are one-line wrappers; BPA-56's tests are unchanged and green.
- **Existing tests edited:** none; `TaskValidityTest` gained a row.
- **Proof so far:** the class 15/15; `HoursProductionApprovalApiTest` 11, `HoursValidationApiTest` 14, `TaskApiTest` 8, `TaskValidityTest` 7, `BookHoursApiTest` 28, all green; frontend 101 tests, lint, Prettier, tsc and build clean. Local stack (WP-000011, fresh instance on the current model): send, validate, production approve, production-supervisor on the PPC task 403, PPC reject without a reason 400, ppc-team reads the submission (stage Ppc), PPC approve: round 1 Closed/Approved with 4 lines Approved and decisions Validated, Approved, Approved; Book hours open again; change, delete and amend of an Approved line all 409 "Line is not editable (status Approved)"; a new line for E1021 on 18 Nov 201; send: round 2 at stage Timekeeper and the instance at `UserTask_ValidateHours`. The earlier-decider 409 is proved by `BPA57_ppcDeciderMustDifferFromEarlierDeciders` (it needs seeded `decided_by`).
