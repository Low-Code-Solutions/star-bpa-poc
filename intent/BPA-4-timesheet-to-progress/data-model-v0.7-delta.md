# Data model v0.7 delta: Epic 2 (Timesheet to Progress)

Status: **MERGED 13 Nov 2026 into `docs/data-model.md` v0.7, section 13 (BPA-48); this file is now history and the data model is the master.** SORs approved by Khitaba for the PO and DDL approved by Ahsan, 11 Nov 2026 (BPA-48 comment 10530); Q-DM16 to Q-DM24 decided. One departure from this rev. 2: the unique key of `digital_timesheet_line` ignores Rejected lines (Q-DM25). Was: DRAFT 11 Nov 2026 (rev. 2), against `docs/data-model.md` v0.6 (main 831155a). Prepared by Claude for
Khitaba (SOR check, deciding for the PO) and Ahsan (DDL approval; Usama left on 2 Nov).
Only changes are listed; everything in v0.6 stays. Conventions, audit columns (§1.2), SOR labels (§1.3)
and markers (§1.4) as in v0.6: **(A)** = ASSUMPTION; **worker-checked** = enforced by a job worker or the
API, not the database. Every new table carries the four audit columns, not repeated below.
Delivery: `V2__epic2_timesheet_to_progress.sql`. V1 is frozen (Q-DM21, Ahsan, 11 Nov 2026: Zain's QA
dev server already runs V1; ADR 0010 amendment); `V1__baseline_schema.sql` is not edited.

**Rev. 2 changes (11 Nov 2026, Ahsan via Claude):** adds the 11 Nov decisions recorded in Jira BPA-48
(worker HR fields, `user_project`, `worker_leave` as Addendum 5; `cbs_block` built now without
`cbs_reopen_request`); records Q-DM21; corrects the summary count; and applies Ahsan's DDL fixes
(`fms_hours_export` key, `hours_month_lock` key, `variance_alert` NOT NULLs, `activity_progress` CHECK,
Addendum 3 table name). Three new **(A)** items await Ahsan's confirmation (§9, Q-DM22 to Q-DM24).

## 0. Summary

| Change | Tables | Count |
|---|---|---|
| Changed | `digital_timesheet_line` (status, links, unique key); `worker` (six HR fields, §1A). `workpack` unchanged: In Progress and Completed are already in its status enum | 2 |
| New, Hours (D) | `hours_submission`, `hours_decision`, `timesheet_file`, `timesheet_file_row`, `hours_month_lock` | 5 |
| New, Progress (H, new domain) | `progress_period`, `fms_hours_export`, `completion_report`, `completion_report_row`, `workpack_progress`, `progress_retraction`, `activity_progress`, `cbs_manhour_snapshot` | 8 |
| New, Cost reference copies (A) | `cbs_forecast`, `cbs_actual_cost`, `cbs_block` (`cbs_reopen_request` stays out with parked BPA-70) | 3 |
| New, Alerts (E) | `variance_alert` | 1 |
| New, Review (H) | `cbs_review` | 1 |
| New, Access scope and leave (Addendum 5) | `user_project`, `worker_leave` | 2 |
| Total | 30 → 50 tables | +20 |

## 1. Changed: `digital_timesheet_line` (§5.8)

v0.6 left grain and uniqueness to Epic 2 (Q-DM4). Epic 1 creates no rows; BPA-13 tests seed rows.

