# Plan: BPA-12 Production Team can submit a Worker assignment file for a scheduled Work Pack
Jira: BPA-12 (Story, epic BPA-3)
Story last updated: 2026-11-02T16:13:11.156+0400
Status: Approved (Ahsan, Build Lead, 2026-11-06)
Approver: Ahsan (Build Lead)
Branch: BPA-12-assignment-file-upload, from main 4fe2409


## Context
Since BPA-10, a printed Work Pack is `AwaitingFile` and its instance waits at
`IntermediateCatchEvent_AssignmentFileReceived`. Nothing can reach it: there is no upload, no Excel handling and no template.
BPA-12 builds the intake (09 S18, S19 as D58, ADR 0009):
- The Production Team uploads the Worker assignment `.xlsx` in the portal.
- The backend records the upload and every row (D49), runs the file-level checks, and routes each Work Pack group in the file
  by its state (D50, D61, D62).
- For a Work Pack in `AwaitingFile`, it sends `AssignmentFileReceived` with the synchronous correlate call (C3), guarded per
  (sha256, Work Pack) against duplicates (D73).
- The result is shown on screen and can be downloaded (CSV).
- Row validation itself is BPA-22. Until then, `ServiceTask_ValidateWorkerAssignment` raises an incident "built by BPA-22", as
  BPA-6 did for the corrections step.

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met |
| Gherkin, testable, unhappy paths | Met: 15 scenarios (1 outline, 2 rows) |
| BPMN IDs exist | Met: Gateway_MergeAssignment, IntermediateCatchEvent_AssignmentFileReceived (message `AssignmentFileReceived`, correlation key `=workpackNumber`), ServiceTask_ValidateWorkerAssignment (`validate-worker-assignment`) |
| SOR per field | Met: assignment_file, assignment_file_row, published_message (POC database, V1 as data model v0.5 §5.1, 5.2, 5.12); worker (myPeople copy); uploaded_by (Camunda identity) |
| DMN complete | n/a |
| No blocking question | Met (D61, D62, D70, D73, B1, B3, C2, C3 decided) |
| ≤ 8 points | 8, "at the limit" (PO, 29 Oct). **My estimate is about 12 to 13** against the stories built so far. A split was proposed (6 Nov); **Ahsan decided to keep it whole**. Recorded here, and I'll stop and tell you if the build overruns further |
| PO approved | Faraz, 28 Oct. **Re-confirmation after the 29 Oct change to the acceptance criteria: not found in Jira** (comments 10406 and 10415 request it; the last status change is 28 Oct 13:30). Ahsan reports Faraz re-confirmed it outside Jira (6 Nov); a Jira comment will record that (text shown first) |

Notes:
- The Jira Description and DoD already read D73 and list 15 test names, so the "needs a yes to edit" item in comment 10406 is
  done.
- The seed has the Workers the Background names (E1021, E1099; E9999 does not exist).
- The AC's Work Pack numbers are illustrative: tests create their own Work Packs and use the numbers they get.

## Design: file handling (Apache POI; 09 S18, D59)
- **Dependency:** `org.apache.poi:poi-ooxml`, pinned to the latest release (checked on Maven Central at build time). Only
  `.xlsx` is read (`XSSFWorkbook`), so a renamed `.csv` or an old `.xls` is "File cannot be read". POI's zip-bomb guard stays on;
  an upload over 5 MB is refused by Spring (`spring.servlet.multipart.max-file-size`, ASSUMPTION).
- **`assignment/AssignmentFileReader`** (pure, unit-tested). The first sheet; row 1 is the header. Columns are matched by
  name, trimmed and case-insensitive, in any order (ASSUMPTION):
  - Workpack number, Badge No and Estimated hours are required; without them → "File layout not recognised";
  - Employee name is ignored; Remarks is optional;
  - data rows are read as displayed text (`DataFormatter`), raw (data model §5.2); blank rows are skipped;
  - no data rows → "File has no rows"; anything POI cannot open → "File cannot be read".
- **Template `templates/worker-assignment-template.xlsx`** (S18, L6, ADR 0009):
  - sheet "Assignment" with the five headers in D59 order;
  - a "README" sheet marking the layout as an ASSUMPTION (D33 rule 1);
  - `templates/README.md` says the same.
  - Written once with the same POI code the tests use (`AssignmentFileBuilder.writeTemplate`), and checked by
    `AssignmentTemplateTest`, so a change to it fails a test.
- **Demo files (L20, showcase only; never used by tests):** `templates/demo/`, the 7 files of L20, built the same way and listed
  in `templates/README.md` (the three about rows show "Pending" until BPA-22). The pom keeps `templates/demo/` out of the jar.
