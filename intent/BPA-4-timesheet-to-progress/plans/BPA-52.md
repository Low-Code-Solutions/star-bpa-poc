# Plan: BPA-52 Supervisor can upload a team's daily hours as an Excel or CSV time sheet
Jira: BPA-52 (Story, epic BPA-4)
Story last updated: 2026-11-18T11:33:12.414+0400
Status: Approved (Ahsan, Build Lead, 18 Nov 2026)
Approver: Ahsan (Build Lead)
Branch: BPA-52-upload-timesheet-file, from main b94df49 (inputs commit 68ca3a5)

> Written in plan mode and approved before this commit. The local branch was behind `origin/BPA-52-upload-timesheet-file` (commit 68ca3a5, template and demo files, was only on the remote); it was fast-forwarded to 68ca3a5 and now tracks the remote. This plan is the next commit. Q1-Q9 are open: the numbered answers decide the behaviour noted in each.

## Context
BPA-50 lets the Supervisor book hours line by line; BPA-51 enforces the phase budget and the daily limit. BPA-52 lets the Supervisor (or a
Production Team member, C-E2-5) upload a whole team's hours as one `.xlsx` or `.csv`, each row checked **exactly like a form entry** and saved as a
Draft line with entry source Upload, even when other rows fail (E2-D4). Same intake pattern as the Worker assignment file (BPA-12/22, ADR 0009, D49,
D58): synchronous upload, a file-level outcome or a row-by-row result on screen, downloadable, every upload logged with its sha256. No model change
(BPMN Reference: the upload saves lines while `UserTask_BookHours` is open).

## Readiness check (Definition of Ready, docs/02, against Jira 18 Nov 11:33)
| Check | State |
|---|---|
| Jira status | **In Progress** since 18 Nov 11:33 (Ready 17 Nov, comment 10545). Not moved by me. Its Development panel lists one **repository** link, not a PR (Q6) |
| Template, persona, outcome | Met |
| Gherkin incl. unhappy paths | Met: 17 scenarios (1 Scenario Outline) |
| BPMN element IDs exist | Met: `UserTask_BookHours` (no new element) |
| Every field has an SOR | Met (SOR Notes; data model §13.2.3) |
| DMN complete | Stale text as in BPA-51 ("3 rules"; the model has 5); BPA-52 calls it exactly as the form does |
| No blocking question | Q1-Q4 below change behaviour; none blocks starting tests-first |
| Template and demo files exist (story DoR) | Met on `origin/...` (68ca3a5): template sheet `Timesheet` (header row only) + `README` sheet; demos good (.xlsx 3 rows, .csv 2 rows), bad-rows (6), daily-limit (1), empty |
| intent.md / spec.md accepted | **Not met in the repo** (both DRAFT) |
| Estimate ≤ 8 | Met (5); a CSV reader, a 413 handler and a screen section make it tight (Risks) |
| SOR Notes vs Gherkin | SOR Notes: "403 outside supervisor"; the Gherkin lets production-team upload (C-E2-5). The Gherkin wins |

## What the files contain (from 68ca3a5)
- Template `templates/timesheet-upload-template.xlsx`: sheet `Timesheet`, row 1 `Workpack number | Badge No | Work date | Phase | Hours | Remarks`, no data; sheet `README` (12 lines, marked ASSUMPTION).
- `templates/demo/timesheet-demo-good.xlsx`: WP-000001 E1021 16/11/2026 Cutting 8; E1099 16/11/2026 Cutting 7.5 "Night shift"; E1099 17/11/2026 Welding 6. `.csv`: the first two of these.
- `timesheet-demo-bad-rows.xlsx`: one good row, then WP-999999, Badge E9999, Phase Painting, date 31/12/2099, Hours "abc". `timesheet-demo-daily-limit.xlsx`: one row of 17 h. `timesheet-demo-empty.xlsx`: header only.
- Dates are stored as text cells "dd/mm/yyyy". All demos name **WP-000001** (Q2).

