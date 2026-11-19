# templates

Files users download from the portal. Master copy: this folder (ADR 0015). Nothing here is deployed except the two templates: `worker-assignment-template.xlsx`,
which the backend serves from `GET /api/v1/assignment-files/template` ("Download template", 09 S18), and `timesheet-upload-template.xlsx`, served from
`GET /api/v1/timesheet-files/template` (BPA-52).

## worker-assignment-template.xlsx

The official template for the Worker assignment file (BPA-12; ADR 0009, 09 S18, D59).

- **ASSUMPTION.** The layout is a working assumption (D33 rule 1) until STAR sends the real FMS template; Faraz has asked for it.
  The workbook says so in its `README` sheet.
- Sheet `Assignment`, header in row 1, one row per Worker from row 2:

| Column | Required | Notes |
| --- | --- | --- |
| Workpack number | yes | for example `WP-000457`; one file may cover several Work Packs |
| Badge No | yes | matched to the Worker master data (`worker.employee_ref`) |
| Estimated hours | yes | a number greater than 0 (checked by BPA-22) |
| Employee name | no | ignored on import; the name shown comes from the Worker master data |
| Remarks | no | at most 250 characters (checked by BPA-22) |

- Columns are found by their header (trimmed, case-insensitive, in any order). Only `.xlsx` is read, from the first sheet.
- The file is built by `AssignmentFileBuilder` (test sources), and `AssignmentTemplateTest` checks the committed file against it. To
  write it again after a layout change, compile the tests and run
  `java -cp backend/target/test-classes;backend/target/classes;<dependencies> com.lcs.star.bpa.AssignmentFileBuilder templates/worker-assignment-template.xlsx`.

## demo/

Seven files for the showcase and manual testing (09 L20). **Tests never use them** (S18): tests build their own files with
`AssignmentFileBuilder`. They name `WP-000001`, the first Work Pack of a fresh database; before showing them, replace it with the number
of a Work Pack that has been printed (assignment state AwaitingFile).

| File | What it shows |
| --- | --- |
| `assign-demo-good.xlsx` | two rows routed to the waiting Work Pack and both read "Assigned" (BPA-22); uploading it again shows "File already processed" |
| `assign-demo-unknown-workpack.xlsx` | the row reads "Work Pack not found" |
| `assign-demo-wrong-layout.xlsx` | "File layout not recognised" |
| `assign-demo-empty.xlsx` | "File has no rows" |
| `assign-demo-duplicate-worker.xlsx` | the same Worker twice for a Work Pack: both rows read "Worker listed twice for this Work Pack" |
| `assign-demo-hours-out-of-range.xlsx` | hours of 0 and 100000: "Estimated hours must be greater than 0" and "Estimated hours cannot exceed 99,999.99" |
| `assign-demo-unknown-employee.xlsx` | a Badge No that is not in the Worker master data: "Worker not in worker master data" |

The last three are routed like the good file; BPA-22 answers every row, and when all rows are rejected the Work Pack goes back to waiting for a corrected file (D5). Write them again with
`... com.lcs.star.bpa.AssignmentFileBuilder demo templates/demo`.

## timesheet-upload-template.xlsx

The official template for the team time sheet upload (BPA-52; E2-D4, FRD-5-12 §5.12.5.2, FRD-7 §7.5.6), served by `GET /api/v1/timesheet-files/template` ("Download template" on the Book hours screen).

- **ASSUMPTION.** The layout is a working assumption until STAR sends its time sheet template (Jira comment 10541). The workbook says so in its `README` sheet.
- Sheet `Timesheet`, header in row 1, one row per Worker, date and phase from row 2. A `.csv` with the same columns in the same order is also accepted (comma, UTF-8, header row; ASSUMPTION).

| Column | Required | Notes |
| --- | --- | --- |
| Workpack number | yes | for example `WP-000457`; one file may cover several Work Packs |
| Badge No | yes | matched to the Worker master data (`worker.employee_ref`) |
| Work date | yes | `dd/mm/yyyy`; from the start of the open week up to today |
| Phase | yes | one of the Work Pack's phases: Engineering, Cutting, Welding, Finishing |
| Hours | yes | greater than 0, at most 2 decimals; a Worker cannot exceed `app.daily-hours-limit` (16) hours on one date over all Work Packs |
| Remarks | no | at most 250 characters |

