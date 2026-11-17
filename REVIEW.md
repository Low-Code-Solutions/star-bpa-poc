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

### BPA-7 at d91ef06, 4 Nov 2026 (qa-reviewer)
- Code: 0 High, 3 Medium (Important), 8 Low. Reviewer read BPA-7 in Jira (evidence; comment 10379), the plan, spec.md, the
  new-job-worker skill, backend/CLAUDE.md, data model §1.2, §5.5, §6.1, V1 (hour_journal_header, workpack_phase), the contract
  (hour-journal-headers, HourJournalHeader, WorkPackNotFound, InvalidWorkpackNumber, Forbidden) and the BPMN create/update/sign-off
  path; no test was run by the reviewer (author: 7 BPA7 red for the expected reasons, then 7/7 green, `./mvnw -B verify` 59).
- Checks passed: SUM in SQL exact (150.5 + 249.5 = 400, 0.01); ON CONFLICT then SELECT ... FOR UPDATE safe for racing retries;
  locked header never written; audit values in §6.1 form, audit columns not logged, times from the injected clock, users from
  completedBy else bpa-service; worker per the skill (autoComplete false, Camunda default retries, no Work Pack -> incident);
  403 before the parameter check; 404 problem text exact; 400.00 is a valid JSON number; 7 scenarios, 7 tests, names as in the
  Jira DoD; diff inside the plan.
- Important (3):
  - Lock stand-in terminated ServiceTask_EvaluateReleaseInputs, which breaks once BPA-8 moves the token on: fixed (the stand-in
    terminates whatever is active and retries; BPA-8 re-check in the plan and test).
  - Audit rows and created_by/updated_by not asserted: fixed (insert rows, the 400 -> 450 update row, no row on an UNCHANGED or
    LOCKED re-run, created_at/changed_at from the clock, the locked re-run's job completion).
  - ASSUMPTIONs and the "group and scope" contradiction not commented in Jira: comment drafted for the developer's yes.
- Low (8): missing workpackNumber gave Spring's 400 text: fixed (InvalidWorkpackNumber; new non-scenario test
  invalidOrMissingWorkpackNumberIs400). "No Confirm task" assertion vacuous and not polled: polled, and the BPA-9 re-check says
  when it gains meaning. BPA-23 and BPA-9 re-checks imprecise: fixed (table/column named; release-Ready data; keep the locked
  re-run). otherRoles thin: fixed (403 problem body; time-method-analyst 200). Plan Risks wrong: corrected. Left: Jira still
  Ready (Ahsan moves statuses); worker fetches all variables (as SaveWorkPackWorker); rerun calls handle directly (skill pattern).
- Tests strengthened after review (said here and in the PR, L15): BPA7_headerIsCreatedWithEstimatedManHours,
  BPA7_headerIsNotDuplicatedOnRerun, BPA7_headerFollowsCorrectionsWhileDraft, BPA7_headerIsLockedOnceReleased,
  BPA7_otherRolesCannotReadHeader.
- Not verified by the reviewer: test runs, failing-first output (tests and code in one commit, d91ef06; red run used an
  uncommitted stub), CI, the local-stack run, Spring's missing-parameter text, SDK fail-on-exception with autoComplete false.

### BPA-23 at de8af72, 5 Nov 2026 (qa-reviewer)
- Code: 0 High, 7 Medium (Important), 9 Low. Reviewer read BPA-23 in Jira (evidence, changelog; comment 10390), the plan, spec.md,
  data model §4.1-4.3, §6.1, §6.3, V1, the BPMN corrections path, Form_ConfirmWorkPack.form, both skills, ADR 0017, frontend/CLAUDE.md
  and the frontend-design SKILL.md; no test, build or browser run by the reviewer (author: backend red 7/8 for the expected reason,
  green 8/8 + BPA-7 8/8 on the real step, `./mvnw -B verify` 67; React red on import, `npm test` 18/18; lint, format, build clean).
- Checks passed: AC texts exact; Draft check, BPMN error and non-Draft incident (retries 0); phases in place (F13); §6.1 audit values;
  clock and completedBy; outputs on both paths (plain-number manhours, string ids); temporary incident and its BPA-6 test removed and
  said; BPA-7 helper only changed; CreateWorkPack unchanged after extraction; styling changes no role, label or text; contrast AA,
  focus visible, reduced motion, small screens.
