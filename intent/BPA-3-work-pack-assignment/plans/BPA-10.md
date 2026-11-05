# Plan: BPA-10 Time & Method Analyst can print a scheduled Work Pack
Jira: BPA-10 (Story, epic BPA-3)
Story last updated: 2026-11-02T16:13:10.954+0400
Status: Approved (Ahsan, Build Lead, 2026-11-05)
Approver: Ahsan (Build Lead)
Branch: BPA-10-print-work-pack, from main ddd8df1


## Context
Since BPA-11, a scheduled Work Pack waits at `ServiceTask_GenerateWorkPackPrint` (`generate-workpack-print`), which has no
worker, and the print API is not built. BPA-10 builds:
- **The worker** (skill `new-job-worker`):
  - renders the Workpack print once, after scheduling (D4), with Thymeleaf and OpenHTMLtoPDF and an embedded font (S1; English
    only, S3);
  - stores it in `workpack_print` with its sha256 (D29), and sets `workpack.assignment_state = AwaitingFile` (D50, D61, G7);
  - takes the PDF creation date from the injected clock, so the same data renders to the same bytes (S1).
- **`GET /api/v1/workpacks/{workpackNumber}/print`** (contract already in api/openapi.yaml): `application/pdf`,
  `Content-Disposition: inline; filename="<WP>.pdf"` (S4), groups time-method-analyst, production-planner and production-team
  (ASSUMPTION in the story).
- **A place in the portal to view it** (your choice, 5 Nov): a "Workpack prints" list on the home page, under My tasks, for the
  three print groups. It shows the Work Packs that have a print, newest first, each with a "Print PDF" button. The PDF is fetched
  as a blob and shown in an iframe on the page (S4 and ADR 0004 as written; no new tab).
  - This needs a list API, which the contract lacks: **new `GET /api/v1/workpack-prints`** (x-assumption), made in this PR and
    recorded here, as the spec.md shared-contract rule says.

After the print, the token waits at `IntermediateCatchEvent_AssignmentFileReceived` (BPA-12).

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met |
| Gherkin, testable, unhappy paths | Met: 8 scenarios |
| BPMN IDs exist | Met: ServiceTask_GenerateWorkPackPrint (`generate-workpack-print`, retries 3); next IntermediateCatchEvent_AssignmentFileReceived |
| SOR per field | Met: workpack_print (POC database; V1 has UNIQUE workpack_id, bytea, sha256, generated_at); assignment_state (POC database, CHECK enum); cutting_sheet (FMS copy) |
| DMN complete | n/a |
| No blocking question | Met; PDF library decided (S1) |
| ≤ 8 points | 3. The PDF rendering and the list the portal needs make it more; said here, the estimate is not changed |
| PO approved | Met (Faraz 28 Oct); status Ready |

The seed matches the AC this time: CS-3310 on DWG-118, and no cutting sheet on DWG-121. But DWG-121's latest revision is IFI, so
its Work Pack cannot pass the release check. As in BPA-11, that test arranges an IFC revision and a reservation and restores the
seed afterwards. The seed is not changed.

## Design: rendering (S1, S3)
- Dependencies (pom):
  - `io.github.openhtmltopdf:openhtmltopdf-pdfbox`, the latest release, checked on Maven Central at build time and pinned;
  - `org.thymeleaf:thymeleaf`, version from the Spring Boot BOM; no web starter, a standalone `TemplateEngine`;
  - PDFBox comes with OpenHTMLtoPDF, and the tests use it to extract text (S1: assert text, not bytes).
- Font: DejaVu Sans (Regular and Bold, TTF) from the official DejaVu release, in `backend/src/main/resources/fonts/` with its
  licence file, embedded through `builder.useFont(...)`. S1 allows Noto Sans or DejaVu Sans; DejaVu ships as plain TTF files.
- `print/WorkPackPrintRenderer`:
  - fills `templates/print/workpack-print.html` (well-formed XHTML, English);
  - converts it to PDF;
  - then, with PDFBox, sets CreationDate and ModDate from the given instant and a document /ID derived from the content, and saves.
    The output is then the same bytes for the same data and instant: a unit test renders twice and compares.
- Print content, as in the AC:
  - STAR heading and the Workpack number;
  - activity, drawing, department; date to complete, planned start and planned end (`dd MMM yyyy`);
  - man hours per phase in the FRD order (Engineering, Cutting, Welding, Finishing), as entered (150.5 stays 150.5), and the total
    (400);
  - the material list (material coupon) with each material's phase;
  - the cutting sheets for the drawing (D30), or "No cutting sheet available";
  - a footer with the generation time (Asia/Riyadh).
  - Job card and return material voucher are out of scope (story).

