# Plan: BPA-48 Data model v0.7, V2 migration, seed, test users and lint for Epic 2
Jira: BPA-48 (Task, epic BPA-4)
Story last updated: 2026-11-13T13:30:38.000+0400
Status: Closed (13 Nov 2026)
Approver: Ahsan (Build Lead)
Branch: BPA-48-epic2-data-model-v07, from main a035b88 (inputs commit 0a61a4b on top)

## Context
Every Epic 2 story builds on shared files: the data model, a V2 migration, the seed, personas, settings, lint and the four
decision tables. BPA-48 delivers them in one reviewed PR so Sprint 3 stories do not collide on them. It has no business
value on its own (Jira). The inputs (delta rev. 2, intent, spec, 2 BPMN, 4 DMN) were staged in 0a61a4b. This plan builds
from them; it does not change the models.

## Readiness check (Definition of Ready, docs/02, against Jira 13 Nov)
| Check | State |
|---|---|
| Template / persona | Task: no Gherkin; the Definition of Done is the check (Jira AC field). OK for a Task |
| SORs named | Delta §1–§6 and addenda 1–5; SORs approved by Khitaba 11 Nov (Jira DoR). OK |
| DMN complete | Four tables with inputs, outputs, hit policy FIRST. OK |
| DDL approved | Ahsan 11 Nov, delta rev. 2, Q-DM22–24 decided (comment 10530). OK |
| Lint change agreed | Ahsan 11 Nov, rule `user-task-definition` (comment 10531). OK |
| Open questions | Q1–Q12 below. **Q5 (unique key) and Q6 (user_project seeding) block parts of V2/seed**: V2 cannot be changed after the QA server applies it |
| Estimate ≤ 8 | 3 points (risk R3) |
| Epic intent.md / spec.md accepted | **Not met**: accepted by Ahsan on Faraz's behalf; Faraz to confirm (Q3) |
| PO approved (Ready) | **Not met**: status went to In Progress without Ready. The build waits for your say-so (CLAUDE.md) |

## Disagreements found (later dated decision applied)
| # | Earlier source | Later source | Applied |
|---|---|---|---|
| D-a | Description item 6: `hours.approvalRoute`; no `alerts.pconManagerEmail` | Comment 10498 (10 Nov 21:08, D2, D3); delta Addendum 3 | Drop approvalRoute; add pconManagerEmail (Q1) |
| D-b | Comment 10491 (10 Nov 20:04): 13 personas, `hours_approval`, `approval_route` | 10498, 10512, 10524 (11 Nov): 11 personas, both parked | 11 personas; tables out of V2 |
| D-c | spec.md shared contracts (line 145, A3) and "14 warnings": rule `user-task-form` | Comment 10531 (11 Nov) and description item 7: `user-task-definition`; count dropped | `user-task-definition` (Q8: spec stale) |
| D-d | Description item 9 earlier text "conditional on BPA-70" | 10487, 10508 (10 Nov): build `cbs_block` now, no `cbs_reopen_request` | Built now |
| D-e | spec.md A5 (Khitaba 10 Nov): unique line key must **ignore Rejected lines** (partial unique index) | Delta rev. 2 §1 (11 Nov, DDL approved): plain `UNIQUE (digital_timesheet_id, work_date, phase)` | Delta, as approved; **Q5 must be answered before V2 is written** |
| D-f | Description item 2 "regenerate V1" option | 10529 (11 Nov, Q-DM21): V1 frozen, V2 only | V2 only |
| D-g | Description item 8 "one Camunda Process Test per rule"; DoD names `E2_00_*` | docs/04 and 09 L15: tests named `BPA<n>_…` | Jira names kept (Jira wins precedence); reported (Q7) |