- Columns are found by their header (trimmed, case-insensitive, in any order). A `.xlsx` is read from its first sheet.
- Each row is checked on its own, in file order, with the same rules as a form entry; a valid row is saved as a Draft line with entry source Upload even when other rows are rejected, an existing line is never overwritten, and the maximum file size is 5 MB (ASSUMPTION).
- The tests build their own files (`TimesheetFileBuilder`, test sources) and `TimesheetFileReaderTest` checks the committed template's header row against the reader.

### demo/ (time sheet)

Five more files for the showcase and manual testing. **Tests never use them.** All name `WP-000001`, the first Work Pack of a fresh database, and Workers E1021 and E1099: before showing them, replace `WP-000001` with the number of a Work Pack
that is Assigned (it has Digital Time sheets) and, if needed, the Badge Nos with two of its Workers, and make the dates fall in the open week up to today.

| File | What it shows |
| --- | --- |
| `timesheet-demo-good.xlsx` | three rows, all read "Saved" (Draft lines with entry source Upload) |
| `timesheet-demo-good.csv` | the same layout as a `.csv` (two rows) |
| `timesheet-demo-bad-rows.xlsx` | one good row, then a Work Pack that does not exist, a Badge No that is not assigned, a phase that is not on the Work Pack, a date in the future and hours that are not a number: each rejected with its own outcome |
| `timesheet-demo-daily-limit.xlsx` | 17 hours for one Worker on one date: "Worker E1021 would exceed 16 hours on 17 Nov 2026" |
| `timesheet-demo-empty.xlsx` | the template with no rows: "File has no rows" |

## fms-hours-export-template.xlsx

The layout of the weekly FMS hours file (BPA-58; E2-08; FRD-10-1 §10.1.3.3). **It is not a download template:** nobody fills it in. The backend writes each weekly file at the cut-off (`FmsHoursFileWriter`), PPC downloads it from `GET /api/v1/fms-hours-exports/{id}/file` and uploads it to FMS by hand (FLOW-FMS-D365 p1, FLOW-CCR p3). This file is the committed picture of the layout, and `FmsHoursTemplateTest` checks its header row against the writer.

- **ASSUMPTION.** The layout is a working assumption until STAR sends its FMS upload template (Faraz asked STAR, S1). The workbook says so in its `README` sheet; the generated files have only the `FMS Hours` sheet.
- Sheet `FMS Hours`, header in row 1, one row per Approved line not yet exported, sorted by Date, Badge No., Project, Activity. A week with no such line gets a file with the header row only (Q-DM19). A week that spans a month-end gets one file per calendar month of the work dates (S5).

| Column | Value | Source |
| --- | --- | --- |
| Type | `FMS` (ASSUMPTION; FRD-5-3 §5.3.8 "Timesheet Category") | POC database |
| Date | the line's work date, text `dd/mm/yyyy` | `digital_timesheet_line.work_date` |
| Badge No. | the Worker's badge | `worker.employee_ref` (myPeople) |
| Employment Type | `Direct` or `Agency` (S2; values ASSUMPTION) | `worker.agency_flag` (myPeople) |
| Project | the time sheet's project | `digital_timesheet.project_ref` (D365 F&O) |
| Activity | the time sheet's activity | `digital_timesheet.activity_ref` (P6 via D365 PMS) |
| Cost Centre | the Worker's personal cost centre; blank when none (D40) | `worker.cost_centre_ref` (myPeople, ASSUMPTION) |
| CBS | the time sheet's CBS code; blank when none (D40) | `digital_timesheet.cbs_code_ref` (D365 F&O CCR) |
| CBS Cost Centre | the Work Pack's department (S3; ASSUMPTION until STAR confirms) | `workpack.department_ref` (D365 F&O) |
| Hrs | the line's hours, a number shown to 2 decimals | `digital_timesheet_line.hours` |

- A line is exported once: it is stamped (`exported_at`, `fms_hours_export_id`) when the file is generated. A late approval goes in the next file. If PPC records "Rejected by FMS" with a reason, that file is kept and its lines are released into the next file (S4).
- Dates and Hrs are the cell types above (Date is text, as in the demo file; Hrs is numeric with format `0.00`).

### demo/ (FMS hours)

| File | What it shows |
| --- | --- |
| `demo/fms-hours-demo-week-2026-11-19.xlsx` | what the cut-off for the week ending 19 Nov 2026 writes for the BPA-58 Background: three rows (E1021, E1099 and E1150, whose Cost Centre and CBS are blank). **Tests never use it** |