| Column | Type | Null | Constraint | SOR | Source |
|---|---|---|---|---|---|
| `status` (new) | text | NOT NULL | DEFAULT `'Draft'`; CHECK IN (`Draft`, `Submitted`, `Approved`, `Returned`, `Rejected`) | POC database | E2-D7; FRD-5-12 §5.12.5.2 #10 |
| `hours_submission_id` (new) | uuid | NULL | FK → `hours_submission(id)`; set at send, kept after a decision | POC database | E2-05 |
| `entry_source` (new) | text | NOT NULL | DEFAULT `'Form'`; CHECK IN (`Form`, `Upload`) | POC database | E2-D3 |
| `timesheet_file_id` (new) | uuid | NULL | FK → `timesheet_file(id)`; set when `entry_source = 'Upload'` | POC database | E2-03b |
| `remarks` (new) | text | NULL | CHECK (char_length(remarks) <= 250) **(A)**, same limit as D59 | POC database | FRD-5-12 §5.12.5.2 #9 (comments) |
| `exported_at` (new) | timestamptz | NULL | set when the line goes into an FMS hours file | POC database | E2-08 |
| `fms_hours_export_id` (new) | uuid | NULL | FK → `fms_hours_export(id)` | POC database | E2-08 |
| `hours` (changed) | numeric(7,2) | NOT NULL | add CHECK (`hours >= 0`); **worker-checked** `> 0`, 2 dp, daily ≤ 16 (E2-D6), phase budget (E2-D5) | POC database | FRD-7 §7.7.1; E2-D6 |
| table | | | **UNIQUE (digital_timesheet_id, work_date, phase)** | | E2-D4 (one line per Worker, date, phase) |

- `hours >= 0` in the database, not `> 0`: BPA-13 tests may seed 0-hour lines to test "no hours loaded" (D36). Entry is checked `> 0`.
- Existing seeded test rows take `status = 'Draft'` from the default.
- Lines of a Cancelled time sheet (BPA-13, D7) cannot be added: **worker-checked** "Time sheet for <badge> on <WP> is Cancelled".
- Line editable only while `Draft` or `Returned` and its week is not cut off (E2-D15): **worker-checked**.
- "Hours loaded" (D36, E2-D17) is unchanged: any line with `hours > 0`, any status.

## 1A. Changed: `worker` (§5.x, reference copy)

Six HR fields from FRD-5-2 §5.2.6.2 Employee Records (BPA-48 item 10; comment 10518; PO decision,
Khitaba for the PO, 11 Nov 2026). Read-only seeded copy, synthetic values. Badge No stays the key (D59).
Existing columns already cover Name (§5.2.6.2.2), Position (`designation`, §5.2.6.2.4) and Cost Center
(§5.2.6.2.8). SOR per column: myPeople (C11); FRD-5-2 names D365 HR, recorded as C14. Personal, passport,
family, bank, salary, contract and medical fields stay out of the POC.

| Column | Type | Null | Constraint | SOR | Source |
|---|---|---|---|---|---|
| `employee_id` (new) | text | NOT NULL | UNIQUE **(A, Q-DM22)**; separate from Badge No (`employee_ref`) | myPeople | FRD-5-2 §5.2.6.2.1 |
| `department_ref` (new) | text | NULL | | myPeople | FRD-5-2 §5.2.6.2.5 |
| `employee_category` (new) | text | NULL | CHECK IN (`Staff`, `Worker`, `Contractor`) | myPeople | FRD-5-2 §5.2.6.2.6 |
| `work_location` (new) | text | NULL | | myPeople | FRD-5-2 §5.2.6.2.7 |
| `grade` (new) | text | NULL | | myPeople | FRD-5-2 §5.2.6.2.9 |
| `aramco_id` (new) | text | NULL | "if applicable" | myPeople | FRD-5-2 §5.2.6.2.17 |

- Badge No is the existing `worker.employee_ref` column (UNIQUE in V1); it stays the key.
- V2 adds `employee_id` in three steps so existing rows stay valid: add it NULL; fill existing rows with
  `'EMP-' || employee_ref`; then `SET NOT NULL`. The repeatable seed (`R__`, runs after V2) then sets the
  named synthetic values **(A, Ahsan to confirm)**.
- Column comments carry the myPeople SOR (S17).

## 2. New: Hours domain (D)

### 2.1 `hours_submission`
One send of a Work Pack's lines by the Supervisor; holds the approval stage.