## Files that change (scope item → files)
| Jira item | Files |
|---|---|
| 1 data-model v0.7 | `docs/data-model.md`: merge delta rev. 2 (§0–§9, §1A, addenda 1–5) as v0.7; change-log entry; ERD and table count 30 → 50; §9 Q-DM16–24 marked **Decided** (Q-DM16–20 Khitaba 11 Nov, Q-DM21–24 Ahsan 11 Nov); parked items (`hours_approval`, `approval_route`, `cbs_reopen_request`) listed as parked, not as tables. The delta file is kept and its status line set to "Merged into docs/data-model.md v0.7 (BPA-48)" (Q2) |
| 2 V2 | new `backend/src/main/resources/db/migration/V2__epic2_timesheet_to_progress.sql` (contents below). V1 untouched |
| 3, 9, 10, 11 seed | `R__seed_reference_data.sql` (rows below) |
| 4 personas | `backend/src/main/resources/personas.yaml`: + timekeeper E9008, production-supervisor E9009, cost-controller E9010, project-manager E9011; header comment seven → eleven |
| 5 test users | `docs/04-conventions.md` "Test users": exactly eleven, the four new IDs, cite E2-D1, D2 |
| 6 settings | `backend/src/main/resources/application.yml` (the file is `.yml`): `progress.basis: Physical`, `progress.source: FMS`, `hours.monthLockDay: 1`, `alerts.pconManagerEmail: <Q1>`; placed under `bpa:` like the other app settings **(ASSUMPTION, Q9)**; no code reads them yet (BPA-58/59/60/62/64/65 do) |
| 7 lint | `.bpmnlintrc`: `"rules": { "camunda-compat/user-task-definition": "off" }`; `package.json`: `--max-warnings=4` → `--max-warnings=0` |
| 8 deploy + tests | Models already in `models/` deploy through `@Deployment` in `BpaApplication` and `ProcessTestApplication` (no change). New test `backend/src/test/java/com/lcs/star/bpa/Epic2DecisionTablesProcessTest.java` (15 tests, below). `ModelDeployTest.BPA43_modelsDeployToCamunda` gets the two new process IDs |
| comment adds | V2: `workpack_progress.override_remark`, `progress_retraction.approved_pct`, `cbs_manhour_snapshot`; seed: activity `A-2301` |
| Repo docs (10524, 10529) | `docs/03-sor-matrix.md`: the nine rows of delta §6 and label `D365 F&O Finance`. `docs/adr/0005-identity-and-authorisation.md`: amendment 11 Nov: eleven personas; project scope comes back as `user_project` (POC database, BPA-76–79), still no department/role scope. `docs/adr/0010-data-model-sor-and-migrations.md`: amendment line "11 Nov 2026 (Q-DM21, Ahsan): V1 frozen (QA dev server runs V1); Epic 2 is V2" |
| Tests changed by the scope | `FlywayBaselineTest` (applied list + V2, tables 30 → 50), `PersonaSyncTest.BPA44_…Seven…` (11 users) — said in the PR (CLAUDE.md test rule) |
| Review | `REVIEW.md` BPA-48 pass; this plan; `intent/README.md` regenerated |

Not changed: V1, BPMN/DMN files, `Process_PPC_WorkPackAssignment.bpmn` (BPA-49), Java workers, `api/openapi.yaml`
(paths in delta §7 belong to the stories), frontend, `.claude/`.

## V2 contents (from delta rev. 2; every new table: `id uuid` PK default `gen_random_uuid()`, the four audit columns, `COMMENT ON TABLE` + column comments where the SOR differs, S17)
Order inside V2 (FKs first-to-last):
1. `worker` (§1A): add `employee_id` text NULL; `UPDATE bpa.worker SET employee_id = 'EMP-' || employee_ref WHERE employee_id IS NULL`; `SET NOT NULL`; `UNIQUE (employee_id)` (Q-DM22). Add `department_ref`, `employee_category` (CHECK Staff/Worker/Contractor), `work_location`, `grade`, `aramco_id`, all NULL. Column comments `myPeople`.
2. Hours: `hours_submission` (§2.1, incl. `UNIQUE (process_instance_key, round_no)`, stage/outcome CHECKs; **no `approval_route`**), `hours_decision` (§2.2), `timesheet_file`, `timesheet_file_row` (§2.3; FK to `digital_timesheet_line` added after step 4), `hours_month_lock` (Add. 3: `month` UNIQUE, `lock_day IN (1,16)`).
3. Progress: `progress_period` (§3.1), `fms_hours_export` (§3.2 + Add. 2: `period_month`, `UNIQUE (progress_period_id, period_month)`, `fms_rejected_at/_reason` both-or-neither CHECK), `completion_report`, `completion_report_row` (§3.3 + Add. 3 `source` CHECK FMS/PMS), `workpack_progress` (§3.4 + Add. 10 Nov `override_remark` ≤ 250), `progress_retraction` (§3.5 + `approved_pct numeric(5,2) NOT NULL`), `activity_progress` (§3.6, CHECK 0–100), `cbs_manhour_snapshot` (Add. 4, `UNIQUE (month, cbs_code_ref, activity_ref)`).
4. `digital_timesheet_line` (§1): add `status` (default Draft, CHECK), `hours_submission_id` FK, `entry_source` (default Form, CHECK), `timesheet_file_id` FK, `remarks` ≤ 250, `exported_at`, `fms_hours_export_id` FK, `reported_completion_pct` (Add. 3, 0–100); add `CHECK (hours >= 0)`; add the unique line key **as decided in Q5**.
5. Reference copies: `cbs_forecast` (§4.1, `currency` default 'SAR' (A)), `cbs_actual_cost` (§4.2, UNIQUE (cbs_code_ref, period_month); `actual_cost` comment `D365 F&O Finance`), `cbs_block` (§4.3, UNIQUE (cbs_code_ref, block_type)).
6. `variance_alert` (§5, NOT NULLs per rev. 2, FK `recipient_username` → `app_user`, 5-column UNIQUE), `cbs_review` (§5.1).
7. Addendum 5: `user_project` (FK `username` → `app_user`, Q-DM24; FK `project_ref` → `project_job_code(project_ref)`; UNIQUE (username, project_ref)), `worker_leave` (FK worker, CHECK leave_to >= leave_from; no status, Q-DM23).