- Important (7):
  - Material matching trusted client ids and input order: fixed (two passes, duplicate id refused).
  - Retry idempotency untested, D20 re-check after midnight: fixed per Ahsan's rule (an unchanged target completes without re-checking);
    new test correctionRetryAfterMidnightCompletesWithoutRechecking.
  - Most correct() branches untested: fixed (multiChangeCorrectionIsAppliedInPlace, exact audit set incl. delete rows).
  - BPA23_noCorrectionAfterRelease asserted only the stand-in: fixed (non-Draft incident asserted inside it); stand-in said in the PR.
  - Portal-wide redesign outside the item's AC: Ahsan's decision, said in the PR and the BPA-23 Jira comment.
  - ASSUMPTIONs and decisions not in Jira: BPA-23 comment drafted for Ahsan's yes. The proposed CLAUDE.md rule is not taken
    (Ahsan's choice).
  - Plan design log uncommitted; spec.md order; files table: log committed, files table completed; spec.md left as it is (Ahsan),
    the order agreement stays in the plan.
- Low (9): CSS :not() specificity: fixed. Job tag lacks the number: plan wording matches the build. Return reason visible label:
  added "Returned:". No remove-line control: noted for the PO. Material order: created_at, id. Deferrable wording: fixed.
  CamundaSteps wording: fixed. Story last updated and plate hex: fixed. Q1 date item: dropped.
- After the fixes: `./mvnw -B verify` 68 (ConfirmWorkPackApiTest 9); `npm test` 18/18; lint, format, build clean.
- Not verified by the reviewer: test and build runs, CI, the browser render, the local-stack run, the flaky BPA-6 run.

### BPA-8 at 7c33be6, 5 Nov 2026 (qa-reviewer)
- Code: 0 High, 3 Medium, several Low. Reviewer read BPA-8 in Jira, the plan, spec.md and the diff; it ran no tests (Docker needed).
  Author's runs: red 10 timeouts + 1 stub throw (1e1b0a3), green 11/11 and `./mvnw -B verify` 79; React 20/20; local stack Ready and both
  Not ready paths through the API (return to Confirm 2.6 to 3.3 s).
- Checks passed: all 11 test names equal the Jira DoD list, one test each, the outline's rows in one test; the worker and repository match
  the SOR Notes (materialsReserved, drawingApproved); read-only, so idempotent; DMN, seed and migrations unchanged (PO decision 5 Nov);
  the worker excluded in ProcessTestApplication; scope is BPA-8 only; scenario 7 only passes if reservations are keyed by drawing,
  scenario 10 only if "any" or "oldest" revision is not used.
- Medium (3):
  - React test named as the backend scenario but asserting the drawing reason: fixed (BPA8_returnedReleaseReasonShowsOnConfirmScreen); plan updated.
  - The Superseded filter was untested (all 11 tests passed without it): fixed (newest revision C Superseded in the As-Built test; only-Superseded
    case in the no-revision test); a mutant without the filter fails the As-Built test.
  - registered_at ties were nondeterministic: fixed (higher revision letter wins, ASSUMPTION in the plan and Javadoc).
- Low: vacuous true for a Work Pack with no material lines (D20 says none; noted in the plan). confirmMessage cleared when Ready and "Confirm no
  longer open" were unasserted: added (scenario 8 and 1). Plan "one React test": corrected to two. Story last updated: refreshed (status
  moved to In Progress only). D65 cite: D24 added. Restore of the seed is not asserted: kept (throwaway DB; no later test depends on it).
- After the fixes: ReleaseCheckApiTest 11/11; `npm test` 20/20; format clean.
- Not verified by the reviewer: test runs, CI, the local-stack run, the combined-run Confirm flake (BPA-23 note).

### BPA-9 at c99921c, 5 Nov 2026 (qa-reviewer)
- Code: 0 High, 3 Medium, 7 Low. Reviewer read BPA-9 in Jira, the plan, REVIEW.md and the diff; it ran no tests (Docker needed).
  Author's runs: red 6 of 8 SignOffApiTest and 3 of 4 TaskValidityTest failed, 6 React tests failed (a84bec2); green SignOffApiTest 8/8 alone,
  BPA-7 re-check 8/8 and BPA-23 re-check 9/9; `npm test` 27/27, lint, format, build clean; local stack decline, send back, confirm again and
  approve through the API (Released, Header locked, two release_signoff rows, instance at Schedule Work Pack).
- Checks passed: the 6 scenario tests named as in the Jira DoD, each asserting its Gherkin; the backend re-check runs after 404, 403, 409 and
  the allow-list 400 and before assign and complete; `completedTaskKey` is set from the task, on no allow-list, and ignored by the other
  workers; ON CONFLICT (task_key) first; `signed_off_by` from `completedBy`; release and lock in one transaction; the decline text comes from the
  job and Ready clears it; the widened read list is reachable only by project-engineer; ADR 0004, openapi text and examples match the code;
  no BPMN, DMN or migration change; the BPA-7 and BPA-23 re-checks keep every assertion.
- Medium (3):
  - Plan header `Story last updated` older than Jira `updated`: fixed (changelog shows only the status move and an assignee change; recorded).
  - Files in the diff not in the plan (signOffMessages.ts, workpacks/index.ts, global.css incl. a global textarea rule, openapi examples): fixed
    (listed in the plan).
  - Worker tests too weak (no missing completedBy or completedTaskKey, no declined re-run output, no non-Draft rollback): fixed (three tests
    added; each checked to need the code it tests).
- Low: audit `signed_off_at` could carry nanoseconds that the column drops: fixed (both use microseconds). An approval that finds the Header already
  locked passed silently: now logs a warning. React tests asserted `onDone` without waiting: fixed (`waitFor`). Helper imports in CamundaSteps: fixed.
  Reason state kept across Approve then Decline (harmless; nothing sent when Approved): left. Empty tables show no empty-state text: left.
  The ASSUMPTION texts and the Approved-with-reason rule need the BPA-9 Jira comment before merge: drafted for Ahsan (not posted).
- Not verified by the reviewer: test runs, CI, the red states, the local-stack run, the browser, `TaskScreenShell` alert and focus behaviour
  (shared code, BPA-23).
