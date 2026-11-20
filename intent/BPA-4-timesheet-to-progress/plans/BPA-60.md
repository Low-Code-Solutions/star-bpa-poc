# Plan: BPA-60 PPC Team can approve the completion % of each Work Pack for the week
Jira: BPA-60 (Story, epic BPA-4)
Story last updated: 2026-11-20T11:03:43.172+0400
Status: Closed (20 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-60-ppc-approve-completion, from main 4c9089a


## Context
After BPA-59 the weekly instance waits at `UserTask_ApproveCompletion`, and nothing serves it. Each Work Pack has the week's `reported_pct` from the FMS/PMS report.

BPA-60 lets PPC see one row per In Progress Work Pack:
- the reported %;
- the last locked %;
- the hours-based %;
- the % reported by production;
- FMS actual hours beside the POC approved hours.

PPC can change an approved % (a change needs a remark) and approve the whole week at once. The approved % drives Earned man-hours (BPA-62) after BPA-61's progress rule.

## Readiness check (Definition of Ready, docs/02, against Jira 20 Nov 11:01)
| Check | State |
|---|---|
| Jira status | **In Progress** already (moved 20 Nov). No other transition without asking (Q6) |
| Template, persona, outcome | Met |
| Gherkin incl. unhappy paths | Met: 12 scenarios (400 decimals, 400 remark, 403, 409) |
| BPMN element IDs exist | Met. `Gateway_MergeApproval` (in: `Flow_ToMergeApproval` from Read report, `Flow_ProgressBlocked`, `Flow_RetractionDeclined`, `Flow_WeekChangesRequested`) → `UserTask_ApproveCompletion` (ppc-team) → `Gateway_MergeProgressCheck` → `ServiceTask_PrepareProgressCheck` (`prepare-progress-check`, BPA-61, no worker yet). **No element between the user task and BPA-61 records the approval** (Q7) |
| Every field has an SOR | Met (SOR Notes; data model §13.3.4 and the 10 Nov addendum: `override_remark` ≤ 250). Hours-based % is derived, not stored |
| DMN | None for BPA-60. BPA-61's `Decision_ProgressRule` blocks `newPct` > 100 and < 0, which conflicts with the `approved_pct` CHECK (0–100) (Q8) |
| No blocking question | Q1 (hours-based %), Q7 (where the approval is written) and Q8 (0–100) change the build |
| intent.md / spec.md accepted | **Not met** (Jira DoR box unchecked) |
| Estimate ≤ 8 | Met (5), tight: a read model, a check, a recording step, a grid screen |

## What already exists (closed plans BPA-58, BPA-59; history only)
- **`workpack_progress`** (V2): UNIQUE (`workpack_id`, `progress_period_id`); columns:
  - `reported_pct` (SOR FMS), written by BPA-59's read step;
  - `approved_pct` numeric(5,2) **CHECK 0–100**;
  - `previous_pct` NOT NULL DEFAULT 0;
  - `override_remark` ≤ 250;
  - `approved_by`, `approved_at`, `rule_result`.
- **`progress_period`:** `week_ending` UNIQUE, `status` CutOff or Locked. Locking is BPA-67 (not built). BPA-58 opens the period; the instance key is on the row.
- **`completion_report` / `completion_report_row`:** the latest `Accepted` report of the week holds `actual_hours_raw` per Work Pack (BPA-59).
- **Hours and budget:**
  - `digital_timesheet_line.reported_completion_pct` (0–100, C-E2-10);
  - `workpack_phase.manhours` per phase;
  - `hour_journal_header.estimated_manhours` (the Work Pack total).
- **Settings:** `config/ProgressSettings` reads `bpa.progress.source` at each call. `bpa.progress.basis: Physical` is in `application.yml` and nothing reads it yet.
- **Patterns to reuse:**
  - Task API checks: `TaskValidity`, `CompletionReportCheck` and `FmsHandOffCheck` (the pattern for a check that needs the database).
  - BPA-58's added recording service task: `ServiceTask_RecordFmsHandOff`, worker `RecordFmsHandOffWorker`.
  - `WorkpackProgressRepository` (BPA-59).
  - Test support: `CompletionReportApiTest`'s set-up (start at a user task, insert the period with the instance key); `BookHoursFixture.fmsBackground` and `completionBackground` (WP-000457, WP-000461, WP-000470 In Progress).

## Design
### 1. Read model (contract first): `GET /api/v1/progress-periods/{weekEnding}/workpacks`
Groups ppc-team and project-manager (delta §7). One row per Work Pack (Q4: In Progress ones, plus any with a `workpack_progress` row for the week).

| Field | Source |
|---|---|
| `workpackNumber` | |
| `reportedPct` | `workpack_progress.reported_pct` of the week, null when none; `noReportRow` = true then (D6) |
| `lastLockedPct` | `approved_pct` of the Work Pack in the latest **Locked** period before this week, else 0 (decision (c)) |
| `hoursBasedPct` | approved hours ÷ estimated hours × 100, 2 dp half-up, derived (Q1) |
| `reportedByProductionPct` | the latest non-null `reported_completion_pct` of the Work Pack's lines, information only (C-E2-10, Q12) |
| `fmsActualHours` | `actual_hours_raw` of the latest Accepted report's row, null when none |
| `approvedHours` | the hours counted for the hours-based % (Q1) |
| `hoursDifference` | `fmsActualHours − approvedHours`, null when either is null (Q10) |
| `approvedPct` (pre-filled) | **Physical:** `reportedPct`, or `lastLockedPct` when there is no report row (D6). **Hours:** `hoursBasedPct`. On a return to the task (Blocked, Declined, Changes requested) the stored `approved_pct` wins (Q9) |
| `remark` | `override_remark` already stored, if any |

### 2. Completing `UserTask_ApproveCompletion` (all-or-nothing, decision (d))
- **Write allow-list:** `completionApprovals` = a list of `{workpackNumber, approvedPct, remark}`. **Read allow-list:** `weekEnding`.
- **`TaskValidity`:** the list is present and not empty: "Approvals are required" (**ASSUMPTION**).
- **`CompletionApprovalCheck`** (task package, as `CompletionReportCheck`). It reads the database and runs before assign and complete, so the task stays open on a refusal:
  1. **409** "Week ending <d MMM yyyy> must be locked first" (Jira) when the period of week ending − 7 days exists and is not Locked. With no earlier period the rule is skipped (Q2).
  2. **400** "Approvals must cover every Work Pack of the week" (**ASSUMPTION**) when the list is not exactly the read model's Work Packs.
  3. **400** "Completion % can have at most 2 decimal places" (Jira).
  4. **400** "Completion % must be between 0 and 100" (**ASSUMPTION**, Q8).
  5. **400** "A remark is required when the approved % differs from the reported %" (Jira). The reference is the pre-filled value for a No-report-row Work Pack (Q9).
  6. **400** "Remark can be at most 250 characters" (**ASSUMPTION**).
- **403** for any group other than ppc-team comes from the candidate group (BPA-26).

### 3. Recording the approval (Q7, recommended A: a service task, as BPA-58)
- **New `ServiceTask_RecordCompletionApproval`** (job `record-completion-approval`) between `UserTask_ApproveCompletion` and `Gateway_MergeProgressCheck`. This is a **BPMN change that needs your yes**.
- **The worker upserts `workpack_progress` for each entry:**
  - `approved_pct`;
  - `override_remark` (NULL when equal to the reported %);
  - `previous_pct` = the last locked %;
  - `approved_by` = `completedBy`, `approved_at` = clock.
- One transaction; audit rows per changed column (D64).
- **Re-run key:** the values themselves (the plan first said `completedTaskKey`): a row that already holds the same values is left as it is and keeps `approved_at` (ASSUMPTION; no new column).
- **No output variable:** BPA-61's `prepare-progress-check` reads `approved_pct` and `previous_pct` from the table.
- Alternative (B): the task API writes before completing. No BPMN change, but the write and the completion are not atomic (rejected in BPA-58 for the same reason).

### 4. Settings
`ProgressSettings.basis()` reads `bpa.progress.basis` (Physical or Hours) at each call, as `source()`. Tests switch it through the environment (BPA-59 pattern).

### 5. Frontend: Approve completion % screen (skill new-task-screen)
- **`features/progress/components/ApproveCompletion/`** on `TaskScreenShell`.
- **Header:** "Week ending <date>".
- **Grid, one row per Work Pack:**
  - Work Pack;
  - Reported %, or "No report row";
  - Last locked %;
  - Hours-based %;
  - "Reported by production: 40.00%";
  - FMS actual, POC approved and Difference (information only);
  - **Approved %** (input, pre-filled);
  - **Remark** (input; required when Approved ≠ Reported).
- **Client checks** use the backend's texts. Complete task sends `completionApprovals` for every row. A server 400 or 409 is shown.
- Registered in `taskScreens.ts` and `constants.ts`. `TaskList` shows "Completion % approved" (**ASSUMPTION**).
- **No retraction reason field** (Q3).

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml`: new path and schema `WorkpackProgressRow`; `TaskSummary.elementId` + `UserTask_ApproveCompletion`; complete-variables description |
| Model (Q7 A) | `models/bpmn/Process_PPC_WeeklyProgressPeriod.bpmn`: `ServiceTask_RecordCompletionApproval` (+ diagram room) |
| Backend | `progress/ProgressReadService.java` (read model), `persistence/WorkpackProgressRepository.java` (approval upsert), `worker/RecordCompletionApprovalWorker.java`, `api/ProgressPeriodController.java`, `task/CompletionApprovalCheck.java`, `task/TaskValidity.java`, `task/TaskService.java` and `TaskConfig.java`, `config/ProgressSettings.java` (basis), `config/SecurityConfig.java` (GET: ppc-team, project-manager), `application.yml` allow-list |
| Test support | `bpatest/ProcessTestApplication` (excludes the new worker); `BookHoursFixture` (a `progressBackground`) |
| Frontend | `features/progress/components/ApproveCompletion/*` (+ test), `features/progress/services/progressService.ts`, `types/api.ts`, `taskScreens.ts`, `constants.ts`, `TaskList.tsx` |
| Tests | new `CompletionApprovalApiTest` (12 BPA60_ + extras); `TaskValidityTest` row |
| Docs | data model §13.3.4 "as built" note; plan; REVIEW.md; `intent/README.md` |

Not changed: database (no migration), DMN, seed.

## Order of work
1. Approve the plan; commit it (`Approved`); regenerate `intent/README.md`. No Jira transition without asking (Q6).
2. Contract (and the BPMN change if Q7 A), then the 12 BPA60_ tests and extras. Run them red **before any production code**, committed separately; the React red run gets its own commit. Expected:
   - GET scenarios: 404, no endpoint.
   - Completion scenarios: 404 "Task not found", because `UserTask_ApproveCompletion` is not on the allow-list.
   - Scenario 6 (cost-controller 403): also 404.
3. Backend:
   - read model, controller, security;
   - check, TaskValidity, allow-list;
   - worker and repository.
   Green: the class, then `CompletionReportApiTest`, `TaskApiTest`, `TaskValidityTest`, `ModelDeployTest`, `ModelResourcesTest`, `FlywayBaselineTest`.
4. Frontend: tests red and committed, then the screen. Lint, format, test, build.
5. Docs, full `./mvnw -B verify`, local stack proof, `qa-reviewer`, fixes, REVIEW.md, plan Closed. Push, PR and Jira comment only when asked.

## Scenario-to-test table
**`CompletionApprovalApiTest` setup:**
- **Clock and fixture:** 20 Nov 2026 Asia/Riyadh. `BookHoursFixture.reset()`, `fmsBackground` and `completionBackground` give WP-000457, WP-000461 and WP-000470 In Progress.
- **Week ending 12 Nov 2026:** the fixture's row is set **Locked**, with `workpack_progress` approved 30 / 60 / 0 for WP-000457 / 461 / 470 (the "last locked week"). Locking itself is BPA-67.
- **Weekly instance:** started at `UserTask_ApproveCompletion` with `startInstructions`, then a `progress_period` row for 19 Nov (CutOff) with its key.
- **The week's report:** an `Accepted` `completion_report` with rows WP-000457 40 (Actual hours 150) and WP-000461 100, and `workpack_progress.reported_pct` 40 / 100, made by SQL as BPA-59's read step would.
- **Calls:** GET the read model and complete through the task API as ppc-team.

| # | Scenario | Test | Asserts | Expected first run |
|---|---|---|---|---|
| 1 | PPC approves the reported % | `BPA60_ppcApprovesReportedPercent` | complete with the pre-filled values → 204; approved 40.00 / 100.00 for 19 Nov, `approved_by` ppc-team, `approved_at` set; the instance at `ServiceTask_PrepareProgressCheck` | red: 404 |
| 2 | PPC changes a % | `BPA60_ppcChangesAPercentBeforeApproving` | 457 → 38, remark "FMS double-counted cutting" → reported 40.00, approved 38.00, remark stored | red |
| 3 | No report row keeps last week's % | `BPA60_missingReportRowKeepsLastWeek` | GET: WP-000470 `approvedPct` 0.00, `noReportRow` true | red: 404 |
| 4 | Hours-based % for information | `BPA60_hoursBasedPercentShownForInformation` | 150 Approved hours, phases total 500 → `hoursBasedPct` 30.00, `approvedPct` 40.00 | red |
| 5 | At most 2 decimals | `BPA60_percentTwoDecimalsOnly` | 38.125 → 400 "Completion % can have at most 2 decimal places"; the task stays open | red |
| 6 | Other personas cannot approve | `BPA60_otherPersonasCannotApproveCompletion` | cost-controller completes → 403 | red (404) |
| 7 | FMS actual hours beside approved hours | `BPA60_fmsActualHoursShownBesideApprovedHours` | Actual 150, 142.5 Approved hours → 150.00, 142.50, difference 7.50; completing then → 204 (nothing blocks) | red |
| 8 | A changed % needs a remark | `BPA60_changedPercentNeedsARemark` | 38 with an empty remark → 400 "A remark is required when the approved % differs from the reported %" | red |
| 9 | Previous week must be locked | `BPA60_previousWeekMustBeLockedFirst` | 12 Nov left CutOff → 409 "Week ending 12 Nov 2026 must be locked first"; the task stays open | red |
| 10 | Basis Hours pre-fills from hours | `BPA60_withBasisHoursTheApprovedIsPreFilledFromLoggedHours` | `bpa.progress.basis` = Hours; 60 Approved hours, WP phases set to total 150 → `approvedPct` 40.00 | red |
| 11 | Basis Physical pre-fills reported | `BPA60_withBasisPhysicalTheReportedPhysicalIsPreFilled` | basis Physical; reported 35 → `approvedPct` 35.00 | red |
| 12 | Reported by production | `BPA60_theReportedByProductionIsShownForInformation` | a line with `reported_completion_pct` 40 → `reportedByProductionPct` 40.00 (the React test checks the text "Reported by production: 40.00%") | red |

Extra tests (not scenarios):
- `approvalsMustCoverEveryWorkPack` (400).
- `aPercentAbove100IsRefused` (Q8).
- `aRemarkOver250IsRefused`.
- `aNoReportRowChangeNeedsARemark` (Q9).
- `theFirstWeekHasNoLockRule` (no earlier period, Q2).
- `projectManagerReadsTheWeek` (200) and `supervisorCannotReadIt` (403).
- `aRerunOfRecordCompletionApprovalChangesNothing`.
- `aReturnToTheTaskPreFillsTheStoredApproval` (Q9).
- `TaskValidityTest` row.
- React: the grid, the pre-filled input, a remark required on change, a server 409 shown.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=CompletionApprovalApiTest test`; `grep -c "void BPA60_" backend/src/test/java/com/lcs/star/bpa/CompletionApprovalApiTest.java` = 12; `gh pr checks <n>` |
| Task screen | `cd frontend && npm test -- ApproveCompletion && npm run build`; `grep -n UserTask_ApproveCompletion frontend/src/features/tasks/taskScreens.ts backend/src/main/resources/application.yml` |
| Process runs end to end locally | run below |
| BPMN and DMN match deployed | deployed `Process_PPC_WeeklyProgressPeriod` XML equals `models/` (whitespace-normalised, as BPA-58) |
| Open questions resolved or logged | Questions below, as Jira comments (shown first) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-60.md` |
| Demoed at sprint review | Human gap |

**End-to-end run in the local stack:**
1. Rebuild `bpa-app` and `frontend` (plain `docker build`, as BPA-58).
2. Reset the week as before, then: cut-off → as ppc-team "Send hours to FMS" (Sent) → upload `completion-demo-all-read.csv` → Complete.
3. As ppc-team, open **Approve completion %**. Check the grid and the information columns. Change one % with a remark, then Complete.
4. **`psql`:** `workpack_progress` with `approved_pct`, `override_remark`, `approved_by` ppc-team and `approved_at` for the 19 Nov week.
5. **Operate:** the instance waits at `ServiceTask_PrepareProgressCheck` (no worker until BPA-61).
6. The local stack has no earlier period, so the lock rule is skipped there (Q2). To show the 409, seed a CutOff period for 12 Nov first.

## Risks
- **The instance waits at `ServiceTask_PrepareProgressCheck`** after approval: no worker until BPA-61, so the job stays open (not an incident). Demos stop there.
- **Locking (BPA-67) is not built:** from the second week on, every approval is refused with 409 until the earlier week is Locked. Locally and on QA a week must be locked by SQL until BPA-67.
- **0–100 (Q8):** refusing out-of-range values at BPA-60 means BPA-61's "Blocked" rows (ABOVE_100, BELOW_0) are never reached through the screen. The alternative leaves values in variables that the table cannot hold.
- **BPMN change** (Q7 A) redeploys the weekly process. Instances on the old version complete the task without recording anything.
- **Estimate 5** is tight: the read model has seven derived values.

## Questions
1. **Hours-based %:** the two scenarios name "150 approved of 500 estimated" and "60 Approved hours against a budget of 150".
   - **Recommended:**
     - Denominator: the Work Pack's total budget, the sum of `workpack_phase.manhours` (= `hour_journal_header.estimated_manhours` in the fixtures).
     - Numerator: **Approved** lines only, dated on or before the week ending.
     - Result: 2 dp half-up (Q-DM18 style); null when the budget is 0.
   - Alternative: every line that is not Rejected.
2. **Previous week locked:**
   - **First week:** with no `progress_period` for week ending − 7 days the rule is skipped (ASSUMPTION).
   - **Local run:** the local stack has only the 19 Nov week, so it passes. Tests seed 12 Nov as Locked by SQL. BPA-67 builds locking.
   - Agree?
3. **Retraction reason field:** `progress_retraction` belongs to BPA-61 and BPA-63. Recommended: BPA-60 **does not** show or store a retraction field. BPA-61 and BPA-63 add it when the NeedsRetraction path exists.
4. **Which Work Packs:**
   - **Recommended:** every In Progress Work Pack, plus any Work Pack that already has a `workpack_progress` row for the week (so a report row is never hidden).
   - **Project scope:** no `user_project` filter for ppc-team or project-manager now (BPA-76 to BPA-79, as BPA-57).
5. **The instance stops at `ServiceTask_PrepareProgressCheck`** (BPA-61): recorded in Risks.
6. **Jira:** BPA-60 is already In Progress. I will ask before any other transition.
7. **Where `approved_pct` is written.** Nothing sits between the user task and BPA-61's step.
   - **Recommended (A):** a new `ServiceTask_RecordCompletionApproval` (job `record-completion-approval`). The task API validates first, and the worker writes with `completedBy`, idempotent (ADR 0006, 0007; BPA-58's pattern). BPMN change, your yes needed.
   - (B): the task API writes before completing. Not atomic.
8. **0–100 vs BPA-61 (contradiction).** `approved_pct` has a database CHECK 0–100, while BPA-59 (e) and BPA-61's DMN say values above 100 or below 0 are blocked by BPA-61.
   - **Recommended:** BPA-60 refuses them at completion with 400 "Completion % must be between 0 and 100" (ASSUMPTION text), so PPC corrects them on the same screen. BPA-61's Blocked rows stay as a backstop.
   - Alternative: relax the CHECK with a migration, which changes an approved data model.
9. **Remark and pre-fill:**
   - **Remark reference:** for a "No report row" Work Pack the remark is required when the approved % differs from the pre-filled last locked % (D6: "may still change it, with a remark").
   - **Return to the task:** when the task comes back (Blocked, Declined, Changes requested), the stored `approved_pct` and remark pre-fill the grid.
   - Agree?
10. **FMS actual hours vs POC approved hours:**
    - Both are cumulative to the week ending (the report has no period, so ASSUMPTION).
    - POC approved hours = Approved lines of the Work Pack dated on or before the week ending.
    - Difference = FMS − POC (the scenario: 150 − 142.5 = 7.50).
11. **Texts not in Jira (ASSUMPTION):**
    - "Approvals are required";
    - "Approvals must cover every Work Pack of the week";
    - "Completion % must be between 0 and 100";
    - "Remark can be at most 250 characters";
    - "No report row" (Jira's marker);
    - "Completion % approved" (task list).
12. **Reported by production:** the latest non-null `reported_completion_pct` of the Work Pack's lines that are not Rejected, by work date then entry time, dated on or before the week ending (ASSUMPTION).

## Human-gap log
- None yet. No inputs were staged before this plan.

## Resolutions (20 Nov 2026)
Approved with the recommended answers: Q1 Approved lines up to the week ending over the sum of workpack_phase.manhours; Q2 the lock rule is skipped with no earlier period; Q3 no retraction field; Q4 In Progress Work Packs plus those with a row for the week, no project scope; Q7 option A (ServiceTask_RecordCompletionApproval, a BPMN change); Q8 refuse outside 0 to 100 at completion; Q9 to Q12 as written (ASSUMPTIONs to comment in Jira after a yes).

## Progress and departures (20 Nov 2026)
Approved with the recommended answers (Resolutions above).

**Red runs** (committed apart, 366d19d and 2bcfb6b):
- `CompletionApprovalApiTest`: 19 of 19 failed with 404 "No static resource api/v1/progress-periods/…". **The completion scenarios failed one step earlier than the plan expected** (at the GET that builds their payload, not at "Task not found"), because the test reads the pre-filled values first.
- `TaskValidityTest.approveCompletionNeedsTheApprovals` failed (no rule); React `ApproveCompletion.test.tsx` failed on the missing import.
- The integration red run came after Docker Desktop was started again (it was not running when the red run was first tried).
- Green after the implementation: 17 of 19 on the first run; the 2 failures were the test's own timing (below); then 19 of 19, and 20 with the re-run test.

**Departures from the plan:**
1. **Two tests wait for the record step.** `BPA60_ppcApprovesReportedPercent` and `BPA60_ppcChangesAPercentBeforeApproving` read the database straight after the 204, but the worker writes after the task completes; they now call `awaitRecorded()` (the instance has reached `ServiceTask_PrepareProgressCheck`) first. No assertion changed.
2. **`aRerunOfRecordCompletionApprovalChangesNothing`** was written with the implementation, with no red run (it needs the new service); it calls `CompletionApprovalService.record` twice.
3. **New classes not in the Files table:** `persistence/ProgressWeekRepository` (the derived figures in one query), `progress/CompletionApprovalService` (parse and record), `progress/ProgressReadService` is the read model as planned; `features/progress/approvalMessages.ts` (the screen's checks).
4. **`ProgressPeriodRepository`** gained `ofWeek` and `statusOfWeek`; `WorkpackProgressRepository` gained `approve` (the upsert with the re-run rule).
5. **A Hours basis with a Work Pack budget of 0** pre-fills the reported % (ASSUMPTION; the hours-based % is absent).
6. **A payload entry without a number** answers 400 "Completion % must be a number" (ASSUMPTION, the BPA-59 text).
7. **Existing test support edited (to say in the PR):** `bpatest/ProcessTestApplication` excludes `RecordCompletionApprovalWorker`. No existing test was changed.

**Local-stack proof (20 Nov 2026, new images, process definition version 6):**
- Reset the week; cut-off by hand; as ppc-team "Send hours to FMS" (Sent), upload `templates/demo/completion-demo-all-read.csv` (3 rows, all "Read"), complete: the instance reached `UserTask_ApproveCompletion`.
- `GET /api/v1/progress-periods/2026-11-19/workpacks`: four rows (WP-000011 45, WP-000030 100, WP-000031 20, and WP-000032 marked `noReportRow` with approved 0), with last locked %, hours-based %, FMS actual, approved hours and difference, and "reported by production" on WP-000031.
- Refusals on the open task: a payload that missed WP-000032 → 400 "Approvals must cover every Work Pack of the week"; 38.125 → 400 "Completion % can have at most 2 decimal places"; 38 without a remark → 400 "A remark is required…"; a changed no-report-row Work Pack without a remark → the same 400; 120 → 400 "Completion % must be between 0 and 100"; a seeded 12 Nov CutOff period → 409 "Week ending 12 Nov 2026 must be locked first" (the seeded row was removed again); cost-controller → 403.
- Approving (WP-000011 to 38 with the remark "FMS double-counted cutting"): 204; `workpack_progress` for 2026-11-19: reported 45 / approved 38 with the remark, 100 / 100, 20 / 20, and WP-000032 no report / approved 0, all `approved_by` ppc-team with `approved_at`, `previous_pct` 0. The instance waits at `ServiceTask_PrepareProgressCheck` (no worker until BPA-61).
- Not run by hand: the Hours basis and the screen in a browser (covered by `BPA60_withBasisHoursTheApprovedIsPreFilledFromLoggedHours` and the React tests).
- Side effect on the local stack: the week ending 19 Nov holds four approved rows.

**After the `qa-reviewer` pass (20 Nov 2026; REVIEW.md "BPA-60 at e0e161e"); the plan header now carries Jira's `updated` (11:03:43; the description is unchanged, only the timestamp moved):**
8. **The Hours test now discriminates.** Jira's numbers make the reported % (40) equal the hours-based % (60 of 150), so `BPA60_withBasisHoursTheApprovedIsPreFilledFromLoggedHours` passed whichever basis was used; it now sets the reported % to 35, so 40.00 can only come from the hours (it passed at once: the implementation does use the basis). The Physical test already discriminated.
9. **Under the Hours basis an untouched pre-fill that differs from the reported % needs a remark.** Jira rule (a) asks for a remark whenever the approved % differs from the *reported* %, and the pre-fill under Hours is the hours-based %. The code follows (a) literally. `underTheHoursBasisAnUntouchedPreFill…NeedsARemark` pins this (an ASSUMPTION, written with no red run). **Open question for Jira:** under Hours, should the remark be compared with the pre-filled (hours-based) %? A hours-based % above 100 (approved hours over the budget) is also pre-filled and then refused at completion with "Completion % must be between 0 and 100".
10. **Rows of a Locked week never change.** `CompletionApprovalService.record` refuses a Locked week ("Week ending <d MMM yyyy> is locked"; the job fails with no retries), as the data model says (13.3.4, worker-checked). `aLockedWeekIsNeverChanged`, red first.
11. **The approved % is a plain decimal on both sides:** `parse` read "1e1" as 10 (`new BigDecimal`) and the screen read "0x10" or "1e1" through `Number()`; both now use the decimal rule of BPA-59 (`CompletionApprovalServiceTest` and `approvalMessages.test.ts`, red first).
12. **A week that cannot be loaded is not "no approvals":** the screen shows "The week could not be loaded" and sends nothing (`BPA60_aWeekThatCannotBeLoadedBlocksCompletion`, red first).
13. **`BookHoursFixture.progressBackground` was not needed** (the test sets the week up itself with the existing `completionBackground` and `fmsBackground`).
14. **The BPMN documentation text** of `ServiceTask_RecordCompletionApproval` said "Re-run key: completedTaskKey"; the code keys on the values. The text is corrected (documentation only; the deployed definition takes it at the next start of `bpa-app`).
15. **Jira's description lists "a retraction reason field for a % below last week"** on the screen; plan Q3 (approved by Ahsan) leaves it out because `progress_retraction` belongs to BPA-61 and BPA-63. Jira wins on precedence: **to say in the Jira comment** for the Product Owner.
16. **Known and accepted (POC):** the screen caches the week's rows, so a Work Pack that becomes In Progress while the task is open makes completion fail with "Approvals must cover every Work Pack of the week" until the task is reopened; `approve` keeps `approved_at` and `approved_by` when the same values are approved again after a return; `ProgressSettings.basis()` falls back to Physical for any value other than "Hours"; the red run failed the negative scenarios at the GET that builds their payload, so none failed on its own rule (weak red evidence, as noted in the departures above); one failure of `ConfirmWorkPackApiTest.BPA23_correctMaterialsAndPhases` in the full run (559 tests), which passed when its class was re-run alone (9 of 9) and which nothing in this branch touches.