| Column | Type | Null | Constraint | SOR | Source |
|---|---|---|---|---|---|
| `id` | uuid | NOT NULL | PK | — | |
| `workpack_id` | uuid | NOT NULL | FK → `workpack(id)` | POC database | E2-05 |
| `process_instance_key` | bigint | NOT NULL | part of UNIQUE (process_instance_key, round_no) — re-run key | POC database | 09 S7 pattern |
| `round_no` | integer | NOT NULL | CHECK (> 0); booking round within the instance | POC database | E2-05 |
| `submitted_by` | text | NOT NULL | Camunda username | Camunda identity | D63 |
| `submitted_at` | timestamptz | NOT NULL | | POC database | |
| `stage` | text | NOT NULL | CHECK IN (`Timekeeper`, `ProductionSupervisor`, `Ppc`, `Closed`) | POC database | E2-D2 |
| `outcome` | text | NULL | CHECK IN (`Approved`, `Returned`, `Rejected`); NULL until Closed; CHECK (stage = 'Closed') = (outcome IS NOT NULL) | POC database | E2-D2 |

SOR reason: approval record of the POC workflow (real equivalent: D365 timesheet approval workflow, FRD-5-12 §5.12.5.5).

### 2.2 `hours_decision`
One row per decision at a step; history of returns and rejections.

| Column | Type | Null | Constraint | SOR | Source |
|---|---|---|---|---|---|
| `id` | uuid | NOT NULL | PK | — | |
| `hours_submission_id` | uuid | NOT NULL | FK → `hours_submission(id)` | POC database | |
| `step` | text | NOT NULL | CHECK IN (`Timekeeper`, `ProductionSupervisor`, `Ppc`) | POC database | E2-D2 |
| `decision` | text | NOT NULL | CHECK IN (`Validated`, `Approved`, `Returned`, `Rejected`); `Validated` only at Timekeeper; `Rejected` not at Timekeeper | POC database | E2-D2 |
| `reason` | text | NULL | CHECK (decision IN ('Validated','Approved') OR btrim(coalesce(reason,'')) <> '') | POC database | as BPA-9 D3 pattern |
| `task_key` | bigint | NOT NULL | UNIQUE — re-run key | POC database | 09 S7; D65 |
| `decided_by` | text | NOT NULL | Camunda username | Camunda identity | D63 |
| `decided_at` | timestamptz | NOT NULL | | POC database | |

Segregation of duties (FRD-21 §21.8.1 spirit; **(A)** for hours): `decided_by` at Production Supervisor and PPC must differ from `submitted_by`: **worker-checked**. With one test user per group this always holds; the check is still coded.

### 2.3 `timesheet_file` and `timesheet_file_row` (bulk upload, E2-03b)
Same pattern as `assignment_file` / `assignment_file_row` (D49, ADR 0009): raw, nullable input columns so invalid rows can be logged.

`timesheet_file`: `id`; `file_name` text NOT NULL; `sha256` text NOT NULL (indexed, not unique); `uploaded_by` text NOT NULL (Camunda identity); `received_at` timestamptz NOT NULL; `outcome` text NULL (file-level: `File has no rows`, `File cannot be read`, `File layout not recognised`). SOR: POC database (intake log).

`timesheet_file_row`: `id`; `timesheet_file_id` uuid NOT NULL FK; `row_number` integer NOT NULL; `workpack_number` text NULL; `badge_no` text NULL; `work_date_raw` text NULL; `phase_raw` text NULL; `hours_raw` text NULL; `remarks` text NULL (unbounded, so 251 chars can be rejected); `outcome` text NOT NULL; `timesheet_line_id` uuid NULL FK → `digital_timesheet_line(id)` (set when saved). SOR: POC database.

Row outcomes (exact strings; wording **(A)**; final list in E2-03b): `Saved` · `Workpack number is required` · `Work Pack not found` · `Badge No is required` · `Worker not assigned to this Work Pack` · `Time sheet is Cancelled` · `Work date is required` · `Work date is not a valid date` · `Work date cannot be in the future` · `Week ending <date> is closed` · `Phase is required` · `Phase <phase> is not on this Work Pack` · `Hours is required` · `Hours must be a number` · `Hours must be greater than 0` · `Hours can have at most 2 decimal places` · `Worker <badge> would exceed 16 hours on <date>` · `Phase <phase> budget of <budget> h would be exceeded` · `Hours already booked for <badge> on <date> for phase <phase>` · `Remarks can be at most 250 characters` · `Line is not editable (status <status>)`.

