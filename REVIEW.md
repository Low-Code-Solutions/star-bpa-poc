# Review instructions: STAR BPA POC

Used by the `qa-reviewer` subagent before a PR is opened, and by any reviewer after. Findings never
approve or block a PR on their own; Ahsan approves and merges.

Inputs for every review: the diff against `main`, the Jira item (read with the Atlassian MCP), the
item's plan (`intent/<EPIC-KEY>-<kebab>/plans/BPA-<n>.md`), the epic's `spec.md`, CLAUDE.md,
docs/02 (DoR and DoD) and docs/04 (conventions).

## Passes
Run three passes and tag each finding with its pass and a severity.

**Bugs**
- Logic errors, broken edge cases, regressions, wrong error handling.
- Every boundary value and unhappy path in the Gherkin scenarios behaves as stated.
- Workers idempotent on their business key; retries and BPMN errors as the ADRs say.
- Dates and times: business dates in the configured business zone, timestamps in UTC, no `now()` in
  FEEL or DMN.

**Security and data**
- No secrets, tokens or passwords committed; `.env` values only as placeholders.
- No real personal data. Test and seed people are obviously synthetic (e.g. "Worker E1001").
- No client names, no names of people from source documents, no signed-document IDs or file names;
  codename "STAR" only; sources cited by source-register key.
- Backend enforces authorisation (group and scope, 403); the UI only hides.
- Injection risks, unsafe file handling, missing input validation.

**Compliance**
- The diff does what the Jira acceptance criteria say, and nothing outside the item.
- The diff matches the plan: every file in "Files that change" is touched or explained; every changed
  file appears in the plan or the plan was updated in the same commit.
- One test per Gherkin scenario, named `BPA<n>_<camelCaseScenarioTitle>`; each was shown failing first.
- Names follow docs/04 (process, element, decision IDs; tables; `_ref` columns; API paths; JSON fields).
- Every field touched has exactly one SOR, matching docs/03 and the data model.
- BPMN and DMN in the repo are the ones deployed; DMN tables complete (inputs, outputs, hit policy).
- No business rule invented: anything not in a source is marked ASSUMPTION and commented in Jira.
- Plan header complete (docs/04); human-gap log filled in; `Status: Closed` set in the final commit
  before merge (while under review the plan stays `Approved`).
- ADRs or docs updated when the change alters a decision, convention or CLAUDE.md rule.

## Severity
- **Important**: wrong behaviour, security or data-rule breach, missing or failing scenario test,
  diff departs from the plan or the acceptance criteria, invented business rule. Must be fixed or
  explicitly accepted by the reviewer in the PR.
- **Nit**: naming, wording, formatting, small clarity improvements. Cap at 5 per review; list the
  rest as a count.

## Skip
- Generated files (`intent/README.md`, OpenAPI-generated sources, build output).
- Anything CI already enforces (bpmnlint, Redocly lint, formatter) unless CI is not yet running.
- Style preferences not written in docs/04.

## Output format
```
Review: BPA-<n> at <sha>
Important (<count>)
- [Bugs|Security|Compliance] <file>:<line or element> <finding> -> <suggested fix>
Nits (<count>)
- ...
Plan check: <files in plan but not in diff> / <files in diff but not in plan>
Scenarios: <n> in Jira, <n> tests found, missing: <list>
```

When a review flags the same mistake a second time, propose a CLAUDE.md change in the PR
(playbook, Stage 5).

## Review log
Newest last. One entry per review; the full report goes in the PR description.

### BPA-41 at 36aed9e, 29 Oct 2026 (qa-reviewer)
- Code: no Important findings. `test-require-plan.sh` 26 passed, 0 failed when the reviewer ran it.
- Important (2, both on the plan; neither is a BLOCKER, none touched the code):
  - REVIEW.md was listed in the plan but absent from the diff: fixed by this entry; the rewritten plan is committed with it.
  - Plan header: placeholder summary (the Jira item is being created), `Story last updated` is a date not a Jira timestamp, no approval record, `Status: Closed` set before review ended. Accepted as set by the Product Owner's instruction; to be completed once the Jira item exists. `epic BPA-17` form corrected.
- Nits (5), not fixed in this commit:
  - the Windows block also fires on any path containing a backslash, so a POSIX filename with a literal backslash is rewritten;
  - `/d/...` paths with different case and UNC paths are not matched case-insensitively (fails open, as before);
  - the no-`cygpath` fallback is untested;
  - `test-require-plan.sh` is mode 100644, `require-plan.sh` is 100755;
  - "Problem" and "Task as given" are sections outside the docs/04 list.
