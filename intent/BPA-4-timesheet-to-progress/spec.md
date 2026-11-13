# Spec: Timesheet to Progress (Epic 2, BPA-4)

Status: **DRAFT 10 Nov 2026**, for review by Khitaba (deciding for the Product Owner). Not accepted.
Reads with: `intent.md` (this folder), the BPA-4 epic in Jira, and the Epic 1 spec
(`intent/BPA-3-work-pack-assignment/spec.md`). Same rules as Epic 1: this file links to the design;
its own content is the decision list, the story map, the Delivery order and the shared-contract table.

## Design sources

| Topic | Master (once accepted) | Draft in this pack |
|---|---|---|
| Models | `models/bpmn/`, `models/dmn/` | `Process_PPC_TimesheetToProgress.bpmn`, `Process_PPC_WeeklyProgressPeriod.bpmn`, `Process_PPC_WorkPackAssignment.bpmn` (E2-01 change only), four `.dmn` |
| Data model | `docs/data-model.md` | `data-model-v0.7-delta.md` (changes against v0.6 only) |
| SOR matrix | `docs/03-sor-matrix.md` | rows in the delta, §6 |
| Stories and acceptance criteria | Jira BPA-4 children | to be drafted after this pack is reviewed |
| Signed sources | 05-source-register (FLOW-FMS-D365 = FPC p1, FLOW-PMS = FPC p2, FLOW-CCR = FPC p3, read 10 Nov 2026) | FRD-7 §7.5.6, §7.6 s13-15, §7.7.1; FRD-5-12 §5.12.4, §5.12.5.2, §5.12.8.1; FRD-10-1 §10.1.3.3; FRD-21 §21.5.2.1, §21.6, §21.7, §21.9.1; FRD-22 §22.4, §22.5.3.2, §22.7, §22.8.1, §22.10; MOM-2026-10-01 |

## Decisions for Epic 2 (DRAFT; Khitaba for the PO, 10 Nov 2026 unless marked)

Numbered E2-Dn so they never clash with Epic 1's D1-D75.