## 3. New domain H: Progress

### 3.1 `progress_period`
| Column | Type | Null | Constraint | SOR | Source |
|---|---|---|---|---|---|
| `id` | uuid | NOT NULL | PK | — | |
| `week_ending` | date | NOT NULL | UNIQUE; Asia/Riyadh date of the cut-off | POC database | E2-D15; FRD-21 §21.9.1 |
| `cut_off_at` | timestamptz | NOT NULL | | POC database | |
| `status` | text | NOT NULL | CHECK IN (`CutOff`, `Locked`) | POC database | E2-16 |
| `locked_at` | timestamptz | NULL | | POC database | |
| `week_approved_by` | text | NULL | Camunda username | Camunda identity | E2-19; FRD-21 §21.7 |
| `week_approved_at` | timestamptz | NULL | | POC database | E2-19 |
| `week_decision_reason` | text | NULL | last "changes requested" reason | POC database | E2-19 |
| `process_instance_key` | bigint | NOT NULL | UNIQUE — re-run key | POC database | 09 S7 |
SOR reason: PMS weekly reporting period (real: D365 PMS, FRD-21); simulated.

### 3.2 `fms_hours_export`
`id`; `progress_period_id` uuid NOT NULL FK (not unique on its own: the key is (`progress_period_id`, `period_month`), Addendum 2); `file` bytea NOT NULL; `sha256` text NOT NULL; `row_count` integer NOT NULL CHECK (>= 0); `generated_at` timestamptz NOT NULL; `sent_by` text NULL (Camunda identity; set when PPC completes UserTask_SendHoursToFms); `sent_at` timestamptz NULL. SOR: POC database (record of the file handed to FMS; FRD-22 §22.8 FMS → D365 flow is the reverse direction, out of scope). A week with no approved lines still gets a file with headers only (**(A)**).

### 3.3 `completion_report` and `completion_report_row`
`completion_report`: `id`; `progress_period_id` uuid NOT NULL FK; `file_name`, `sha256`, `uploaded_by`, `received_at`; `outcome` text NULL. Several uploads per period allowed (re-upload after REPORT_INVALID); the latest accepted one is used **(A)**.
`completion_report_row`: `id`; `completion_report_id` FK; `row_number`; `workpack_number` text NULL (raw); `completion_pct_raw` text NULL; `actual_hours_raw` text NULL; `outcome` text NOT NULL.
SOR: the **values** are FMS's (completion report, FRD-21 §21.5.4, FRD-22 §22.6.2 s5); the rows are the POC's intake log. Column labels: raw columns `FMS`; `outcome` `POC database`. Layout **(A)** until E2-17.

### 3.4 `workpack_progress`
| Column | Type | Null | Constraint | SOR | Source |
|---|---|---|---|---|---|
| `id` | uuid | NOT NULL | PK | — | |
| `workpack_id` | uuid | NOT NULL | FK; part of UNIQUE (workpack_id, progress_period_id) | POC database | |
| `progress_period_id` | uuid | NOT NULL | FK | POC database | |
| `reported_pct` | numeric(5,2) | NULL | from the completion report | FMS | FRD-21 §21.6 s3 |
| `approved_pct` | numeric(5,2) | NULL | CHECK (approved_pct BETWEEN 0 AND 100); set by PPC | POC database (real: D365 PMS) | FRD-21 §21.6 s4; E2-D12 |
| `previous_pct` | numeric(5,2) | NOT NULL | DEFAULT 0; last locked week's approved % | POC database | E2-D12 |
| `rule_result` | text | NULL | CHECK IN (`Accepted`, `Blocked`, `NeedsRetraction`) | POC database | Decision_ProgressRule |
| `approved_by` | text | NULL | Camunda username | Camunda identity | |
| `approved_at` | timestamptz | NULL | | POC database | |
Rows of a Locked period never change (FRD-21 §21.9.1): **worker-checked**.