**Excluded:** `hours_approval`, `hours_submission.approval_route` (parked with BPA-71, D2), `cbs_reopen_request` (BPA-70, F7).
Count: 20 new tables → 50 (delta §0). Types, NULLs and CHECKs are copied from the delta; nothing is added that it does not state.

## Seed rows: where and why
Rule: reference-data copies go in `R__` (L8, ADR 0010; idempotent, can change later without a new V-file). Only DML that a
DDL step needs goes in V2. Users and scope rows are written by the S22 job, not by SQL (L2, ADR 0005).

| Rows | Where | Why |
|---|---|---|
| `worker.employee_id = 'EMP-' \|\| employee_ref` for existing rows | **V2** | Needed before `SET NOT NULL` on a DB that already holds workers (QA server, Epic 1 data); on a fresh DB it updates 0 rows because R__ runs after V2 (comment 10530) |
| Six HR fields on all 12 seeded workers | **R__** (worker INSERT and ON CONFLICT list extended) | Reference copy, myPeople. `employee_id` = `EMP-<badge>` (delta §8); one worker per category; `aramco_id` NULL on at least one. Values for department/location/grade/aramco **ASSUMPTION** (synthetic, Q10) |
| `activity` A-2301, budgeted units 1200, no Work Packs | **R__** | Reference copy, P6 via D365 PMS (comment 10481). `activity_name`, `wbs_ref`, dates, CBS not given: **ASSUMPTION, Q10** |
| `cbs_forecast` CBS-139-PIP-01 (1000.00 MH, 250000.00) | **R__** | Reference copy, D365 F&O CCR (delta §8, spec) |
| "One CBS with no forecast row" | **R__** (by absence) | CBS-139-PIP-02 gets no `cbs_forecast` row **(ASSUMPTION: which CBS is not named, Q10)** |
| `cbs_actual_cost` CBS-139-PIP-01, 2026-11-01, 180000.00 | **R__** | Reference copy, D365 F&O Finance (delta §8) |
| `cbs_block` PIP-01 FmsTimesheet open; PIP-99 FmsTimesheet closed | **R__** | POC master data (Khitaba 10 Nov), seeded like the reference copies (delta §4.3, comment 10487). Note: no activity maps to CBS-139-PIP-99 (Q11) |
| `worker_leave`: one worker, one date range in the demo month | **R__** (`INSERT … SELECT … WHERE NOT EXISTS`, no UNIQUE key) | Reference copy, myPeople (A). Worker and dates not named: **Q10** |
| `user_project` rows | **Neither: S22 persona sync** (proposed) | FK to `app_user`, which only the S22 job writes at start-up, after Flyway: an R__ or V2 insert fails on a fresh DB. L2 says scope rows come from the S22 job. Needs code in the persona sync + a `projects:` list in `personas.yaml`. **Q6** (and who gets which project) |
| Four personas | `personas.yaml` | S22 job creates Camunda users/groups and `app_user` (L2) |