## Design
### Contract (`api/openapi.yaml` first; tag `timesheet-files`; groups supervisor and production-team on all three)
| Call | Success | Errors |
|---|---|---|
| `POST /api/v1/timesheet-files` (multipart, part `file`; `.xlsx` or `.csv`) | 201 `TimesheetFileResult` (file outcome **or** rows with outcomes) | 400 no file part; 413 "File is larger than 5 MB"; 401; 403 |
| `GET /api/v1/timesheet-files/{id}/result` | 200 `TimesheetFileResult`; `Accept: text/csv` gives the same as a CSV download | 404 "Upload not found" (ASSUMPTION text); 401; 403 |
| `GET /api/v1/timesheet-files/template` | 200 the `.xlsx` (attachment) | 401; 403 |
`TimesheetFileResult`: `{id, fileName, receivedAt, outcome?, rows[]}`; `TimesheetFileRow`: `{rowNumber, workpackNumber?, badgeNo?, workDate?, phase?, hours?, remarks?, outcome}` — the values **as uploaded** (raw text) and the outcome.

### Reading the file (new `timesheet/TimesheetFileReader`, copying `assignment/AssignmentFileReader`)
- `.xlsx` (first sheet) with Apache POI, as BPA-12; `.csv` comma-separated, UTF-8 (BOM tolerated), header row, quoted fields allowed (ASSUMPTION parser rules; Jira decision b). Chosen by the file name's extension; any other extension → "File cannot be read" (ASSUMPTION).
- Columns found by header, **trimmed and case-insensitive, any order** (as the assignment reader; Q1). Required headers: Workpack number, Badge No, Work date, Phase, Hours; Remarks optional. A missing required header → file outcome "File layout not recognised".
- File outcomes (data model §13.2.3): "File cannot be read", "File layout not recognised", "File has no rows". Blank rows skipped. Row cap 5000 → "File has too many rows" (copied from the assignment reader; not in the §13.2.3 list: ASSUMPTION, Q9).
- A Work date cell may be text "dd/mm/yyyy" or a real Excel date (both read; the raw text is what is logged).

### The row pipeline (new `timesheet/TimesheetFileService`) — each row on its own, in file order
1. **Required**, in column order: "Workpack number is required", "Badge No is required", "Work date is required", "Phase is required", "Hours is required" (the Outline's texts).
2. **Parse:** date not dd/mm/yyyy → "Work date is not a valid date"; hours not a number → "Hours must be a number" (data model §13.2.3).
3. **Resolve the time sheet:** Work Pack by number → "Work Pack not found"; the Worker's time sheet on it, assigned and not flagged `HoursLoadedNotRemoved` → else "Worker not assigned to this Work Pack" (upload text, Gherkin; the form says "Worker <badge> is not assigned to <WP>"). A Cancelled time sheet → "Time sheet is Cancelled" (data model §13.2.3; the form's 409 text differs, Q7).
4. **In approval** (Jira decision d): "Hours for <WP> are in approval; book them after the decision" (Q4).
5. **Every other rule through the form's own code**: `HourLineService.add(...)` — remarks (250), hours above 0, 2 decimals, not in the future (Asia/Riyadh clock), cut-off week, phase on the Work Pack, duplicate (Worker, date, phase, not Rejected), then `Decision_HoursWithinBudget` with `app.daily-hours-limit` and the phase budget (BPA-51), `cbsClosed`/`workerOnLeave` false (Q5). A `Refused` becomes the row outcome (same texts as §13.2.3). **No second copy of any rule.** `add` gains a source argument: `Form` (as now) or `Upload(fileId)`; the repository writes `entry_source = 'Upload'` and `timesheet_file_id`. Its first-line "Released → In Progress" move applies to uploads too.
6. Each saved row commits on its own (as the form), so **a later row sees earlier rows of the same file** (duplicate key, daily limit, phase budget). A saved line is never overwritten (duplicate rule).
7. `timesheet_file` is written first (sha256, uploaded_by, received_at, file outcome), then one `timesheet_file_row` per row (raw values, outcome, `timesheet_line_id` when saved). No duplicate-file rule (Jira).