### 3.5 `progress_retraction`
`id`; `workpack_progress_id` uuid NOT NULL FK; `requested_by` text NOT NULL (Camunda identity); `reason` text NOT NULL CHECK (btrim(reason) <> ''); `decision` text NULL CHECK IN (`Approved`, `Declined`); `decision_reason` text NULL (required when Declined **(A)**); `decided_by` text NULL; `decided_at` timestamptz NULL; `task_key` bigint NULL UNIQUE. SOR: POC database (FRD-21 §21.7 "documented justification and full audit trail").

### 3.6 `activity_progress`
| Column | Type | Null | Constraint | SOR | Source |
|---|---|---|---|---|---|
| `id` | uuid | NOT NULL | PK | — | |
| `activity_ref` | text | NOT NULL | FK → `activity(activity_ref)`; part of UNIQUE (activity_ref, progress_period_id) | P6 via D365 PMS | |
| `progress_period_id` | uuid | NOT NULL | FK | POC database | |
| `actual_pct` | numeric(5,2) | NOT NULL | CHECK (actual_pct BETWEEN 0 AND 100); hours-weighted roll-up (E2-D10 **(A)**) | POC database (real: D365 PMS) | |
| `budgeted_units` | numeric(9,2) | NULL | snapshot of `activity.budgeted_units` used | P6 via D365 PMS | FRD-21 §21.5.2 #22 |
| `earned_manhours` | numeric(11,2) | NULL | = actual_pct / 100 × budgeted_units, rounded half-up to 2 dp **(A)**; NULL when budgeted_units is NULL (A-2299 case) | POC database (real: D365 PMS) | FRD-21 §21.5.2.1 #3 |
Never updated once its period is Locked (FRD-21 §21.9.1 "stored weeks are never overwritten").

## 4. New reference copies (domain A, seeded)

### 4.1 `cbs_forecast`
`id`; `cbs_code_ref` text NOT NULL UNIQUE (D365 F&O Cost Control Report); `current_forecast_mh` numeric(11,2) NULL (D365 F&O Cost Control Report, FRD-22 §22.5.3.2); `revised_budget_cost` numeric(15,2) NULL (D365 F&O Cost Control Report, FRD-22 §22.5.4); `currency` text NOT NULL DEFAULT `'SAR'` **(A)**.

### 4.2 `cbs_actual_cost`
`id`; `cbs_code_ref` text NOT NULL (D365 F&O Cost Control Report); `period_month` date NOT NULL (first day of month); `actual_cost` numeric(15,2) NOT NULL (**new label `D365 F&O Finance`**, FRD-22 §22.5.3.1: project posted transactions); UNIQUE (cbs_code_ref, period_month). Cumulative actual cost = sum of months up to the period **(A)**.

**Actual man-hours by CBS are not a reference copy**: they are the sum of approved line hours whose time sheet carries that `cbs_code_ref` (POC database). A line whose time sheet has no CBS (D40) is counted under "No CBS" and never alerts **(A)**.

### 4.3 `cbs_block` (in V2 now: read by BPA-69, Sprint 4; F6, BPA-48 comment 10508)
`id`; `cbs_code_ref` text NOT NULL (D365 F&O Cost Control Report); `block_type` text NOT NULL CHECK IN (`DigitalTimesheet`, `FmsTimesheet`, `Procurement`, `Reimbursement`, `AssetUtilization`) (FRD-22 §22.3.1 list); `is_closed` boolean NOT NULL DEFAULT false (D365 F&O Cost Control Report); UNIQUE (cbs_code_ref, block_type). For the POC this is POC master data (Khitaba, 10 Nov 2026), real SOR D365 F&O Cost Control Report. No row in cbs_block = open **(A)**.
`cbs_reopen_request` is **not** in V2: it belongs to E2-20 / BPA-70, which stays parked (F7). The `pcon-manager` persona is not added either.