## Rule-to-test table (class `Epic2DecisionTablesProcessTest`, Camunda Process Test via `ProcessTestApplication`, `newEvaluateDecisionCommand` like `ModelDeployTest`)
| Decision | Rule ID | Test (Jira DoD name) | Inputs (illustrative) | Expected |
|---|---|---|---|---|
| HoursWithinBudget | Rule_CbsClosed | E2_00_hoursCbsClosedBlocks | cbsClosed true, others within | Blocked, CBS_CLOSED |
| HoursWithinBudget | Rule_WorkerOnLeave | E2_00_hoursOnLeaveBlocks | workerOnLeave true | Blocked, WORKER_ON_LEAVE |
| HoursWithinBudget | Rule_DailyLimitExceeded | E2_00_hoursDailyLimitBlocks | dailyHoursAfter 16.01 | Blocked, DAILY_LIMIT_EXCEEDED |
| HoursWithinBudget | Rule_PhaseBudgetExceeded | E2_00_hoursPhaseBudgetBlocks | phaseHoursAfter − budget = 0.01 | Blocked, PHASE_BUDGET_EXCEEDED |
| HoursWithinBudget | Rule_Allowed | E2_00_hoursAllowedAtLimits | daily 16, phase = budget | Allowed, null |
| ProgressRule | Rule_AboveHundred | E2_00_progressAbove100Blocks | newPct 100.01 | Blocked, ABOVE_100 |
| ProgressRule | Rule_BelowZero | E2_00_progressBelow0Blocks | newPct −0.01 | Blocked, BELOW_0 |
| ProgressRule | Rule_DecreaseWithoutRetraction | E2_00_progressDecreaseNeedsRetraction | new 40, previous 50, retraction false | NeedsRetraction, DECREASE |
| ProgressRule | Rule_Accepted | E2_00_progressAccepted | new 100, previous 100 (and decrease with retraction true) | Accepted, null |
| ManhourVariance | Rule_NoForecast | E2_00_manhourNoForecast | forecast null (and 0) | false, NO_FORECAST |
| ManhourVariance | Rule_OverFifteen | E2_00_manhourOver15Alerts | forecast 1000, actual 1150.01 | true, OVER_15_PCT |
| ManhourVariance | Rule_Within | E2_00_manhourAt15NoAlert | forecast 1000, actual 1150 | false, WITHIN |
| CostVariance | Rule_NoBudget | E2_00_costNoBudget | budget null (and 0) | false, NO_BUDGET |
| CostVariance | Rule_OverTen | E2_00_costOver10Alerts | budget 250000, actual 275000.01 | true, OVER_10_PCT |
| CostVariance | Rule_Within | E2_00_costAt10NoAlert | budget 250000, actual 275000 | false, WITHIN |
5 + 4 + 3 + 3 = 15. Each test asserts both outputs and the matched rule ID (`getMatchedRules`), so a test proves its rule,
not just its output. Input values are boundary picks from the DMN text, not business rules.

## Order of work
1. Commit this plan (Approved) and regenerated `intent/README.md`. Ask before any Jira status move.
2. Answers to Q5 and Q6 before V2 is written (V2 is permanent once the QA server applies it).
3. Tests first, run, shown red for the expected reason: `FlywayBaselineTest` (expects V2 + 50 tables: fails, no V2),
   a new V2 check in the same class (worker HR columns, `employee_id` NOT NULL/UNIQUE, the 20 tables, excluded tables absent),
   `PersonaSyncTest` (11 users: fails), `ModelDeployTest` (new IDs: already pass, the models are committed),
   the 15 decision tests (pass at once: the DMNs predate the plan; recorded, cannot be shown red honestly).
