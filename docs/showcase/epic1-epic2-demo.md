# Demo script: one Work Pack from creation to locked progress (Epic 1 + Epic 2)

Status: **DRAFT 22 Nov 2026** (BPA-68), for review by Khitaba; the dry run is recorded in the plan (`intent/BPA-4-timesheet-to-progress/plans/BPA-68.md`).
Owner: Faraz. Codename "STAR". Synthetic data only. Data marked ASSUMPTION is demo data that Jira does not give.
The same run is proved by `EndToEndRunApiTest` (`BPA68_workPackRunsFromCreationToLockedProgress`, `BPA68_returnedHoursRejoinTheRun`, `BPA68_rulesAreShownLive`).

## What the audience sees
One Work Pack for activity A-2301 goes through every step of both epics with the real portal screens: Work Pack created and released (Epic 1), assigned to two Workers, their hours booked, approved and sent to FMS, the week's progress approved, Earned man-hours calculated and the week locked (Epic 2). Two rules are shown live.

## Ahead of the demo
Do these the same day, on a local or dedicated stack. **Never on the QA server**: `down -v` wipes the testers' data.

- [ ] `.env` is copied from `.env.example`, with `DEMO_PASSWORD` set, `BPA_PROFILES=local,qa` and `BPA_CLOCK_TODAY=2026-11-19` (the business date; the time of day keeps moving). Do not change it during the demo: a restart ends every login.
- [ ] Clean start: `docker compose down -v`, then `docker compose up -d`. The seed is loaded again, there are no Work Packs (the demo Work Pack is **WP-000001**), no progress period, and Camunda is empty. This also removes any period the real Thursday timer created since BPA-48.
- [ ] Signed in, `GET /api/v1/business-date` shows `2026-11-19`.
- [ ] Seed check: activity A-2301 (budgeted units 1200, no Work Packs) and Workers E1021 and E1099 exist.
- [ ] Operate (http://localhost:8080, demo/demo) shows no instance of `Process_PPC_WeeklyProgressPeriod`. If one is there, start again from `down -v`.
- [ ] Mailpit is open (http://localhost:8025): the weekly variance alerts, if any, arrive there.
- [ ] One browser profile per persona, or log out between steps. Every persona's username is its group name and the password is `DEMO_PASSWORD`.
- [ ] Files at hand: `templates/demo/assign-demo-good.xlsx` and `templates/demo/completion-demo-run.xlsx`.
- [ ] The cut-off command is ready in a terminal (step 10).
- [ ] The dry run of this script has been done on this stack.

## The run

Portal: http://localhost:3000. Dates follow the business date 19 Nov 2026.

| # | Persona (login) | Screen / action | Data | Expected |
| --- | --- | --- | --- | --- |
| 1 | `time-method-analyst` | Pick project **PRJ-139** in the Project picker beside Start, then Start Work Pack request (BPA-78: the analyst has PRJ-139 and PRJ-140 from `personas.yaml`); task **Create Work Pack**, which offers only PRJ-139 activities | Activity A-2301; drawing DWG-118; department Fabrication; completion date 31 Dec 2026; phases Cutting 300 h and Welding 200 h; material MAT-PIPE-06 for Cutting (ASSUMPTION: drawing, material, department and date come from the seed) | The Work Pack is saved as WP-000001 |
| 2 | `time-method-analyst` | **Confirm Workpack** | Nothing changed | The release check reads Ready |
| 3 | `project-engineer` | **Sign off release** | Approved | Work Pack Released |
| 4 | `production-planner` | **Schedule Work Pack** | Start 26 Nov 2026, end 18 Dec 2026 (ASSUMPTION; the start must be at least 7 days after today) | Scheduled; the print is generated |
| 5 | `time-method-analyst` | Open the Work Pack print | WP-000001 | The Work Pack print opens |
| 6 | `production-team` | Upload the assignment file | `assign-demo-good.xlsx`: E1021 40 h and E1099 60 h | Both rows read "Assigned" (wait a moment: they read "Pending" for a second or two first) |
| 7 | | (Operate) | | Epic 1 instance ended at `EndEvent_WorkPackAssigned`; the timesheet process waits at `UserTask_BookHours` |
| 8 | `supervisor` | **Book hours** | E1021, 16 Nov, Cutting, 8 h; E1099, 16 Nov, Cutting, 7.5 h | Both lines saved; WP-000001 becomes In Progress |
| 8a | `supervisor` | **Live rule 1, the daily limit** | E1021, 17 Nov, Cutting, 17 h | Refused: "Worker E1021 would exceed 16 hours on 17 Nov 2026"; no line saved |
| 9 | `supervisor`, `timekeeper`, `production-supervisor`, `ppc-team` | Send to Timekeeper; **Validate hours**: Validated; **Approve hours (Production)**: Approved; **Approve hours (PPC)**: Approved | | The lines are Approved; the process is back at **Book hours** |
| 9a | | *Optional detour, returned hours:* at **Validate hours** the Timekeeper chooses Returned with the reason "Check E1099"; the Supervisor changes E1099 to 7 h (ASSUMPTION) and sends again; then step 9 as above | | Same end state |
| 10 | (terminal) | Start the cut-off for week ending 19 Nov 2026 (command below) | | A progress period for 19 Nov opens; the weekly process waits at **Send hours to FMS** |
| 11 | `ppc-team` | **Send hours to FMS** | Download the file; mark it "Sent to FMS" | The file has 2 rows for A-2301 (E1021 16/11/2026 8; E1099 16/11/2026 7.5); other rows, if any, are ignored |
| 12 | `ppc-team` | **Upload FMS completion report** | `completion-demo-run.xlsx`: WP-000001, 40%, 15.5 h (ASSUMPTION), source FMS | The row reads "Read" |
| 13 | `ppc-team` | **Approve completion %**, **live rule 2** | Enter 120% for WP-000001 | Refused: "Completion % must be between 0 and 100"; the task stays open |
| 14 | `ppc-team` | **Approve completion %** | 40% (no remark: it equals the report) | Accepted |
| 15 | `cost-controller` | **Review actual man-hours** | Remark "Reviewed for the demo" if the task asks for one (ASSUMPTION) | Completed |
| 16 | `project-manager` | **Approve weekly progress**: Approve week | | A-2301 shows Earned man-hours **480.00**; the week is approved |
| 17 | | (Operate) | | The weekly process ended at `EndEvent_PeriodLocked` |

**Step 10, the command**, in Git Bash or cmd (in PowerShell write `curl.exe`; its `curl` is another command) (Camunda REST on :8080, user demo, password demo; this is what the Thursday 23:59 timer does):

```
curl -u demo:demo -X POST http://localhost:8080/v2/process-instances -H "Content-Type: application/json" -d "{\"processDefinitionId\":\"Process_PPC_WeeklyProgressPeriod\",\"startInstructions\":[{\"elementId\":\"ServiceTask_OpenProgressPeriod\"}]}"
```

Live rule 2 shows BPA-60's check. BPA-61's own text ("<WP> completion cannot exceed 100% (approved 120%)") cannot come from the screen, because the check at approval refuses a value above 100 first; it is proved by `ProgressRuleApiTest` (see questions 1 and 5 of the plan `intent/BPA-4-timesheet-to-progress/plans/BPA-68.md`; the Gherkin of scenario 3 still reads "sent back" until Khitaba amends it).

## Where it ends
- The period for week ending 19 Nov 2026 is **Locked**.
- The FMS file has **2 rows** for WP-000001.
- A-2301 has Earned man-hours **480.00** (40% of 1200).
- WP-000001 is **In Progress**, its timesheet process waiting at **Book hours** (a Work Pack below 100% is not completed).

## Not shown
Payroll and overtime, non-production timesheets, billing, live integrations, leave, HSE eligibility, Level 3 cost allocation, reopening a locked week.

Where each item comes from in `intent/BPA-4-timesheet-to-progress/intent.md` (the file has no "Not shown" heading, so the list follows Jira item BPA-68 (d), and the wording differs a little there):
- payroll and overtime, billing (Epic 3), live integrations: "What it does not show", L40;
- non-production timesheets, leave, Level 3 cost allocation: "Out of scope", L89;
- billing ("progress billing and the Invoice Certificate"): "Out of scope", L90;
- reopening a locked week, live FMS integration, hours on leave days, Worker eligibility from HSE TFC and medical fitness (HSE eligibility): "Out of scope", L91.

## What is behind it
- **Epic 1** (BPA-3): the Work Pack numbered, released, scheduled, printed and assigned.
- **Epic 2** (BPA-4): hours booked and approved, the FMS file, progress approved, Earned man-hours and the locked week.
- **Proof:** `EndToEndRunApiTest` runs this script through the same task API, as each persona.

## If something goes wrong
- A wrong value in a task cannot be undone in the portal: stop, `docker compose down -v`, `docker compose up -d` and start again from step 1.
- If step 10 answers "already has a progress period", the stack was not reset today.