- After the review (BPA-9, Ahsan's decision 5 Nov, not reviewed by qa-reviewer): a test-only `application.properties` sets the Camunda job lock to PT10S,
  because the first test of SignOffApiTest failed in every full run (local and CI) from a job locked to a closed client for the default 5 minutes.

### BPA-11 at 5597c1e, 5 Nov 2026 (qa-reviewer)
- Code: 0 High, 3 Medium, 4 Nits. Reviewer read BPA-11 and its two comments in Jira, the plan and the diff; it ran no tests (Docker needed). Author's runs: red
  14 of 15 ScheduleApiTest, 6 of 7 ScheduleValidatorTest and 6 of 6 React tests failed (41e8b1d); green ScheduleApiTest 16/16 and ScheduleValidatorTest 7/7 alone, full
  `./mvnw -B verify` 117 tests, 0 failures, `npm test` 33/33, lint, format and build clean; local stack with the real Riyadh clock (look-ahead 200, 404 and 400; Analyst 403;
  a start before today + 7 and an end before the start returned with the messages; a valid schedule saved by production-planner; the instance at Generate Workpack print).
- Checks passed: the 9 scenario tests named as in the Jira DoD, each asserting its Gherkin; the validator order and the once-only lead-time text; the 27 and 28 Oct boundary
  through the real process; today from the injected Clock and the business zone; the worker order (completedBy, existing schedule, Released, dates); ON CONFLICT on
  workpack_id and audit rows only for a new row; the look-ahead API (200 with absent dates, 404, 400, 401, only the Work Pack, drawing and three dates exposed); the data-model
  variable names; strict "late" comparison; the widened read list reachable only by production-planner; Jira `updated` equal to the plan's date (no drift).
- Medium (3):
  - The Riyadh-versus-UTC row promised in the plan Risks was missing (a mutant using the UTC day survived): fixed (a test with the clock at 22:00 UTC on 20 Oct).
  - An unreadable date skipped the validator, so other rules were lost (start unreadable and end 27 Oct returned only the bad-start text): fixed (every rule runs on what can be read; tests for three
    combinations).
  - The unreadable-date texts and test were written with the worker, and Q2 and the Files list were incomplete: said in the plan and the PR, Q2 and the file list completed, the texts go to Khitaba.
- Nits: a failed look-ahead call, a one-day-late end and a missing plan date had no test or text: tests added, "Not set" shown. The two panels have no headings and no live announcement for a failed
  look-ahead: left (acceptable; the date inputs have labels).
- Not verified by the reviewer: test runs, CI, the browser, the red states, spec.md and data-model sections in full.

### BPA-10 at 7f99ff5, 5 Nov 2026 (qa-reviewer)
- Code: 0 High, 3 Medium, 5 Low. Reviewer read BPA-10 in Jira, the plan, S1/S3/S4, ADR 0004, data model 4.5 and 6.1 and the diff; it ran no tests (Docker needed). Author's runs: red PrintApiTest 12 of 12,
  WorkPackPrintRendererTest 6 of 6, React 5 of 6 (4be7b68); green PrintApiTest 12/12 and the renderer 6/6; the full `./mvnw -B verify` (136 tests) found one failure, the BPA-11 test that
  expected the process to wait at the print step (re-checked, ScheduleApiTest 17/17); React 41/41; on the local stack the print of WP-000013 fetched as time-method-analyst (200, application/pdf,
  inline filename) and page 1 rendered to an image and read.
- Checks passed: the 8 test names equal the Jira DoD list and each asserts its Gherkin (exact Content-Disposition, the 404 text, 403 for project-engineer, AwaitingFile, one print per Work Pack);
  the worker and repository (existing print first, ON CONFLICT, one transaction, audit with `sha256:<hex>` per A3, state only when empty, faults for unscheduled); the renderer (dates and /ID from the
  instant, embedded DejaVu Sans with its licence, English only, Thymeleaf XML mode with th:text only, no external resource, man hours as entered); the contract addition follows the spec.md rule and
  leaks nothing; pom pins; the BPA-11 test edit is a real re-check.
- Medium (3):
  - A HEAD request bypassed the group rule (the matcher was GET only): fixed (all methods) with a test.
  - The determinism test could pass with a wall-clock leak at second precision: fixed (1.1 s between renders, /ID and ModDate compared).
  - Scenario 1 and the renderer test used a bare `contains` (the date to complete and planned end are both 06 Nov in the AC): fixed (each value next to its label; the unit test uses different dates).
- Low: worker mutation gaps (the existing-print check, the state guard, the not-Released branch): tests added; blob URL not released after a failed fetch and an unhandled rejection on a network
  error: fixed with tests; plan header and Files list: updated; unused import: removed. Left: two quick clicks on Print PDF may show the earlier response (minor); no loading text while the list
  loads; the new PDF is not announced to screen readers and focus does not move to it.
- Not verified by the reviewer: test runs, the layout, the browser (the iframe), whether CSP or frame headers affect a blob iframe, Maven Central latest for 1.1.108 (resolves locally).

### BPA-12 at 40f8a2f (plan notes ea8b845), 6 Nov 2026 (qa-reviewer)
- Code: 0 High, 5 Medium, 9 Low. Author's runs: red AssignmentFileApiTest 21 of 21, reader 7 of 8, template 4 of 4, React 7 of 8 (7ea3734); green API 21/21, full `./mvnw -B verify` 172 tests, React 51/51;
  local stack through the API with a real `.xlsx` (routed, same file again, unknown Work Pack, 403, CSV, template, the BPA-22 incident).
- Medium (5):
  - A non-engine exception from the correlate call left the claim and returned a 500: fixed (any failure undoes the claim; the next Work Pack is still sent), with a unit test.
  - Mutation gaps (D73 before state, a claim lost to a concurrent upload, the compensation paths): `AssignmentFileServiceTest` added.
  - No row cap, zip limit or log for unreadable files: 5,000-row cap ("File has too many rows", ASSUMPTION), POI entry-size cap, debug log, tests.
  - ASSUMPTIONs not yet commented and Remarks maximum 250 not enforced: ASSUMPTION comment text for Khitaba prepared; the Remarks rule is BPA-22's.
  - A read timeout after Camunda correlated could show a false "Not processed": noted in the plan, no change.
- Low: attachment name sanitised (fixed, test); `Accept: */*` untested (test added); Upload not disabled while in flight and generic upload errors (fixed, tests); left: CSV formula injection (PO call),
  trailing-slash path, file input not cleared after upload, the Work Pack outcome shown inside the Outcome cell; plan file list and `updated` drift (plan updated).
- Not verified by the reviewer: test runs, CI, the browser.

### BPA-22 at 69c0d1d, 6 Nov 2026 (qa-reviewer)
- Code: 0 High, 4 Medium, 6 Low. Reviewer read BPA-22 in Jira, the plan, the AC (13 scenarios) and every main and test source in the diff; it ran no tests (Docker needed). Author's runs: red (6131a83) 16 new process tests, 7 re-checked
  BPA-12 tests and 7 validator tests failed for the expected reason (rows stayed "Pending", instance at the placeholder); green 38 process tests, validator 7/7, full `./mvnw -B verify` 207 tests; local stack through the API with real `.xlsx`
  files (mixed file Assigned plus unknown Worker, state Assigning, BPA-14 incident; an all-rejected file back at the catch event, then a corrected file assigned).
- Checks passed: the 13 test names equal the Jira DoD list and each asserts its Gherkin (texts, the outline rows, gateway No count, instance back at the catch event, the single audit row); created_by from the uploader (D63), audit
  rows with old value null, the injected Clock; ON CONFLICT key equals the schema UNIQUE; schema CHECKs match the validator; the BPA-14 placeholder follows the skill; the BPA-12 test changes are not weakened beyond "Pending or Assigned";
  the lock is taken before the Pending read.
- Medium (4):
  - A false return from `assign()` was ignored and the row read "Assigned": fixed (the job fails and rolls back, no invented rule), with a test.
  - Plan Q3 (a resent all-rejected file reads "File already processed") was not tested: fixed (scenario 13).
  - Hours parsing and rule-order mutations survived (`1E3`, `+5`, Unicode digits, a no-break space, hours versus remarks): hours are plain decimals only (ASSUMPTION Q5), tests for each.
  - Plan file list and Q-list drift: updated.
- Low: retried job with no Pending row only logged nothing (log added); a retried all-rejected file untested (test added); no concurrency test for FOR UPDATE (noted in the plan); scenarios 3 and 13 did not assert state or audit
  unchanged (added). Left: `settled()` polls up to 60 s and the class takes about 6 minutes of Work Pack set-up (the plan's risk); instances left at the BPA-14 incident accumulate in the shared Camunda (harmless).
- Not verified by the reviewer: test runs, CI, the local-stack run, the browser.

### BPA-14 at 0058d5e, 9 Nov 2026 (qa-reviewer)
- Code: 0 High, 2 Medium, 9 Low. Reviewer read BPA-14 in Jira, the plan, the AC (6 scenarios), the schema and every main and test source in the diff; it ran no tests (Docker needed). Author's runs: red (0238dd3) 17 of 49 failed for
  the expected reasons (no Lines created, the stub answered 200 for 404 and 400, the re-checked tests found the instance still at the Lines step); green the three process classes 49/49 and the full `./mvnw -B verify` 226 tests; local
  stack through the API with a real `.xlsx` (three Lines for E1021, E1099 and E1150, none for the rejected E9999, 18 audit rows, ppc-team and supervisor 200, production-planner 403, unknown Work Pack 404, `WP-12` 400, the
  instance at ServiceTask_CreateDigitalTimesheets with "built by BPA-15").
- Checks passed: the 6 test names equal the Jira DoD list and each asserts its Gherkin; the re-checked BPA-12 and BPA-22 assertions were not weakened (constant, method and incident-text renames only); the security rule has no
  HTTP method so HEAD is covered, is decided before the controller (a 403 reveals nothing about a Work Pack), and sits before `anyRequest().authenticated()`; the re-run is idempotent (ON CONFLICT with RETURNING-driven audit, one
  transaction); the BPA-15 placeholder follows the skill and is excluded in `ProcessTestApplication`.
- Medium (2):
  - `intent/README.md` was truncated in the commit (nine rows dropped): the generator run was cut short when the command was stopped; regenerated, it differs from main by the BPA-14 row only.
  - Nothing proved the snapshot rule against a re-run (a `DO UPDATE` mutant passed everything): the snapshot test now runs the job again after the master changed.
- Low: the header-missing branch, scope (`WHERE workpack_id`), read ordering and the trailing-slash path had no test: tests added (some passed on first run); the "no Line for E9999" assertion was a tautology and the audit counts
  used the whole table: fixed; the audit test checked 4 of 6 columns and not the time: all six and `changed_at` now; the insert lacked an order for concurrent jobs: `ORDER BY a.worker_id`; plan header and file list: updated.
  Left: the 403 of scenario 6 was not seen red (it failed in its set-up; the other-role tests exercise it green); no real two-thread concurrency test (theoretical, not a defect).
- Not verified by the reviewer: test runs, CI, the local-stack run, the browser.

### BPA-15 at 01e3fac, 9 Nov 2026 (qa-reviewer)
- Code: 0 High, 4 Medium, 7 Low. Reviewer read BPA-15 in Jira, the plan, the AC (9 scenarios), the schema and every main and test source in the diff; it ran no tests (Docker needed). Author's runs: red (e9fe490) 29 of 69 failed for the expected reasons
  (the process never reached EndEvent_WorkPackAssigned, the stub answered 200 for 404 and 400); green the four process classes, then the full `./mvnw -B verify` 246 tests; local stack through the API with a real `.xlsx` (three time sheets with the coding from
  A-2201, none for the rejected E9999, no daily lines; supervisor 200, ppc-team and production-team 403, unknown Work Pack 404; Assigned with the two-step audit trail; the end event completed with no incident; an A-2299 Work Pack with E1150 gave a time sheet
  with no CBS code or cost centre).
- Checks passed: the 9 test names equal the Jira DoD list and each asserts its Gherkin values; the CI-failure fix of BPA-22's retried-job test is sound (waits for the end, counts only this Work Pack's `worker_assignment` audit rows; the plan's "80 vs 86" is
  the six rows of one BPA-14 Line); data model 5.7 (unique key, NOT NULL columns, Draft, no daily lines); nine audit rows per sheet (seven when blank) by the uploader with the clock's instant; the one `@Transactional` method locks the Work Pack so two jobs
  serialise; the rule is supervisor only on every method and is decided before the controller.
- Medium (4):
  - The re-run test did not catch dropping the `assignment_state = 'Assigning'` guard (a second state audit row): it now checks the state audit rows and `updated_at`, and a re-run with the state still Assigning is tested.
  - Several branches survived mutation (NO_LINES, a named state in the refusal, the Open filter, the state update scope): tests added.
  - A retry failed once the Lines were Closed although the time sheets existed: fixed (D65).
  - The BPA-12 "nothing changes" assertions were always true after an end: removed, the duplicate guard stays proved by the claim.
- Low: BPA-22's state audit rows are asserted in any order (the fixed clock gives both one instant): left; the `ReleaseCheckApiTest` whole-table audit count: scoped; hand-edited seed rows are restored from the read value: fixed; stale comments: fixed; instances left at
  the BPA-14 and BPA-15 steps are not cancelled between classes (noise, not failure) and non-GET methods as supervisor are not tested: left.
- Not verified by the reviewer: test runs, CI, the local-stack run, the browser, that `variable()` in AssignmentFileApiTest still returns `assignmentFileId` after the instance has ended (the test passes locally).

### BPA-13 at d50f3f9, 9 Nov 2026 (qa-reviewer)
- Code: 0 High, 4 Medium, 9 Low. Reviewer read BPA-13 in Jira, the plan, the AC (11 scenarios), the BPMN, the schema and every main and test source in the diff; it ran no tests (Docker needed). Author's runs: probe of Camunda's replacement message (second correlate while
  active: 404 NOT_FOUND, no second instance); red (4f7562d) planner 6 of 6, 4 new upload tests and 12 of 14 process tests failed for the expected reason ("Replacement is not available yet", no reassignment end), two passed against the old code (the Assigning and Replacing rejections, BPA-12's);
  green the new class 14/14, BPA-14's and BPA-15's classes unchanged, then the full `./mvnw -B verify` 275 tests; local stack through the API with real `.xlsx` files (applied replacement, rejected replacement, duplicate file; both end events completed, no incident).
- Checks passed: every scenario's text and value matches the AC; the decision table matches the code (unchanged hours write no history but update remarks and file, history hours only, planner order, `compareTo`); the worker's `FOR UPDATE` against the upload's compare-and-set is sound under
  READ COMMITTED; the message variables and the gateway condition match the BPMN; the BPA-14 and BPA-15 refactors preserve behaviour by reading and by their unchanged tests.
- Medium (4):
  - Decision-table cases and audit assertions were untested (Q1, Q2, Q3, a kept Worker left alone, deletes, history, Line and sheet status, the uploader on new Lines): tests added.
  - The re-add scenario did not wait for its own second instance: `awaitReassignmentEnd(wp, end, n)`.
  - A failing undo could leave a Work Pack in Replacing and stop the loop: each undo is isolated and logged; the application-stops-in-between window is a known limitation in the plan.
  - The undo was not tied to its file: it only runs while the file's rows are still Pending; two residual cases are recorded in the plan.
- Low: stale comment fixed (the ADR 0009 line left for its owner); the plan header refreshed; the probe now completes its leftover instances; a weak negative assertion strengthened; lock order made deterministic. Noted, not changed: the 404 always reads "already in progress", D73 blocks going back to an earlier
  file's exact bytes, the short window before the instance ends, result rows not audited.
- Not verified by the reviewer: test runs, CI, the local-stack run, the browser, `coalesce(?, a.created_by)` with a null parameter (it works in the unchanged BPA-14 and BPA-15 tests).

### BPA-16 at d99e502, 10 Nov 2026 (qa-reviewer)
- Code: 0 High, 3 Medium, 8 Low. Reviewer read BPA-16 in Jira, the plan (header matched Jira `updated`), the AC (12 scenarios) and the diff; it ran no tests. Author's runs: probe of the group member read as `bpa-service` (200 with an empty list without GROUP READ, the member with it, no USER READ
  needed); red (d2943ad) 15 of 16 failed with 403 (path not open, no endpoint), one passed at once (`BPA16_noticesNeedALogin`, 401, named in the PR); green after the build 16 of 16 and the mail-down class; first run found the test databases had no `app_user` rows (seeded from `personas.yaml`) and the mail
  spy counted 12 sends for 6 attempts (counts only the worker's own `send(SimpleMailMessage)`); full `./mvnw -B verify` 302 tests; local stack: 201, 200 "Revision already received", 404, 400, two emails in Mailpit with the Riyadh time, own notices only (also with `?recipientUsername=`), instance
  completed, and with Mailpit stopped 2 incidents on SendTask_NotifyRecipients with all in-system notices stored.
- Checks passed: all 12 test names exist and each asserts its AC; the CSRF exemption and `permitAll` are limited to POST on `/api/v1/drawing-revisions` (another POST with a session still needs the token, GET still needs a login); the message is correlated inside the transaction (a failed send gives 503 and records
  nothing; `ON CONFLICT DO NOTHING` serialises concurrent duplicates); `find-revision-recipients` is read-only; `send-revision-notice` is idempotent (unique key, `emailRecorded`); notice rows are audited once per inserted row by `bpa-service`; GROUP READ is on the two groups only; times use the injected zone.
- Medium (3):
  - The plan's ASSUMPTIONs (Q1 to Q5) are not yet in Jira: drafts shown to Ahsan, posted on his yes (CLAUDE.md write-back rules).
  - `BPA16_supersededRevisionNotInCopyIsLogged` depended on test order (DWG-118 revisions of other tests, B left Superseded by scenario 1): `@BeforeEach` now keeps only A and B on DWG-118 and sets B to IFC, and the test asserts that no revision is Superseded, as the AC says.
  - The mail starter adds a health indicator: with the mail server down `/actuator/health` (public) answered 503 (new test `BPA16_appStaysHealthyWhileMailIsDown`, red first); `management.health.mail.enabled: false`.
- Low: stale contract text ("today's SecurityConfig would answer 403") corrected; comment on the one-page member read; the mail-down test cancels its instances in `finally`. Noted, not changed: the BPMN documentation of ServiceTask_FindRecipients is stale (department and project role; the story says groups), a model change for Khitaba and
  Faraz; the unknown-status check reads the generated enum's message (`BPA16_unknownStatusIsRejected` would catch a change); `RevisionFlow.instancesFor` searches 1000 variables (works today); the correlation key `drawingRef/revision` is ambiguous if a part contains "/"; a commit failing after a successful send leaves an
  instance with no recorded revision (D365's retry gets 503 until it ends); the open endpoint accepts any text for the revision label (S28).
- Not verified by the reviewer: test runs, CI, the local-stack run, whether the mail-down incident text reads well in Operate.

### BPA-80 at bdb3181, 11 Nov 2026 (qa-reviewer)
- Code: 0 High, 3 Medium, 10 Low. Reviewer read BPA-80 in Jira (and comment 10516), the plan, the whole diff, the root `.dockerignore`, `application.yml` and the BPA-43 plan; it ran no Docker and took the proof from the plan. Author's runs: red first (nothing on :3000, no `frontend` service); image build (context 420 kB, vite build inside); a fresh clone of the branch with `.env` from `.env.example`, project `bpa80scratch`,
  `docker compose up -d --build`; the proof script through :3000 only: 7 personas (401, login 200, `/me` shows the user, logout 204, 401), CSRF 201 with the token and 403 without, deep links 200 `index.html`, `/api` paths never `index.html`, `Set-Cookie` identical to the app's own, uploads (2 MB, an ordinary .xlsx); `bpa-app` moved to a new container address (.7 to .9) and nginx followed with no restart
  (a first attempt got the same address back and proved nothing). The mistake with the branch is in the plan's build notes.
- Checks passed: location precedence (`/api`, `/assets/`, `/`), the resolver variable passes the URI unchanged, header and cookie pass-through, the Dockerfile-specific ignore file, `main` equal to `origin/main`, four commits on the branch with the plan first, no change to Vite, `package.json`, `src/` or the backend.
- Medium (3):
  - CI is not yet proven (no PR at review time) and CI does not build the image: the PR is read for its checks before I say green; Q7 left for the PO.
  - Plan questions Q1 to Q7 open (Q3: no image tag or registry, against comment 10516; Q4: BPA-43 plan Q11 says no compose frontend until the showcase): recorded in the plan and carried to the PR.
  - `frontend` now builds with plain `docker compose up -d --build`: a failed portal build stops the whole stack for backend-only work. Recorded with the options (compose profile, CI build); not changed.
- Low, fixed: `server_tokens off`; `Connection ""` upstream; the upload-limit comment overstated what was proved (now 7 MB and 9 MB tested); "BuildKit required" note; README says use `localhost` and warns of the cold-start 502. Recorded, not changed: published on all interfaces (new Q8: `127.0.0.1:3000:80` is a PO decision), no security headers, prefix match on `/api`, SPA fallback for missing files,
  tags not digest-pinned, `COPY frontend/` re-runs the build when `nginx.conf` changes, the README line "first run builds bpa-app and the portal" (`up --build frontend` also rebuilds `bpa-app`).
- Not verified by the reviewer: Docker runs, the exact tags, CI, a browser.

### BPA-81 at eed6388, 11 Nov 2026 (qa-reviewer)
- Code: 0 High, 0 Medium, 8 Low. Reviewer read BPA-81 in Jira (and comment 10517), the plan, ADR 0013, 09 S9, S27 and the diff; it could not run the tests (the repo path has a space; the known `subst S:` work-round) and relied on the author's runs. Author's runs: red first (20 of 21 failed for the expected reasons, one guards the default); green after a fix to the malformed-value message (the date parser's own text had become
  the root cause); 21 of 21; no change to any of the 14 clock-replacing test classes; full `./mvnw -B verify` 324 tests, 0 failures; `docker compose config` renders `local` and an empty date by default and `local,qa` plus the date when set. After the review fixes 22 of 22.
- Checks passed: `ChronoUnit.DAYS.between` on the business-zone date is right across midnight, year boundary, leap day and negative offsets; strict ISO parsing refuses 2026-02-30, 2026-1-1 and a datetime; with the variable set and no `qa` it is not parsed; empty or blank counts as unset without a log; the bean name `clock` and its type are unchanged, so the tests' `@Primary` clock still wins and
  `ClockConfig` builds without effect in the 14 classes; the plan's risks (the portal's `todayInRiyadh()`, DB-side `now()`, engine time, S27) are accurate.
- Low, fixed: a test flake at Riyadh midnight (the date was read a few milliseconds after start-up); the unproven "rolls on after midnight" claim (new test); the Javadoc did not say the offset is exact only without DST; `.env.example` did not warn that a shell variable overrides `.env` or that `BPA_PROFILES=qa` alone drops `local`; plan header refreshed to Jira's current `updated`, the Proof and Build notes added.
- Low, recorded not changed: no WARN for "qa active, no date set" (the plan says unset is silent); a QA stack running past midnight rolls past the set date; data across restarts with different dates can look out of order; the README or ADR 0013 line and the portal's browser date (plan Q7, Q6) are decisions for the PO and Ahsan.
- Not verified by the reviewer: test runs, CI, the manual compose check.

### BPA-82 at 69d6de4, 11 Nov 2026 (qa-reviewer)
- Code: 0 High, 1 Medium, 6 Low. The reviewer could not read Jira (the repo MCP needed sign-in), so it reviewed against the task text, the plan, REVIEW.md and CLAUDE.md; it ran the frontend tests (39 pass) and could not run the backend test, the red-first claim or CI. Author's runs: red first (backend 2 of 3 with 404, frontend 3 of 17 for the expected reason, two guard tests pass at once); green after the build and the review fix; frontend 56 of 56, `tsc`, ESLint, Prettier clean; full `./mvnw -B verify` 328 tests, 0 failures; manual compose check with `BPA_CLOCK_TODAY=2026-12-01` through the portal's origin answered 2026-12-01.
- Checks passed: no other portal "today" reader (the schedule screen has no local past-date rule; backend rules use the injected clock and zone, as the controller does); the new path falls to `.anyRequest().authenticated()` (401 without a session, any group), GET only so CSRF is untouched; `refetchOnWindowFocus: true` overrides the app default as intended; the 21:30Z boundary test is deterministic.
- Medium, fixed: while the query was pending the screen used the browser date, so with a later QA date a fast click passed locally and was refused by the server. Now no minimum and no local past-date check until the first answer; a failed call still falls back to the browser date (ASSUMPTION, plan Q2).
- Low, fixed: the fallback test could not go red (now waits for the minimum to appear); new Java files were LF, neighbours CRLF; plan text still described `useWorkPackForm(today)` and a committed `schema.d.ts`.
- Low, recorded not changed: singular path against docs/04's plural rule (`/me` precedent); no format check on a 200 body; cached date shows briefly on remount until the refetch lands; no timer across midnight; the `new-task-screen` skill still names `todayInRiyadh()` (a `.claude/` file, for Ahsan).
- Not verified by the reviewer: backend test, CI, the browser look at the date picker.

### BPA-48 at 15295e3, 13 Nov 2026 (qa-reviewer)
- Code and docs: 0 Critical, 4 Important, about 10 Minor. The reviewer could read Jira (the description with the comments embedded) and the diff; it did not run tests, Flyway or the lint, so those are the author's runs: red first for the V2, seed and persona checks (5 of 22 failing for the expected reason: no V2 yet; the 15 decision tests and `ModelDeployTest` passed at once, because the DMN and BPMN files predate the plan); green after the build; `npm run lint:bpmn` 0 warnings; full `./mvnw -B verify` 351 tests, 0 failures, after the review fixes.
- Checks passed: V1 byte-identical to main; V2 matches the delta table by table (20 tables, columns, types, NULLs, CHECKs, UNIQUEs, FKs, the rev. 2 fixes); the three parked items are absent; `override_remark`, `progress_retraction.approved_pct`, `cbs_manhour_snapshot` and A-2301 are in; the 15 decision tests map one to one to the 15 DMN rule IDs and assert the matched rule; seed statements are idempotent; eleven personas in the file, the sync, docs/04, ADR 0005 and the test.
- Important, fixed: (1) no test for the partial unique index, `UNIQUE (employee_id)` or `hours >= 0`: added; (2) the persona sync re-added a project the PPC Team had removed on every restart: now it seeds a user only while the user has no `user_project` row, with a test, and ADR 0005 says so; (3) the "V2 on a DB with Epic 1 data" proof did not run the seed twice: a test runs the seed again on a migrated database; (4) docs/09 still said seven personas and no project scope, `docs/data-model.md` kept stale markers ("seven groups", "Ahsan to confirm", "open points", missing 13.6 and 13.7, no `D365 F&O Finance` label): amended in place and corrected.
- Important, **open (a decision, not a fix):** the partial unique index on `digital_timesheet_line` departs from the plain UNIQUE that Ahsan approved on 11 Nov (comment 10530). It follows the epic spec A5 (Khitaba, 10 Nov) so that E2-06 can re-book a Worker, date and phase after a rejection. It was taken on Ahsan's "as recommended" and is not confirmed by Khitaba or Faraz. It can still be changed later, but only by a V3 once the QA server has run V2. Ahsan, Khitaba and Faraz confirm before merge.
- Minor, fixed: `E1021`, the main Epic 1 Worker, was put on leave in the seed (now E1203); a redundant column comment; constraint-message assertions now name the constraint; test comments that said "seven personas".
- Minor, recorded not changed: no 0 to 100 CHECK on `progress_retraction.approved_pct` (the delta states none); the `bpa.*` setting keys differ from the Jira text and nothing validates their values (plan Q9); the A-2301 to CBS-139-PIP-01 mapping and every Worker HR, leave and A-2301 value are invented synthetic ASSUMPTIONS (plan Q10); CBS-139-PIP-99 maps to no activity (BPA-69); `E2_00_*` test names against the `BPA<n>_` convention (Jira names kept); spec.md still cites `user-task-form` (left to Khitaba); the plan is the second commit, after the staged inputs (recorded in the plan).
- Not verified by the reviewer: test, build and lint runs; the Camunda permissions of the four new groups (none are added: no story starts the new processes yet); BPMN content beyond deploy; how the weekly timer behaves on the QA server (plan R4).

### BPA-49 at f2b246b, 17 Nov 2026 (qa-reviewer)
- Code: 0 High, 3 Important, 8 other findings. Reviewer read BPA-49 in Jira (evidence), the plan, spec.md and the diff; no test was run by the reviewer
  (author: 6 BPA49 red first for the expected reasons, then 6/6 green, the five Epic 1 classes and PersonaSyncTest 100 tests, `./mvnw -B verify` 357 tests, 0 failures).
- Important (3):
  - Branch `bpa-49` (HEAD in lower case) has no description, against docs/04: fixed, renamed `BPA-49-start-timesheet-process` before the first push; plan header and Q9 updated.
  - Plan "Story last updated" behind Jira: re-read; the only change since is the assignee set to Ahsan (13:51); stamp updated. The guard choice (database row, not a Camunda
    search) stays an ASSUMPTION with no Jira comment yet: drafted in the PR for the developer's yes.
  - Publish-then-record can leave a row without a process when the model or the permission is missing (the publication call does not say whether a start event received
    it): accepted for the POC and written into the plan Risks and the endpoint's description ("already running" means "published once"); test 1 catches both causes. No re-publish route built.
- Other: fixed: `claim` result now checked (two simultaneous one-off calls: one 201, one 409). Documented: a Camunda 4xx on publish is an `IllegalStateException` (worker incident, endpoint 500).
  Left: no tests for 400, 503, 401, GET/HEAD on the path; test 2 uses two fixed pauses for its negative check; the instance finder reads at most 1000 variables; the 201 body reuses the schema name `TimesheetProcessRequest`.
- Test edit (said here and in the PR, L15): `TimesheetProcessStartApiTest` finder counts only ACTIVE instances; no assertion weakened (reviewer checked).
- Not verified by the reviewer: red-first runs, test runs, `lint:bpmn` (author: 0 warnings), the local stack (author, below), CI.
- Local stack (author, 17 Nov): WP-000030 taken through Epic 1 by API as the personas; Epic 1 instance COMPLETED at EndEvent_WorkPackAssigned, one `Process_PPC_TimesheetToProgress`
  instance ACTIVE at UserTask_BookHours for group supervisor, `published_message` row `WorkPackAssigned`; one-off call for it 409; WP-000011 (assigned before BPA-49) 201 then 409; an AwaitingFile Work Pack 409 "not assigned yet";
  unknown 404; deployed Process_PPC_WorkPackAssignment XML equals the repo file.

### BPA-50 at c850bf4, 17 Nov 2026 (qa-reviewer)
- Code: 0 High, 6 Important, 9 Nits. Reviewer read BPA-50 in Jira (Description, 19 scenarios, SOR Notes, 4 comments), the plan, the diff and REVIEW.md; no test was run by the reviewer
  (author: 21 backend tests red first with 404 "No static resource", then green on the first run; full `./mvnw -B verify` 384 tests; frontend 10 React tests red first
  with `Failed to resolve import "./BookHours"`; lint, format, build clean).
- Checks passed: all 19 scenario messages character-exact; check order; business date from the injected Clock and zone; week ends on the Thursday on or after the date; duplicate rule
  matches the partial index; decision inputs and `cbsClosed`/`workerOnLeave` sent as false; audit rows in the write transaction; security patterns cover every method; task serving.
- Important (6):
  - Production-team access (C-E2-5) is met only on paper: the list and the task stay supervisor-only because widening them would break `BPA15_otherRolesCannotReadTimesheets` and
    `BPA49_assignedWorkPackStartsTimesheetProcess`. This is an exception to the plan's own Q7 recommendation, taken so no merged test is edited silently: Ahsan or Faraz must accept it in
    the PR or settle Q7 with Khitaba. Fixed: openapi says so on the POST; the plan's Design, Files and Proof rows now match the diff.
  - PUT replaces hours, remarks and completion (a body with only `hours` clears the other two): fixed by stating it in openapi, the data model and the plan, and by a test
    (`aChangeReplacesTheRemarkAndTheCompletionItDoesNotCarry`).
  - Test gaps: added tests for 403 on GET, PUT and DELETE; a Draft line in a cut-off week (PUT and DELETE 400) and a Returned one corrected; the `HoursLoadedNotRemoved` flag; insert and
    delete audit rows; the four 404 texts; a body without a phase; and a React test for `canComplete=false` (Enter completes nothing). Not covered: the 503 path and the unique-index race.
  - Plan did not match the diff: Files table, Design and Proof rows corrected; "no existing test was edited" now says `PersonaSyncTest` gained one expected-permission line.
  - Transaction scope overstated: only the write, its audit rows and the status move are transactional; checks and the decision's sums run before it, so two simultaneous saves can both
    pass the daily and phase limits (only the one-line rule has a backstop). Fixed in the Javadoc, the data model and the plan; accepted for the POC.
  - Open for people: plan Q3 (scenario 2 title and Source Reference still say ASSUMPTION), Q5 (the screen was built inside BPA-50, so the story is larger than its 5 points), Q7, Q8 (who owns
    "Send to Timekeeper", so the Book hours task cannot complete yet), the Q9 ASSUMPTION comments (drafted for a yes), and the epic's intent/spec acceptance (Jira DoR box).
- Nits (9): fixed: DELETE documents its 400. Left: the API lets a Returned line be deleted while the screen offers Delete on Draft only (Jira comment 10515 names Draft); PUT and DELETE are not
  refused on a Cancelled time sheet; `add` does not check the Work Pack is Released or In Progress (FRD-7 §7.7.1; the plan chose not to); non-numeric hours show "Hours must be greater than 0";
  the remark length counts UTF-16 characters; the empty cell shows "+"; `<p role="group">` for the confirmation; two naming nits.
- Not verified by the reviewer: red-first runs (tests and code are in separate commits only for the plan), test runs, CI, a browser click-through (human gap), spec.md and data-model §13.1 in full.
- Local stack (author, 17 Nov): the supervisor's list shows the Book hours tasks; on WP-000031 a line booked (201), the Work Pack went Released to In Progress with one audit row, a change, a duplicate,
  a missing phase, a future date, 7.125 h, the timekeeper (403) and a production-team line all behaved as the tests say; the lines were deleted again.