| # | Decision | Source / class |
|---|---|---|
| E2-D1 | Personas: Yard Supervisor = existing `supervisor`; new groups and users `timekeeper`, `production-supervisor`, `cost-controller`, `project-manager`. PCON Planner not added (Earned MH is calculated, FRD-21 §21.9.1). | Khitaba for the PO, 10 Nov |
| E2-D2 | Production hours route: Supervisor books → Timekeeper validates or returns → Production Supervisor approves, returns or rejects → PPC approves, returns or rejects. | FRD-22 §22.7, §22.6.2 s4; FRD-7 §7.6 s15. Return/reject options by analogy with FRD-5-12 §5.12.5.5: ASSUMPTION |
| E2-D3 | Hours are entered by form **and** by Excel bulk upload; both run the same checks. | Khitaba, 10 Nov; bulk upload matches today's practice (MOM-2026-10-01), no FRD requires it |
| E2-D4 | Bulk upload: a row for a Worker, date and phase that already has hours is rejected; valid rows of a file are saved even if others are rejected; one file may cover several Work Packs. | Khitaba, 10 Nov (pending confirmation); mirrors Epic 1 D49 |
| E2-D5 | Phase budget: total hours on a phase (all Workers, lines not Rejected) cannot exceed `workpack_phase.manhours`; exactly equal is allowed. | FRD-7 §7.7.1 |
| E2-D6 | Daily limit: one Worker cannot exceed 16 h on one date across all Work Packs; exactly 16 is allowed. | FRD-5-12 §5.12.5.2 #7 gives "e.g., 16h": ASSUMPTION |
| E2-D7 | Line statuses: Draft, Submitted, Approved, Returned, Rejected. The approval stage is held on the submission, not the line. | FRD-5-12 §5.12.5.2 #10 |
| E2-D8 | Work Pack status: first saved line sets Released → In Progress; PPC approval at 100% sets Completed. | FRD-7 status list (§7.8.2.4); trigger points ASSUMPTION |
| E2-D9 | Progress source: PPC-approved physical % per Work Pack. FRD-7's hours-based % is shown on screen only. | FRD-21 §21.6 s3-4 over FRD-7 §7.7.1 (C-E2-2) |
| E2-D10 | Activity % = hours-weighted average of its Work Packs' approved % (weight = Work Pack estimated man hours). | ASSUMPTION; no source states the roll-up |
| E2-D11 | Earned MH = activity % × `activity.budgeted_units` (taken as the latest forecast budgeted units); stored per week, never overwritten, not editable. | FRD-21 §21.5.2.1 #3, §21.9.1 |
| E2-D12 | Progress rule: above 100% blocked; below 0 blocked; lower than last locked week needs a retraction approved by the Project Manager with a reason. | FRD-21 §21.9.1, §21.7 |
| E2-D13 | Variance alerts: man-hours > 15% over Current Forecast → `cost-controller`; cost > 10% over Revised Budget → `project-manager`. Over-run only; exactly at the threshold = no alert; no forecast/budget = no alert. | FRD-22 §22.10; direction and boundary ASSUMPTION |
| E2-D14 | FMS hours file: one row per approved line in the FRD-10-1 §10.1.3.3 layout (Type, Date, Badge No., Project, Activity, Cost Centre, CBS, CBS Cost Centre, Hrs); PPC downloads and uploads it to FMS by hand. | FRD-10-1 §10.1.3.3; MOM-2026-10-01. "Type" value, "CBS Cost Centre" source and the layout as the FMS upload template: ASSUMPTION until STAR answers (Faraz) |
| E2-D15 | Weekly cut-off Thursday 23:59 Asia/Riyadh. From the cut-off, lines dated on or before the week end cannot be added or changed; approved lines still pending export go in the next file. | Cut-off concept FRD-21 §21.9.1; day and time ASSUMPTION |
| E2-D16 | B1: replacement allowed while Released or In Progress, blocked when Completed. A removed Worker keeps every line; Draft and Submitted lines continue through approval; no new lines. | Khitaba 31 Oct / 10 Nov; ASSUMPTION; supported by FRD-22 §22.8.1, FRD-21 §21.9.1 |
| E2-D17 | B6: "hours loaded" = any line with hours > 0 that is not Rejected (narrows D36: Rejected lines are not worked hours; the BPA-13 query changes). Flag clears when a later file keeps the Worker, or removes them once their hours are 0. | Khitaba 31 Oct / 10 Nov; ASSUMPTION |
| E2-D19 | The Project Manager approves each week (Approved / Changes requested, reason required) after the Cost Controller's review and before the lock; changes go back to PPC. | FRD-21 §21.6, §21.7; FRD-10 §10.10 (Khitaba for the PO, 10 Nov 2026: follow FRD). Options ASSUMPTION |
| E2-D20 | PARKED for Faraz: closed CBS. FRD-22 §22.3.1 only lists CBS blocks (open/close by Cost Control and the PCON Manager); it does not say how a closed block behaves. LCS suggestions for the POC, both parked: E2-21 refuses hours on a closed CBS (option A, recommended, 2 pts); E2-20 adds a re-open request approved by the PCON Manager (3 pts, needs E2-21). All data is POC master data. | FRD-22 §22.3.1 + LCS suggestion (Khitaba, 10 Nov 2026) |
| E2-D18 | Epic 1 hand-off: `EndEvent_WorkPackAssigned` becomes a message end event (job `publish-workpack-assigned`) that starts `Process_PPC_TimesheetToProgress`, one instance per Work Pack (messageId = workpackNumber). | Design; replaces "Epic 2 starts only after Epic 1 completes" (D37) with a mechanism |

## Contradictions recorded