### 413 "File is larger than 5 MB"
`spring.servlet.multipart.max-file-size: 5MB` already exists, but nothing maps the exception: a new `@RestControllerAdvice` maps `MaxUploadSizeExceededException` to 413 problem "File is larger than 5 MB". It also applies to the assignment upload (same limit, Jira "as assignment files"; Q8).

### Frontend (on the existing Book hours screen, `UserTask_BookHours`)
New `features/timesheets/components/TimesheetFileUpload` inside `BookHours` (below the grid): file input (`.xlsx,.csv`) and "Upload time sheet"; the result as a table (row, the values as uploaded, outcome), or the file outcome; "Download result" (CSV of the same result) and "Download template" links; after a save, the grid refreshes (invalidate `timesheet-lines`). Built like `AssignmentFileUpload` (BPA-12); service calls through `apiClient` only.

### templates/README.md
A `timesheet-upload-template.xlsx` section (columns, required, ASSUMPTION, served by the template endpoint) and the five demo files in the `demo/` table, with the WP-000001 note (Q2).

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml` (3 paths, `TimesheetFileResult`, `TimesheetFileRow`, tag) |
| Backend | new `timesheet/TimesheetFileReader`, `timesheet/TimesheetFileService`, `persistence/TimesheetFileRepository`, `api/TimesheetFileController`, `api/TimesheetFileResultCsvConverter` (as BPA-12's), `api/UploadLimitAdvice` (413); `timesheet/HourLineService` + `persistence/DigitalTimesheetLineRepository` (source argument, entry_source/timesheet_file_id); `config/SecurityConfig` (`/api/v1/timesheet-files`, `/**` → supervisor, production-team); a shared "in approval" check (Q4) |
| Frontend | `features/timesheets/components/TimesheetFileUpload/*`, `services/timesheetService.ts` (upload, result, template, CSV), `BookHours.tsx` (mount it), `types/api.ts` |
| Templates | `templates/README.md` |
| Tests | new `TimesheetFileApiTest` (17), `TimesheetFileBuilder` (test sources; builds .xlsx/.csv), `TimesheetTemplateTest` (committed template header = reader's columns); `BookHoursFixture` (WP-000461 with Cutting 100 h and E1021 assigned); `TimesheetFileUpload.test.tsx` |
| Docs | data model §13.2.3 (final row-outcome list, the in-approval text), plan, REVIEW.md, `intent/README.md` |
Not changed: database (V2 already has the tables), BPMN, DMN.

## Order of work
1. Approve; fast-forward the branch to 68ca3a5; commit this plan (`Approved`); regenerate `intent/README.md`. No Jira transition without asking (Q6).
2. Contract; `TimesheetFileBuilder`; fixture extension; the 17 tests; run, show red (404 "No static resource api/v1/timesheet-files").
3. Backend: reader, repository, the source argument on `HourLineService.add`, service, controller, CSV converter, 413 advice, security. Green: the class, then `BookHoursApiTest`, `HoursBudgetApiTest`, `AssignmentFileApiTest` (413 advice touches it).
4. Frontend tests first (red), then the upload section; lint, format, test, build. `templates/README.md`; docs.
5. Full `./mvnw -B verify`; local stack (Proof); `qa-reviewer`; fixes; REVIEW.md; plan Closed; push, PR and Jira comment only when asked.

## Scenario-to-test table (`TimesheetFileApiTest`; clock 18 Nov 2026 Asia/Riyadh; Background by `BookHoursFixture`: WP-000457 Cutting 300 h / Welding 200 h with E1021, E1099; WP-000461 Cutting 100 h with E1021; files built by `TimesheetFileBuilder`, **never the demo files**)
Expected red for all: 404 "No static resource api/v1/timesheet-files" (no path yet).
| # | Scenario | Test | Asserts |
|---|---|---|---|
| 1 | A valid file saves every row | `BPA52_validFileSavesAllRows` | 201; 3 rows "Saved"; 3 Draft lines, entry_source Upload, `timesheet_file_id` set; one `timesheet_file` with sha256 |
| 2 | One file covers two Work Packs | `BPA52_oneFileCoversTwoWorkPacks` | both rows "Saved" (WP-000457 and WP-000461) |
| 3 | Bad rows rejected, good rows saved (E2-D4) | `BPA52_badRowsAreRejectedGoodRowsSaved` | outcomes in order: Saved, "Work Pack not found", "Worker not assigned to this Work Pack", "Phase Finishing is not on this Work Pack", "Work date cannot be in the future", "Hours must be a number"; only row 1's line exists |
| 4 | Existing line not overwritten | `BPA52_existingLineIsNotOverwritten` | outcome "Hours already booked for E1021 on 16 Nov 2026 for phase Cutting"; line still 8 h |
| 5 | Same key twice: second rejected | `BPA52_sameKeyTwiceInFileSecondRejected` | row 1 Saved (4 h), row 2 the duplicate text |
| 6 | Row over the phase budget (BPA-51) | `BPA52_rowOverPhaseBudgetIsRejected` | WP-000461 Cutting at 96 h; +5 → "Phase Cutting budget of 100 h would be exceeded" |
| 7 | Rows applied in file order | `BPA52_rowsAppliedInFileOrder` | E1021 8 h on 17 Nov; row 1 WP-000457 Welding 6 Saved; row 2 WP-000461 Cutting 3 → "Worker E1021 would exceed 16 hours on 17 Nov 2026" |
| 8 | Outline: required columns (5 rows) | `BPA52_requiredColumnsChecked` | one file per Outline row (or one file of 5 rows), each the Outline's text |
| 9 | A CSV file is accepted | `BPA52_csvFileIsAccepted` | the rows of #1 as `.csv` → 3 Saved |
| 10 | Rows for a Work Pack in approval (BPA-53) | `BPA52_rowsForWorkPackInApprovalAreRejected` | seeded per Q4; outcome "Hours for WP-000457 are in approval; book them after the decision" |
| 11 | Remark over 250 | `BPA52_remarkOver250IsRejected` | "Remarks can be at most 250 characters" |
| 12 | File above 5 MB (ASSUMPTION) | `BPA52_fileAbove5MbIsRefused` | 6 MB upload → 413 "File is larger than 5 MB"; no `timesheet_file` row |
| 13 | Unreadable file | `BPA52_unreadableFileIsRejected` | text bytes named `notes.xlsx` → file outcome "File cannot be read"; no line |
| 14 | A file with no rows | `BPA52_emptyFileIsRejected` | header-only file → "File has no rows" |
| 15 | The result can be downloaded | `BPA52_resultCanBeDownloaded` | GET `/{id}/result` 200: every row, raw values, outcome; with `Accept: text/csv` the same as CSV |
| 16 | Other personas cannot upload | `BPA52_otherPersonasCannotUpload` | timekeeper → 403; no `timesheet_file` row |
| 17 | A Production Team member can upload (C-E2-5) | `BPA52_aProductionTeamMemberCanAlsoUploadTheTimeSheet` | a file like `timesheet-demo-good.xlsx` but for WP-000457, built by the builder (tests never use demo files) → Draft lines `created_by` production-team |
Extra (not scenarios): `TimesheetTemplateTest` (committed template's headers equal the reader's); a "File layout not recognised" test; React tests: upload shows the row table, a file outcome, the server's 413/403 message, template and result download links.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| 17 tests passing in CI | `./mvnw -B -Dtest=TimesheetFileApiTest test` (17 run, 0 failures); `gh pr checks <n>` |
| Template in `templates/`; endpoint in openapi; screen on `UserTask_BookHours` | `git ls-files templates/timesheet-upload-template.xlsx`; `npx --yes @redocly/cli@2.62.1 lint api/openapi.yaml`; `cd frontend && npm test -- TimesheetFileUpload && npm run build` |
| Process runs end to end in the local stack | rebuild `bpa-app` and `frontend` (`docker build ...`, `docker compose up -d --no-build bpa-app frontend`); copy `timesheet-demo-good.xlsx` with WP-000001 replaced by an Assigned Work Pack's number and its Workers (Q2); upload it in the portal as supervisor (Book hours → Upload time sheet): the result table shows "Saved" rows; `psql -c "select entry_source, status, timesheet_file_id from bpa.digital_timesheet_line ..."` = Upload, Draft, set; "Download result" gives the CSV; "Download template" gives the template |
| BPMN and DMN match deployed | no model change; `curl .../v2/process-definitions/<key>/xml` diff unchanged |
| Open questions resolved or logged | Questions below, as Jira comments (shown first) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-52.md` |
| Demoed at sprint review | Human gap |

## Risks
- **"In approval" cannot happen yet** in real use: nothing writes `hours_submission` until BPA-53 builds `ServiceTask_SubmitHours`; the rule is proven only with seeded data.
- **Partial saves are by design** (E2-D4); a crash mid-file leaves earlier rows saved and the file's later rows unlogged (POC, accepted; each row is its own transaction, as the form).
- **CSV parsing** is new code (no CSV library today): ASSUMPTION rules (comma, UTF-8, quotes) until STAR's template arrives (comment 10541).
- **The 413 advice is global**: the assignment upload gets the same 413 text; its tests must stay green.
- **Size**: 17 scenarios + CSV + 413 + screen section at 5 points.

## Questions
1. **Header case.** The Gherkin writes "Workpack Number / Work Date"; the template and comment 10488 write "Workpack number / Work date". Recommend: match headers trimmed and case-insensitive, in any order, as the assignment reader already does (`templates/README.md`). Agree?
2. **WP-000001 in the demos.** It is not seed data: it is the first Work Pack a fresh database creates (no `INSERT` in `R__seed_reference_data.sql`), and its Workers depend on the assignment uploaded. Recommend: keep the demos as staged and document in `templates/README.md`, as for the assignment demos, "replace WP-000001 with an Assigned Work Pack and its Workers before showing". (The local stack was down at planning time, so its data was not checked.)
3. **Template URL.** Comment 10488: `GET /api/v1/timesheet-files/template`; you mention data model §7 naming a served `templates/` path (I found no such line). The assignment template already uses the endpoint pattern (`GET /api/v1/assignment-files/template`, from the classpath copy of `templates/`). Recommend the endpoint. Agree?
4. **"In approval" detection and seeding (ASSUMPTION).** Recommend: a Work Pack is in approval when it has an `hours_submission` with `stage` in (`Timekeeper`, `ProductionSupervisor`, `Ppc`) (E2-D7: the approval stage is held on the submission). The test inserts one `hours_submission` (stage `Timekeeper`, test-only process key, round 1) for WP-000457; the Gherkin's "hours wait at UserTask_ValidateHours" is that stage. Alternative: any line of the Work Pack with status Submitted. The **form** needs the same rule (Jira decision d: "same rule as the form, BPA-53"): BPA-52 puts the check in one shared place but calls it from the upload only; BPA-53 adds it to the form. Agree?
5. **CBS-closed and leave rows.** `cbsClosed` and `workerOnLeave` stay false (BPA-69, BPA-75), exactly as at save, since the upload calls the same code.
6. **Jira.** BPA-52 is In Progress (since 18 Nov 11:33); its Development panel shows a repository link, not a merged PR. I will ask before any transition.
7. **Two texts differ from the form.** Upload: "Worker not assigned to this Work Pack" (Gherkin) and "Time sheet is Cancelled" (data model §13.2.3); form: "Worker <badge> is not assigned to <WP>" and "Time sheet for <badge> on <WP> is Cancelled". Recommend the upload uses its own texts for these two (checked before the shared rules), everything else through the form's code. Agree?
8. **413 for all uploads.** The advice maps the Spring limit for every multipart call, so the assignment upload also answers 413 "File is larger than 5 MB". OK?
9. **ASSUMPTIONs to comment in Jira** (after a yes): the CSV rules, an unknown extension read as "File cannot be read", the 5000-row cap and "File has too many rows", Excel date cells accepted, "Upload not found" (404 text), the in-approval detection.

## Human-gap log
- Template and demo files staged from the Epic 2 draft pack by Ahsan via Claude before the plan (commit 68ca3a5), a departure from ADR 0015.
- The local branch was behind `origin/BPA-52-upload-timesheet-file` at planning time (68ca3a5 only on the remote).
- Plan written in plan mode; the local stack was not running, so the live data (WP-000001) was not checked.
