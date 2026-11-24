# Plan: BPA-83 Project Manager can reopen a locked week with a documented justification
Jira: BPA-83 (Story, epic BPA-4)
Story last updated: 2026-11-25T00:47:29.158+0400
Status: Closed (25 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-83-reopen-locked-week, from main 6742c1c (the merge of PR #70, BPA-79)

> Checked read-only: the branch is at 6742c1c with no commits of its own and a clean tree (only the untracked `.postman/` and `postman/`, which stay out).

## Context
BPA-67 locks a progress week at the end of `Process_PPC_WeeklyProgressPeriod` (`ServiceTask_LockProgressPeriod`, "reopening is out of scope"). After that, approved % and Earned MH of the week never change, and a correction goes through a retraction in a later week (BPA-63). BPA-83 lets the Project Manager reopen a **Locked** week with a reason (FRD-21 §21.7, FRD-10 §10.10). It works only when `progress.allowReopen` is true (C-E3-3, 11 Nov 2026; default false). The reopened week goes back through the normal approval path and locks again.

Decision 12 Nov 2026 (Khitaba for the PO, in the Jira description): `reopened_by`, `reopened_at` and `reopen_reason` have SOR **POC database**; the setting stays false by default; the certificate behaviour (Epic 3) is out of scope.

## Readiness check (Definition of Ready, docs/02, against Jira 25 Nov 00:47)
| Check | State |
|---|---|
| Jira status | **In Progress** (Ahsan moved it on 25 Nov) |
| Template, persona, outcome | Met (Project Manager) |
| Gherkin incl. unhappy path | Met: 6 scenarios. 5 are built here; the certificate scenario is out of scope (Q5) |
| BPMN element IDs | `Process_PPC_WeeklyProgressPeriod` exists. Jira's BPMN Reference names "'Reopen week' API action plus message after the lock (ASSUMPTION)"; the message start event is new (Q1) |
| Every field has an SOR | Met: `progress_period.reopened_*` → POC database (12 Nov). The SOR Notes list a status **Open**, which the table does not have (CutOff, Locked): not added (Q7) |
| DDL for `progress_period.reopened_*` (Ahsan) | **Open; proposed below (Q2).** Approving this plan closes it |
| Epic 2 intent.md and spec.md accepted (Faraz) | Unticked in the Jira checklist. The story was moved to Ready on 17 Nov with Faraz's approval (comment 10567). Reported, not blocking |
| No blocking question | Q1 to Q4 resolved in plan mode (below) |
| Estimate ≤ 8 | 3 |

## What exists today (read-only findings)
- **Process.** One timer start event (`StartEvent_WeeklyCutOff`), no message start. `Gateway_MergeApproval` → `UserTask_ApproveCompletion` (ppc-team) → record → progress rule → (retraction) → `ServiceTask_CalculateEarnedManhours` → variance → `UserTask_ReviewActualManhours` (cost-controller) → `UserTask_ApproveWeeklyProgress` (project-manager) → `ServiceTask_RecordWeekDecision` → `Gateway_IsWeekApproved` → `ServiceTask_CompleteWorkPacks` → `ServiceTask_LockProgressPeriod` → `EndEvent_PeriodLocked`. Variables used on this path: `progressPeriodId`, `weekEnding`.
- **The period is found by the instance key.** `ProgressPeriodRepository.ofInstance(processInstanceKey)` is used by the task checks (`CompletionApprovalCheck`, `CbsReviewCheck`, `RetractionDecisionCheck`, `WeekDecisionCheck`) and three workers. A new instance must therefore become the period's `process_instance_key` (column NOT NULL, UNIQUE).
- **The "is locked" guard.** `ProgressPeriodRepository.requireOpen` (and its copy in `WeekDecisionService.record`) refuses only `status = 'Locked'`: a Reopened week already passes. `LockProgressPeriodWorker` → `ProgressPeriodRepository.lock` locks any status except Locked, so Reopened → Locked works. But its audit row writes the old `locked_at` as null, which is wrong on a re-lock.
- **BPA-60.** `CompletionApprovalCheck` refuses approved % for week N+1 with 409 "Week ending <N> must be locked first" while week N is not Locked. This runs on every pass.
- **BPA-62.** `EarnedManhoursService.calculate` updates `activity_progress` row by row (insert, update changed columns, delete; audit each) and is safe to run again for the same week.
- **BPA-66.** `recordApproval` keeps the first `week_approved_by/at` when the same approver approves again. The segregation rule ("You cannot approve a week whose progress you approved") is unchanged.
- **Camunda calls.** `CamundaProcessClient.correlate` (`/v2/messages/correlation`, BPA-12) returns only true/false. `publish` (BPA-49). Start instructions are used only by tests as admin; no BPA-25-style check proves them for the app.
- **API.** Three GETs under `/api/v1/progress-periods/{weekEnding}` in `ProgressPeriodController`. SecurityConfig's catch-all `/api/v1/progress-periods/**` admits ppc-team and project-manager. No project-manager-only rule exists yet. The model for a reason body is `amendTimesheetLine` (problem texts from the service, not bean validation).
- **Settings.** `ProgressSettings` reads `bpa.progress.*` from the `Environment` on each call (basis, source). compose passes no `bpa.*` variables today.
- **Screen.** BPA-73's `ProgressTab` (`WorkPackDetail/tabs.tsx`) lists the weeks of one Work Pack. Its status enum is `[CutOff, Locked]`. No weeks page exists.

## Design

### 1. DDL (Q2, for Ahsan's approval; closes the DoR item): `V9__progress_period_reopen.sql`
```sql
-- V9__progress_period_reopen.sql (BPA-83; ADR 0010; docs/data-model.md section 3.1). V1 to V8 are frozen.
-- A Project Manager may reopen a Locked week (FRD-21 §21.7, FRD-10 §10.10) when bpa.progress.allow-reopen is true (C-E3-3).
-- SOR of the three columns: POC database (decision 12 Nov 2026, Khitaba for the PO). They hold the latest reopen; every reopen is in audit_log.
ALTER TABLE bpa.progress_period DROP CONSTRAINT progress_period_status_check;
ALTER TABLE bpa.progress_period ADD CONSTRAINT progress_period_status_check CHECK (status IN ('CutOff', 'Locked', 'Reopened'));
ALTER TABLE bpa.progress_period
    ADD COLUMN reopened_by   text,
    ADD COLUMN reopened_at   timestamptz,
    ADD COLUMN reopen_reason text;
ALTER TABLE bpa.progress_period ADD CONSTRAINT progress_period_reopen_check
    CHECK (status <> 'Reopened' OR (reopened_by IS NOT NULL AND reopened_at IS NOT NULL AND reopen_reason IS NOT NULL AND btrim(reopen_reason) <> ''));
COMMENT ON COLUMN bpa.progress_period.reopened_by IS 'POC database';
COMMENT ON COLUMN bpa.progress_period.reopened_at IS 'POC database';
COMMENT ON COLUMN bpa.progress_period.reopen_reason IS 'POC database';
```
- `progress_period_status_check` is Postgres's name for V2's unnamed CHECK. The red run proves it; if the name differs, the migration uses the real one.
- The columns stay set after the re-lock, so the last reopen stays visible on the row (ASSUMPTION). A second reopen overwrites them; audit_log keeps every one.
- `reopened_by` is labelled POC database by the 12 Nov decision, although the similar `week_approved_by` is labelled "Camunda identity". The decision wins.

### 2. Setting
- `application.yml`: `bpa.progress.allow-reopen: false` beside `basis` and `source`. Jira writes `progress.allowReopen`; the repo's keys sit under `bpa.` in kebab case.
- `ProgressSettings.allowReopen()` = `environment.getProperty("bpa.progress.allow-reopen", Boolean.class, false)`.
- `docker-compose.yml` `bpa-app`: `BPA_PROGRESS_ALLOW_REOPEN: ${BPA_PROGRESS_ALLOW_REOPEN:-false}`, and `.env.example` gets the line with `false`. This switches it on for a demo.

### 3. Reopen action: `POST /api/v1/progress-periods/{weekEnding}/reopen` `{ reason }`
- **Contract first** (`reopenProgressPeriod`, `x-assumption`). Answers:
  - 200 with the period (week ending, status Reopened, reopened by/at, reason).
  - 400 "Enter a reason to reopen the week".
  - 403: the shared `Forbidden`.
  - 404 "Week not found" (the existing text).
  - 409 "Reopening a locked week is not allowed".
  - 409 for a week that is not Locked.
  - 503 when the engine is down.
- **SecurityConfig:** `/api/v1/progress-periods/*/reopen` → `hasAuthority(PROJECT_MANAGER)`, every method, placed before the catch-all.
- **Order of checks:**
  1. The group (403).
  2. The setting (409 "Reopening a locked week is not allowed"; ASSUMPTION that it comes before the body).
  3. The week (404).
  4. The reason, trimmed and not blank (400; the week stays Locked).
  5. The status, which must be Locked: 409 "Week ending <d MMM yyyy> is not locked" (**ASSUMPTION**: no source gives the text; it mirrors "Week ending … is locked").
- **Then, in one transaction:**
  1. Take the row `FOR UPDATE`.
  2. Correlate the message (§4); it returns the new instance key.
  3. Write `status = 'Reopened'`, `reopened_by` (session user), `reopened_at` (injected clock), `reopen_reason`, and `process_instance_key` (the new instance).
  4. Clear `week_approved_by` and `week_approved_at` (Q4), so the re-approval is recorded as a new one.
  5. Write audit rows.
- If the engine is down: 503, and nothing changes.
- **Audit (Q4):** one `audit_log` row per changed column (D64): status, reopened_by, reopened_at, reopen_reason, process_instance_key, week_approved_by, week_approved_at. Exactly **one** row carries the reason (`column_name = reopen_reason`, username, changed_at). This is how "the audit log has one row with the reason, my username and the time" is read here.
- A new `ProgressReopenService` (package `progress`) next to `ProgressLockService`. Repository methods are added in `ProgressPeriodRepository` (`reopen(State, …)`), reusing its `audit(...)` helper.

### 4. The process path back to approval (Q1): recommended **A, a message start event in the same process**
- **BPMN** (`models/bpmn/Process_PPC_WeeklyProgressPeriod.bpmn`):
  - add `Message_WeekReopened` (name `WeekReopened`, correlation key `=weekEnding`);
  - add `StartEvent_WeekReopened` (message start, Project Manager lane);
  - add `Flow_WeekReopened` → `Gateway_MergeApproval`, its fifth incoming flow.
  - Nothing else in the main path changes. With the setting false, the event is never triggered.
  - The lock step's documentation drops "reopening is out of scope".
- **Backend:** `CamundaProcessClient.correlate` gains a variant that returns the started instance's key (`/v2/messages/correlation` answers `processInstanceKey`). The variables are `progressPeriodId` and `weekEnding`, as `OpenProgressPeriodWorker` sets them. The correlation key is the week ending: Zeebe starts at most one active instance per correlation key from a message start event, so a double click cannot start two.
- **Why A:** it is what Jira's BPMN Reference names ("message after the lock"). It uses the message call this repo already uses (BPA-12 correlate), and the model shows the path. **B (start instructions at `Gateway_MergeApproval`)** changes no BPMN, but the app has never made that call: 09 S25 allows only calls BPA-25 proved or a later story checked, and the path would be invisible in the model. **A new small process** would duplicate the approval path.
- **Proof of the call:** a check in `Bpa13CorrelationChecksTest` (it already probes correlation to a message start) that the answer carries `processInstanceKey`. It is red first if the variant does not exist.

### 5. Guards and the re-lock
- **"Is locked" guards** (`requireOpen` and `WeekDecisionService.record`): unchanged; they already treat Reopened as editable. Tests prove it (approved % change by ppc-team on the reopened week).
- **Lock:** `ProgressPeriodRepository.lock` already accepts Reopened → Locked. Fixed: its audit row for `locked_at` now writes the real old value, not null.
- **Earned MH:** recalculated by the normal `ServiceTask_CalculateEarnedManhours` on the new pass, once per pass (BPA-62's update in place). A later week that is already Locked keeps the values it computed; it is not recalculated (Q6).
- **BPA-60 (Q3):** while week N is Reopened, week N+1's approved % is refused with the existing 409 "Week ending <N> must be locked first" until N is Locked again. No code change; a guard test.
- **Not reopened (unchanged, said in the PR):** hours entry stays closed (`weekClosed` is any period row); Work Packs already Completed stay Completed (the `WorkPackCompleted` message is not sent again); "last Locked %" reads skip the week while it is Reopened.
- **Epic 3 (Q4 of the brief):** BPA-84 will publish `ProgressPeriodLocked` on every lock, so a re-lock would publish it again. Noted in the plan and the Jira comment; not built here.

### 6. Screen: the smallest option
- On BPA-73's `ProgressTab`, a row whose status is Locked shows **Reopen week** to a `project-manager` (`useAuth().groups`). Clicking it shows a reason field and **Reopen**, which posts and shows the backend's text on refusal (`ErrorMessage`). The detail query is refreshed on success.
- The labels are ASSUMPTION, and the button says it reopens the whole week, not only this Work Pack.
- The button is shown whatever the setting is: the backend answers 409 when it is off (no settings endpoint is added).
- The status enum of the Progress tab gains `Reopened` (contract), shown as text.
- `progressService.reopenWeek` (openapi-fetch). `ProgressTab` gets `api`, as `HistoryTab` already does.

### 7. Docs
`docs/data-model.md` §3.1 (the three columns, the CHECKs, "As built by BPA-83"), the `docs/03-sor-matrix.md` row for Epic 2 hours/progress (reopen columns, POC database), the BPMN documentation, REVIEW.md, intent/README.md. No ADR change.

## Files that change
| Area | Files |
|---|---|
| Migration | `backend/src/main/resources/db/migration/V9__progress_period_reopen.sql` |
| Model | `models/bpmn/Process_PPC_WeeklyProgressPeriod.bpmn` (message start, flow, documentation) |
| Contract | `api/openapi.yaml` (`reopenProgressPeriod`, `ReopenRequest`, `ProgressPeriodReopened`, status enum + Reopened) |
| Backend | `api/ProgressPeriodController.java`, `progress/ProgressReopenService.java` (new), `persistence/ProgressPeriodRepository.java` (`reopen`, the lock audit fix), `adapter/CamundaProcessClient.java` (correlate returning the key), `config/ProgressSettings.java`, `config/SecurityConfig.java`, `resources/application.yml` |
| Compose | `docker-compose.yml`, `.env.example` |
| Backend tests | new `WeekReopenApiTest.java`; `Bpa13CorrelationChecksTest` (+1 check); `FlywayBaselineTest` (V9 in the list; existing-test edit); `ProgressSettingsTest` (new or +1) |
| Frontend | `features/workpacks/components/WorkPackDetail/tabs.tsx`, `WorkPackDetail.tsx` (pass `api`), `features/progress/services/progressService.ts`; test `pages/WorkPackDetailPage/WorkPackDetailPage.test.tsx` |
| Docs | `docs/data-model.md`, `docs/03-sor-matrix.md`, plan, `REVIEW.md`, `intent/README.md` |

## Order of work
1. Approve and commit the plan (`Approved`).
2. Contract, migration and BPMN first; then the tests **red before production code** (compile stubs only); commit with the red output.
3. Build: setting, SecurityConfig, the correlate variant, `ProgressReopenService`, the repository, the lock audit fix; then the Progress tab. Green.
4. Docs; the targeted proof; `npm run lint:bpmn`; the local check; `qa-reviewer`; fixes (red first); REVIEW.md; plan Closed. Push, PR and Jira only when you ask.

## Scenario-to-test table
**Backend `WeekReopenApiTest`** (per-class Postgres, shared Camunda, the week's instance driven to the lock with the helpers of `WeekLockApiTest` / `WeekApprovalApiTest`; the setting set per test through the `Environment`, which `ProgressSettings` reads on each call). Background: week ending 19 Nov 2026 Locked; logged in as project-manager.

| # | Scenario | Test | Asserts | Expected first run |
|---|---|---|---|---|
| 1 | PM reopens a locked week with a reason | `BPA83_pmReopensLockedWeekWithReason` | 200; status Reopened; reopened_by project-manager, reopened_at = clock, reason; one audit row with column `reopen_reason`, the reason, project-manager, the time; a new instance waits at `UserTask_ApproveCompletion` and ppc-team changes approved % there (accepted) | **red** (404: no endpoint) |
| 2 | A reason is required | `BPA83_reasonIsRequired` | 400 "Enter a reason to reopen the week" (missing, empty, blank); status Locked; no audit row; Camunda never asked (Mockito spy) | **red** |
| 3 | Reopen is off by default | `BPA83_reopenOffByDefault` | the setting unset → 409 "Reopening a locked week is not allowed"; status Locked | **red** |
| 4 | Other personas cannot reopen | `BPA83_otherPersonasCannotReopen` | ppc-team → 403; status Locked | **red** (the catch-all lets ppc-team reach a 404/405) |
| 5 | A reopened week locks again through the normal approval | `BPA83_reopenedWeekLocksAgainThroughApproval` | after reopen: ppc approves %, cost-controller reviews, PM approves → status Locked; `ServiceTask_CalculateEarnedManhours` completed once in the new instance; activity_progress shows the new Earned MH, one row per activity; the lock's `locked_at` audit has the real old value | **red** |
| 6 | Certificate flag (ASSUMPTION, Epic 3) | **not built, no test** (12 Nov decision; Q5) | | |

**Extras (not scenarios):**
- `aWeekThatIsNotLockedCannotBeReopened`: a CutOff week and a Reopened week each give 409 (ASSUMPTION text).
- `anUnknownWeekIsNotFound`: 404 "Week not found".
- `theNextWeekWaitsUntilTheReopenedWeekIsLockedAgain`: BPA-60's 409 for week N+1 (a guard).
- `nothingChangesWhenTheEngineIsDown`: 503, status Locked.
- `Bpa13CorrelationChecksTest`: correlation to a message start answers `processInstanceKey`.
- `ProgressSettingsTest`: allowReopen is false by default.

**React `WorkPackDetailPage.test.tsx`:**
- `BPA83_pmReopensLockedWeekWithReason`: PM sees Reopen week on a Locked row, enters a reason, the POST carries it, the row shows Reopened.
- `BPA83_reasonIsRequired`: the backend's 400 text is shown.
- `BPA83_otherPersonasCannotReopen`: ppc-team sees no button.

All three are red first. Scenarios 3 and 5 are backend-only; the screen shows the 409 text through the same path as scenario 2's.

`grep -c "void BPA83_"` = 5 in Java, 3 `BPA83_` in React.

## Proof (one per Definition of Done line, docs/02), targeted only
| DoD line | Evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt` |
| One test per scenario, passing in CI | **Locally only:** `cd backend && ./mvnw -B -Dtest='WeekReopenApiTest,WeekLockApiTest,WeekApprovalApiTest,CompletionApprovalApiTest,EarnedManhoursApiTest,Bpa13CorrelationChecksTest,FlywayBaselineTest,ProgressSettingsTest,ModelDeployTest' test` and `cd frontend && npm test -- WorkPackDetailPage`, plus `npm run lint`, `npm run format:check`, `npx tsc --noEmit`, `npm run lint:bpmn`. **The whole suites run only in CI** (if Actions does not start, that is a human gap, said in the PR) |
| Process runs end to end locally | Rebuild `bpa-app` (with `BPA_PROGRESS_ALLOW_REOPEN=true` in `.env`) and `frontend`. As project-manager, reopen a Locked week with a reason. As ppc-team, change an approved % on the new Approve completion task. Cost-controller reviews, PM approves, and the week is Locked again. The audit rows and the Earned MH are read back. Then the setting is set back to false |
| BPMN and DMN match deployed | `ModelDeployTest`; the local stack deploys from `models/` |
| Open questions resolved or logged | the Jira comment (shown first, posted on your yes) |
| Plan Closed | `grep '^Status: Closed'` |
| Demoed at sprint review | Human gap |

## Risks
- **Two writes, two systems:** if the database commit fails after Camunda started the instance, an instance exists for a week that is still Locked. Its tasks find no period (403/404 from the checks). POC risk, logged, no compensation built.
- **Reopening an older week** while later weeks are Locked: their Earned MH and baselines are not recalculated (Q6).
- **Targeted runs only:** regressions in other progress classes show only in CI.
- **Start-event change:** every weekly process model now has two start events. `ModelDeployTest` and the BPMN lint cover it.

## Questions
1. **Process path:** A (message start event `StartEvent_WeekReopened` → `Gateway_MergeApproval` in the same process), B (start instructions, no BPMN change), or other? Recommended A. No BPMN change until you answer.
2. **DDL:** V9 as above (status CHECK + Reopened; three nullable columns; reason, by and at required when Reopened; SOR comments). Approving it closes the DoR item.
3. **Week N reopened while N+1 is in approval:** N+1's approved % is blocked by BPA-60's existing 409 until N is Locked again (recommended; no change).
4. **On reopen**, clear `week_approved_by/at` (so the re-approval is recorded fresh; the old values stay in audit_log), and read the audit line as "one row per changed column, exactly one carrying the reason". Both ASSUMPTION.
5. **Epic 3:** BPA-84's `ProgressPeriodLocked` would be published again on the re-lock: noted for Epic 3, not built.
6. **Older weeks:** may any Locked week be reopened, or only the latest? Jira does not limit it; the plan allows any (ASSUMPTION) and states the risk.
7. **Status "Open"** in the SOR Notes: not added; the statuses stay CutOff, Locked and Reopened.
8. **The certificate scenario** is in the Gherkin but out of scope. Proposed Jira comment (shown first, posted on your yes): "BPA-83: the scenario 'A week already on an Invoice Certificate is flagged' is out of scope for Epic 2 (decision 12 Nov 2026, Khitaba for the PO). It is not built or tested in BPA-83 and is carried to Epic 3 (certificates). The Definition of Done for BPA-83 covers the other five scenarios. Note for Epic 3 (BPA-84): a reopened week is locked again, so ProgressPeriodLocked would be published a second time."
9. **Jira:** I ask before any transition or comment.

## Resolutions (Ahsan, 25 Nov 2026, in plan mode)
- **Q1:** A, the message start event `StartEvent_WeekReopened` → `Gateway_MergeApproval`; the BPMN change is approved.
- **Q2:** the V9 DDL is approved as shown. This closes the DoR item "DDL for progress_period.reopened_* (Ahsan)".
- **Q3:** week N+1 is blocked by BPA-60's existing 409 until N is Locked again.
- **Q4 and Q6:** clear `week_approved_*`; one audit row per changed column, exactly one with the reason; any Locked week may be reopened.
- Still open, for the Jira comment (on your yes): Q5 (Epic 3 note), Q7 (no "Open" status), Q8 (the certificate scenario).

## ASSUMPTIONs (marked in code, contract and data model)
The 409 text for a week that is not Locked; the setting checked before the body; the reopen columns kept after re-lock; clearing week_approved_*; the audit reading; any Locked week may be reopened; the screen labels; the button shown whatever the setting.

## Human-gap log
- **DoR:** epic intent.md and spec.md acceptance unticked in Jira (reported).
- **DoD:** the whole suite runs only in CI. The demo at sprint review. Clicking the portal as project-manager is a person's.

## Progress and departures (25 Nov 2026, red phase)
- Contract (`reopenProgressPeriod`, `ReopenRequest`, `ProgressPeriodReopened`, status enum + Reopened), `V9__progress_period_reopen.sql` (as approved, the CHECK name `progress_period_status_check` is Postgres's own for V2's unnamed one) and the BPMN (`StartEvent_WeekReopened` → `Gateway_MergeApproval`, `Message_WeekReopened`) are committed with the red tests. `npm run gen:api` parses the contract; `lint:bpmn` is clean.
- **Departure: no correlation key in the BPMN.** `Message_WeekReopened` has no `zeebe:subscription` (a message start event takes none, as `Message_DrawingRevised`); the endpoint still sends the week ending as the correlation key, which is what makes Zeebe start one instance per week (proved by the probe).
- **Compile stubs only** (they throw or answer false): `CamundaProcessClient.correlateForKey`, `ProgressSettings.allowReopen`, `ProgressPeriodController.reopenProgressPeriod`.
- **Red run, targeted:** `WeekReopenApiTest` 9 of 9 fail (the stub answers 500 where the story's answers are expected); `ProgressSettingsTest` 2 of 4 fail (true is not read; the two false cases are guards); React `BPA83_pmReopensLockedWeekWithReason` and `BPA83_reasonIsRequired` fail (no button).
- **Pass before code (guards, said so):** `FlywayBaselineTest` (V9 exists in this commit); React `BPA83_otherPersonasCannotReopen` (no button exists; the plan said all three red); `Bpa13CorrelationChecksTest.probeCorrelateOfTheWeekReopenedMessage` (it checks Camunda with the new BPMN: 200 with the new instance's key, a second correlate for the same week while it is active answers 404; it deploys the weekly process itself, as that class runs no application).

## Green (25 Nov 2026)
- Built as planned: `bpa.progress.allow-reopen` (`ProgressSettings.allowReopen`, read at each call; compose `BPA_PROGRESS_ALLOW_REOPEN`, `.env.example`), SecurityConfig (project-manager only, before the BPA-60 catch-all), `CamundaProcessClient.correlateForKey`, `ProgressReopenService`, `ProgressPeriodRepository.reopen`, the controller, the Progress tab (Reopen week, reason, Reopen, Cancel; project-manager only; the backend's text shown).
- **Lock audit fix:** `ProgressPeriodRepository.lock` now audits the real previous `locked_at` (it wrote null), proved by `BPA83_reopenedWeekLocksAgainThroughApproval`.
- **Departure (ASSUMPTION, text):** when Camunda starts nothing because an instance of the week is already active for the message, the reopen answers 409 "Week ending <d MMM yyyy> could not be reopened; try again" (QA review: the first text named a cause that a 404 cannot prove; the cause is logged); the contract's 409 list covers it by its generic description.
- **Test-side departures:** `WeekReopenApiTest` cancels the instances it started after each test (one active instance per correlation key; all classes share one Camunda), and the setting is switched on through a static property source (`ProgressSettings` reads the environment at each call).
- Docs: `docs/data-model.md` (§13.3.1 columns, CHECK, "As built by BPA-83"), `docs/03-sor-matrix.md`.
- **Targeted proof (Ahsan's rule), backend:** `WeekReopenApiTest` 9, `ProgressSettingsTest` 4, `FlywayBaselineTest` 2, `Bpa13CorrelationChecksTest` 2, `ModelDeployTest` 2, `WeekLockApiTest` 11, `WeekApprovalApiTest` 12, `CompletionApprovalApiTest` 22, `EarnedManhoursApiTest` 14: all pass, 0 failures (one `ModelDeployTest` 10 s socket timeout against Camunda in the first run passed alone, 2 of 2). Frontend `WorkPackDetailPage` and `WorkPacksPage` 23 of 23; `tsc`, lint, format check and `lint:bpmn` clean. The whole suites run only in CI.

## Review and departures (25 Nov 2026)
- qa-reviewer at 4b92255 (REVIEW.md "BPA-83 at 4b92255"): 0 High, 4 Important (all fixed, below), 5 Nits (fixed or recorded). Jira was not read by the reviewer (no sign-in in its session).
- **Fixed (tests):** scenario 5 now asserts the recalculated values (actual % 51.67 and Earned MH 620.00, BPA-62's formula) and that the second lock audits the first lock's time (2026-11-19T21:30:00Z) as its old value; scenario 1 now asserts that `week_approved_by/at` are cleared and audited. These passed on the first run (the behaviour was right; the tests were weak).
- **Fixed (red first):** a 404 from Camunda is reported as "Week ending <d MMM yyyy> could not be reopened; try again" (ASSUMPTION) and logged, not as "already being approved"; `aMessageThatStartsNothingIsRefused` failed on the old text, then passed.
- **Nits fixed:** Cancel clears the typed reason and the error; the `useAuth` import order; the SOR matrix amendment order.
- **Nits recorded, not changed:** `ReopenRequest.reason` has no maximum length (no source gives one, so none is invented); `V9`'s header says "section 3.1" where the table is §13.3.1 (the file is applied on a running database, so its checksum is not changed for a comment); only the Work Pack detail query is invalidated after a reopen; `lock` reads `locked_at` with a second SELECT (once per week).
- **Plan file table, as built:** also changed `docs/03-sor-matrix.md`, `.env.example`, `docker-compose.yml`, `REVIEW.md`, `intent/README.md`; `ProgressSettingsTest` is new (the plan wrote "new or +1").
- **Setting false:** the API refuses first (409) before any database or Camunda call. The message start event stays deployed, so someone with Camunda admin access could correlate `WeekReopened` directly: accepted in the plan, stated in the PR.
- **Local check (setting on, then back to false):** as project-manager on the local stack: ppc-team 403; no or blank reason 400 "Enter a reason to reopen the week" (week stays Locked); unknown week 404; reopen with a reason 200 (status Reopened, new instance linked, one `reopen_reason` audit row, `week_approved_*` cleared and audited); a second reopen of the Reopened week 409 "Week ending 19 Nov 2026 is not locked"; PPC changed WP-000001 from 40 to 55 on the new Approve completion % task; Earned MH recalculated (A-2301 55.00 %, 660.00); cost-controller reviewed; the PM approved; the week is Locked again and the second lock's `locked_at` audit row has the first lock's time as its old value. A further reopen was taken back through approval to Locked, so the local week shows `reopen_reason` "second". The setting is back to false (line removed from `.env`): a reopen answers 409 "Reopening a locked week is not allowed".
- **Human gaps:** nobody has clicked Reopen week in the portal as project-manager; the whole backend and frontend suites run only in CI (Ahsan's rule); the demo at sprint review. Jira DoR: the epic's `intent.md` and `spec.md` acceptance is unticked in Jira (the BPA-4 spec was not read here).
