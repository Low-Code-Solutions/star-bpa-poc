# Plan: BPA-46 Draft the full api/openapi.yaml contract (09 S20, §4 item 4)
Jira: BPA-46 (Task, epic BPA-3)
Story last updated: 2026-11-02T19:03:57.272+0400
Status: Closed (2026-11-04)
Approver: Faraz (Product Owner and Architect); Ahsan (Build Lead) reviews and merges
Branch: BPA-46-openapi-contract, from main fdef8d1


## Context
Sprint 2 stories (BPA-26 first, then BPA-6…) must build against one reviewed contract instead of each
adding its own slice. BPA-46 drafts every 09 S20 endpoint into `api/openapi.yaml` (OpenAPI 3.1), with
error texts taken from the stories (L12). It is contract only: no endpoint is implemented here.

## Readiness check (Task, light)
- Sources read: Jira BPA-46; AC and SOR Notes of BPA-6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 22, 23, 26
  (all Ready); spec.md (Accepted, delivery item 5); 09 S14, S20, S21, L12; ADR 0011; data-model v0.6
  (§3.3, 3.5–3.7, 3.11, 6.3); current `api/openapi.yaml`, controllers, pom generator config, AuthApiTest.
- No Gherkin of its own; proof is lint + build + generated types (BPA-46 "Done when").
- Disagreements found (reported, not resolved silently): Q1–Q4 below.

## 1. Endpoint inventory
Prefix `/api/v1`. Every response field maps to a data-model column in camelCase; `→` marks a join.
Keys from Camunda (task, process instance) are `string` (int64 exceeds JS number precision).

| # | Method, path | Source | Request → response fields (column) | Errors (story text) |
|---|---|---|---|---|
| 1 | POST auth/login | BPA-43, S5 | kept as is | kept |
| 2 | POST auth/logout | BPA-43 | kept | kept |
| 3 | GET me | BPA-43, S6 | kept | kept |
| 4 | POST workpacks | BPA-6, D52, S20 | no body (Work Pack data comes via the Create Work Pack task, BPA-6 BPMN). 201: processInstanceKey **(x-assumption)** | 403 (BPA-6, no text) |
| 5 | GET tasks | BPA-26, S14 | TaskSummary[]: key, name, elementId, processInstanceKey, workpackNumber (process var), creationDate | 401 |
| 6 | GET tasks/{key} | BPA-26 | Task: TaskSummary + variables (allow-listed, object) + validationMessages (string[]) | 404 "Task not found"; 403 "You are not allowed to work on this task" |
| 7 | POST tasks/{key}/complete | BPA-26, BPA-9, S14 | {variables: object}; 204 | 400 "Variable {name} is not allowed for this task"; 400 "Reason is required" (BPA-9); 403 as #6; 404 "Task not found"; 409 "Task is already completed" |
| 8 | GET activities | S20, BPA-6 (D17) | activityRef, activityName, wbsRef, projectRef (→wbs.project_ref), jobCodeRef (→project_job_code), facCrpo, facType, facName, discipline, baselineStart, baselineEnd, budgetedUnits (nullable last seven) | 401 |
| 9 | GET drawings | S20, BPA-6 | drawingRef, title | 401 |
| 10 | GET materials | S20, BPA-6 | materialRef, description | 401 |
| 11 | GET departments | S20, BPA-6 | departmentRef, name | 401 |
| 12 | GET workpacks/{workpackNumber}/look-ahead | S20, BPA-11 | workpackNumber, drawingRef (lookahead_item.design_dwg_ref), shopDrgMarkPlanDate, assemblyPlanDate, weldMapPlanDate (all null when no row: React shows "No Look-Ahead plan dates for …") | 404 "Work Pack not found" |
| 13 | GET hour-journal-headers?workpackNumber= | BPA-7, D22 | [workpackNumber, estimatedManhours, locked] | 404 "Work Pack not found"; 403 |
| 14 | GET workpacks/{workpackNumber}/print | BPA-10, S4 | 200 application/pdf, Content-Disposition `inline; filename="<WP>.pdf"` | 404 "Workpack print not available"; 403 |
| 15 | POST assignment-files | BPA-12, 13, 22, D58 | multipart `file`; 201 AssignmentFileResult: id, fileName, receivedAt, outcome (file-level, nullable), rows[rowNumber (nullable), workpackNumber, badgeNo, workerName (→worker.name), outcome, wpOutcome] | 403 |
| 16 | GET assignment-files/{id}/result | BPA-12 | same AssignmentFileResult | 404 **(x-assumption)** |
| 17 | GET hour-journal-lines?workpackNumber= | BPA-14, D39 | [employeeRef (→worker), role, designation, estimatedHours, status] | 404 "Work Pack not found"; 403 |
| 18 | GET digital-timesheets?workpackNumber= | BPA-15 | [employeeRef, workpackNumber, projectRef, wbsRef, activityRef, cbsCodeRef, costCentreRef, submissionStatus] | 404 "Work Pack not found"; 403 |
| 19 | POST drawing-revisions | BPA-16, S28 | {drawingRef, revision, status enum, registeredAt date-time, supersededRevision?}; 201; `security: []` | 200 "Revision already received"; 404 "Drawing not found"; 400 "Unknown drawing status" |
| 20 | GET me/notifications | BPA-16, D44 | [workpackNumber (→workpack), drawingRef, revision, sentAt] (InSystem channel only) | 401 |