| Ref | Source A | Source B | Working decision |
|---|---|---|---|
| C-E2-1 Hours approval route | FRD-22 §22.7: Timekeeper → Production Supervisor → PPC | FRD-5-12 §5.12.5.5: Line Manager → HR Manager | FRD-22 for production hours; FRD-5-12 serves payroll (out of scope) |
| C-E2-2 Progress source | FRD-7 §7.7.1: progress % auto-calculated from logged hours | FRD-21 §21.6 s3-4: PPC approves physical % | FRD-21 (E2-D9) |
| C-E2-3 Earned progress source | FRD-21 §21.9: FMS | FRD-22 §22.6.2 s10: PMS weekly summary | FMS completion report |
| C-E2-4 Integration number | BPA-4: hours file = Int-7 | MOM-2026-10-01: ERP Excel download = FMS integration #8 | Flow text read 10 Nov: Excel-upload box has no number near it; Khitaba checks page 1 |
| C-E2-5 Who enters hours | FRD-7 §7.6 s13-14: Production team, resource from logged-in user | FRD-5-12 §5.12.4: Yard Supervisor updates the digital tab; FLOW-FMS-D365 p1: "Supervisor enters Workers Timesheet"; ALL p20: production FMS clerk uploads | Yard Supervisor (`supervisor`) books for the team (E2-D1, E2-D3) |
| C-E2-7 Who uploads hours to FMS | ALL p20: "production FMS clerk" | FLOW-CCR p3: "by PPC" | PPC (E2-08) |
| C-E2-8 Cut-off vs attendance lock | E2-D15: week cut-off Thursday 23:59 Asia/Riyadh (FRD-21 weekly cycle) | FRD-5-3: attendance lock on its own schedule | Keep Thursday 23:59; Faraz to confirm with STAR (E2-16) |
| C-E2-9 Weekly lock vs monthly FMS posting | E2-D15: progress and hours locked weekly (Thursday cut-off, FRD-21) | FRD-10-1 §10.1.5.4 monthly allocation period; FRD-5-3 §5.3.3.3 locks attendance on the 1st, §5.3.8.1 on the 15th-16th of the following month (FRD-5-3 contradicts itself) | E2-08 splits a week that spans a month-end into one FMS file per month (S5); Faraz to confirm with STAR |
| C-E2-6 Approval route on the flow pages | FLOW-FMS-D365 p1: Timekeeper validates → PPC approves (no Production Supervisor) | FLOW-PMS p2, FLOW-CCR p3: Timekeeper/Production/PPC review and approve; FRD-22 §22.7 | Keep the Production Supervisor step (E2-D2) |
| C-E2-10 Who enters completion % | FRD-7 §7.5.6 #6, §7.6 s13: Production Team enters % with daily hours | FLOW-CCR, FLOW-FMS-D365, FRD-21 §21.9: weekly FMS completion report | Weekly FMS report (E2-09/E2-10); hours-based % for information (E2-D9); Faraz to confirm (extends C-E2-2) |

## Processes and element map

**`Process_PPC_TimesheetToProgress`** (one instance per Work Pack; lanes Supervisor, Timekeeper,
Production Supervisor, PPC Team, System)

| Element | Story |
|---|---|
| StartEvent_WorkPackAssigned (message WorkPackAssigned) | E2-01 |
| Gateway_MergeBooking, UserTask_BookHours | E2-03, E2-03b |
| ServiceTask_PrepareHoursSubmission (`prepare-hours-submission`), BoundaryEvent_NothingToSubmit (HOURS_INVALID) | E2-05 |
| BusinessRuleTask_CheckHoursWithinBudget (multi-instance, Decision_HoursWithinBudget), Gateway_AreHoursWithinBudget | E2-04 |
| ServiceTask_SubmitHours (`submit-hours`), UserTask_ValidateHours, ServiceTask_RecordTimekeeperDecision, Gateway_IsValidated | E2-05 |
| UserTask_ApproveHoursProduction, ServiceTask_RecordProductionDecision, Gateway_IsProductionApproved | E2-06 |
| UserTask_ApproveHoursPpc, ServiceTask_RecordPpcDecision | E2-07 |
| BoundaryEvent_WorkPackCompleted (message WorkPackCompleted, key workpackNumber), EndEvent_TimesheetsClosed | E2-16 |

All three decision steps use one job type, `record-hours-decision`, with task header `step`
(Timekeeper, ProductionSupervisor, Ppc).

**`Process_PPC_WeeklyProgressPeriod`** (one instance per week; lanes PPC Team, Project Manager,
Cost Controller, System)

