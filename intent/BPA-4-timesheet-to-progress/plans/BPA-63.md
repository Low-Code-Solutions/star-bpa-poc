# Plan: BPA-63 Project Manager can approve or decline a Work Pack's progress retraction
Jira: BPA-63 (Story, epic BPA-4)
Story last updated: 2026-11-21T13:36:51.506+0400
Status: Closed (21 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-63-pm-approve-retraction, from main 9f8b739

> Checked read-only: branch `BPA-63-pm-approve-retraction` exists at 9f8b739 (the PR #55 merge, BPA-62), no commits of its own.

## Context
BPA-61 sends a week with a decrease (and no Blocked row) to `UserTask_ApproveRetraction` (project-manager), but nothing serves that task. BPA-61 also decided (Q2) that BPA-63 creates the retraction request, including PPC's reason. The pieces:
- **PPC side (BPA-60 screen and approval):** a retraction reason per Work Pack whose approved % is below last week, and a `progress_retraction` request.
- **PM side:** one task that decides every waiting Work Pack, each one Approved or Declined (with a reason).
- **`record-retraction-decision`:** stores the decisions. All Approved means the check runs again and passes. Any Declined sends the week back to PPC.

## Readiness check (Definition of Ready, docs/02, against Jira 21 Nov 13:36)
| Check | State |
|---|---|
| Jira status | **In Progress** already. The development panel shows 2 merged PRs (earlier stories'). No transition without asking (Q6) |
| Template, persona, outcome | Met (Project Manager) |
| Gherkin incl. unhappy paths | Met: 8 scenarios (400 ×2, 403, 409) |
| BPMN element IDs exist | Met. `UserTask_ApproveRetraction` (project-manager) → `ServiceTask_RecordRetractionDecision` (`record-retraction-decision`) → `Gateway_IsRetractionApproved` (`Flow_RetractionApproved` → `Gateway_MergeProgressCheck`; default `Flow_RetractionDeclined` → `Gateway_MergeApproval`). **The gateway tests one `retractionDecision`; decisions are per Work Pack** (Q2) |
| Every field has an SOR | Met (`progress_retraction`, POC database). **`task_key` UNIQUE does not fit one task deciding several rows** (Q1) |
| DMN | `Decision_ProgressRule` (BPA-61) is unchanged |
| No blocking question | D8 (decline needs a reason, ≤ 250) decided 10 Nov. Q1 and Q2 change the build |
| intent.md / spec.md accepted | **Not met** (Jira DoR box unchecked; same as BPA-58 to BPA-62) |
| Estimate ≤ 8 | Met (3) |

## What already exists (closed plans BPA-56, BPA-60, BPA-61; history only)
- **`progress_retraction` (V2:267):**
  - `workpack_progress_id`, `requested_by`, `reason` (not blank), `approved_pct` numeric(5,2) NOT NULL;
  - `decision` NULL or Approved/Declined; `decision_reason` required when Declined (CHECK);
  - `decided_by`, `decided_at`, `task_key` **UNIQUE (single column)**.
  - The main code never writes it. `ProgressRuleApiTest.retraction()` seeds one (task_key 1). The highest migration is V3; V1 to V3 are frozen.
- **Task API (`task/TaskService.complete`)**, in this order:
  - 404, then 403 "You are not allowed to work on this task" (candidate group);
  - 409 already completed; 400 allow-list;
  - `TaskValidity` (first message only; already holds "Reason is required" and "Decision must be Approved or Declined");
  - the per-element checks: `HoursSegregationCheck` (BPA-56) and `CompletionApprovalCheck` (BPA-60);
  - then it adds `completedBy` and `completedTaskKey`.
  - `UserTask_ApproveRetraction` has **no allow-list entry and is not in the `TaskSummary`/`Task` `elementId` enums** (`api/openapi.yaml:1729`), so the task API does not serve it.
- **BPA-60:**
  - `CompletionApprovalCheck`: coverage, 2 dp, 0–100, the remark rule against `referencePct`, remark ≤ 250.
  - `CompletionApprovalService.record` → `WorkpackProgressRepository.approve`: writes `approved_pct` and `previous_pct` (= the last locked %); same values write nothing.
  - `RecordCompletionApprovalWorker` (`record-completion-approval`): fails with no retries, no BPMN error.
  - Read model: `GET /api/v1/progress-periods/{weekEnding}/workpacks` (ppc-team **and project-manager**), `WorkpackProgressRow`.
  - Screen: `frontend/src/features/progress/components/ApproveCompletion/` ("no retraction field", plan Q3).
- **BPA-61:**
  - `ProgressWeekRepository.retractionApprovedFor(progressId, pct)`: an `Approved` row whose `approved_pct` = the new %.
  - `RecordProgressResultWorker` sets `validationMessages` on every pass, including DECREASE "<WP> completion fell from <prev>% to <new>%; a retraction is needed". Those messages stay on the variable when the week comes back through a decline.
- **BPA-56 segregation:**
  - `HoursSegregationCheck` (task-API pre-check, `Refusal(status, message)`) plus the same check in `RecordHoursDecisionWorker`, which fails with no retries as the backstop.
  - Re-run key: `completedTaskKey` → `hours_decision.task_key`, `ON CONFLICT DO NOTHING`.
  - Tests seed `submitted_by` by SQL (one user per group).

## Design
### 1. PPC side: reason and request (UserTask_ApproveCompletion, record-completion-approval)
**Payload:**
- `completionApprovals` entries gain `retractionReason` (optional): `{workpackNumber, approvedPct, remark, retractionReason}`.
- It is a field inside the existing variable, so the allow-list is unchanged.

**A Work Pack "needs a reason"** when:
- its approved % < its last locked % (`lastLockedPct`, as BPA-61's `previousPct`);
- **and** no `Approved` retraction exists for this week's row at exactly that % (an already approved retraction needs no new request; BPA-61 a).

This is the same condition that gives NeedsRetraction, so a request exists for every Work Pack the PM task shows.

**`CompletionApprovalCheck`** (task-API pre-check) adds, after the remark rules:
- 400 "A retraction reason is required when completion falls below last week (<prev>%)" (Jira; `<prev>` without trailing zeros, as `ProgressMessages`);
- 400 "Retraction reason can be at most 250 characters" (**ASSUMPTION**, Q7).

**`CompletionApprovalService.record`** (in `ServiceTask_RecordCompletionApproval`'s transaction) writes the request (Q3):
- **Needs a reason, no open request** (decision NULL) for the row: INSERT `requested_by` = `completedBy`, `reason`, `approved_pct` = the new %, decision NULL.
- **Needs a reason, an open request exists:** UPDATE its `reason`/`approved_pct` when they differ (one undecided request per row at a time).
- **No longer needs a reason** (≥ last locked %, or an Approved row matches): DELETE the open request (**ASSUMPTION**).
- Decided rows (Approved or Declined) are never touched. So a re-approval at a different % makes a new request, and an old Approved one is kept (BPA-61 Q4).
- One `audit_log` row per changed column (D64). Same inputs write nothing (re-run key: the values).

**Read model** (`WorkpackProgressRow`, contract first), new optional fields:
- `retractionReason`, `retractionRequestedBy`: the open request, if any;
- `retractionDeclinedReason`: the reason of the row's latest **Declined** request whose `approved_pct` equals the row's current `approved_pct`. It disappears once PPC changes the %.

**PPC screen:**
- A "Retraction reason" input per row, shown when the approved % < the last locked %, pre-filled from `retractionReason`.
- "Retraction declined: <reason>" in that Work Pack's row (Q5).
- The client-side check uses the same texts as the server.

**After a decline (Jira: "must set a % not below last week"):** is this a hard 400 or only guidance? See Q8.

### 2. PM task: UserTask_ApproveRetraction
**Allow-list:**
- write: `[retractionDecisions]` = `[{workpackNumber, retractionDecision, reason}]`;
- read: `[weekEnding]`.
- Contract: add `UserTask_ApproveRetraction` to the `TaskSummary` and `Task` `elementId` enums.

**Rows shown:** the same GET `/progress-periods/{weekEnding}/workpacks` (project-manager already allowed), filtered on the screen to rows with an open request. Each row shows:
- Work Pack;
- last locked % (`lastLockedPct`);
- new % (`approvedPct`);
- PPC's reason (`retractionReason`);
- Approved / Declined, and a Reason textarea when Declined.

**Checks, in TaskService order:**
1. **403** for any group other than project-manager (existing `requireCandidate`).
2. **`TaskValidity`** (new `checkRetractionDecisions`):
   - list required;
   - each decision "Decision must be Approved or Declined" (existing text);
   - Declined with a blank reason: 400 "Reason is required" (Jira);
   - reason over 250: 400 "Reason can be at most 250 characters" (**ASSUMPTION** text; 250 from D8).
3. **New `RetractionDecisionCheck`** (like `CompletionApprovalCheck`):
   - the decisions must cover exactly the week's open requests: 400 "Decisions must cover every Work Pack waiting for a retraction" (**ASSUMPTION**);
   - **segregation:** any covered request whose `requested_by` = the session user gives **409** "You cannot approve a retraction you requested" (Jira). Q4 covers whether this also applies to a Decline.

**Screen:** `frontend/src/features/progress/components/ApproveRetraction/` on `TaskScreenShell` (new-task-screen skill), per-row fieldsets like `SendHoursToFms`. Registered in `USER_TASKS` and `taskScreens.ts`.

### 3. record-retraction-decision (RecordRetractionDecisionWorker → RetractionDecisionService)
**Inputs:** `retractionDecisions`, `completedBy`, `completedTaskKey`, the instance's period (`ProgressCheckService.periodOf`).

**Backstop checks** (fail with no retries, as BPA-56, 60 and 61):
- no period;
- a Locked week;
- a payload that does not cover the open requests;
- a Declined without a reason;
- requester = `completedBy` (the same 409 text).

**Writes** each open request: `decision`, `decision_reason` (NULL when Approved), `decided_by` = `completedBy`, `decided_at` (injected `Clock`), `task_key` = `completedTaskKey`. One transaction, audited per column.

**Re-run key:** `task_key`. Rows already carrying this task key are left as they are, so a retried job writes nothing (needs Q1).

**Completes with:**
- `retractionOutcome` = "Approved" when every decision is Approved, else "Declined" (Q2);
- on "Declined", `validationMessages` = `[]`, so BPA-61's stale "… a retraction is needed" lines do not show beside the decline (Q5, **ASSUMPTION**).

**Then:**
- **All Approved:** `Gateway_MergeProgressCheck`. The check runs again; `retractionApprovedFor` is true for each approved %, so the rows are Accepted, then `ServiceTask_CalculateEarnedManhours` (BPA-62), then `ServiceTask_PrepareVarianceCheck` (BPA-64, no worker yet).
- **Any Declined:** `UserTask_ApproveCompletion`. The Approved rows stay Approved for their exact %; when PPC re-approves the same %, no new request is made (§1).

### 4. BPMN (Q2; not changed until you answer)
- **`Flow_RetractionApproved` condition** `=retractionDecision = "Approved"` becomes **`=retractionOutcome = "Approved"`** (set by the worker after its checks).
  - The alternative is FEEL on the raw input: `=every d in retractionDecisions satisfies d.retractionDecision = "Approved"`. It works without a worker variable, but it routes on unchecked user input.
- **Documentation text** on `UserTask_ApproveRetraction` and `ServiceTask_RecordRetractionDecision`: per-Work-Pack decisions, what is written, the re-run key.
- The default flow stays Declined. A deploy redeploys `Process_PPC_WeeklyProgressPeriod`.

### 5. Database (Q1; not added until you answer)
`V4__progress_retraction_task_key.sql`: drop `UNIQUE (task_key)`, add `UNIQUE (task_key, workpack_progress_id)`. Data model §13.3.5 and delta §3.5 are updated to match.

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml`: `elementId` enums + `UserTask_ApproveRetraction`; `WorkpackProgressRow` + `retractionReason`, `retractionRequestedBy`, `retractionDeclinedReason` |
| Model (Q2) | `models/bpmn/Process_PPC_WeeklyProgressPeriod.bpmn`: `Flow_RetractionApproved` condition, two documentation texts |
| Database (Q1) | `backend/src/main/resources/db/migration/V4__progress_retraction_task_key.sql` |
| Backend | `task/TaskValidity.java` (retractionDecisions); new `task/RetractionDecisionCheck.java` (+ wired in `TaskService`); `task/CompletionApprovalCheck.java` (reason rules); `progress/CompletionApprovalService.java` (request upsert); new `persistence/ProgressRetractionRepository.java`; `progress/ProgressReadService.java` + `persistence/ProgressWeekRepository.java` (retraction fields); `api/ProgressPeriodController.java` (map); new `worker/RecordRetractionDecisionWorker.java`, `progress/RetractionDecisionService.java`; `application.yml` allow-list |
| Frontend | `ApproveCompletion.tsx` + `approvalMessages.ts` (reason field, decline line); new `features/progress/components/ApproveRetraction/` + `retractionDecisionMessages.ts`; `utils/constants.ts`, `features/tasks/taskScreens.ts` |
| Test support | `ProcessTestApplication` excludes the new worker; `BookHoursFixture` already clears `progress_retraction` |
| Existing tests (decreases now need a reason) | `ProgressRuleApiTest`: `BPA61_decreaseNeedsRetraction`, `BPA61_retractionValidOnlyForApprovedPercent` and any other decrease through the API gain `retractionReason`; its seeded `task_key 1` stays valid. `ApproveCompletion.test.tsx` if a decrease row is rendered. To be said in the PR |
| Tests | new `RetractionApiTest` (8 `BPA63_` + extras); `ApproveRetraction.test.tsx`; additions to `ApproveCompletion.test.tsx` |
| Docs | data model §13.3.5 "As built by BPA-63" (+ §13.3.4 note); delta §3.5 if V4; plan; REVIEW.md; `intent/README.md` |

## Order of work
1. Approve the plan and commit it (`Approved`). No Jira transition without asking (Q6).
2. Contract (`api/openapi.yaml`), plus the BPMN change and V4 once answered.
3. Tests: backend `RetractionApiTest` and the React tests. Run them red **before any production code**: one commit for the backend red run, one for React. Expected failures:
   - the task is not served (404/allow-list) or the instance waits at `UserTask_ApproveRetraction` with no request rows;
   - no reason field on the screen.
4. Backend (PPC side, then task checks, worker), then green: the class, then `ProgressRuleApiTest`, `CompletionApprovalApiTest`, `EarnedManhoursApiTest`, `ModelDeployTest`, `ModelResourcesTest`.
5. Frontend screens; `npm run lint`, `format:check`, `test`, `build`.
6. Docs; full `./mvnw -B verify`; local-stack proof; `qa-reviewer`; fixes; REVIEW.md; plan Closed. Push, PR and Jira comments only when asked.

## Scenario-to-test table
**`RetractionApiTest` setup** (as `ProgressRuleApiTest`):
- **Clock and fixture:** 20 Nov 2026 Asia/Riyadh. `BookHoursFixture` background.
- **Locked 12 Nov week:** WP-000457 approved 30, WP-000461 approved 60.
- **19 Nov week:** the report gives 457 at 25 (and 461 at 55 for scenario 7), so no remark is needed.
- **Getting to the PM task:** ppc-team completes `UserTask_ApproveCompletion` through the task API with `retractionReason` "FMS double-counted cutting" (and "Recount"). BPA-61's real workers bring the instance to `UserTask_ApproveRetraction`.

| # | Scenario | Test | Act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | Approved retraction accepts the decrease | `BPA63_retractionApprovedAcceptsTheDecrease` | PM: 457 Approved | `rule_result` Accepted, `approved_pct` 25; the 19 Nov `activity_progress` row for A-2201 exists, and the instance reaches `ServiceTask_PrepareVarianceCheck` | red: the task is not served (not on the allow-list) |
| 2 | Declined goes back to PPC | `BPA63_retractionDeclinedGoesBackToPpc` | PM: 457 Declined "Recheck with FMS first" | waits at `UserTask_ApproveCompletion`; GET `/workpacks` as ppc-team: 457 `retractionDeclinedReason` "Recheck with FMS first" (React test: the row reads "Retraction declined: Recheck with FMS first") | red |
| 3 | A decline needs a reason | `BPA63_declineNeedsAReason` | PM: Declined, reason "" | 400 "Reason is required"; the task stays open | red |
| 4 | Decrease without a reason refused | `BPA63_decreaseWithoutReasonIsRefused` | ppc-team: 457 at 25, `retractionReason` "" | 400 "A retraction reason is required when completion falls below last week (30%)"; no request row | red: 204 today |
| 5 | Recorded with who and why | `BPA63_retractionIsAudited` | as 1 | `progress_retraction`: requested_by ppc-team, reason "FMS double-counted cutting", decision Approved, approved_pct 25.00, decided_by project-manager, decided_at, task_key; `audit_log` rows | red |
| 6 | Other personas cannot approve | `BPA63_otherPersonasCannotApproveRetraction` | ppc-team completes the PM task | 403 | red: not served gives 404, not 403 |
| 7 | PM decides each Work Pack | `BPA63_pmDecidesEachWorkPack` | 457 Approved, 461 Declined "Keep 60% until QC" | waits at `UserTask_ApproveCompletion`; 457's row stays Approved at 25.00; 461 `retractionDeclinedReason` "Keep 60% until QC" | red |
| 8 | Approver must not be the requester | `BPA63_approverMustNotBeRequester` | `requested_by` set to project-manager by SQL (Q4); PM: Approved | 409 "You cannot approve a retraction you requested"; the task stays open | red |

**React tests** (named after the scenario they show):
- `ApproveRetraction.test.tsx`: `BPA63_pmDecidesEachWorkPack` (rows with last locked, new %, reason; a decision each), `BPA63_declineNeedsAReason`, `BPA63_approverMustNotBeRequester` (the server's 409 shown).
- `ApproveCompletion.test.tsx`: `BPA63_decreaseWithoutReasonIsRefused` (the reason field appears below last week, and the message), `BPA63_retractionDeclinedGoesBackToPpc` (the decline line).

**Extra tests** (not scenarios):
- `aRerunRecordsNothingTwice`: `handle` twice with a mocked client; the same rows, no second audit.
- `segregationAlsoFailsTheJob`: worker backstop.
- `aReApprovalAtADifferentPercentMakesANewRequest`: the old Approved is kept.
- `anOpenRequestFollowsTheReApproval`: updated, or deleted when ≥ last locked.
- `anApprovedPercentNeedsNoNewReason`: re-approve 25 after scenario 7, no 400, no new request.
- `aDeclineReasonOver250IsRefused`.
- `decisionsMustCoverEveryWaitingWorkPack`.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=RetractionApiTest test`; `grep -c "void BPA63_" backend/src/test/java/com/lcs/star/bpa/RetractionApiTest.java` = 8; `npm test`; `gh pr checks <n>` |
| Task screen | `ApproveRetraction` registered in `taskScreens.ts`; seen in the browser (below) |
| Process runs end to end locally | the run below |
| BPMN and DMN match deployed | deployed `Process_PPC_WeeklyProgressPeriod` equals `models/` (whitespace-normalised, as BPA-61 and 62) |
| Open questions resolved or logged | the Questions below, as Jira comments (shown first) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-63.md` |
| Demoed at sprint review | Human gap |

**End-to-end run in the local stack** (rebuild `bpa-app` and the portal; reset and seed as BPA-62's proof: a Locked 12 Nov week with approved %; start the weekly process):
1. **Approve path:**
   - As ppc-team: send hours, upload the completion CSV, then Approve completion with one Work Pack below its locked %. The reason field appears; leaving it empty shows the 400 text; fill it and complete.
   - As project-manager: open **Approve progress retraction** and check the row (last locked, new %, reason). Approve.
   - **`psql`:** the `progress_retraction` row (requested_by, reason, decision, decided_by/at, task_key) and `rule_result` Accepted.
   - **`psql`:** `activity_progress` rows for 19 Nov (Earned man-hours ran).
   - **Operate:** the instance waits at `ServiceTask_PrepareVarianceCheck`.
2. **Decline path** (a fresh week, or reset):
   - The same request; the PM declines with a reason (and tries an empty reason first: "Reason is required").
   - As ppc-team, the task is back with "Retraction declined: <reason>" on the row.
   - Set a % not below last week and complete; the week goes on to Earned man-hours.
3. **Refusals:**
   - ppc-team opening the PM task gets 403.
   - The 409 needs `requested_by` = project-manager seeded by SQL; done once, then reset.

## Risks
- **Existing BPA-61 tests approve decreases** through the API; they now need a retraction reason (listed above).
- **BPMN change** redeploys the weekly process. The local 19 Nov instance from BPA-61's proof waits at `UserTask_ApproveRetraction` on the old wiring with no request rows; reset it before the demo.
- **The V4 migration** changes a constraint on a frozen table's schema (new file, as V3 did).
- **Stale `validationMessages`** from BPA-61 on the decline path (cleared, Q5).
- **After the approve path the week stops at `ServiceTask_PrepareVarianceCheck`** (BPA-64).

## Questions
1. **`task_key` UNIQUE vs one task deciding several Work Packs.**
   - **Recommended:** V4 migration: `UNIQUE (task_key, workpack_progress_id)` instead of `UNIQUE (task_key)`. Each decided row carries the task key, and the re-run key stays the task key, as BPA-56.
   - Alternative, no migration: keep `task_key` NULL on all rows and use "decision already set" as the re-run key. That departs from the data model, which names `task_key` as the record of the task.
   - I won't add V4 without your yes.
2. **Gateway.** **Recommended:** the worker sets `retractionOutcome` ("Approved" only when every decision is Approved) and `Flow_RetractionApproved` tests `=retractionOutcome = "Approved"`; documentation texts updated. The alternative is FEEL on the raw input (`every d in retractionDecisions satisfies …`). Not changed until you answer.
3. **Where the request is created, and an undecided request on re-approval.**
   - **Recommended:** in `ServiceTask_RecordCompletionApproval` (`CompletionApprovalService.record`, same transaction as `approved_pct`), with `requested_by` = `completedBy`.
   - **An undecided request on re-approval:** at most one per row; it is updated to the new %/reason, or deleted when the % is no longer below last week or an Approved retraction matches (**ASSUMPTION**).
   - Decided requests are never changed. An Approved one is kept (BPA-61 Q4) and a Declined one is kept as history.
4. **Segregation.**
   - Only ppc-team can complete Approve completion, so `requested_by` is always ppc-team through the screen. Scenario 8 seeds `requested_by = 'project-manager'` by SQL (as BPA-56's tests seed `submitted_by`). Confirm.
   - **Also:** the 409 applies to **any** decision by the requester (Approved or Declined), since "approve or decline" is one decision. The text is Jira's "You cannot approve a retraction you requested". Recommended; the alternative is to refuse only Approved.
5. **"Retraction declined: <reason>" for PPC.**
   - **Recommended:** a field on the BPA-60 read model (`retractionDeclinedReason`), shown in that Work Pack's row, because the Gherkin says "for WP-000461". Not `validationMessages`, which have no Work Pack column.
   - On the decline path the worker sets `validationMessages = []`, so BPA-61's "a retraction is needed" lines (one of which is for an already approved Work Pack) do not show (**ASSUMPTION**).
6. **Jira:** BPA-63 is In Progress, with merged PRs of earlier stories linked. I will ask before any transition.
7. **Texts not in Jira (ASSUMPTION):**
   - "Retraction reason can be at most 250 characters" (PPC side; 250 as `override_remark` and D8);
   - "Reason can be at most 250 characters" (PM side; the 250 is D8);
   - "Decisions must cover every Work Pack waiting for a retraction".
   - Jira's texts are used as written: "Reason is required", "A retraction reason is required when completion falls below last week (<prev>%)", "You cannot approve a retraction you requested", "Retraction declined: <reason>".
8. **After a decline, "PPC must set a % not below last week".**
   - **Recommended:** not a new hard rule. If PPC approves below last week again, a new request goes to the PM, who can decline again; no new message is invented.
   - The alternative: a 400 for a Work Pack whose retraction was declined this week, with a new **ASSUMPTION** text.
9. **PM screen rows:** read from the existing `GET /progress-periods/{weekEnding}/workpacks` (project-manager already allowed) and filtered to rows with an open request. There is no new endpoint and no `SecurityConfig` change. Agree?

## Human-gap log
- None yet. No inputs were staged before this plan.

## Resolutions (21 Nov 2026)
Approved with the recommended answers: Q1 the V4 migration, UNIQUE (task_key, workpack_progress_id); Q2 retractionOutcome set by the worker (the Flow_RetractionApproved condition and two documentation texts change); Q3 the request is written in ServiceTask_RecordCompletionApproval, one undecided request per row, decided rows kept; Q4 scenario 8 seeds requested_by by SQL and the 409 applies to any decision by the requester; Q5 retractionDeclinedReason on the read model and validationMessages cleared on the decline path; Q7 the ASSUMPTION texts as written (to comment in Jira after a yes); Q8 no new hard rule after a decline; Q9 the existing /workpacks endpoint.

## Progress and departures (21 Nov 2026)
**Commits:** cba7de1 plan Approved; 300c7b1 contract, V4, gateway and red backend tests; 12f6dec red React tests; 81d0825 backend; 81db41e frontend; 5713987 `FlywayBaselineTest` lists V4.

**Red runs shown before any production code:**
- Backend `RetractionApiTest`, 13 of 13 red: the Project Manager's task answered 404 "Task not found" (not on the allow-list), no request row existed, a decrease with no reason still got 204.
- React: `BPA63_decreaseWithoutReasonIsRefused` and `BPA63_retractionDeclinedGoesBackToPpc` red (no reason field, no decline line); `ApproveRetraction.test.tsx` failed only because its screen did not exist (unresolved import).

**Departures from the plan:**
1. **Read model gains `retractionApproved`** (boolean, derived), not in the plan. Without it the screen would ask for a reason where the server does not (an Approved retraction already holds for the stored %). The server stays the authority. Contract, `ProgressReadService.Row`, controller and `ApproveCompletion` changed.
2. **New ASSUMPTION text "Decisions are required"** (`TaskValidity`, an empty or missing `retractionDecisions` list), mirroring "Approvals are required".
3. **Edit to an existing test:** `ProgressRuleApiTest`'s `approve` helper now sends `retractionReason` for a changed %, because a decrease needs one (BPA61_decreaseNeedsRetraction and BPA61_retractionValidOnlyForApprovedPercent would otherwise get a 400). No assertion on stored values changed.
4. **Edit to an existing test:** `FlywayBaselineTest` lists V4 ("progress retraction task key") in the applied migrations. Found by the full `verify`.
5. **Edit to a test after its red run:** my `RetractionApiTest.approve` helper added the remark only when the % differed from the pre-fill, but BPA-60's remark rule compares with the reported % (or the last locked % with no report row). `anApprovedPercentNeedsNoNewReason` failed on that helper, not on the code; the helper now follows the rule. The red run had failed earlier in that test, so the helper was not exercised then.
6. **Three service-level extras written after the code, no red run:** `aRerunRecordsNothingTwice`, `segregationAlsoFailsTheRecordStep`, `anOpenRequestFollowsTheReApprovalAndGoesWhenNotNeeded`. `anOpenRequestFollows…` calls `CompletionApprovalService.record` directly because through the task API an open request cannot survive to a re-approval (PPC cannot complete Approve completion while the Project Manager's task is open).
7. **`RetractionApiTest` has 16 tests** (8 scenarios, 5 HTTP extras, 3 service-level extras); the plan listed 7 extras.
8. **The PM screen filters on `retractionReason`** (an open request), as planned (Q9); the empty case shows "No retraction is waiting for a decision" (ASSUMPTION text).
9. **`Flow_RetractionApproved` is `=retractionOutcome = "Approved"`** (Q2, as approved); the worker also clears `validationMessages` on a decline (Q5).
10. **BPA-61 pre-fill:** after a decline the stored `approved_pct` pre-fills the grid (BPA-60 Q9), so PPC sees the rejected % until they change it.

**Verification:** `RetractionApiTest` 16/16; neighbouring `ProgressRuleApiTest`, `CompletionApprovalApiTest`, `ModelDeployTest`, `ModelResourcesTest` green; full `./mvnw -B verify` 617 tests, 1 failure (`FlywayBaselineTest`, departure 4, fixed and rerun green). Frontend: `npm run lint`, `format:check`, `build` clean; `npm test` 125 of 126, the one failure `BPA6_dateToCompleteBoundary` (CreateWorkPack, unrelated) passes alone and failed once under parallel load.

## Local-stack proof (21 Nov 2026)
Images rebuilt from this branch (`docker build` of `bpa-app` and the portal; `docker compose up -d --no-build`). The weekly instance was started **at `UserTask_ApproveCompletion`** for the week ending 19 Nov 2026 (the period reset by SQL, the 12 Nov Locked week holding approved 30/60/10; so the hours upload and report steps were not run again). All calls through the portal's origin (http://localhost:3000, nginx proxy) as the personas.
- **Approve path** (WP-000011 reported 45, last locked 30, to 25):
  - ppc-team without a reason: 400 "A retraction reason is required when completion falls below last week (30%)".
  - With the reason: 204; `progress_retraction` row requested_by ppc-team, reason "FMS double-counted cutting", approved_pct 25.00, decision NULL; `rule_result` NeedsRetraction.
  - project-manager's rows show last locked 30, new 25 and the reason; ppc-team on the PM task: 403 "You are not allowed to work on this task".
  - PM Declined with an empty reason: 400 "Reason is required". PM Approved: 204.
  - Row: decision Approved, decided_by project-manager, decided_at and task_key set; audit rows for requested_by, reason, approved_pct, decision, decided_by; all four Work Packs Accepted; `activity_progress` rows for 19 Nov; the instance waits at `ServiceTask_PrepareVarianceCheck` (BPA-64).
- **Decline path** (WP-000011 to 25 and WP-000030 from 60 to 55, both with reasons):
  - PM deciding only one of them: 400 "Decisions must cover every Work Pack waiting for a retraction".
  - PM: WP-000011 Approved, WP-000030 Declined "Keep 60% until QC": 204. The instance waits at `UserTask_ApproveCompletion`.
  - PPC's read model: WP-000030 `retractionDeclinedReason` "Keep 60% until QC", WP-000011 `retractionApproved` true; the task's `validationMessages` is empty.
  - PPC re-approves WP-000011 at 25 with no reason (204; still one retraction row, Approved) and WP-000030 at 60: all Accepted, Earned man-hours ran, the instance waits at `ServiceTask_PrepareVarianceCheck`. The declined row is kept.
- **Not run by hand:** the 409 segregation (only ppc-team can request; covered by `BPA63_approverMustNotBeRequester` with `requested_by` seeded) and the browser screens (covered by the React tests; the screens were not opened in a browser).
- **BPMN match:** the deployed `Process_PPC_WeeklyProgressPeriod` equals `models/bpmn/Process_PPC_WeeklyProgressPeriod.bpmn` (whitespace-normalised, same hash) and contains `retractionOutcome = "Approved"`.
- **Side effect on the local stack:** the 19 Nov week now holds this decline-path run (instance at `ServiceTask_PrepareVarianceCheck`, WP-000030's Declined request kept).

## After the `qa-reviewer` pass (21 Nov 2026; REVIEW.md "BPA-63 at 5713987")
- **Added after the review:** `theReadModelShowsTheRequestAndItsOutcome` (backend, covers `retractionReason`, `retractionRequestedBy`, `retractionApproved`, `retractionDeclinedReason`) and `BPA63_anApprovedRetractionNeedsNoNewReason` (React); both written after the code, no red run. `RetractionApiTest` now has **17 tests**. `approvalMessages.ts` comment corrected: the screen asks for the reason "in the same cases or more" than the server.
- **Files in the diff that the plan did not list:** `progress/RetractionRules.java` (new, pure), `persistence/ProgressRetractionRepository.java` (the retraction reads and writes live here, not in `ProgressWeekRepository`), `FlywayBaselineTest.java`, `task/TaskConfig.java` (wiring), `ProgressRuleApiTest.java` (helper). `ProgressWeekRepository.java` was **not** changed. The plan's extra test `segregationAlsoFailsTheJob` is `segregationAlsoFailsTheRecordStep`.
- **Accepted for the POC (recorded, not built):**
  - no partial unique index on open requests: two open requests for one Work Pack row are prevented by the process only (one Approve completion task at a time);
  - an Approved decision ignores a `reason` that is sent with it (not stored; ASSUMPTION);
  - `decided_at` and `task_key` (and `workpack_progress_id` on insert) are not audited;
  - Complete on the Project Manager's screen before the week has loaded sends an empty list and gets the server's 400 "Decisions are required";
  - `RetractionDecisionService.decide` checks the Locked week before the re-run key, so a retried job after the lock fails (no retries) instead of returning the stored outcome;
  - `ofPeriod` is read once per Work Pack in `CompletionApprovalService.request` and once per week in `ProgressReadService` (small table).
- **ASSUMPTIONs to comment in Jira (shown to Ahsan first; posted only on his yes):** the texts "Retraction reason can be at most 250 characters", "Reason can be at most 250 characters", "Decisions must cover every Work Pack waiting for a retraction", "Decisions are required", "No retraction is waiting for a decision" (screen), and "The week of this task has no progress period"; the rules: an open request is deleted when no retraction is needed any more, `validationMessages` is cleared on a decline, the 409 applies to a decline as well as an approval, and after a decline the stored % pre-fills the grid. Jira's SOR Notes say "Process variable retractionDecision (per Work Pack)": the build uses the list `retractionDecisions` and the worker variable `retractionOutcome`, and Jira's scenarios 1 and 2 ("with retractionDecision 'Approved'") are tested through that list.