4. V2, then R__ seed, then personas.yaml (and the S22 `user_project` change if Q6 says so), then application.yml, lint files.
5. Docs: data-model.md v0.7, docs/03, docs/04, ADR 0005, ADR 0010.
6. Proof commands below; `./mvnw -B verify` in the background.
7. `qa-reviewer`, fixes, `REVIEW.md`, plan Closed, push, PR, read CI; draft the Jira comments (linkage, open questions), shown first.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| PR merged; plan Closed | `gh pr view <n> --json state,mergedAt`; `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-48.md` |
| Flyway V2 on a fresh DB | `./mvnw -B -Dtest=FlywayBaselineTest,SorCommentTest test` (Testcontainers, empty DB: V1, V2, R__ applied; 50 tables; every table commented) |
| Flyway V2 on a DB with Epic 1 data (BPA-13 fixtures pass) | Local stack: `git stash`-free check on main's DB: `docker compose up -d` on main, run the Epic 1 flow to create Work Packs, assignments and time sheets, then switch to the branch and `docker compose up -d --build bpa-app`; `docker compose exec postgres psql -U postgres -d bpa -c "select version, success from bpa.flyway_schema_history order by installed_rank"` shows V2 success; `select count(*) from bpa.worker where employee_id is null` = 0. Plus `./mvnw -B -Dtest=WorkerReplacementApiTest,Bpa13CorrelationChecksTest,DigitalTimesheetApiTest test` (BPA-13 fixtures insert lines without the new columns) |
| Persona sync creates eleven users and groups | `./mvnw -B -Dtest=PersonaSyncTest test` (eleven usernames = group IDs, `app_user` 11 rows) |
| Workers carry six HR fields; `user_project`, `worker_leave` rows | `psql … -c "select count(*) from bpa.worker where employee_id like 'EMP-%'"` (12) and a V2/seed test asserting the named rows; `select count(*) from bpa.user_project`, `… worker_leave` > 0 |
| `npm run lint:bpmn` 0 warnings | `npm ci && npm run lint:bpmn` (exit 0 with `--max-warnings=0`, all five BPMN files) |
| 15 decision tests pass | `./mvnw -B -Dtest=Epic2DecisionTablesProcessTest test` (15 run, 0 failures); then full `./mvnw -B verify` and the six CI checks |

## Risks
- **R1 Flyway checksum on the QA dev server.** V1 must stay byte-identical (CRLF included); any change fails validation there. Once V2 runs on that server it is frozen too, so its DDL must be right first time (Q5, Q6). The R__ checksum changes, which is expected: Flyway re-runs it.
- **R2 V2 over existing rows.** `employee_id` backfill before NOT NULL; new NOT NULL columns on `digital_timesheet_line` have defaults; `CHECK (hours >= 0)` and the new UNIQUE fail if existing lines break them (QA data, test fixtures): check the QA DB first (`select digital_timesheet_id, work_date, phase, count(*) … having count(*) > 1`; `hours < 0`).
- **R3 Size.** 11 scope items, 20 tables, 5 docs, 15 tests, a possible persona-sync code change, against 3 points. Likely 5. Raise in the PR; estimate stays Faraz's/Ahsan's.
- **R4 Weekly timer.** Deploying `Process_PPC_WeeklyProgressPeriod` starts an instance every Thursday (`0 59 20 * * THU`) whose first job (`open-progress-period`) has no worker until its story lands: open jobs/incidents pile up in every running stack, including the QA server (Q12).
- **R5 Red-first.** The DMNs predate the plan, so the 15 tests go green at once; recorded, not hidden.

