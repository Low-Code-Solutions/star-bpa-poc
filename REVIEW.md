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