## 5. New: `variance_alert` (domain E)
`id`; `progress_period_id` uuid NOT NULL FK; `cbs_code_ref` text NOT NULL (D365 F&O Cost Control Report); `alert_type` text NOT NULL CHECK IN (`Manhour`, `Cost`); `actual_value` numeric(15,2) NOT NULL; `baseline_value` numeric(15,2) NOT NULL; `variance_pct` numeric(7,2) NOT NULL; `recipient_username` text NOT NULL FK → `app_user(username)` (Camunda identity); `channel` text NOT NULL CHECK IN (`Email`, `InSystem`); `sent_at` timestamptz NOT NULL; UNIQUE (progress_period_id, cbs_code_ref, alert_type, recipient_username, channel) — idempotency key for `send-variance-alerts`. Not reusing `notification`: its `drawing_ref` and `revision` are NOT NULL (BPA-16).

### 5.1 New: `cbs_review` (domain H)
`id`; `progress_period_id` uuid NOT NULL FK; `cbs_code_ref` text NOT NULL (D365 F&O Cost Control Report); `remark` text NULL CHECK (char_length(remark) <= 500) **(A)**; `reviewed_by` text NOT NULL (Camunda identity); `reviewed_at` timestamptz NOT NULL; UNIQUE (progress_period_id, cbs_code_ref). SOR: POC database (Cost Controller review of the week, FRD-22 §22.4, §22.5.6.5 "Remarks"). Added for E2-14.

## 6. SOR matrix rows to add (03-sor-matrix)