## Questions
1. **Settings (D-a).** Jira description item 6 still lists `hours.approvalRoute` and omits `alerts.pconManagerEmail`; delta Addendum 3 and comment 10498 (D2, D3) say the reverse. Applied: the later decision. No value for `alerts.pconManagerEmail` is given anywhere: use a synthetic `pcon-manager@example.com` **(ASSUMPTION)**? Khitaba to update the description.
2. **Delta status.** The delta header says "DRAFT 11 Nov (rev. 2)" while Jira says SORs (Khitaba) and DDL (Ahsan) were approved 11 Nov. In the PR: data-model.md v0.7 status "Approved 11 Nov 2026", §9 Q-DM16–24 "Decided", and the delta's header marked merged. Confirm.
3. **intent.md and spec.md** are still marked DRAFT/"Not accepted". Acceptance was by Ahsan on Faraz's behalf (10530) with no recorded delegation. Faraz to confirm; do I change their status lines in this PR, or leave them for Faraz?
4. **WorkPackAssignment change (BPA-49).** The message end event that starts Epic 2 is not on this branch. No BPA-48 proof depends on it: the DMN tests evaluate decisions directly; deploying `Process_PPC_TimesheetToProgress` (message start `WorkPackAssigned`) needs no publisher. Confirm.
5. **Unique line key (D-e), blocking V2.** spec A5 (Khitaba 10 Nov) wants the key to ignore Rejected lines (partial unique index `WHERE status <> 'Rejected'`); delta rev. 2 §1 (approved 11 Nov) has a plain UNIQUE. A plain UNIQUE stops E2-06's re-booking after a rejection and cannot be changed in V2 later. Which one?
6. **`user_project` seed, blocking.** Its FK to `app_user` means R__ and V2 cannot insert rows on a fresh DB (app_user is written by the S22 job after Flyway). Proposal: a `projects:` list per persona in `personas.yaml`, written by the S22 sync (L2). That is Java in the persona sync, beyond "seed rows". Also: which users are "project-scoped", and which one gets both PRJ-139 and PRJ-140 (delta §8 names none)?
7. **Test names (D-g).** DoD names `E2_00_*`; docs/04 and 09 L15 say `BPA<n>_…`. I keep the Jira names. Or `BPA48_*`?
8. **spec.md is stale** on the lint rule (`user-task-form`, 14 warnings) and on `R__` scope. Fix it in this PR (it is an input, not a design change), or leave it to Khitaba?
9. **Setting keys.** Jira gives `progress.basis`, `progress.source`, `hours.monthLockDay`, `alerts.pconManagerEmail`. Existing app settings sit under `bpa:` and `app:`. I propose `bpa.progress.basis` etc. (ASSUMPTION). Agree?
10. **Unnamed seed values.** A-2301's name, WBS, baseline dates and CBS; the worker HR values (department, location, grade, aramco_id); the no-forecast CBS; the `worker_leave` worker and dates. I will pick synthetic values marked ASSUMPTION in the seed header unless you name them.
11. **CBS-139-PIP-99** (closed for FmsTimesheet) is mapped to no activity in `cbs_activity`, so no time sheet can carry it. BPA-69 needs one. Add a `cbs_activity` row now (which activity?) or leave it to BPA-69?
12. **Weekly timer (R4).** Deploy `Process_PPC_WeeklyProgressPeriod` now as item 8 says, accepting weekly orphan instances until its workers exist, or deploy it with BPA-49/later?

### Resolutions (Ahsan, 13 Nov 2026: "do everything yourself as per your recommended approach")
Taken as the recommended answer to each question; each is open to challenge by Faraz or Khitaba in the PR.
| Q | Applied |
|---|---|
| 1 | `bpa.alerts.pcon-manager-email: pcon-manager@example.com` **(ASSUMPTION: synthetic)**; `hours.approvalRoute` not added |
| 2 | data-model.md v0.7 status "Approved 11 Nov 2026"; Q-DM16–24 **Decided**; the delta header says it is merged |
| 3 | intent.md and spec.md status lines **not touched**: acceptance stays Faraz's to confirm |
| 4 | Confirmed: no BPA-48 proof depends on the BPA-49 change |
| 5 | **Partial unique index** `UNIQUE (digital_timesheet_id, work_date, phase) WHERE status <> 'Rejected'` (spec A5, Khitaba 10 Nov, E2-06 c). **Departs from the DDL approved 11 Nov (plain UNIQUE)**: the plain key would make E2-06 (re-booking after a rejection) impossible. It could be changed later only by a V3 on a server that already ran V2. Flagged in the PR for Ahsan; Khitaba and Faraz to confirm before merge. Covered by `BPA48_oneLinePerWorkerDatePhaseIgnoresRejectedLines` |
| 6 | The S22 persona sync writes `user_project` from a `projects:` list per persona in `personas.yaml`, **only while the user has no `user_project` row** (so a project removed in the portal, BPA-76, is not brought back by a restart). **ASSUMPTION** assignments: timekeeper PRJ-139; production-supervisor PRJ-139; cost-controller PRJ-140; project-manager PRJ-139 and PRJ-140 (the one with both). `PersonaSync.GROUPS` grows from 7 to 11. Camunda permissions for the two new processes are not added: no story starts them yet |
| 7 | Jira test names kept (`E2_00_*`) |
| 8 | spec.md left to Khitaba (not touched) |
| 9 | Keys under `bpa:` (`bpa.progress.basis`, `bpa.progress.source`, `bpa.hours.month-lock-day`, `bpa.alerts.pcon-manager-email`): Spring relaxed binding, kebab-case as in the file |
| 10 | Synthetic values chosen and marked ASSUMPTION in the seed header |
| 11 | CBS-139-PIP-99 maps to no activity: left for BPA-69. (Separate from this: A-2301 is mapped to CBS-139-PIP-01 in `cbs_activity`, an ASSUMPTION so its time sheets carry the CBS that has a forecast and an actual cost) |
| 12 | Both processes deploy now, as item 8 says; the weekly timer's orphan jobs are accepted until its workers land (R4) |