| Element | Story |
|---|---|
| StartEvent_WeeklyCutOff (timer `0 59 20 * * THU`, UTC), ServiceTask_OpenProgressPeriod | E2-16 |
| ServiceTask_GenerateFmsHoursFile, UserTask_SendHoursToFms | E2-08 |
| Gateway_MergeReport, UserTask_UploadCompletionReport, ServiceTask_ReadCompletionReport, BoundaryEvent_ReportInvalid (REPORT_INVALID) | E2-09 |
| Gateway_MergeApproval, UserTask_ApproveCompletion | E2-10 |
| Gateway_MergeProgressCheck, ServiceTask_PrepareProgressCheck, BusinessRuleTask_CheckProgressRule (multi-instance), Gateway_ProgressOutcome | E2-11 |
| UserTask_ApproveRetraction, ServiceTask_RecordRetractionDecision, Gateway_IsRetractionApproved | E2-12 |
| ServiceTask_CalculateEarnedManhours | E2-13 |
| ServiceTask_CompleteWorkPacks (publishes WorkPackCompleted) | E2-16 |
| ServiceTask_PrepareVarianceCheck, BusinessRuleTask_CheckManhourVariance, UserTask_ReviewActualManhours | E2-14 |
| BusinessRuleTask_CheckCostVariance, ServiceTask_SendVarianceAlerts | E2-15 (alerts for both) |
| UserTask_ApproveWeeklyProgress, Gateway_IsWeekApproved | E2-19 |
| ServiceTask_LockProgressPeriod, EndEvent_PeriodLocked | E2-16 |

**`Process_PPC_WorkPackAssignment`** (Epic 1): `EndEvent_WorkPackAssigned` gains a message event
definition and job type `publish-workpack-assigned` (E2-01). No other element changes.

Decisions: `Decision_HoursWithinBudget` (E2-04), `Decision_ProgressRule` (E2-11),
`Decision_ManhourVariance` (E2-14), `Decision_CostVariance` (E2-15). All FIRST hit policy.

## Story map and Delivery order (DRAFT)

| Sprint | Order | Item | Area | Pts |
|---|---|---|---|---|
| 3 (16-30 Nov) | 1 | E2-00 Task: data model v0.7 → V2 migration, seed, four personas, lint threshold | data | 3 |
| 3 | 3 | E2-01 System starts the timesheet process when a Work Pack is assigned | process | 3 |
| 3 | 4 | E2-03 Supervisor can book daily hours per Worker and phase | forms | 5 |
| 3 | 5 | E2-04 Supervisor is blocked from booking hours above the phase budget | decisions | 3 |
| 3 | 6 | E2-03b Supervisor can upload the team's time sheet as an Excel file | integration | 5 |
| 3 | 7 | E2-05 Timekeeper can validate and send a time sheet for approval | forms | 3 |
| 3 | 8 | E1-F1 Production Team can replace Workers while a Work Pack is In Progress (B1) | process | 2 |
| 3 | 9 | E1-F2 "Hours loaded" flag clears (B6) | process | 2 |
| 4 (30 Nov-14 Dec) | | E2-06, E2-07, E2-08, E2-09, E2-10, E2-11, E2-13 | | 24 |
| 5 (14-28 Dec) | | E2-12, E2-14, E2-15, E2-19, E2-16, E2-18 | | 20 |

Sprint 3 = 26 points against 23 delivered in Sprint 2: a stretch. E2-17 dropped 10 Nov (flow pages read; sample files in E2-09; integration number checked by Khitaba; template questions to STAR via Faraz). If capacity holds at 23, E1-F2 moves to
Sprint 4.

## Story-specific Definition of Ready (added to the standard list, 02-story-standard)

| Story | Extra DoR items |
|---|---|
| All Epic 2 stories | This intent and spec accepted; data model v0.7 approved; models reviewed in Desktop Modeler (S13) |
| E2-00 | Four personas approved (done: E2-D1); lint threshold decision recorded (see shared contracts) |
| E2-01 | Build team agrees the Epic 1 end-event change; BPA-15 tests adjusted to run the `publish-workpack-assigned` worker |
| E2-03 | E2-00 merged (line status, unique key); E2-01 merged (a running instance exists) |
| E2-03b | E2-D4 confirmed; template `templates/timesheet-upload-template.xlsx` agreed; E2-03 merged (shared line rules) |
| E2-04 | E2-D6 (16 h) confirmed or replaced |
| E2-05 | `timekeeper` persona in `personas.yaml` (E2-00) |
| E1-F1, E1-F2 | E2-D16, E2-D17 confirmed; E2-03 merged (In Progress and lines exist to test against) |
| E2-08, E2-09 | Integration number checked on FLOW-FMS-D365 p1 (Khitaba); STAR asked for the FMS upload template (Faraz, non-blocking) |
| E2-14, E2-15 | Seed rows for Current Forecast MH, Revised Budget cost, actual cost per CBS |
| E2-16 | Cut-off (E2-D15) confirmed |

