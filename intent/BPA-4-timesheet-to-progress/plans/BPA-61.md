# Plan: BPA-61 System can block a Work Pack's progress above 100% or below last week without a retraction
Jira: BPA-61 (Story, epic BPA-4)
Story last updated: 2026-11-20T14:16:07.488+0400
Status: Approved (Ahsan, Build Lead, 20 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-61-progress-rule-check, from main c2aad8b


## Context
After BPA-60 PPC approves the week's completion %, and `ServiceTask_RecordCompletionApproval` writes `approved_pct` and `previous_pct`. The instance then waits at `ServiceTask_PrepareProgressCheck`, where no worker exists.

BPA-61 checks every Work Pack's approved % against FRD-21 §21.9.1 with `Decision_ProgressRule` (FIRST, one evaluation per Work Pack):
- **Above 100% or below 0%:** Blocked. The week goes back to PPC with the reason.
- **Lower than the last locked week, with no approved retraction for that exact %:** NeedsRetraction. The Project Manager gets the retraction task (BPA-63).
- **Otherwise:** Accepted. The instance goes on to Earned man-hours (BPA-62).

## Readiness check (Definition of Ready, docs/02, against Jira 20 Nov 14:16)
| Check | State |
|---|---|
| Jira status | **In Progress** already. The development panel shows a merged PR (BPA-60's #53, which names BPA-61 in its text). No transition without asking (Q6) |
| Template, persona, outcome | Met ("As the System") |
| Gherkin incl. unhappy paths | Met: 9 scenarios (1 Scenario Outline with 2 examples) |
| BPMN element IDs exist | Met. `Gateway_MergeProgressCheck` → `ServiceTask_PrepareProgressCheck` (`prepare-progress-check`) → `BusinessRuleTask_CheckProgressRule` (multi-instance over `progressChecks`, output `progressCheckResults`) → `Gateway_ProgressOutcome`. The gateway has `Flow_ProgressBlocked` → `Gateway_MergeApproval`, `Flow_NeedsRetraction` → `UserTask_ApproveRetraction` (project-manager) and default `Flow_ProgressAccepted` → `ServiceTask_CalculateEarnedManhours`. **No element writes `rule_result` or the messages** (Q3) |
| Every field has an SOR | Met. `workpack_progress.rule_result` and `progress_retraction.approved_pct` are POC database |
| DMN complete | Met: `Decision_ProgressRule`, 3 inputs, 2 outputs, 4 rules, FIRST (matches the Jira table) |
| No blocking question | Q1 (Blocked scenarios cannot come through BPA-60), Q2 (who creates the retraction request) and Q3 (BPMN wiring and where `rule_result` is written) change the build |
| intent.md / spec.md accepted | **Not met** (Jira DoR box unchecked) |
| Estimate ≤ 8 | Met (3). One worker, one recording step, a possible model fix |

## What already exists (closed plan BPA-60; history only)
- **`workpack_progress`:**
  - `approved_pct` numeric(5,2), CHECK 0–100;
  - `previous_pct` NOT NULL DEFAULT 0, written by BPA-60's record step as the last locked %;
  - `rule_result` NULL, CHECK IN (Accepted, Blocked, NeedsRetraction);
  - UNIQUE (`workpack_id`, `progress_period_id`).
- **`progress_retraction`:** `workpack_progress_id` FK, `requested_by` and `reason` NOT NULL (reason not blank), `approved_pct` numeric(5,2) NOT NULL (the % the retraction is for, 10 Nov addendum), `decision` Approved or Declined, `decision_reason` (required when Declined), `decided_by`/`at`, `task_key` UNIQUE. **Nothing writes it yet.**
- **BPA-60:**
  - Q3: no retraction reason field on the Approve completion screen.
  - Q7: `ServiceTask_RecordCompletionApproval` writes the approval.
  - Q8: approved % outside 0–100 is refused at completion, and the DB CHECK forbids storing it. **So DMN rules 1 and 2 can never fire through the real path.**
  - `ProgressWeekRepository.lastLockedPct(workpackId, weekEnding)` gives the last locked %, 0 when none.
- **`Decision_ProgressRule`** (DMN):
  - Inputs `newPct`, `newPct - previousPct`, `retractionApproved`; outputs `progressResult`, `progressReason`.
  - FIRST: >100 Blocked ABOVE_100; <0 Blocked BELOW_0; change < 0 and not retractionApproved NeedsRetraction DECREASE; else Accepted.
  - `Epic2DecisionTablesProcessTest` (BPA-48) tests the table **only with flat variables**, never through the process.
- **BPMN wiring of `BusinessRuleTask_CheckProgressRule`:**
  - `inputElement="progressCheck"`, `calledDecision resultVariable="progressCheck"` (the same name), `outputElement="=progressCheck"`.
  - **Concern:** the decision reads `newPct`, `previousPct` and `retractionApproved` as top-level variables, but each inner instance only has a `progressCheck` context. Every Work Pack would then evaluate as Accepted (null inputs fall through to rule 4).
  - **Second concern:** the results carry only `progressResult` and `progressReason`, not the Work Pack.
  - **The red run will show which is true** before any model change (Q3).
- **Patterns to reuse:** the job worker pattern (skill new-job-worker; ADR 0006 BPMN error and messages, ADR 0007 re-run keys); BPA-58, 59, 60 added recording service tasks the same way; test set-up from `CompletionApprovalApiTest` (start at an element, a period row with the instance key, last locked week by SQL).

## Design
### 1. `prepare-progress-check` (PrepareProgressCheckWorker)
- **Reads** the instance's `progress_period` (by instance key). For each `workpack_progress` row of the week that has an `approved_pct`, it builds one entry:
  - `workpackNumber`;
  - `newPct` = `approved_pct`;
  - `previousPct` = the last locked % (`lastLockedPct`, 0 when none; the same value BPA-60 stored in `previous_pct`);
  - `retractionApproved` = true **only** when a `progress_retraction` of this row has decision `Approved` and `approved_pct` = `newPct` (decision (a)).
- **Output** `progressChecks` (list, by Work Pack number).
- **Re-run safe:** it only reads, so a retry gives the same list.
- **No rows to check** (a week with nothing approved): the job fails with no retries ("No approved completion % for the week", **ASSUMPTION**). BPA-60's all-or-nothing approval makes this unreachable through the screen.

### 2. Model wiring (Q3, needs your yes; proved first)
**(a) Inputs of the rule task.** If the red run shows the inputs are not seen (every row Accepted), add input mappings to `BusinessRuleTask_CheckProgressRule`:
- `newPct = progressCheck.newPct`;
- `previousPct = progressCheck.previousPct`;
- `retractionApproved = progressCheck.retractionApproved`.

Rename `resultVariable` to `progressRuleResult` and set `outputElement` to `={workpackNumber: progressCheck.workpackNumber, newPct: progressCheck.newPct, previousPct: progressCheck.previousPct, progressResult: progressRuleResult.progressResult, progressReason: progressRuleResult.progressReason}`. Each result then names its Work Pack. The gateway conditions (`r.progressResult`) are unchanged.

**(b) A new `ServiceTask_RecordProgressResult`** (job `record-progress-result`) between the rule task and `Gateway_ProgressOutcome`. For each result it:
- writes `workpack_progress.rule_result` (one transaction; audit per change, D64);
- sets `validationMessages`, one message per non-Accepted Work Pack, with the Jira texts:
  - ABOVE_100: "<WP> completion cannot exceed 100% (approved <n>%)";
  - BELOW_0: "<WP> completion cannot be below 0%";
  - DECREASE: "<WP> completion fell from <prev>% to <new>%; a retraction is needed".

Numbers are written without trailing zeros (120, 100.01, 25), an **ASSUMPTION**. The list is set on every pass (empty when all are Accepted), so an old message never shows. Re-run key: the values (writing the same `rule_result` again changes nothing).

The gateway then routes as today: any Blocked goes back to `UserTask_ApproveCompletion`, whose screen already shows `validationMessages`.

**Alternative (no BPMN change):** `prepare-progress-check` works out the messages in advance and writes `rule_result` on the next pass. This duplicates the DMN in Java and leaves an Accepted week without its `rule_result`. Not recommended.

### 3. Retraction request (Q2)
- **BPA-61 only reads approved retractions; it creates no `progress_retraction` row.**
- **Recommended:** BPA-63 (Project Manager approves the retraction, `ServiceTask_RecordRetractionDecision` "writes progress_retraction") creates the row with its decision. The PPC reason ("with retraction reason …" in the Gherkin) then belongs to BPA-63, either as a field it adds to the Approve completion screen or on its own screen.
- **In BPA-61's tests:** scenario 4's reason is not stored. Scenario 8 seeds an Approved retraction by SQL.

### 4. The 0–100 conflict (Q1)
BPA-60 refuses values outside 0–100 at completion, and `approved_pct` cannot hold them, so rules 1 and 2 are a backstop.

**Recommended test approach (ASSUMPTION):** the Blocked scenarios start an instance **at `BusinessRuleTask_CheckProgressRule`** (`startInstructions`) with a `progressChecks` variable built in the test:
- the Outline's 100.01;
- 120;
- −5;
- 120 with a 25 decrease ("blocked wins").

The decision, the new record step and the gateway then run for real. The assertions read `rule_result` (on seeded `workpack_progress` rows without an approved %) and the returned task's `validationMessages` through the task API.

The other scenarios go through the real path: complete `UserTask_ApproveCompletion` through the task API (BPA-60). The approval is recorded, then `prepare-progress-check` and the rule run.

## Files that change
| Area | Files |
|---|---|
| Model (Q3, if proved) | `models/bpmn/Process_PPC_WeeklyProgressPeriod.bpmn`: input mappings and output element on `BusinessRuleTask_CheckProgressRule`; new `ServiceTask_RecordProgressResult` (+ diagram room) |
| Backend | `worker/PrepareProgressCheckWorker.java`, `worker/RecordProgressResultWorker.java`, `progress/ProgressCheckService.java` (builds the checks, writes the results, the messages), `persistence/WorkpackProgressRepository.java` (`setRuleResult`), a small query for approved retractions |
| Test support | `bpatest/ProcessTestApplication` excludes the two new workers |
| Tests | new `ProgressRuleApiTest` (9 BPA61_ + extras); `ProgressMessagesTest` (unit, the three texts and the number format) |
| Docs | data model §13.3.4 "as built" note; plan; REVIEW.md; `intent/README.md` |

Not changed: DMN, contract (no new endpoint, no task screen), database.

## Order of work
1. Approve the plan; commit it (`Approved`). No Jira transition without asking (Q6).
2. The 9 BPA61_ tests and extras against the model **as it is**. Run them red **before any production code** and commit. Expected:
   - Every test that goes through the real path times out at `ServiceTask_PrepareProgressCheck` (no worker).
   - The Blocked tests that start at the rule task show the wiring problem, if it exists: Accepted where Blocked is expected, and no `rule_result` (no record step).
   - **That run decides Q3 (a).**
3. If the red run proves the wiring problem and you agree: the BPMN change (a) and (b), linted.
4. Workers, service, repository; green: the class, then `CompletionApprovalApiTest`, `Epic2DecisionTablesProcessTest`, `ModelDeployTest`, `ModelResourcesTest`.
5. Docs, full `./mvnw -B verify`, local stack proof, `qa-reviewer`, fixes, REVIEW.md, plan Closed. Push, PR and Jira comment only when asked.

## Scenario-to-test table
**`ProgressRuleApiTest` setup:**
- **Clock and fixture:** 20 Nov 2026 Asia/Riyadh. `BookHoursFixture.reset()`, `completionBackground` and `fmsBackground` give WP-000457, WP-000461 and WP-000470 In Progress.
- **Last locked week (12 Nov):** Locked by SQL, with WP-000457 at 30 and WP-000461 at 60. WP-000470 has none.
- **Weekly instance for 19 Nov:** a `progress_period` row with its key, and the week's reported % seeded.
- **Real path:** start at `UserTask_ApproveCompletion`, then complete through the task API with `completionApprovals` (BPA-60).
- **Blocked path:** start at `BusinessRuleTask_CheckProgressRule` with `progressChecks` (Q1).

| # | Scenario | Test | Path | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | Outline: up to 100% (100 Accepted, 100.01 Blocked) | `BPA61_exactly100IsAccepted` | 100: real; 100.01: rule task | WP-000461 `rule_result` Accepted, then Blocked | red: times out at PrepareProgressCheck; the 100.01 part Accepted or no rule_result |
| 2 | Above 100% back to PPC | `BPA61_above100IsBlocked` | rule task (120) | the instance at `UserTask_ApproveCompletion`; `validationMessages` contains "WP-000461 completion cannot exceed 100% (approved 120%)" | red |
| 3 | Unchanged % accepted | `BPA61_unchangedPercentIsAccepted` | real (WP-000457 at 30) | `rule_result` Accepted | red |
| 4 | Decrease needs a retraction | `BPA61_decreaseNeedsRetraction` | real (WP-000457 at 25, with the remark BPA-60 asks for) | `rule_result` NeedsRetraction; a `UserTask_ApproveRetraction` task for group project-manager | red |
| 5 | First week starts from 0% | `BPA61_firstWeekStartsFromZero` | real (WP-000470 at 10) | `rule_result` Accepted | red |
| 6 | Blocked wins over a retraction | `BPA61_blockedWinsOverRetraction` | rule task (461 at 120, 457 at 25) | back at `UserTask_ApproveCompletion`; no `UserTask_ApproveRetraction` task | red |
| 7 | All accepted goes to Earned MH | `BPA61_allAcceptedGoesToEarnedManhours` | real (457 at 40, 461 at 100) | the instance at `ServiceTask_CalculateEarnedManhours` | red |
| 8 | A retraction is valid only for its % | `BPA61_retractionValidOnlyForApprovedPercent` | real; an Approved `progress_retraction` for WP-000457 at 25 seeded by SQL; PPC approves 20 | `rule_result` NeedsRetraction; a new `UserTask_ApproveRetraction` task | red |
| 9 | Below 0% blocked before a retraction | `BPA61_belowZeroBlockedBeforeRetraction` | rule task (WP-000457 at −5, previous 30) | `rule_result` Blocked; `validationMessages` contains "WP-000457 completion cannot be below 0%" | red |

Extra tests (not scenarios):
- `anApprovedRetractionForTheSamePercentIsAccepted` (25 with an Approved retraction at 25 → Accepted).
- `aRerunOfPrepareProgressCheckGivesTheSameChecks`.
- `aRerunOfRecordProgressResultChangesNothing`.
- `theMessagesAreResetOnTheNextPass`.
- `ProgressMessagesTest` (the three texts; 120, 100.01, 25.50 → "25.5").

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=ProgressRuleApiTest test`; `grep -c "void BPA61_" backend/src/test/java/com/lcs/star/bpa/ProgressRuleApiTest.java` = 9; `gh pr checks <n>` |
| Process runs end to end locally | run below |
| BPMN and DMN match deployed | deployed `Process_PPC_WeeklyProgressPeriod` and `Decision_ProgressRule` equal `models/` (whitespace-normalised, as BPA-58 and BPA-60) |
| Open questions resolved or logged | Questions below, as Jira comments (shown first) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-61.md` |
| Demoed at sprint review | Human gap |

**End-to-end run in the local stack:**
1. Rebuild the images. Reset the week as before, and seed a **Locked** week ending 12 Nov with approved %s by SQL (locking is BPA-67).
2. Run the cut-off, "Send hours to FMS" and the report upload, to reach "Approve completion %".
3. **All accepted:** approve without changes. **`psql`:** `rule_result` Accepted for every row. **Operate:** the instance waits at `ServiceTask_CalculateEarnedManhours` (BPA-62, no worker yet).
4. **A decrease:** a fresh week; approve one Work Pack below its locked % with a remark. **`psql`:** that row NeedsRetraction. **Operate:** the instance waits at `UserTask_ApproveRetraction` (BPA-63, no screen yet).

## Risks
- **The current model may never block:** if the DMN does not see `progressCheck`'s fields, every week is "Accepted" today. The red run proves it either way (Q3).
- **BPA-62 and BPA-63 are not built:** the instance waits at `ServiceTask_CalculateEarnedManhours` (no worker) or at `UserTask_ApproveRetraction` (no screen, not on the task API's allow-list). Demos stop there.
- **Rules 1 and 2 are reachable only by a test or a data fix** (BPA-60 refuses those values, and the column cannot hold them).
- **BPMN change** (Q3) redeploys the weekly process. Instances on the old version keep the old wiring.
- **No retraction request exists yet** (BPA-63), so a decrease always ends at the retraction task until BPA-63 builds the decision.

## Questions
1. **Blocked scenarios** (100.01, 120, −5, "blocked wins"): they cannot come through BPA-60 (refused at completion; the column cannot hold them). **Recommended (ASSUMPTION):** those tests start the instance at `BusinessRuleTask_CheckProgressRule` with a `progressChecks` variable, so the real decision, record step and gateway run. The other scenarios go through the real approval path. Agree?
2. **Who creates the retraction request?** **Recommended:** BPA-63, together with the Project Manager's decision; the PPC reason field (Gherkin "with retraction reason …") is added by BPA-63, not BPA-61 or BPA-60. BPA-61 only reads Approved retractions; scenario 8 seeds one by SQL. Agree?
3. **Where `rule_result` and the messages are written, and the model wiring (BPMN change, your yes):**
   - **(a)** Input mappings on the rule task (`newPct`, `previousPct`, `retractionApproved` from `progressCheck`), a `resultVariable` that is not `progressCheck`, and an output element that keeps the Work Pack. Only if the red run proves the current wiring does not feed the decision.
   - **(b)** A new `ServiceTask_RecordProgressResult` after the rule task, which writes `rule_result` and sets `validationMessages`.
4. **"Valid only for the % it approved":** after a re-approval at a different %, the old Approved retraction is **kept** (history, FRD-21 §21.7 audit) and simply no longer matches (its `approved_pct` ≠ the new %). A new request is a new row (BPA-63). Agree?
5. **BPA-62 and BPA-63** have no worker or screen yet: recorded in Risks.
6. **Jira:** BPA-61 is already In Progress. The merged PR in its development panel is BPA-60's #53. I will ask before any transition.
7. **Messages (ASSUMPTION):**
   - Numbers are written without trailing zeros ("approved 120%", "fell from 30% to 25%").
   - The list holds one message per Work Pack that is not Accepted, so a week sent back as Blocked also shows its DECREASE messages.
   - The list is reset on every pass.
8. **Which Work Packs are checked:** every `workpack_progress` row of the week with an `approved_pct` (BPA-60 approves the whole week). `previousPct` is the last locked % worked out again (equal to BPA-60's stored `previous_pct`).

## Human-gap log
- None yet. No inputs were staged before this plan.

## Resolutions (20 Nov 2026)
Approved with the recommended answers: Q1 the Blocked scenarios start at BusinessRuleTask_CheckProgressRule with a progressChecks variable; Q2 BPA-63 creates the retraction request; Q3 (a) the rule-task wiring change only if the red run proves the problem, (b) the new ServiceTask_RecordProgressResult (BPMN changes); Q4 an old Approved retraction is kept; Q7 and Q8 as written (ASSUMPTIONs to comment in Jira after a yes).

## Progress and departures
- **Red run (f9bf94b):** 10 of 10 failed. A throwaway probe (deleted) showed every `progressCheckResults` entry Accepted with no Work Pack, and the instance reached `ServiceTask_CalculateEarnedManhours`: the rule task's wiring did not feed the decision. This proved Q3 (a), so the BPMN change (a) and (b) was made (8582920).
- **Departure (ASSUMPTION):** the workers find the week's period by the instance key, else by the `progressPeriodId` variable, because an instance started at the rule task (the Blocked tests) does not own the `progress_period` row. In production the instance key is always found.
- **Departure, tests after code:** `ProgressMessagesTest` was written with the implementation, with no red run.
- **Edit to a test after the red run:** `ProgressRuleApiTest.ruleResult` returns null while the row does not exist (WP-000470 gets its row from BPA-60's record step); it threw before. No scenario or assertion changed.
- **Test support:** `ProcessTestApplication` excludes the two new workers (no database there). `BookHoursFixture` resets `progress_retraction` (f9bf94b).
- **Result:** `ProgressRuleApiTest` 10/10, `ProgressMessagesTest` 5/5; `CompletionApprovalApiTest` 22/22, `Epic2DecisionTablesProcessTest` 16/16, `ModelDeployTest` and `ModelResourcesTest` pass. (One `Epic2DecisionTablesProcessTest` run failed on a Camunda container start and passed on rerun.)
- **Known:** BPA-62 and BPA-63 have no worker or screen yet, so a week stops at `ServiceTask_CalculateEarnedManhours` or `UserTask_ApproveRetraction`; the BPMN change redeploys the weekly process (old instances keep the old wiring).
- **Edit to an existing test (BPA-60's `CompletionApprovalApiTest`):** the full `verify` showed `BPA60_ppcApprovesReportedPercent` and `BPA60_ppcChangesAPercentBeforeApproving` timing out, because the instance no longer rests at `ServiceTask_PrepareProgressCheck` (BPA-61's worker now runs). `awaitRecorded` now accepts that step or any later resting step (`ServiceTask_CalculateEarnedManhours`, `UserTask_ApproveRetraction`). No assertion on stored values changed. To be said in the PR.

**Local-stack proof (20 Nov 2026, new `bpa-app` image, process definition version 8):**
- Reset the week by SQL (cancelled the old instance, deleted the 19 Nov progress rows and period), seeded a Locked 12 Nov period with approved 30 / 60 / 10 for WP-000011 / WP-000030 / WP-000031, started the weekly process at `ServiceTask_OpenProgressPeriod` (cut-off by hand), then as ppc-team through the portal API: "Send hours to FMS" (Sent), upload `templates/demo/completion-demo-all-read.csv`, complete, and approve.
- **All accepted** (approved 45 / 100 / 20, WP-000032 with no report row at 0): `rule_result` Accepted on all four rows, four `audit_log` rows (`rule_result`, null to Accepted, `bpa-service`); the instance waits at `ServiceTask_CalculateEarnedManhours` (BPA-62, no worker yet).
- **A decrease** (fresh week; WP-000011 approved at 25 with the remark, last locked 30): WP-000011 NeedsRetraction, the others Accepted; the instance waits at `UserTask_ApproveRetraction` (BPA-63, no screen yet).
- Deployed `Process_PPC_WeeklyProgressPeriod` and `Decision_ProgressRule` equal `models/` (whitespace-normalised).
- Not run by hand: Blocked (above 100 or below 0) cannot arise through the screen; covered by `BPA61_above100IsBlocked`, `BPA61_belowZeroBlockedBeforeRetraction`, `BPA61_blockedWinsOverRetraction`.
- Side effect on the local stack: the week ending 19 Nov holds the decrease run (instance at `UserTask_ApproveRetraction`) and a Locked 12 Nov seed period (`process_instance_key` 1).
- Frontend: no change in this story, so no frontend lint, test or build run.