- **`AssignmentFileBuilder`** (test scope): builds `.xlsx` bytes in memory, row by row. It also builds the broken shapes (header
  only, missing columns, wrong first sheet) and a non-xlsx (CSV text). Every test file comes from it or from `src/test/resources/`
  (S18).

## Design: upload, routing and the duplicate guard
`POST /api/v1/assignment-files` (contract exists; multipart `file`; 201 with `AssignmentFileResult`) →
`assignment/AssignmentFileService`:
1. Read the bytes, compute the sha256, and parse.
2. A file-level problem → one `assignment_file` row with that `outcome` and no rows, as the AC's "the result holds one line".
3. Otherwise group the rows by Workpack number (D33 rule 3), and decide per group, in this order:
   1. blank number → "Workpack number is required" (data model §7.3, an intake outcome);
   2. no such Work Pack → "Work Pack not found";
   3. the pair (sha256, number) is in `published_message` → "Already processed in an earlier upload" (D73; checked before the
      state, because a published Work Pack moves on to Assigning once BPA-22 exists);
   4. status not Released → "Work Pack is not Released";
   5. Released with an empty `assignment_state` (no print yet) → "Work Pack not ready for assignment yet" (D50);
   6. `Assigning` → "Assignment still in progress; try again later";
   7. `Replacing` → "Replacement already in progress";
   8. `Assigned` → replacement is BPA-13. Until it exists the rows read "Replacement is not available yet" (**ASSUMPTION text**,
      Q2; no process path reaches Assigned before BPA-15);
   9. `AwaitingFile` → claim the pair: `INSERT … ON CONFLICT DO NOTHING`. No row inserted means a concurrent upload claimed it →
      treat as already processed. Otherwise the rows start "Pending".
4. **File already processed:** if every group is "Already processed in an earlier upload", the upload gets the file-level line
   "File already processed" and no rows (D73).
5. **One transaction:** the `assignment_file` row (`uploaded_by` = session user, D63; `received_at` from the clock), every
   `assignment_file_row`, and the claims. Then commit.
6. **After the commit, per claimed group:** correlate `AssignmentFileReceived` (correlation key = Work Pack number; variables
   `assignmentFileId`; `POST /v2/messages/correlation` through a new `CamundaProcessClient.correlate`; `bpa-service` already holds
   MESSAGE CREATE).
   - Nothing correlates (404) → a compensating transaction deletes the claim and sets those rows to "Not processed: Work Pack not
     ready; resend the file" (C2, D62). The Work Pack stays `AwaitingFile`.
   - Camunda unreachable → the same compensation, plus a 503 for the request? No: the rows read "Not processed …" and the file is
     recorded (ASSUMPTION; the user resends). Q3.
7. Answer with the result rebuilt from the database (D49).
- At-most-once between the commit and the send is accepted, as in data model §5.12.

`GET /api/v1/assignment-files/{id}/result`:
- JSON or CSV, chosen by the Accept header (contract; PO decision on BPA-46 Q3).
- CSV comes from a small `HttpMessageConverter` for `AssignmentFileResult` (text/csv, RFC 4180, header row,
  `Content-Disposition: attachment; filename="<name>-result.csv"`), so the generated interface stays as it is.
- Rows in row order, synthetic rows last (BPA-13); `workerName` joined from the worker master on read, absent when not found
  (B15, D59).
- 404 "Assignment file not found".

**New contract operation (spec.md shared-contract rule; x-assumption):** `GET /api/v1/assignment-files/template` returns the
template as an `.xlsx` attachment, for the portal's "Download template" (S18).

**Security:** `/api/v1/assignment-files` and everything under it → `production-team` only, every method (S6; the BPA-10 HEAD
lesson). Anything else is 403; anonymous is 401. CSRF as usual.

## Design: the BPA-22 placeholder
`worker/ValidateWorkerAssignmentWorker` (`validate-worker-assignment`) fails every job with retries 0 and the message
"validate-worker-assignment is built by BPA-22". That is an incident, as BPA-6's corrections step did before BPA-23 (skill
`new-job-worker`, "a job type shared with a step a later story builds"). A routed instance therefore stops at
ServiceTask_ValidateWorkerAssignment with that incident, and its rows stay "Pending", as the AC says.

## Design: portal (new-task-screen conventions; there is no user task)
New feature folder `frontend/src/features/assignment-files/` (ADR 0017 layout: components, hooks, services, types), rendered by
`TasksPage` for `production-team` only:
- "Assignment file" section with:
  - a "Download template" link (GET template → blob);
  - a file input ("Assignment file", `.xlsx`) and an "Upload" button.