## Departures from the plan (recorded while building, 13 Nov 2026)
- **Migration test.** The proof "V2 on a DB with Epic 1 data" is the new `Bpa48MigrationTest` (Flyway alone: V1, Epic 1 rows by hand, then V2), not a local compose run. Flyway runs repeatable migrations whatever the target, so the V1-only step gives the repeatable prefix a value that matches nothing, otherwise the new seed fails on the V1 schema.
- **PersonaSync code.** `PersonaFile.Persona` gets `projects`, `PersonaSync.GROUPS` has eleven entries and `syncUserProjects` writes `user_project` (Resolution 6). Not in the original "Files that change" table.
- **Persona test order.** `PersonaSyncTest` compares the `app_user` names without order: the database collation sorts `timekeeper` before `time-method-analyst`, Java does not.
- **Delta file.** Its status line says MERGED and names the one departure (Q-DM25); the data model section 13 is now the master.
- **Data model.** Merged as a new section 13 (with Q-DM16 to Q-DM26) and a v0.7 change-log entry, not woven into sections 3 to 5: the v0.6 sections stay as they were so the Epic 1 history is unchanged.
- **Seed values chosen (ASSUMPTION, Q10):** A-2301 "Synthetic end-to-end demo module M-04" on WBS 11031, 16 Nov to 31 Dec 2026, mapped to CBS-139-PIP-01 in `cbs_activity`; Worker HR values (department Fabrication, Painting or Assembly; Yard A or B; grades G1 to G3; Aramco IDs on three Workers); no forecast for CBS-139-PIP-02; E1203 on leave 23 to 26 Nov 2026 (not E1021, the main Epic 1 Worker, whom the leave would block).

- **Tests the review (qa-reviewer, 13 Nov) asked for or the scope needed, not in the table above:** `ModelResourcesTest` (it lists the deployed models; the Epic 2 inputs commit added two processes and four decisions); the second test in `FlywayBaselineTest` (`BPA48_seedCarriesTheEpic2ReferenceRows`); in `Bpa48MigrationTest`: `BPA48_oneLinePerWorkerDatePhaseIgnoresRejectedLines`, `BPA48_hoursCannotBeNegativeAndEmployeeIdIsUnique`, `BPA48_seedCanBeRunAgainOnAMigratedDatabaseWithoutChangingIt`; in `PersonaSyncTest`: `BPA48_personaSyncDoesNotBringBackAProjectRemovedInThePortal`. These were written after the code they test, so they were not shown red first.
- **docs/09** is amended in place for S6, S22 and L9 and with a dated line (the review found it still said seven personas), as BPA-44 did.
- **Not done on purpose:** a 0 to 100 CHECK on `progress_retraction.approved_pct` (the delta does not state one; the API will validate it); the `E2_00_*` test names stay (Jira Definition of Done names them).

## Human-gap log
- Epic 2 inputs staged from SharePoint by Ahsan via Claude (commit 0a61a4b) before the plan commit, a departure from ADR 0015 plan-first.
- The DMN tables were never executed in the design session (spec, human gap 2); BPA-48 runs them for the first time.
- Faraz to confirm intent/spec acceptance and move BPA-48 to Ready (it is In Progress without Ready).
- Khitaba to update the Jira description (items 6, 7 history) and spec.md if Q1/Q8 say so.
- The Epic-1-data Flyway check is a Testcontainers test (V1, Epic 1 rows by hand, then V2 and the seed), not the QA server itself, which is Zain's. No test replays the old seed's checksum history: the seed is run a second time on the migrated database instead.
- Resolution 5 (partial unique index) differs from the DDL Ahsan approved on 11 Nov; Ahsan to confirm in review.