## Design: worker (skill `new-job-worker`)
`worker/GenerateWorkPackPrintWorker` (`@JobWorker(type = "generate-workpack-print", autoComplete = false)`) with
`persistence/WorkPackPrintRepository`:
1. Find the Work Pack by instance key (else a fault). **Idempotent, checked first** (D29, D65, S7): a print already exists →
   change nothing, complete. That keeps the sha256 unchanged, and an assignment_state that has moved on is never reset.
2. The Work Pack must be Released and scheduled; anything else is a fault (retries, then an incident).
3. Read the print data (workpack, phases, materials, schedule, cutting sheets by drawing), render at `clock.instant()`, and
   compute the sha256 (hex).
4. In one transaction:
   - `INSERT … ON CONFLICT (workpack_id) DO NOTHING`;
   - if inserted: audit rows (workpack_id, `file` as `sha256:<hex>` per §6.1 A3, sha256, generated_at), then
     `assignment_state` null → AwaitingFile with its audit row.
5. Who: `completedBy` (the Planner's completion before this step) or `bpa-service` when missing (B12). This is a system step,
   unlike the sign-off. Complete the job.

## Design: APIs
- `api/PrintController implements PrintsApi` (generated, tag `prints`):
  - `getWorkPackPrint`:
    - an unknown Work Pack → 404 "Work Pack not found";
    - no print yet → 404 "Workpack print not available";
    - otherwise 200 with the bytes, `application/pdf` and `inline; filename="WP-000457.pdf"`;
    - a malformed number → 400 (the shared handler pattern).
  - `listWorkPackPrints`: the new operation.
- **New in api/openapi.yaml:** `GET /api/v1/workpack-prints`, tag `prints`, x-assumption. It returns the Work Packs that have a
  print, newest first: schema `WorkPackPrintSummary` = workpackNumber, drawingRef, plannedStartDate, plannedEndDate, generatedAt.
  Same three groups; no paging (POC).
- SecurityConfig: GET `/api/v1/workpacks/*/print` and GET `/api/v1/workpack-prints` → hasAnyAuthority(time-method-analyst,
  production-planner, production-team); others 403 (the contract's Forbidden).

## Design: portal (a list, not a task screen)
`frontend/src/features/workpacks/components/WorkPackPrints/` rendered by `TasksPage` under the task list, only when the user is
in one of the three groups:
- A "Workpack prints" section: a "Refresh" button, then rows of Work Pack number, drawing and planned start to end, each with a
  "Print PDF" button (accessible name includes the number). "No Workpack prints yet" when empty.
- Print PDF → `getWorkPackPrint` (openapi-fetch, `parseAs: 'blob'`) → `URL.createObjectURL` → an iframe titled
  "Workpack print WP-000457" below the list; the previous object URL is revoked. A 404 shows "Workpack print not available";
  any other failure shows a short error.
- Service functions in `features/workpacks/services/workpackService.ts`; a `useWorkPackPrints` hook (TanStack Query); types in
  `types/api.ts`. Existing tokens and classes (`register`, `notice`); frontend-design guidance as in BPA-23.

## Scenario-to-test table
Backend: a new `PrintApiTest` on the BPA-11 pattern:
- the app on the integration Camunda with workers on, the TestClock at 21 Oct, `@DirtiesContext`;
- a Background helper that schedules a Work Pack the real way: create, confirm, release check Ready, sign-off approved, schedule
  03 to 06 Nov;
- arrange/restore of seed rows for DWG-121.

Unit: `WorkPackPrintRendererTest`. React: `WorkPackPrints.test.tsx`.

| # | Scenario | Test | Red first? |
|---|---|---|---|
| 1 | Print generated on scheduling | BPA10_printIsGeneratedOnScheduling: WP DWG-118, Cutting 150 / Welding 250, MAT-PIPE-06 (Cutting), MAT-FLG-02 (Welding), due 06 Nov; scheduled 03 to 06 Nov → GET print 200 application/pdf; extracted text shows the number, both phases and hours, total 400, 06 Nov 2026, 03 Nov 2026, 06 Nov 2026, DWG-118, both materials with their phases, CS-3310; the stored sha256 equals the file's | yes |
| 2 | No print before scheduling | BPA10_noPrintBeforeScheduling: a Released Work Pack at the Schedule task → 404 "Workpack print not available" | yes |
| 3 | Decimal man hours as entered | BPA10_decimalManHoursPrintAsEntered: Cutting 150.5 → the text shows 150.5 (not 150.50) | yes |
| 4 | No cutting sheet | BPA10_printWithoutCuttingSheet: DWG-121 (arranged to pass the release check; no cutting sheet in the seed) → the print exists and shows "No cutting sheet available" | yes |
| 5 | Re-run, no second print | BPA10_rerunDoesNotCreateSecondPrint: after the print, `handle` twice with a mocked JobClient → one row, sha256 unchanged, both jobs complete, no new audit rows | yes |
| 6 | Opens the Work Pack for assignment | BPA10_printOpensWorkPackForAssignment: assignment_state null before, AwaitingFile after (audit row); the instance waits at IntermediateCatchEvent_AssignmentFileReceived | yes |
| 7 | Served inline | BPA10_printIsServedInline: as time-method-analyst → 200, `application/pdf`, `Content-Disposition: inline; filename="<WP>.pdf"`, body starts with `%PDF` | yes |
| 8 | Other roles | BPA10_otherRolesCannotReadPrint: as project-engineer → 403, no PDF in the body | yes (no rule; the path does not exist yet) |

Non-scenario tests:
- production-planner and production-team can read the print, an anonymous call gets 401, an unknown Work Pack 404 "Work Pack not
  found", a malformed number 400;
- the list: printed Work Packs only, newest first, the fields; 403 for project-engineer;
- the renderer: the same data and instant give the same bytes, another instant changes the CreationDate, and the font is
  embedded (PDFBox);
- React:
  - the list rows and the empty text;
  - Print PDF shows the iframe with a blob URL;
  - a 404 shows "Workpack print not available";
  - the panel is not rendered for project-engineer.

Stubs (worker, renderer, controller methods) keep the classes compiling for the red run, as before.

## Files that change
- Backend:
  - `pom.xml`;
  - new `print/WorkPackPrintRenderer.java`, `print/PrintData.java`, `worker/GenerateWorkPackPrintWorker.java`,
    `persistence/WorkPackPrintRepository.java`, `api/PrintController.java`;
  - `config/SecurityConfig.java`;
  - resources `templates/print/workpack-print.html`, `fonts/DejaVuSans*.ttf` and the licence.
- Tests: new `PrintApiTest`, `WorkPackPrintRendererTest`; `ProcessTestApplication` (exclude the worker).
- Contract: `api/openapi.yaml` (the new list operation and schema).
- Frontend:
  - new `features/workpacks/components/WorkPackPrints/*` and its test;
  - `services/workpackService.ts`, a hook, `types/api.ts`, `pages/TasksPage/TasksPage.tsx`, `features/workpacks/index.ts`;
  - `global.css` only if needed.
- Docs: `REVIEW.md`, this plan. No BPMN, DMN, migration or seed change.

## Order of work
1. Commit this plan (Approved). You move statuses.
2. Tests first, with stubs; run them and show they fail for the expected reason.
3. Backend:
   - dependencies and font;
   - renderer and its unit test;
   - repository and worker;
   - controller, contract and security.
   - Green, then `./mvnw -B verify`.
4. Frontend: service, hook, panel, page. `npm test`, lint, format, build.
5. Local stack (rebuild bpa-app):
   - schedule a Work Pack and open the PDF from the portal list as time-method-analyst;
   - check the iframe, headers and sha256, and that the instance waits at the assignment-file message.
6. qa-reviewer → REVIEW.md → fixes → plan Closed → push → PR → wait for all CI checks green → tell you → Jira comments (text
   shown first). You merge.

## Risks
- **Determinism of the PDF bytes** (PDF /ID, dates, font subset tags): handled by the PDFBox post-process and proved by the unit
  test. If OpenHTMLtoPDF adds something else that varies, I'll find it in that test.
- **The font file:** downloaded from the DejaVu release and committed with its licence (about 700 KB per face).
- **A new contract operation** for the list. Small; recorded here and in the PR.
- **The long Background** (create, confirm, sign-off, schedule) per test.

## Proof
`./mvnw -B verify` (red first, then green), `npm test`, lint, build, the local-stack run in the portal, CI.

## Questions (for Khitaba, ASSUMPTIONs)
- Q1: `GET /api/v1/workpack-prints` (the list) and its groups.
- Q2: who the print is recorded as (`completedBy` of the Planner's completion, else `bpa-service`).
- Q3: the print layout beyond the AC's fields (activity, department, a generation-time footer).

## Human-gap log
- Local Maven build uses the `subst S:` work-round and JAVA_HOME per command; `npm.cmd` in PowerShell; Docker Desktop must be
  running.