## Shared contracts

| Contract | Change | Owner |
|---|---|---|
| `V1__baseline_schema.sql` | Frozen. Epic 2 adds `V2__epic2_timesheet_to_progress.sql` from the v0.7 delta (09: V1 frozen from the first shared environment; confirm V1 is frozen, else Ahsan decides) | E2-00 |
| `R__seed_reference_data.sql` | Adds `cbs_forecast`, `cbs_actual_cost` rows for CBS-139-PIP-01 and one CBS without forecast | E2-00 |
| `personas.yaml` | 7 → 11 users (E2-D1; line-manager and hr-manager dropped with BPA-71, D2); usernames = group IDs; synthetic employeeRef E9008-E9011 | E2-00 |
| `docs/04-conventions.md` "Test users" | Says "exactly seven"; change to eleven with the four new groups (E2-D1). Repo master: change by PR, Ahsan approves (L16) | E2-00 |
| `.bpmnlintrc` / `package.json` `lint:bpmn` | `--max-warnings=4` fails with 14 "no form" warnings (4 Epic 1 + 10 Epic 2), all intended (S14). Proposal: turn the camunda-compat user-task-form rule off in `.bpmnlintrc` and set `--max-warnings=0` | E2-00, Ahsan |
| Messages | New: `WorkPackAssigned` (Epic 1 → Epic 2, messageId workpackNumber), `WorkPackCompleted` (weekly → per Work Pack, key workpackNumber, TTL P7D ASSUMPTION) | E2-01, E2-16 |
| `workpack.status` | Released → In Progress (E2-03), → Completed (E2-16) | as listed |
| `digital_timesheet.submission_status` | Keeps Draft / Cancelled (per Worker per Work Pack); line statuses live on the line (E2-D7) | E2-00 |
| `api/openapi.yaml` | New paths in the v0.7 delta §7 | each story |
| Backend task API | New user tasks join the per-task variable allow-list (S14) | each user-task story |

## Questions parked for Ahsan (database and build; business rule already decided)

| # | Question | Business rule it serves | Story |
|---|---|---|---|
| A1 | DB CHECK `hours >= 0` while entry requires `> 0` (keeps BPA-13 fixtures valid) | Q-DM16 | E2-00 |
| A2 | V2 migration vs V1 regeneration; is V1 frozen? | Q-DM21 | E2-00 |
| A3 | Switch off the user-task-form lint rule and set `--max-warnings=0` | — | E2-00 |
| A4 | Duplicate guard for `publish-workpack-assigned`: Camunda search or a database record | One timesheet process per Work Pack | E2-01 |
| A5 | Unique key "one line per Worker, date and phase" must **ignore Rejected lines** (e.g. a partial unique index) | A Rejected line stays Rejected; the Supervisor may book a new line for the same Worker, date and phase (E2-06 c, Khitaba 10 Nov 2026) | E2-00, E2-06 |
| A6 | TTL of message `WorkPackCompleted` (proposal P7D) | Completion waits while hours are in approval | E2-16 |

## Human gaps so far (POC measure)
1. FPC p1-p3 (FMS-PMS-CCR Process Flow Diagrams.pdf) are in SharePoint 02 Source material › All Process Flows and have a text layer (read 10 Nov 2026; an earlier search had wrongly suggested they existed only inside a zip). The text loses box positions, so which "Integration n" label belongs to which box still needs Khitaba to look at page 1.
2. The DMN tables were drafted but **not executed**: this session cannot reach Maven Central, so the Camunda DMN engine could not run. Ahsan or Faraz to run them with `camunda-process-test` (E2-00).
3. 31 Oct-1 Nov decisions on the Epic 1 design canvas never reached Jira ("update on hold"); B1 and B6 are carried here as E1-F1 and E1-F2.