- Not verified by the reviewer: `test-hooks.sh` (120 passed per the plan), the compose stack, and the claim that the Windows cases failed before the fix (test and fix landed in one commit).

### BPA-42 at f044b8d, 29 Oct 2026 (qa-reviewer)
- Code: 0 BLOCKER, 1 Important, 4 Nits. V1 matches the data model; the seed is within L8 and section 11; the diff stays inside the plan.
- Important (fixed): `notification.revision` (D365 F&O Document Control) had no column comment although `notification` is on the section 7.2 list; added to V1
  and the plan. The 31/31 and 19-table counts could not detect it; a per-column script now checks the rule (141 columns, no differences).
- Nits (4), none changed the work except the plan:
  - `cutting_sheet` and `material_reservation` use `INSERT ... WHERE NOT EXISTS` only, not `UPDATE` then `INSERT` as plan Q4 said (no non-key column to update): plan updated; editing a seeded value adds a row.
  - `drawing_revision` has 8 rows against "about 10": left, all required cases covered.
  - Plan header "Story last updated" carried a comment note: timestamp only now.
  - data-model.md says "approved" while Usama's Jira confirmation is open: set on Ahsan's instruction, recorded in the plan's Human-gap log.
- Not verified by the reviewer: Jira (no sign-in in the session). Flyway itself was run by the author; the reviewer applied both files with psql to a separate throwaway database and got the same counts.


### BPA-43 at a8bb6f2, 30 Oct 2026 (qa-reviewer, review 1 of 2: Part A)
- Code: 0 BLOCKER, 2 Important, 10 Nits. Reviewer reran `./mvnw -B verify` in `eclipse-temurin:21-jdk`: 8 tests, 0 failures, BUILD SUCCESS. Part B not started and not reviewed.
- Important (2):
  - `docker-compose.yml` bpa-app connects to Camunda as the admin (demo/demo), not `bpa-service` (S25, ADR 0005), and the env vars override `application.yml`
    for good: recorded as a temporary deviation in the plan; the compose comment now says Part B deletes both lines.
  - Part A gate requires CI green; `backend.yml` had not run (it triggers on PRs only): a draft PR is opened for the run, and Part B waits for it.
- Nits (10): fixed in the plan or code: `Story last updated` bumped; step 3 test names and the file table (`.gitattributes`, `ProcessTestApplication.java`,
  `SorComments.java`) brought in line; the copy check also catches `.xlsx`; the CPT image property name confirmed in the starter metadata. Reported, not
  resolved: spec.md says the skeleton has the `workpack` table only (L5), Jira and the plan use the full V1 (Jira wins). Left open: Dockerfile runs as root;
  no compose healthcheck for bpa-app; `../models` as a resource root; `mvnw.cmd` mode 100644; CPT logs unexpected-error lines during green runs.
- Not verified by the reviewer: test-first failing runs (tests and code landed in one commit, 1a1b9b4); CI; `docker compose up -d --build` from clean (only
  `/actuator/health` UP on the running stack); Flyway against a pre-populated local `bpa` schema.

### BPA-43 at 2ebb550, 30 Oct 2026 (qa-reviewer, review 2 of 2: whole Task, focus Part B and the Q13 model fix)
- Code: 0 BLOCKER, 6 Important, 8 Nits. Reviewer reran `AuthApiTest`, `IdleSessionTest` and `PersonaSyncProfileTest` in `eclipse-temurin:21-jdk`: 12 tests,
  0 failures. Probed the running stack: anonymous `/me` 401, empty login body 400 problem, wrong password 401 problem, anonymous `GET /error` 401.
- Important (6):
  - Login errors other than a wrong password reached the caller as 401 and React showed "Wrong username or password": fixed, 503 problem, declared,
    tested (backend and React), `/error` permitted.
  - `bpa-service` holds RESOURCE `CREATE` on `*` (ASSUMPTION), wider than S25's runtime calls: open, for Faraz and Ahsan (accept, or deploy with
    another identity).
  - No ASSUMPTION or contradiction commented in Jira: comments drafted for Usama's yes (RESOURCE CREATE; persona usernames and fields; Q13; spec.md L5).
  - CI has not run: open until the PR runs it; the Part A gate departure is recorded in the plan for Ahsan.
  - Plan files table out of step: fixed (profile yml files "not needed (as built)", `.gitkeep` listed, `intent/README.md` regenerated).
  - Q13 changes `models/` and BPA-6's model contract: open, Ahsan to accept in the PR; BPA-6 to be told (Jira comment drafted).