Enums from data-model §7.1, used as schema `enum` where a field carries them: drawing status,
hour_journal_line status, submission_status (as built; phase, workpack status and channel appear in no
response field, so they get no schema, review Low 2). Row/file outcomes from BPA-12/13/22 are documented as an enum-like list
in the description, not a schema `enum` (several carry parameters).

## 2. Error messages: extraction and the x-assumption list
**Method.** From the Jira AC and SOR Notes saved this session (14 stories), take every quoted string in
a `Then … response is <code> with "…"` / `message "…"` step or an `API:` line of the SOR Notes. Those
are HTTP problem-detail texts; each goes in the YAML as `example` + `description` citing the story.
Strings that a story shows on a task screen (`validationMessages`, e.g. BPA-6 "Drawing is required",
BPA-11 "Date cannot be earlier than …") or as a file-result row outcome are not HTTP errors; they are
listed in the `Task.validationMessages` and `AssignmentFileRow.outcome` descriptions with their story.
A script check in the PR greps the YAML's quoted texts against the saved Jira text and lists any not
found; those must carry `x-assumption: true`.

**x-assumption list (no story states them), for Khitaba:**
1. POST workpacks 201 body (`processInstanceKey`); and 503 "The process engine is not available right now;
   try again later" on every call that reaches Camunda (start, task API, drawing-revisions). *Built:
   503 added to the task API and drawing-revisions too.*
2. 403 text on non-task endpoints (BPA-6, 7, 10, 12, 14, 15 give only the code): "Your groups do not allow this call, or the CSRF token is missing" (the SecurityConfig text, as built after review Medium 2).
3. 400 for a missing or malformed `workpackNumber` query/path value: "Workpack number must match WP-######"
   (one text for both cases, as built).
4. GET workpacks/{n}/print for an unknown Work Pack: reuse "Work Pack not found" (BPA-10 states only the not-scheduled case).
5. GET assignment-files/{id}/result, unknown id: "Assignment file not found".
6. POST assignment-files with no `file` part: "File is required" (an unreadable file is a 200/201 result line per BPA-12).
7. POST drawing-revisions missing a required field: "drawingRef, revision, status and registeredAt are required".
8. GET tasks/{key} for a task outside the user's groups: reuse the BPA-26 403 text (BPA-26 states it for complete only);
   and POST tasks/{key}/complete for an unknown task: reuse "Task not found" (BPA-26 states it for GET only).
13. *Added at review:* POST drawing-revisions is CSRF-exempt (no session, S28).
9. 401 texts: "Wrong username or password" and "Login is not available right now; try again later" kept
   from BPA-43 (a Task, not a story); "Not logged in or the session has expired" new.
