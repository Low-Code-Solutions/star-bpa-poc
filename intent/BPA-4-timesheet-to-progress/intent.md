# Intent: Timesheet to Progress (Epic 2, BPA-4)

Author: Claude (BA / solution designer), from the BPA-4 epic, the Epic 1 delivery (BPA-3, main 831155a)
and the signed FRDs read on 10 Nov 2026.
Status: **DRAFT 10 Nov 2026, updated 21:15 after the DoR decisions D1-D8**, reviewed by Khitaba (deciding for the Product Owner, 10 Nov 2026).
Not yet accepted by the PO and not yet in the repo (needs a PR). Stories are in Jira as BPA-48 to BPA-70 (Backlog); BPA-71 parked (D2).
Source of truth once accepted: Jira BPA-4 and its stories for scope and acceptance criteria; this file states intent.

## Problem
Approved production hours reach FMS through a manual Excel upload after several teams have approved
them (Timekeeper, Production, PPC) with no single tracked path (BPA-4; FRD-22 §22.6.2 step 4;
MOM-2026-10-01). Work Pack completion %, earned progress, PMS reports and CCR actual man-hours all depend
on that hand-off. STAR leadership asked to see productive hours and capacity across projects so it can
move people week to week (MOM-2026-10-01, business priorities).

Epic 1 leaves every assigned Worker with a **Draft Digital Time sheet and no hours** (BPA-15). Epic 2
fills it and closes the loop to progress.

## Objective
Turn the hours booked against each assigned Worker into approved production hours, and those into Work
Pack completion, Earned % and Earned man-hours, through one tracked path from the yard to cost control
(BPA-4 Objective, extended to cover hours entry and approval).

## Business value
Epic 1 and Epic 2 are delivered and shown together: Epic 1 alone creates the empty time sheets, and the
value below appears only when hours flow through them.

| Value | How Epic 2 delivers it | Evidence |
|---|---|---|
| Productive hours per Worker and per Work Pack, visible weekly | Hours booked per Worker, date and phase; approved hours reported each week | STAR GM and CFO priority: see productive hours and capacity across projects to move people weekly (MOM-2026-10-01) |
| One tracked approval path instead of an untracked Excel hand-off | Timekeeper → Production Supervisor → PPC in Camunda, with return and reject, full audit trail | FRD-22 §22.6.2 step 4, §22.7; BPA-4 problem statement |
| Budget control at the point of entry | Hours above the phase budget are blocked when booked, not found later | FRD-7 §7.7.1 |
| A clean, repeatable file for FMS | Approved hours exported weekly in the FMS timesheet layout; no re-keying | FRD-10-1 §10.1.3.3; MOM-2026-10-01 |
| Trustworthy progress | Progress above 100% blocked; a decrease needs an approved retraction; weeks locked and never overwritten | FRD-21 §21.9.1, §21.7 |
| Earned progress without spreadsheets | Earned man-hours calculated automatically from approved completion % and budgeted units | FRD-21 §21.5.2.1, §21.9.1 |
| Early warning on cost and man-hours | Alerts above 15% man-hour and 10% cost variance per CBS | FRD-22 §22.10 |

What the POC proves (its own value): the full spine from Work Pack to Earned man-hours runs on Camunda
with D365, FMS, P6 and myPeople kept as systems of record, built from Jira stories by Claude Code.
What it does not show: payroll and overtime, billing (Epic 3), live integrations. The GM tied the
priority to payroll and finance (MOM-2026-10-01), so the showcase should say so plainly.