## Model changes from the story review (10 Nov 2026)
- Process_PPC_WeeklyProgressPeriod: `Gateway_MergeReview` before `UserTask_ReviewActualManhours`; new `Flow_WeekCostReviewRequested` (weekChangeTarget = CostReview) from `Gateway_IsWeekApproved` (BPA-66 b).
- `ServiceTask_CompleteWorkPacks` moved after the PM's approval: `Gateway_IsWeekApproved` → `Flow_WeekApproved` → `ServiceTask_CompleteWorkPacks` → `ServiceTask_LockProgressPeriod` (BPA-67 b). `ServiceTask_CalculateEarnedManhours` now flows straight to `ServiceTask_PrepareVarianceCheck`.
- bpmnlint: 0 errors; only the 14 form-less user-task warnings remain (rule switched off in BPA-48).

- Parked for Ahsan, A7: how the demo starts the weekly cut-off on demand (start Process_PPC_WeeklyProgressPeriod by hand for a given week, or a settable business clock) (BPA-68 c).

## PO decisions 10 Nov 2026 (Khitaba for Faraz)
- F1 E2-D8 accepted: the first saved hour line sets the Work Pack to In Progress (ASSUMPTION kept until STAR confirms) — BPA-50.
- F2 E2-D10 accepted: activity % = latest % of its Released/In Progress/Completed Work Packs, weighted by estimated hours; not started = 0% — BPA-62.
- F3 Q-DM18 accepted: Earned man-hours rounded half-up to 2 dp — BPA-62.
- F4 E2-D15 accepted: weekly cut-off Thursday 23:59 Asia/Riyadh (confirm with STAR) — BPA-67, BPA-58.
- F5 C-E2-8: keep Thursday 23:59 until STAR says otherwise — BPA-67.
- F6 BPA-69 (closed CBS blocks hours, option A) taken into Sprint 4, 2 pts.
- F7 BPA-70 (CBS re-open request) stays in the backlog; no 12th persona.
- F8 B19 (BPA-16) and F9 (Ready per story) not decided here: see chat.

## Decisions S2–S5 (Khitaba for the PO, 10 Nov 2026; applied to E2-08 / BPA-58)
| ID | Decision | Source |
|---|---|---|
| S2 | FMS file gets an Employment Type column, "Direct" or "Agency", from worker.agency_flag | FRD-5-2 §5.2.6.3.85 Employment Type (HR Master), §5.2.6.3.19; FRD-10-1 §10.1.3.2 (STRO15 agency personnel); values ASSUMPTION |
| S3 | CBS Cost Centre = the Work Pack's department_ref | FRD-7 §7.5.5 #9, §7.8.2.6; D18; ASSUMPTION until STAR confirms |
| S4 | PPC can record "Rejected by FMS" with a reason; lines are released to the next file; the file is kept | FLOW-FMS-D365 p1 (push only); rejection path ASSUMPTION |
| S5 | A week across a month-end gives one FMS file per month | FRD-10-1 §10.1.5.4; FRD-5-3 §5.3.3.3, §5.3.8.1; C-E2-9 |
| S6 | Budget revision request: backlog suggestion, not drafted | FRD-22 |