- Nits (8): fixed: CSRF token renewed at login and cleared at logout; 403 test asserts the group reason; logged-in POST without token tested;
  session change at login tested; login 400 declared; login groups sorted; check 4 sends strings (ADR 0006). Documented: the sync never removes
  memberships or users. Left: `updateAuthorization` branch untested; job activation in checks 4/5 may lock another instance's job; Vite port in
  the proof; Dockerfile root and no healthcheck (carried); `bpa-service` and `demo` can open portal sessions.
- Not verified by the reviewer: full `./mvnw -B verify` (integration classes per the author's run), CI, React in a browser, test-first failing runs
  (tests and code in one commit, 70ccf24), the Camunda-outage path end to end, FEEL behaviour of the new output mappings when the variable is absent.

### BPA-44 at b12f100, 2 Nov 2026 (qa-reviewer)
- Code: 0 BLOCKER, 1 Important, 3 Nits. Reviewer read the code (no JDK, no Maven); no test was run.
- Important (1): `FlywayBaselineTest` asserted 31 tables, V1 now has 30: fixed (30), plan updated.
- Nits: `App.test.tsx` assertion relied on the username appearing twice: fixed (user line and Groups list checked separately). Left: long lines in
  `AuthApiTest`/`Bpa25CamundaChecksTest` after the rename; `BPA43_personaSyncIsIdempotent` counts all `app_user` rows (fine while only the sync writes them).
- Open for Ahsan: `spec.md` row 69, `docs/`, ADR-0005 and closed plans still name `user_role_scope` (Jira's grep criterion has no docs exception);
  a Jira comment or docs item recording that the PO decision of 2 Nov overrides them is still to be made; test-first failing runs not shown;
  local compose run (7 logins, `/me`) not done; backend tests first run in CI.

### BPA-45 at f9a4a37, 2 Nov 2026 (qa-reviewer)
- Docs only: 0 High, 3 Medium (Important), 6 Low. Reviewer read BPA-45, BPA-46, BPA-3 comments 10462/10460 and BPA Sprint 2 in Jira.
- Checks passed: no 09 row and no ADR Decision text changed; history (deciders, closed plans, data-model history) untouched; spec.md
  delivery order matches comment 10462 and the Sprint 2 rank exactly; every "Done" readiness item verified in Jira and git.
- Important (3), all fixed: ADR 0011 still named Usama as OpenAPI owner (dated Notes entry added); plan row for ADR 0015 did not match the
  diff (plan corrected, note moved to a `## Notes` section); spec.md marked Accepted on text edited after the PO's acceptance and one
  PROPOSED marker dropped (status now says "as at 73c85a5, then updated per comment 10462"; marker restored).
- Low (6): fixed: 09 change log now names S20 owner column and L19; plan Status format per docs/04; skeleton line explains full V1;
  ADR 0015 note moved out of Decision. Open for Faraz (plan Q1, Q2): owner of items waiting on Usama; BPA-46 not in a sprint.
- Not verified by the reviewer: CI; `intent/generate-index.sh` rerun (author reran it).

### BPA-46 at 198cca5, 2 Nov 2026 (qa-reviewer)
- Contract only: 1 High, 5 Medium (Important), 9 Low. Reviewer read BPA-46 in Jira and the 14 stories' AC and SOR Notes saved from Jira the
  same session; lint, `./mvnw -B verify` (31 tests) and the frontend build were run by the author, not the reviewer.
- Checks passed: all 20 S20/ADR 0011 endpoints present with a source cited; 20 problem texts = 11 verbatim from stories + 9 x-assumption
  (independently recounted); names and types follow the data model; task API matches S14 and BPA-26 apart from the High.
- High, fixed: the Confirm task's release/decline reason is the process variable `confirmMessage` (BPMN), not `validationMessages`;
  `Task.confirmMessage` added.
- Medium, fixed: 401/403 texts now match SecurityConfig; tags split so no two stories share an interface; drawing-revisions documented as
  CSRF-exempt (x-assumption, BPA-16); `x-assumption` added to the reused TaskForbidden/TaskNotFound texts.
- Medium, answered by Faraz (plan Q1): POST workpacks keeps no body; scope wording proposed as a Jira comment on BPA-46.
- Low, fixed: `x-assumption: true` (reason in `x-assumption-note`); required fields per NOT NULL columns; invented orderings removed; plan
  enum line corrected. Low, left: no `multipleOf: 0.01`; TaskKey and CamundaKey patterns duplicated; Jira BPA-46 still Backlog; reference
  lists have no implementing story (plan Q4); generated validation needs exception handlers in BPA-16 and the 400-text stories (noted in plan).

### BPA-26 at 0cb0940, 4 Nov 2026 (qa-reviewer)
- Code: 0 High, 5 Medium (Important), 7 Low. Reviewer read BPA-26 in Jira (evidence and changelog), the plan, spec.md, the contract's
  task paths, 09 S5/S6/S14/S15/S20/S25/G9 and ADR 0004/0005; no test was run by the reviewer (author: 7 BPA26 failing first with
  404 "No static resource", then `./mvnw -B verify` 38 tests green).
- Checks passed: check order 404/403/409/400 as planned; 403 before any variable read; groups from the session only; CSRF enforced
  on complete; `bpa-service` for all Camunda calls; engine 404 on a second assign mapped to 409; 7 scenarios, 7 tests, names as in
  the Jira DoD; diff inside the plan.
- Important (5):
  - AC "Operate shows project-engineer as the user who completed the task": the audit log and Operations Log name `bpa-service`;
    only the Assignee is the real user (plan Q1). Decided by Ahsan (Build Lead), 4 Nov: met through the assignee; PO to confirm in
    Jira. Open until Faraz answers.
  - ASSUMPTIONs not commented in Jira (check order, read lists, 404 for non-Epic-1 tasks, other states, page limit 100, oldest-first
    sort): all now in plan Q2; Jira comment drafted, posting needs the developer's yes.
  - `workpackNumber` and `confirmMessage` sent as JSON null where the contract says absent: fixed (`spring.jackson.default-property-inclusion:
    non_null`; BPA26_getsOneTask asserts `confirmMessage` is absent).
  - `assertStaysOpen` read eventually consistent storage: fixed (the project engineer completes the task through the API, 204, which
    the engine would refuse on a completed task).
  - Second-complete test covered only the engine-rejection path: fixed (immediate call, then a third call once the search reads
    COMPLETED; both 409).
- Low (7): malformed or above-int64 taskKey gave 500: fixed (404 "Task not found", asserted in BPA26_unknownTaskIsNotFound).
  Variable-scope Javadoc overstated, searches not paged: Javadoc corrected, warning logged when Camunda holds more than a page.
  Transient listener states mapped to 404: fixed (treated as open). getsOneTask did not exercise the read allow-list: fixed
  (declineReason returned, workpackStatus not). Plan inaccuracies: fixed (config key shape, Story last updated, calls); and
  `GET /v2/variables/{key}` was not proved by BPA-25: confirmed 403 for `bpa-service` on the local stack, call removed, the variable
  search uses `truncateValues=false` instead. Left: assign then complete is not atomic (harmless, allowOverride true); a task cancelled
  between the read and the assign is reported as 409.
- Tests strengthened after review (said here and in the PR, L15): BPA26_getsOneTask, BPA26_wrongGroupIsForbidden,
  BPA26_variableNotAllowedIsRejected, BPA26_unknownTaskIsNotFound, BPA26_completedTaskCannotBeCompletedAgain. After the fixes: 7 BPA26
  tests pass, `./mvnw -B verify` 38 tests green.
- Not verified by the reviewer: test runs, failing-first output (tests and code in one commit, 79e617e; the run is in the plan's
  Human-gap log and the PR), CI.

### BPA-6 at 498a708, 4 Nov 2026 (qa-reviewer)
- Code: 0 High, 6 Medium (Important), 14 Low. Reviewer read BPA-6 in Jira (evidence; comments 10378, 10465), the plan, spec.md,
  the contract's workpacks/reference/task schemas, data model §1.2, §1.5, §3.1-3.7, §4.1-4.3, §6.1, the BPMN create path, the form
  spec, 09 S7/S9/S14/S20 and ADR 0006/0007/0013; no test was run by the reviewer (author: 10/11 backend red for the expected reason,
  React import red; then 11/11 BPA6, `./mvnw -B verify` 49, `npm test` 9/9, build clean).
- Checks passed: all 14 AC texts exact in the validator, React field texts the same; D14/D16/D20/D74 rules; find-then-ON CONFLICT
  idempotency; created_by/updated_by/audit username from server-set completedBy (client copy rejected 400); audit_log per business
  column, §6.1 value format, changed_at timestamptz UTC; Double/Integer man hours parsed exactly; reference lists absent-not-null,
  budgetedUnits a number; POST workpacks group-gated with CSRF; corrections jobs failed retries 0 as decided.
- Important (6):
  - Worker validated before the idempotency check, so a retry after a committed save could loop back and drop the next input;
    the rerun test bypassed the worker: fixed (the worker returns the saved Work Pack before any check; BPA6_rerunDoesNotCreateSecondWorkPack
    now runs the job twice through the worker, the retry with other data after Riyadh midnight).
  - Business zone hard-coded: fixed (`app.business-zone`, default Asia/Riyadh, injected; S9, ADR 0013).
  - After a rejected save the portal never showed the returned task without a reload and dropped the entered data: fixed (the task
    list watches for the returned task and reopens it; the screen prefills from the task's variables; new React test in TaskList.test.tsx).
  - The Riyadh boundary rows passed with a UTC implementation: fixed (row added in both tests: 20 Oct rejected at 20 Oct 21:30 UTC;
    React also asserts the date picker's `min`); to be proposed to Jira as an extra Examples row.
  - ASSUMPTIONs not commented in Jira: comment drafted for the developer's yes (plan Q4, Q5, completedBy).
  - Contract still documented the 501 stub: fixed (501 response and sentence removed; `completedBy` documented in CompleteTaskRequest).
- Low (14), fixed: vacuous otherRoles task-list check (an analyst's Create task is now open first); `contains` replaced by exact
  message lists; double message on an unreadable date; tests added for the corrections incident, a client-sent completedBy (400) and
  the audit rows; created_at/updated_at now from the injected clock; React decimal check catches "1e-3"; TaskList now tested.
  Left: stale material phase after a phase row changes (the save step rejects it); Camunda 4xx on start gives 500; completedBy not in
  ADR 0004 (contract updated; ADR note left for Ahsan); leftover instances in the shared test Camunda (classes assert on their own
  keys); 12cc690 red on its own (AuthApiTest fixed in 498a708); form spec key dateToComplete (contract wins); plan Story last updated
  bumped (status change only).
- Tests strengthened after review (said here and in the PR, L15): BPA6_rerunDoesNotCreateSecondWorkPack, BPA6_otherRolesCannotCreateWorkPack,
  BPA6_dateToCompleteBoundary (backend and React), exact message lists in the rejection tests. After the fixes: `./mvnw -B verify` 52 tests
  (WorkPackApiTest 14), `npm test` 10/10, `npm run build` clean.
- Not verified by the reviewer: test runs, failing-first output (tests and code in one commit, 12cc690), CI, SDK default backoff
  vs S7, test order across integration classes, React in a browser.

### BPA-47 at 5758989, 4 Nov 2026 (qa-reviewer)
- Task (frontend structure, docs, skills): 0 High, 2 Medium (Important), 4 Low. Reviewer read BPA-47 in Jira (evidence; comment 10470), the plan,
  spec.md, the old (827a8ba) and new portal code, and every source the two skills cite; ran `eslint .` and `prettier --check .` (both clean); npm test,
  build and `./mvnw -B verify` per the author (12/12, clean, 52).
- Checks passed: DOM, texts, order, polling (500 ms x 10), password clear and error texts unchanged; the moved tests change imports and render wrapper only
  (format-only commit 194a60a has identical tokens); layout and naming as planned; ADR 0004 amendment matches TaskService, SaveWorkPackWorker and WorkPackRepository,
  Decision unchanged; ADR 0017 as briefed; skill citations all real; `.claude/settings.json`, hooks, api/, models/ and backend code untouched.
- Important (2):
  - TanStack Query cache not cleared at logout: a second persona in the same tab saw the previous user's task list until the refetch returned: fixed
    (AuthContext clears the cache at login and logout; new tests/integration/Session.test.tsx, shown failing without the fix).
  - Jira Description ("no router") and estimate (3) still differ from the agreed scope; comment 10470 awaits Faraz: open, recorded as plan Q1 and in the
    PR for Ahsan to accept; plan "Story last updated" bumped (comment only).
- Low (4), fixed: catch-all `Navigate` rewrote non-root URLs (now the portal renders at any URL, URL kept; tested); awaited task-list refetch after a
  completion (no longer awaited); `@types/node`, `USER_TASKS` and the package.json description added to the plan / updated; `taskScreens` lookup now
  own keys only (`screenFor`).
- After the fixes: `npm test` 14/14, lint, format:check and build clean; `./mvnw -B verify` 52 (no backend change).
- Not verified by the reviewer: test, build and Maven runs; CI; the brief's exact ADR 0004 wording; the browser run.