| Entity | Real SOR | POC access | Real integration |
|---|---|---|---|
| Current Forecast MH, Revised Budget cost per CBS | D365 F&O Cost Control Report | Reference (seeded copy) | Real-time (internal D365) |
| Actual cost per CBS and month | D365 F&O Finance | Reference (seeded copy) | Internal D365 integration (FRD-22 §22.8) |
| FMS completion report (completion %, actual hours) | FMS | Upload by PPC (file) | Batch/file, partner-side |
| Approved hours file to FMS | POC database (the file); FMS receives | Download by PPC (file) | Batch/file, partner-side (Int-7 or #8, C-E2-4) |
| Hours submission, decisions, time sheet lines, progress, Earned MH, alerts | POC database (real: D365 timesheet and D365 PMS) | Write | n/a |
| Worker HR fields (Employee ID, Department, Category, Work Location, Grade, Aramco ID) | myPeople (FRD-5-2 names D365 HR, C14) | Reference (seeded copy, synthetic) | n/a in POC |
| Worker approved leave | myPeople **(A)** | Reference (seeded copy, synthetic) | n/a in POC |
| User-to-project assignment | POC database **(A)**: no STAR system holds portal access scope | Write (PPC Team, BPA-76) | n/a |
| CBS open/closed block per type | POC master data (real: D365 F&O Cost Control Report) | Reference (seeded) | Real-time (internal D365) |

Canonical label added: `D365 F&O Finance`.

## 7. API paths (for `api/openapi.yaml`; final shapes in the stories)

| Method and path | Story | Group |
|---|---|---|
| GET/POST `/api/v1/digital-timesheets/{id}/lines`; PUT/DELETE `/api/v1/digital-timesheet-lines/{id}` | E2-03 | supervisor |
| POST `/api/v1/timesheet-files`; GET `/api/v1/timesheet-files/{id}/result` | E2-03b | supervisor |
| GET `templates/timesheet-upload-template.xlsx` (served download) | E2-03b | supervisor |
| GET `/api/v1/hours-submissions/{id}` | E2-05-07 | timekeeper, production-supervisor, ppc-team |
| GET `/api/v1/fms-hours-exports/{id}/file` | E2-08 | ppc-team |
| POST `/api/v1/completion-reports`; GET `/api/v1/completion-reports/{id}/result` | E2-09 | ppc-team |
| GET `/api/v1/progress-periods/{weekEnding}/workpacks` | E2-10 | ppc-team, project-manager |
| GET `/api/v1/progress-periods/{weekEnding}/earned-manhours` | E2-13 | ppc-team, cost-controller |
| GET `/api/v1/progress-periods/{weekEnding}/cbs-actuals` | E2-14 | cost-controller, project-manager |
| GET `/api/v1/me/variance-alerts` | E2-14, E2-15 | cost-controller, project-manager |

## 8. Seed additions (named rows)
- `cbs_forecast`: CBS-139-PIP-01 (current_forecast_mh 1000.00, revised_budget_cost 250000.00) — values synthetic.
- `cbs_actual_cost`: CBS-139-PIP-01, 2026-11-01, 180000.00 — synthetic.
- One CBS with no forecast row, to prove "no forecast, no alert".
- `cbs_block`: CBS-139-PIP-01 FmsTimesheet open; CBS-139-PIP-99 FmsTimesheet closed (for BPA-69).
- `worker`: the six HR fields on every seeded worker, synthetic (e.g. `employee_id` `EMP-<badge>`; one worker per `employee_category`; `aramco_id` NULL on at least one).
- `user_project`: each project-scoped test user assigned to one of the two seeded projects, and one user assigned to both, so BPA-77 to BPA-79 have an in-scope and an out-of-scope case **(A)**.
- `worker_leave`: one seeded worker on approved leave for a named date range in the demo month, for BPA-75's "on leave" scenario **(A)**.
- Personas: `timekeeper` E9008, `production-supervisor` E9009, `cost-controller` E9010, `project-manager` E9011 (synthetic, L8).

## 9. Questions for the approvers
| # | Item | Proposal |
|---|---|---|
| Q-DM16 | DB CHECK `hours >= 0` vs entry `> 0` | As proposed (keeps BPA-13 fixtures valid) |
| Q-DM17 | Roll-up Work Pack % → activity % | Hours-weighted (E2-D10) |
| Q-DM18 | Earned MH rounding | Half-up, 2 dp |
| Q-DM19 | Week with no approved hours | File with headers only |
| Q-DM20 | Several completion reports per period | Latest accepted wins |
| Q-DM21 | V2 vs V1 regeneration | **Decided: V1 frozen, V2** (Ahsan, 11 Nov 2026) |
| Q-DM22 | `worker.employee_id` unique? | UNIQUE **(A)** |
| Q-DM23 | `worker_leave` scope | Only approved leave is stored; no status column **(A)** |
| Q-DM24 | `user_project.username` reference | FK → `app_user(username)` **(A)** |


## Addendum 10 Nov 2026 (BPA-60 review)
- `workpack_progress.override_remark` text NULL, ≤ 250 characters; required by the API when approved_pct <> reported_pct. SOR: POC database. FRD-21 §21.7, §21.9.1.
- `progress_retraction.approved_pct` numeric(5,2) NOT NULL: the % the retraction was requested and approved for; an approval applies only when the newly approved % equals it (BPA-61 review). SOR: POC database.
- Seed (§8): activity `A-2301` (P6 via D365 PMS) with budgeted units 1200 and no Work Packs, for the end-to-end demo (BPA-68), so its Earned man-hours are not mixed with seeded Work Packs on A-2201 (BPA-62 roll-up).

## Addendum 2 (S4, S5; 10 Nov 2026)
- `fms_hours_export`: add `period_month` date NOT NULL (first day of the month); unique key becomes (`progress_period_id`, `period_month`) (S5). Add `fms_rejected_at` timestamptz NULL and `fms_rejected_reason` text NULL (S4; CHECK both null or both set). SOR: POC database.
- No new column for S2/S3: Employment Type comes from `worker.agency_flag` (myPeople), CBS Cost Centre from `workpack.department_ref` (D365 F&O).

## Addendum 3 (FRD options, 10 Nov 2026)
- (PARKED with BPA-71, D2: not in V2) `hours_approval` (E2-22): `id`; `hours_submission_id` uuid NOT NULL FK (was written `timesheet_submission_id`; the table is `hours_submission`, §2.1); `stage` text NOT NULL CHECK (LineManager, HrManager); `decision` text NOT NULL CHECK (Approved, Returned, Rejected); `reason` text NULL (required for Returned/Rejected); `decided_by`, `decided_at`; audit columns. UNIQUE (submission, stage, round). SOR: POC database.
- (PARKED, D2) `hours_submission.approval_route` text NOT NULL DEFAULT 'Production' CHECK (Production, HR) (route fixed at send).
- `digital_timesheet_line.reported_completion_pct` numeric(5,2) NULL CHECK 0-100 (C-E2-10, information only).
- `completion_report_row.source` text NOT NULL CHECK (FMS, PMS) (C-E2-3).
- `hours_month_lock` (S5): `id` uuid PK; `month` date NOT NULL UNIQUE (first day); `locked_at` timestamptz NOT NULL; `lock_day` smallint NOT NULL CHECK (lock_day IN (1, 16)); audit columns. SOR: POC database. (Rev. 2: `id` PK added and `month` made UNIQUE, to follow the `id uuid` convention of every other table.)
- Settings (application.yaml): progress.basis, progress.source, hours.monthLockDay, alerts.pconManagerEmail (hours.approvalRoute dropped, D2).

## Addendum 4 (DoR decision D5, 10 Nov 2026)
- `cbs_manhour_snapshot`: `id`; `month` date NOT NULL (first day); `cbs_code_ref` text NOT NULL (SOR D365 F&O CCR); `activity_ref` text NOT NULL (SOR P6 via D365 PMS); `approved_manhours` numeric(10,2) NOT NULL; `frozen_at` timestamptz NOT NULL; audit columns. UNIQUE (month, cbs_code_ref, activity_ref). Written once at the month lock; never updated (FRD-22 §22.8.1). SOR of the row: POC database.

## Addendum 5 (11 Nov 2026: access scope and leave; BPA-48 item 11)
Business need decided in Jira (BPA-76 to BPA-79 bring back project scope; BPA-75 blocks hours for a Worker on approved leave). Column design proposed by Claude for Ahsan's approval; open points are Q-DM23 and Q-DM24.

### `user_project` (domain: Access scope)
| Column | Type | Null | Constraint | SOR | Source |
|---|---|---|---|---|---|
| `id` | uuid | NOT NULL | PK | — | |
| `username` | text | NOT NULL | FK → `app_user(username)` **(A, Q-DM24)**; part of UNIQUE (username, project_ref) | Camunda identity | BPA-76 |
| `project_ref` | text | NOT NULL | FK → `project_job_code(project_ref)` (V1 has no `project` table; `project_job_code.project_ref` is the UNIQUE project key) | D365 F&O Project Management | BPA-76 to BPA-79 |
SOR reason: POC database **(A)**: no STAR system of record holds which portal user may see which project. Rows written by the PPC Team (BPA-76) and seeded (§8). Audit columns record who assigned and when. Note: this brings back the project-scope idea that `user_role_scope` held before BPA-44 removed it (ADR 0005); the new table holds project only, not department or role.

### `worker_leave` (domain: A, reference copy)
| Column | Type | Null | Constraint | SOR | Source |
|---|---|---|---|---|---|
| `id` | uuid | NOT NULL | PK | — | |
| `worker_id` | uuid | NOT NULL | FK → `worker(id)` | myPeople | BPA-75 |
| `leave_from` | date | NOT NULL | | myPeople | BPA-75 |
| `leave_to` | date | NOT NULL | CHECK (leave_to >= leave_from); inclusive | myPeople | BPA-75 |
SOR: myPeople **(A)**, read-only seeded copy with synthetic values. Only **approved** leave is stored, so there is no status column **(A, Q-DM23)**. A Worker is "on leave" on a date when a row has `leave_from <= work_date <= leave_to` (**worker-checked** by `Decision_HoursWithinBudget`, rule "on leave blocks", BPA-75). Overlapping rows for one Worker are allowed and harmless.