## FRD re-read for S1–S3 (10 Nov 2026; FRD-5-2, 5-3, 5-12, 7, 10-1 read in full; all 47 signed FRDs searched for "agency" and "timesheet")
- S1 timesheet template: columns match FRD-5-12 §5.12.5.2 (Employee ID, Date, Hours max 16, Comments; Project/CBS/Cost Centre/WBS filled by the system) and FRD-7 §7.5.6 Load Manhour (Workpack, Phase, Hours). FRD-7 Role and Resource are filled from the Worker master (FRD-7 §7.6 step 14 "auto-fills Resource"). Out of scope in the POC: Leave Type (FRD-5-12 #8), Is Billable and GHRS ID (FRD-5-3 §5.3.5).
- C-E2-10 (new contradiction): FRD-7 §7.5.6 #6 and §7.6 step 13 have the Production Team enter completion % with the daily hours, and FRD-7 §7.7.1 auto-calculates progress from hours against budget. The flows (FLOW-CCR, FLOW-FMS-D365) and E2-09/E2-10 take the weekly FMS completion report. The POC follows the flows; the hours-based % is shown for information only (E2-D9). Faraz to confirm with STAR.
- S2 and S3: see the decisions table above (FRD-5-2, FRD-7, FRD-10-1 citations).

## Contradiction resolution: follow the FRDs (Khitaba for the PO, 10 Nov 2026 19:54)
Rule: where the FRDs agree, follow them; where FRDs give different options, the POC supports both, as a configuration setting with a stated default. Flow pages and minutes do not override an FRD. Settings are POC configuration (application.yaml), seeded per environment; the default follows the FRD that owns the process.

| # | Decision | FRD basis | Default | Story impact |
|---|---|---|---|---|
| S1 | Timesheet upload columns as FRD-5-12 §5.12.5.2 and FRD-7 §7.5.6; Role/Resource from the Worker record | FRD-5-12, FRD-7 | — | BPA-52 (done) |
| S2 | Employment Type column from the HR Master field | FRD-5-2 §5.2.6.3.85; FRD-10-1 §10.1.3.2 | values ASSUMPTION | BPA-58 (done) |
| S3 | CBS Cost Centre = Work Pack department | FRD-7 §7.5.5 #9, §7.8.2.6 | — | BPA-58 (done) |
| S5 | Both monthly locks: hours period locks monthly for FMS posting; lock day configurable `hours.monthLockDay` = 1 (FRD-5-3 §5.3.3.3) or 16 (FRD-5-3 §5.3.8.1); allocation period monthly (FRD-10-1 §10.1.5.4); weekly progress lock unchanged (FRD-21) | FRD-5-3, FRD-10-1, FRD-21 | 1 | BPA-58 (file per month, done); BPA-67 + new scenarios for the month lock |
| C-E2-1 | **Revised by DoR decision D2:** the route follows the type of person, not a setting. FRD-22 §22.7: production timesheets Timekeeper → Production Supervisor → PPC; non-production timesheets Employee → Head of Department (HR system); FLOW-CCR p3. Epic 2 covers production staff and production agency only; the HR route (BPA-71) is parked | FRD-22 §22.7, FRD-5-12, FLOW-CCR p3 | — | BPA-53/56/57; BPA-71 parked |
| C-E2-2 | Two progress bases: Physical % approved by PPC (FRD-21 §21.6) and Hours-based % (FRD-7 §7.7.1); setting `progress.basis`; with Hours the approved % is pre-filled from hours and PPC still approves | FRD-21, FRD-7 | Physical | BPA-60 |
| C-E2-3 | Two earned-progress sources: FMS completion report (FRD-21 §21.9) and PMS weekly summary (FRD-22 §22.6.2 s10); the upload carries Source = FMS or PMS; setting `progress.source` | FRD-21, FRD-22 | FMS | BPA-59, BPA-62 |
| C-E2-4 | No FRD on either side (BPA-4 vs minutes); unchanged, Khitaba checks the flow page | — | — | BPA-58 |
| C-E2-5 | Both may enter hours: Yard Supervisor for the team (FRD-5-12 §5.12.4) and Production Team member for own/team hours (FRD-7 §7.5.6, §7.6 s13) | FRD-5-12, FRD-7 | both enabled | BPA-50, BPA-52 (+ scenarios for production-team) |
| C-E2-6 | FRD wins over the flow page: keep the Production Supervisor step | FRD-22 §22.7 | — | none |
| C-E2-7 | No FRD names the uploader; keep PPC (FLOW-CCR p3) | — | — | none |
| C-E2-8 | Weekly progress cut-off (FRD-21) and monthly attendance lock (FRD-5-3) both apply; see S5 | FRD-21, FRD-5-3 | — | BPA-67 |
| C-E2-9 | As S5 | FRD-10-1, FRD-5-3 | — | as S5 |
| C-E2-10 | Both: optional completion % entered with daily hours (FRD-7 §7.5.6 #6), stored as "reported by production", information only; the weekly FMS/PMS report (FRD-21 §21.9) drives approval | FRD-7, FRD-21 | — | BPA-50, BPA-60 |

## Model change for E2-22 (BPA-71), 10 Nov 2026 — REVERTED by D2 (BPA-71 parked); the model is back to the production route only (bpmnlint 0 errors, 14 warnings)
Process_PPC_TimesheetToProgress: ServiceTask_SubmitHours → Gateway_ApprovalRoute (default Flow_RouteProduction → UserTask_ValidateHours; Flow_RouteHr `=hoursApprovalRoute = "HR"` → UserTask_ApproveHoursLineManager → ServiceTask_RecordLineManagerDecision → Gateway_IsLineManagerApproved → UserTask_ApproveHoursHrManager → ServiceTask_RecordHrManagerDecision → Gateway_MergeHoursApproved). Flow_PpcDecided now ends at Gateway_MergeHoursApproved, then Flow_ToMergeBookingAfterApproval → Gateway_MergeBooking. New lanes Line Manager and HR Manager. bpmnlint: 0 errors, 16 warnings (form definitions only, as before).

## DoR decisions 10 Nov 2026, 20:30-21:15 (Khitaba for Faraz; each re-checked against the FRDs and flows)
| # | Decision | Basis | Stories |
|---|---|---|---|
| D1 | First saved hour line sets the Work Pack In Progress (was E2-D8 ASSUMPTION, now sourced) | FRD-7 §7.7.1, §7.6 s13-14; FLOW-FMS-D365 p1 (Workpack Executed → Integration-8 status update before validation) | BPA-50 |
| D2 | Epic 2 stays Work-Pack-based, production staff and production agency only; BPA-71 (HR route) parked; approval-route setting and line-manager/hr-manager users dropped | FRD-22 §22.7 (non-production: Employee → Head of Department, HR system), §22.8; FLOW-CCR p3; Epic 1 canvas P1-P13 and "Why a Work Pack is needed" | BPA-71, BPA-48 |
| D3 | PCON Manager added as an email-only recipient (alerts.pconManagerEmail) of both variance alerts; exactly 15% / 10% = no alert (strict ">") | FRD-22 §22.10 | BPA-64, BPA-65 |
| D4 | C-E2-4 closed: the hours file is the unnumbered manual Excel upload to FMS; Int-7 = Workpack closed in D365, Int-8 = WorkPack status and man-hours FMS → D365, both played by the POC | FLOW-FMS-D365 p1 | BPA-58; comment on BPA-4 |
| D5 | Weekly cycle kept (FRD-21) plus a monthly CBS man-hour snapshot frozen at the month lock (FRD-22) | FRD-21 §21.5.2; FRD-22 §22.8, §22.8.1 | BPA-64 (+3 scenarios, 5 → 8) |
| D6 | No report row → last locked % (0% if none), marked "No report row" | FRD-21 §21.5.2 (cumulative; This Week = 0) | BPA-60 |
| D7 | "Hours loaded" excludes Rejected lines (narrows Epic 1 D36) | FRD-22 §22.8.1 | BPA-55; comment on BPA-13 |
| D8 | Declining a retraction needs a reason | FRD-5-12 §5.12.5.5 pattern; FRD-22 §22.8.1 audit | BPA-63 |

## Story dependencies (moved from the stories' Definition of Ready, 10 Nov 2026)
Epic 1 kept dependencies out of the DoR; Epic 2 now does the same. They decide build order, not readiness. Every story also needs BPA-48 (data model, personas, settings) merged first.

| Story | Needs merged first |
|---|---|
| BPA-49 | BPA-48 merged |
| BPA-50 | BPA-48 merged, BPA-49 merged |
| BPA-51 | BPA-50 merged |
| BPA-52 | BPA-50 merged, BPA-51 merged |
| BPA-53 | BPA-50 merged |
| BPA-54 | BPA-50 merged |
| BPA-55 | BPA-50 merged |
| BPA-56 | BPA-53 merged |
| BPA-57 | BPA-56 merged |
| BPA-58 | BPA-57 merged |
| BPA-59 | BPA-58 merged |
| BPA-60 | BPA-59 merged |
| BPA-61 | BPA-60 merged |
| BPA-62 | BPA-61 merged |
| BPA-63 | BPA-61 merged |
| BPA-64 | BPA-62 merged |
| BPA-65 | BPA-64 merged (shared alert sender) |
| BPA-66 | BPA-64 merged |
| BPA-67 | BPA-62 merged, BPA-64 merged, BPA-66 merged |
| BPA-68 | BPA-49 to BPA-67 and BPA-54, BPA-55 Done |
