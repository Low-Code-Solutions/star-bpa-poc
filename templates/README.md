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

The layout of the weekly FMS hours file (BPA-58; E2-08; FRD-10-1 §10.1.3.3). **It is not a download template:** nobody fills it in. The backend writes each weekly file at the cut-off (`FmsHoursFileWriter`), PPC downloads it from `GET /api/v1/fms-hours-exports/{id}/file` and uploads it to FMS by hand (FLOW-FMS-D365 p1, FLOW-CCR p3). This file is the committed picture of the layout, and `FmsHoursFileWriterTest` (`theCommittedTemplateHasTheWritersHeader`) checks its header row against the writer.

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

## completion-report-template.xlsx

The layout of the weekly FMS completion report (BPA-59; E2-09; FLOW-CCR p3, FLOW-PMS p2; FRD-21 §21.9, FRD-22 §22.6.2 s10). PPC uploads it in the portal ("Upload FMS completion report", the task of the weekly process) as `.xlsx` (first sheet) or `.csv`, through `POST /api/v1/completion-reports`; the result of each row can be read again from `GET /api/v1/completion-reports/{id}/result`. It is a layout, not a served download: nobody fills in the committed file.

- **ASSUMPTION.** No source gives the columns; the layout is a working assumption until STAR sends the real FMS report (the workbook's `README` sheet says so).
- Sheet `Completion`, header in row 1, one row per Work Pack from row 2. Columns are found by their header, trimmed and case-insensitive, in any order (the rule of the time sheet upload), so "Workpack Number" and "Workpack number" are the same column.

| Column | Required | Notes |
| --- | --- | --- |
| Workpack number | yes | an In Progress Work Pack, for example `WP-000457`; one row per Work Pack |
| Completion % | yes | a number with at most 2 decimals; 120 and negative values are read here and blocked at approval (BPA-61) |
| Actual hours | no | a number of 0 or more when given; shown beside the POC approved hours at approval (BPA-60), information only |
| Source | yes | `FMS` or `PMS`; only the source set in `progress.source` (default `FMS`) is accepted |

- The report has no week column: it belongs to the week of the task it is uploaded into (the upload names the open task).
- Each row gets one outcome: `Read`, or `Work Pack not found`, `Work Pack is not In Progress`, `Completion % must be a number`, `Completion % can have at most 2 decimal places`, `Duplicate row for <WP>`, `Source <x> is not the configured progress source` (the story's texts), and the ASSUMPTION texts for a missing required value, an Actual hours that is not a number of 0 or more, and a Completion % outside -999.99 to 999.99. A report with no `Read` row goes back to the upload task with "No usable rows in the completion report". Several uploads may be made into one task; the one named when the task is completed is the one that counts.
- The tests build their own files (`CompletionReportBuilder`, test sources, in memory, as the Epic 1 uploads; it also writes the demo file of the bad rows); **tests never use the demo files.**

### demo/ (completion report)

`completion-demo-good.xlsx` and `completion-demo-bad-rows.xlsx` name Work Packs of a fresh database (`WP-000001` and the next ones); before showing them, replace the Work Pack numbers with In Progress Work Packs. `completion-demo-run.xlsx` is for the demo script, on a stack reset the same day.

| File | What it shows |
| --- | --- |
| `completion-demo-good.xlsx` | two rows (`WP-000001` 40%, `WP-000002` 100%) that both read "Read" |
| `completion-demo-run.xlsx` | BPA-68 demo run: one row, `WP-000001` at 40% with 15.5 actual hours from FMS, for the Work Pack of a freshly reset stack (`docs/showcase/epic1-epic2-demo.md`) |
| `completion-demo-bad-rows.xlsx` | one row for each outcome, so none hides another: an unknown Work Pack, a 120% row that is still "Read" (left for approval), a second row for `WP-000001` ("Duplicate row for WP-000001"), `WP-000002` at 40.125 (two decimals), `WP-000003` at "forty" (not a number) and `WP-000004` with Source PMS (rejected while `progress.source` is FMS). It names `WP-000001` to `WP-000004` |

Five `.csv` files (the same layout, comma and UTF-8) are for the portal demo and manual testing on the local dev stack. They name `WP-000011`, `WP-000030`, `WP-000031` and `WP-000032`, which are In Progress there (`WP-999999` does not exist); on another database replace the numbers with In Progress Work Packs. The upload needs an open "Upload FMS completion report" task of the weekly process.

| File | What it shows |
| --- | --- |
| `completion-demo-all-read.csv` | three rows, all "Read": the clean report to complete the task with (`WP-000011` 45%, `WP-000030` 100%, `WP-000031` 20%) |
| `completion-demo-good-and-bad-rows.csv` | a mix: two rows read, a 40.125 ("at most 2 decimal places"), "forty" ("must be a number") and a second `WP-000011` ("Duplicate row for WP-000011") |
| `completion-demo-no-usable-row.csv` | only an unknown Work Pack ("Work Pack not found"): completing the task with it sends it back with "No usable rows in the completion report" |
| `completion-demo-wrong-layout.csv` | the wrong columns: "File layout not recognised" |
| `completion-demo-pms-source.csv` | a row with Source PMS ("Source PMS is not the configured progress source" while `progress.source` is FMS) and a row that reads |