10. POST assignment-files success code 201; the CSV layout and file name of the file result (Q3: JSON and CSV).
11. *Added while building:* groups for endpoints whose story names none. The reference lists (#8–12) are
    open to every logged-in user; the file result (#16) is `production-team` only, as for the upload.
12. *Added while building:* look-ahead with no Look-Ahead row answers 200 with the three dates absent,
    not 404 (BPA-11 states only the screen text).

**Message check (scratchpad script, output in the PR):** 20 `detail`/`message` texts in the YAML;
11 found verbatim in the Jira text, 9 marked x-assumption, 0 unmarked.

## 3. What the regenerated interfaces break, and how the build stays green
Generator: `spring`, `interfaceOnly`, `skipDefaultInterface: true`, `useTags: true` (pom.xml). So each
tag is one interface, and every operation in it is abstract.
- **Break 1: WorkpacksApi.** Removing the placeholder `StartWorkPackRequest` body changes
  `startWorkPack(Map)` to `startWorkPack()`. Fix: change the one signature in `WorkPackController`;
  body still answers 501 (BPA-6 builds it). `AuthApiTest.BPA43_*` posts `{}`; with no request body
  declared Spring ignores it, so 403/501 stay as they are. No test edited.
- **Break 2: operations added to an existing tag.** `me/notifications` under `me` would make
  `MeController` fail to compile; look-ahead or print under `workpacks` would break `WorkPackController`.
  Fix: one tag per implementing story, so new operations land in new interfaces nobody implements yet:
  `tasks`, `reference` (#8–11), `look-ahead` (#12), `hour-journal-headers` (#13),
  `hour-journal-lines` (#17), `prints`, `assignment-files`, `timesheets`, `drawing-revisions`,
  `notifications` (split as built after review Medium 3, so BPA-7 and BPA-14, and BPA-11 and the
  unowned lists, never share an interface). `auth`, `me`, `workpacks` keep exactly their current operations.
  Each later story implements a whole interface, so `skipDefaultInterface` keeps forcing completeness.
- **AuthController, MeController:** `LoginRequest`, `Me` unchanged → untouched.
- **Unimplemented operations** have no controller, so they answer 404 until their story lands; noted
  in `info.description`. SecurityConfig is not changed here (drawing-revisions `security: []` is
  implemented by BPA-16).
- **Frontend:** `npm run gen:api` regenerates `schema.d.ts`; App.tsx only uses login/logout/me, so
  `tsc --noEmit` stays green.
- **Proof run locally before PR:** `npx @redocly/cli@2.62.1 lint api/openapi.yaml`, `./mvnw -B verify`
  (backend), `npm test && npm run build` (frontend). Same commands as CI `backend.yml`.

## 4. One PR, two commit groups
One PR (one Task, one review), in commit groups that each build green:
1. Shared components (Problem, security, enums, ids) + task API (#5–7) + reference lists (#8–12) +
   POST workpacks (#4) + the WorkPackController signature change. Enough for BPA-26 and BPA-6 planning.
2. Story endpoints #13–20.
Then plan Closed. If review of group 2 stalls, group 1 can be split off as its own PR without rework.

## Files that change
- `api/openapi.yaml` (main change, ~900–1,200 lines)
- `backend/src/main/java/com/lcs/star/bpa/api/WorkPackController.java` (signature only)
- `intent/BPA-3-work-pack-assignment/plans/BPA-46.md`, `REVIEW.md` (qa-reviewer)

## Order of work
Plan commit (Approved) → group 1 → lint/build/test → group 2 → lint/build/test → message-check script
→ qa-reviewer → PR → Jira comment with the x-assumption list (shown to you first) → plan Closed.

## Risks
- **Riskiest step: the task API shape (#5–7).** Five user-task stories and BPA-26 build on it: string
  keys, generic `variables`, `validationMessages` in GET tasks/{key}, 204 on complete. A wrong choice
  costs rework in BPA-6, 8, 9, 11, 23. Mitigation: it lands in group 1 and is checked against the
  BPA-25 spike findings (check 4: `validationMessages` readable after WORKPACK_INVALID).
- Variable names differ between sources (Q2).
- Generated `@Pattern` (WorkpackNumber) and Java enums (drawing status) make Spring reject bad input with
  its default 400 before a controller runs. BPA-16 ("Unknown drawing status") and the stories using the
  x-assumption 400 text need an exception handler that writes those texts (review Low 4).
- 401/403 texts: the contract now uses the live SecurityConfig texts (review Medium 2), so stories inherit
  them without change.

## Review (qa-reviewer, 2 Nov 2026, at 198cca5)
1 High, 5 Medium, 9 Low. Fixed in the review-fix commit: High (Confirm/decline reasons are the process
variable `confirmMessage`, per the BPMN; added to `Task`, removed from `validationMessages`); Medium 2
(401/403 texts = SecurityConfig); Medium 3 (tags split); Medium 4 (drawing-revisions documented as
CSRF-exempt, x-assumption, BPA-16 implements); Medium 5 (`x-assumption` on the reused TaskForbidden and
TaskNotFound texts); Low: `x-assumption: true` with the reason in `x-assumption-note`; required fields
per data-model NOT NULL; invented orderings removed. Medium 1 answered by the PO (Q1, no body). Lows on
`multipleOf`, the duplicate TaskKey pattern, Jira status Backlog. See REVIEW.md.

## Proof
Redocly lint clean; `./mvnw -B verify` green with all existing tests unchanged; frontend test + build
green; message-check script output in the PR; every operation has a `description` citing its story or 09 row.

## Questions (for Faraz), answered 2 Nov 2026 after review
- Answers (Faraz, PO, in the session): Q1 no body (as built); Q2 data-model names (as built); Q3 JSON and
  CSV, by Accept header (contract changed: `text/csv` on GET assignment-files/{id}/result; the generated
  method still returns `AssignmentFileResult`, so BPA-12 adds a `text/csv` HttpMessageConverter; CSV
  layout and file name x-assumption); Q4 BPA-6 builds the four lists, BPA-11 builds look-ahead
  (proposed to Jira as comments on BPA-6 and BPA-11, shown to Faraz first). Q1's scope wording is
  proposed as a comment on BPA-46; the Description is not edited.

Asked:
- Q1. POST workpacks: BPA-46 says "complete the request body from BPA-6", but BPA-6 starts the process
  and takes the data through UserTask_CreateWorkPack. Plan: no body, 201 with processInstanceKey (x-assumption). Agree?
- Q2. Variable names: form-specs use `dateToComplete`, `plannedStart`, `plannedEnd`; data-model columns
  give `completionDate`, `plannedStartDate`, `plannedEndDate`. BPA-46 says camelCase from data-model.
  Plan: data-model names, documented in the task descriptions, form-spec divergence logged for BPA-26.
- Q3. "Downloadable" result (BPA-12): JSON only, with React building the download, or also `text/csv`?
- Q4. Reference lists (#8–12) are contract-only here; no story owns their implementation. Who builds them before BPA-6?

## Chosen not to do
- No implementation, controllers, SecurityConfig or tests for new endpoints (each story builds its own).
- No typed per-task variable schemas: the allow-list lives in config (S14); typing it in the contract
  would duplicate it and generate `oneOf` code the Spring generator handles badly.
- No change to generator settings (`skipDefaultInterface` stays true).
- No edits to Jira fields; no new endpoints beyond S20 and the stories (e.g. no GET workpacks/{n}).

## Human-gap log
- Atlassian MCP dropped mid-session; Faraz reconnected it before Jira was read.
- Planning discussion (2 Nov 2026), outside BPA-46's scope, for Faraz to decide: a shared test helper that
  puts a Work Pack at a chosen process step (rows + process instance started at an element) would let
  stories be tested out of order and let BPA-16 run in parallel with BPA-26. Proposed as a new Jira Task;
  not created.
- Q1–Q4 not answered at approval; the plan's defaults are applied (Q1 no body; Q2 data-model names;
  Q3 JSON only, later answered JSON and CSV). Q4 does not change the contract.