## Proposed outcome
For each assigned Work Pack:
1. The Yard Supervisor or a Production Team member books daily hours per Worker and phase, by form or by
   uploading the team's Excel time sheet (FRD-5-12 §5.12.4, FRD-7 §7.5.6); hours above the phase budget, above
   16 h a day or on a closed CBS are blocked (FRD-7 §7.7.1, FRD-22 §22.3.1). An optional completion % may be
   entered with the hours, for information (FRD-7 §7.5.6 #6).
2. Hours are validated by the Timekeeper and approved by the Production Supervisor and then PPC
   (FRD-22 §22.7, production timesheets), with return and reject paths.
3. Each week, approved hours go to FMS as an Excel file (one file per month when the week crosses a month-end;
   FRD-10-1 §10.1.5.4); PPC records the hand-off or an FMS rejection. PPC uploads the FMS or PMS completion report
   (FRD-21 §21.9, FRD-22 §22.6.2) and approves each Work Pack's completion %, physical or hours-based by setting
   (FRD-21 §21.6, FRD-7 §7.7.1).
4. Progress above 100% is blocked and a decrease needs a retraction approved by the Project Manager
   (FRD-21 §21.9.1, §21.7).
5. Earned man-hours = approved % x latest budgeted units, per activity per week (FRD-21 §21.5.2.1).
6. The Cost Controller sees actual man-hours by CBS; variance alerts fire above 15% (man-hours against
   Current Forecast) and 10% (cost against Revised Budget), also to the PCON Manager by email (FRD-22 §22.10);
   actual man-hours by CBS are frozen monthly at the month lock (FRD-22 §22.8.1).
7. At the weekly cut-off the period is locked after the Project Manager approves; a Work Pack approved at 100%
   becomes Completed. Hours also lock monthly, on the 1st or the 16th by setting (FRD-5-3 §5.3.3.3, §5.3.8.1).

Epic 1 and Epic 2 together are the showcase: Work Pack created to progress locked, in one run (E2-18).

## Affected users
Existing: Supervisor (= Yard Supervisor), Production Team, PPC Team. New Camunda groups and test users (Khitaba for
the PO, 10 Nov 2026): `timekeeper`, `production-supervisor`, `cost-controller`, `project-manager`. The seven-persona rule of 2 Nov
(BPA-44) is extended to eleven; access stays by group only. The PCON Manager receives alerts by email only.

## Affected systems
- FMS (partner-side): receives the approved hours file and supplies the completion report. Batch/file
  only; in the POC both are files handled by PPC in the portal.
- P6 via D365 PMS: budgeted units (seeded copy, already in `activity.budgeted_units`).
- D365 F&O Cost Control Report: CBS code, Current Forecast MH, Revised Operative Budget cost (seeded copies).
- D365 F&O Finance: actual cost per CBS (seeded copy).
- myPeople: Worker identity (unchanged from Epic 1).
- POC: Camunda 8.10.1 (two new processes, four DMN tables), Spring Boot backend, PostgreSQL schema `bpa`
  (V2 migration), React portal.

## Constraints
- As Epic 1: no change to D365, SAP, FMS or myPeople; synthetic data; codename STAR; one SOR per field;
  Asia/Riyadh business dates and UTC timestamps; 09 decisions are inputs.
- No business rule without a source; everything else is marked ASSUMPTION.
- The Epic 1 model changes in one place only: `EndEvent_WorkPackAssigned` becomes a message end event
  (E2-01). Its element ID does not change.

## Out of scope
Payroll and overtime, non-production timesheets and their HR approval route (FRD-22 §22.7; BPA-71 parked, D2), leave, Level 3 cost allocation,
progress billing and the Invoice Certificate (Epic 3, BPA-5), the six PMS reports and dashboards, P6 and
forecast uploads, reopening a locked week, live FMS integration, AI features. Also out of scope (QA pass 10 Nov): blocking hours on leave days (FRD-5-12 §5.12.5.4; leave is mastered in myPeople), Worker eligibility from HSE TFC and medical fitness (FLOW-HSE), alerts for negative ETC and integration failures (FRD-22 alerts), activity weightage (FRD-21). All are backlog candidates.

## Success
BPA-4 Definition of Done: all steps run end to end in Camunda from the portal; hours over the phase
budget, progress above 100% and an unapproved decrease are blocked; Earned man-hours are calculated
automatically; variance alerts fire; audit trail complete; Epic 1 + Epic 2 demoed as one run.

## Open questions (working decision in brackets)
1. Weekly cut-off day and time (Thursday 23:59 Asia/Riyadh; ASSUMPTION) - Faraz with STAR (C-E2-8).
2. Completion report columns: not in any source; the flow pages (read 10 Nov) confirm the report is per Work
   Pack with actual hours, and the hours go to FMS by Excel upload by PPC (FRD-10-1 §10.1.3.3 layout, ASSUMPTION as the FMS template) - STAR via Faraz.
3. ~~Roll-up~~ accepted (F2): hours-weighted by estimated man-hours, consistent with FRD-21 weighting by budgeted units.
4. Daily hours limit (16 h, the FRD-5-12 example; ASSUMPTION) - STAR to validate.
5. ~~Integration number~~ closed (D4): the hours file is the unnumbered manual Excel upload on FLOW-FMS-D365 p1.
6. Employee master after the POC: D365 HR (canvas, Khitaba 1 Nov) vs myPeople (03-sor-matrix, C11) -
   label only; Faraz.

## Settings (FRD options, 10 Nov 2026)
Where two FRDs give different options, the POC supports both (spec "Contradiction resolution"):
progress.basis (Physical | Hours), progress.source (FMS | PMS),
hours.monthLockDay (1 | 16), alerts.pconManagerEmail. Defaults follow the FRD that owns the process.