- Field check on screen: no file chosen → "File is required" (the contract's 400 text), no call made.
- After an upload, the result:
  - file name and time;
  - either the one file-level line, or a table: Row, Workpack number, Badge No, Worker name, Outcome, and the Work Pack outcome
    when present;
  - "Refresh result" (rows read "Pending" until BPA-22);
  - "Download result (CSV)" (Accept text/csv → blob → file).
- Services through `apiClient`; multipart through openapi-fetch's `bodySerializer`; types from `schema.d.ts`.
- React tests beside the component, named `BPA12_<scenario>` where a scenario has a screen part (screen shows the result,
  file-level line, Pending), plus the screen-only conditions.

## Scenario-to-test table
Backend: a new `AssignmentFileApiTest` on the BPA-10 pattern:
- the app on the integration Camunda with workers on, the TestClock at 30 Oct 2026 (Asia/Riyadh), `@DirtiesContext`;
- Work Packs taken the real way to the state each scenario needs: Draft (at Confirm), Released (at Schedule), or AwaitingFile
  (scheduled 10 to 12 Nov and printed, waiting at the catch event);
- the states with no path yet (Assigning, Replacing, AwaitingFile without a waiting instance) are set in the database, with a
  comment saying so.

React: `AssignmentFileUpload.test.tsx`. Unit: `AssignmentFileReaderTest`, `AssignmentTemplateTest`.

| # | Scenario | Backend test | React |
|---|---|---|---|
| 1 | File routed to its process | BPA12_fileForScheduledWorkPackIsRouted: an AwaitingFile Work Pack, rows E1021 40 and E1099 60 → 201; a `published_message` row for (sha, WP); the instance moves to ServiceTask_ValidateWorkerAssignment (with the BPA-22 incident) and has `assignmentFileId`; both rows "Pending" | BPA12_fileForScheduledWorkPackIsRouted: upload shows both rows Pending with Worker names |
| 2 | Not Released | BPA12_workPackNotReleased: a Draft Work Pack → "Work Pack is not Released", no claim | — |
| 3 | Not ready yet | BPA12_workPackNotReadyForAssignment: Released, not scheduled → "Work Pack not ready for assignment yet", no claim | — |
| 4 | Message not deliverable | BPA12_messageNotDeliverableRowsNotProcessed: AwaitingFile set on a Work Pack whose instance waits elsewhere → "Not processed: Work Pack not ready; resend the file" at once; the claim deleted; still AwaitingFile | — |
| 5 | Assigning / Replacing (2 rows) | BPA12_assignmentInProgressRejectsUpload: each state → its message, no claim | — |
| 6 | Work Pack not found | BPA12_workPackNotFound: WP-999999 → "Work Pack not found" | — |
| 7 | Several Work Packs | BPA12_fileWithSeveralWorkPacks: AwaitingFile + Draft → only the first routed; the Draft rows "Work Pack is not Released" | — |
| 8 | No rows | BPA12_fileHasNoRows: header only → one line "File has no rows", no rows stored, no claim | BPA12_fileHasNoRows: the screen shows the one line |
| 9 | Cannot be read | BPA12_fileCannotBeRead: CSV text as `.xlsx` → "File cannot be read" | — |
| 10 | Layout not recognised | BPA12_fileLayoutNotRecognised: first sheet without the three columns → "File layout not recognised" | — |
| 11 | Same file again | BPA12_sameFileAgainIsIgnored: after a routed upload, the same bytes under another name → "File already processed", recorded as its own row, no new claim, the instance unchanged | — |
| 12 | Resend of an unrouted file | BPA12_resendOfUnroutedFileIsAccepted: not deliverable first; then the Work Pack's real print brings its instance to the catch event; the same bytes again → routed, no file-level line | — |
| 13 | Duplicate only for published Work Packs | BPA12_resendIsDuplicateOnlyForPublishedWorkPacks: file with WP-A (routed) and WP-B (not deliverable); WP-B made ready; resend → WP-B routed, WP-A rows "Already processed in an earlier upload", no file-level line | — |
| 14 | Upload is recorded | BPA12_uploadIsRecorded: one assignment_file (name, sha256, uploader production-team, no outcome), 2 rows (row number, WP, Badge No, outcome); GET result JSON returns the same rows; CSV has the header and 2 lines | BPA12_uploadIsRecorded: Download result asks for text/csv |
| 15 | Other roles | BPA12_otherRolesCannotUpload: as production-planner → 403, no assignment_file row | BPA12_otherRolesCannotUpload: the section is not shown to other groups |

Non-scenario tests:
- blank Workpack number → "Workpack number is required";
- result 404 "Assignment file not found"; result and template for other groups → 403; HEAD → 403; anonymous → 401;
- upload without a file → 400 "File is required";
- the Worker name is absent for an unknown Badge No;
- the reader: header matching, blank rows, displayed text, an `.xls` refused;
- the template's layout;
- the BPA-22 incident message;
- React: no file chosen → "File is required"; Download template; Refresh result.

Stubs keep the classes compiling for the red run, as before. All 15 backend scenario tests are expected to fail first. A React
test that passes before the build is said in the PR.

## Files that change
- Backend:
  - `pom.xml` (poi-ooxml; exclude `templates/demo/` from the jar);
  - new `assignment/AssignmentFileReader.java`, `assignment/AssignmentFileService.java`,
    `persistence/AssignmentFileRepository.java`, `api/AssignmentFileController.java`, `api/AssignmentFileResultCsvConverter.java`,
    `worker/ValidateWorkerAssignmentWorker.java`;
  - `adapter/CamundaProcessClient.java` (correlate);
  - `config/SecurityConfig.java`;
  - `application.yml` (multipart size).
- Tests: new `AssignmentFileApiTest`, `AssignmentFileReaderTest`, `AssignmentTemplateTest`, `AssignmentFileBuilder`;
  `ProcessTestApplication` (exclude the worker); `TestBrowser` (a multipart POST).
- Contract: `api/openapi.yaml` (the template operation).
- Templates: `templates/worker-assignment-template.xlsx`, `templates/README.md`, `templates/demo/*.xlsx`.
- Frontend: new `features/assignment-files/*` and its tests; `pages/TasksPage/TasksPage.tsx`; `types/api.ts`; `global.css` if
  needed.
- Docs: `REVIEW.md`, this plan. No BPMN, DMN, migration or seed change.

## Order of work
1. Commit this plan (Approved). You move statuses.
2. Tests first: the builder, the reader and template tests, the 15 scenario tests and the React tests, with stubs. Run them and
   show they fail for the expected reason.
3. Backend:
   - POI and the reader;
   - repository and service;
   - correlate;
   - controller, CSV converter and security;
   - placeholder worker;
   - template and demo files.
   - Green, then `./mvnw -B verify`.
4. Frontend: services, hooks, the section, the page. `npm test`, lint, format, build.
5. Local stack (rebuild bpa-app), in the portal as production-team:
   - upload for a printed Work Pack → Pending, and the instance at ServiceTask_ValidateWorkerAssignment with the BPA-22 incident
     (Operate);
   - the same file again → "File already processed";
   - CSV download; Download template.
6. qa-reviewer → REVIEW.md → fixes → plan Closed → push → PR → wait for all CI checks green → tell you → Jira comments (text
   shown first, including Faraz's re-confirmation). You merge.

## Risks
- **Size:** above 8 points by my estimate; kept whole by your decision. If the build runs well past it, I'll stop and tell you
  rather than cut a scenario.
- **Long Backgrounds:** six scenarios need Work Packs in AwaitingFile, each through create, confirm, sign-off, schedule and print
  (about 20 s each). The test-only 10 s job lock (BPA-9) is in place.
- **The BPA-22 incident leaves instances with incidents** in the shared test Camunda; harmless, since every test uses its own
  instances.
- **Excel as an attack surface:** POI on uploaded files. Mitigations: xlsx only, 5 MB limit, POI's zip-bomb guard, raw text only
  (no formulas evaluated), production-team only.

## Proof
`./mvnw -B verify` (red first, then green), `npm test`, lint, build, the local-stack run in the portal and Operate, CI.

## Questions (for Khitaba, ASSUMPTIONs; Jira comment, text shown first)
- Q1: header matching (first sheet, names trimmed and case-insensitive, any order), blank rows skipped, `.xlsx` only, 5 MB limit.
- Q2: an upload for an `Assigned` Work Pack before BPA-13: rows "Replacement is not available yet".
- Q3: when Camunda cannot be reached during the send, the rows read "Not processed: Work Pack not ready; resend the file" (the
  claim is undone).
- Q4: `GET /api/v1/assignment-files/template` (new operation), production-team only.
- Faraz: record his re-confirmation of Ready after the 29 Oct change.

## Human-gap log
- Local Maven build uses the `subst S:` work-round and JAVA_HOME per command; `npm.cmd` in PowerShell; Docker Desktop must be
  running.
