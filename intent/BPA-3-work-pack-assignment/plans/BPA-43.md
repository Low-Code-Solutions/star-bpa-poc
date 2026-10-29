# Plan: BPA-43 Walking skeleton: Spring Boot backend, Flyway, model deploy, one worker, login and CI
Jira: BPA-43 (Task, epic BPA-3)
Story last updated: 2026-10-29T17:33:15.248+0400
Status: Approved (Ahsan, 29 Oct 2026, answers to Q1-Q12 below)
Approver: Ahsan (Build Lead)
Branch: BPA-43-walking-skeleton, from main 965a386

## Readiness check (Task, so a light check)
- Scope, output and done criteria: Jira BPA-43 description (no comments). Sources: ADR 0002, 0003, 0005, 0010, 0014; 09 L1, L2, L4, L5, L9,
  L10, L18, S5, S6, S17, S22, S23, S25-S27; `docs/data-model.md`; merged V1 and seed (BPA-42, PR #17); BPA-25 results (comment 10420).
- The Task is larger than one Claude Code session (Ahsan's choice, Jira note). Work is ordered Part A, then Part B. **Part B starts only when
  every Part A test is green** locally and in CI. One branch, one PR, commits grouped by part.
- Rules: test-first (ADR 0014: write the test, run it, show it fails for the expected reason, then write the code); nothing from `models/` or
  `templates/` copied into `backend/`; no password in `personas.yaml`; `.env` never committed; no `docker compose down -v` without asking;
  schema checks on a throwaway database (as in BPA-42); no Jira changes.
- Not a story: no Gherkin acceptance criteria are implemented here. Test names for this Task are `BPA43_<camelCase>`; the BPA-25 JUnit checks
  keep their own key, `BPA25_<camelCase>` (L18).
- Environment finding: this machine has **no JDK and no Maven** (`java`, `mvn` not found); Docker 29.6.2 is present. See Q1.
- Open on Jira from BPA-42: Usama has not confirmed the data-model approval (waived by Ahsan, PR #17). Not blocking.

## Proposals (each justified; the ones marked "confirm" are also in Questions)

### P1. Camunda client library (record for ADR 0002)
**Camunda Spring Boot Starter, Spring Boot 3 variant: `io.camunda:camunda-spring-boot-3-starter:8.10.1`** (`@JobWorker` annotation, auto-complete,
config in `application.yml`; no hand-written client lifecycle). Justification:
- 08 proposed the Camunda Spring SDK; `@JobWorker` is what the Camunda starter provides; the older `spring-zeebe-starter` is deprecated.
- Alternatives rejected: `camunda-client-java` alone (no Spring wiring, we would write the worker lifecycle ourselves); TypeScript SDK (ADR 0002: workers are Spring Boot).
- Version equals the pinned server, 8.10.1 (compose and `.env.example` pin one patch for every Camunda component). Verified on Maven Central on 29 Oct:
  `camunda-spring-boot-3-starter`, `camunda-spring-boot-4-starter` and `camunda-process-test-spring-boot-3` / `-boot-4` all exist at 8.10.1.
- The plain `camunda-spring-boot-starter` and `camunda-process-test-spring` 8.10.1 are built on Spring Boot **4.1.1**, so they are the Boot 4 path. Do not mix.
- The decision is recorded here and in one added line in ADR 0002 "Consequences" (PR change), which also says "Boot 4 migration is a production item" (Q2).

### P2. Java and Spring Boot versions
- **Java 21** (LTS). Spring Boot 3.5 needs 17+; the Camunda Process Test guidance asks for 21+; GitHub's `ubuntu-latest` has Temurin 21.
- **Spring Boot 3.5.16** (the version `camunda-spring-boot-3-starter` 8.10.1 is built against), because 09 S21 (LOCKED) says the OpenAPI generator targets Spring Boot 3
  (ADR 0002, 0011). Risk accepted by Ahsan (Q2): Spring's open-source support for 3.5 ended June 2026 and Camunda's 8.10 default is Boot 4. This matters for production, not the POC; "Boot 4 migration is a production item" is recorded here, in the Risks and in the ADR 0002 line, so Faraz sees it.
- Maven 3.9 through a committed **Maven Wrapper** (`backend/mvnw`), so CI and every laptop use one Maven. Confirm: Q1.
- PostgreSQL 17 driver and Flyway through Spring Boot's managed versions (`flyway-core` plus `flyway-database-postgresql`); tests use Testcontainers PostgreSQL 17 (the compose version).

### P3. The one worker: `create-hour-journal-header` as a pass-through (accepted, Q3)
Every job type on the real model belongs to a story (`save-workpack` BPA-6, `create-hour-journal-header` BPA-7, `evaluate-release-inputs` BPA-8, and so on),
so any worker with real behaviour would implement acceptance criteria. Proposal: implement `create-hour-journal-header`
(`ServiceTask_CreateHourJournalHeader` in `Process_PPC_WorkPackAssignment`) as a **pass-through**: `@JobWorker(type = "create-hour-journal-header")` that logs the job key
and completes with no variables and no database access. Why this one:
- It needs no input variable and writes none, so the process can run on to the next element without inventing data. `evaluate-release-inputs` would have to produce
  `materialsReserved` and `drawingApproved`, which is BPA-8's logic.
- It proves what the skeleton must prove: the app connects to Camunda as `bpa-service` (BPA-25 permissions), activates a job of a real type from the auto-deployed real model,
  and completes it, and the Camunda Process Test can drive the real model through it.
- It has no behaviour for a story to contradict. BPA-7 replaces the body (upsert on `workpack_id`, D-numbers per BPA-7) and its own tests, test-first.
- The class and the test are named and commented as skeleton placeholders (`SkeletonCreateHourJournalHeaderWorker`), with the code comment "skeleton stub; BPA-7 replaces the body test-first". Its process test proves deploy, activation and completion only.
Alternatives considered: `evaluate-release-inputs` (read-only, also exercises the DMN, but it is BPA-8's logic); `record-release-signoff` and the others (write to the database).

### P4. Camunda Process Test in CI
- Library: `io.camunda:camunda-process-test-spring-boot-3:8.10.1` (test scope), JUnit 5; runtime image pinned to Camunda **8.10.1** in test properties so it matches compose.
  (The exact property name is confirmed while writing the first test; the CPT default image follows the library version.)
- It starts Camunda in Testcontainers, so the test job needs Docker. GitHub-hosted `ubuntu-latest` provides it.
- New workflow `.github/workflows/backend.yml`, job `backend-tests`: `actions/setup-java` (Temurin 21, Maven cache), `./mvnw -B verify` from `backend/`, then upload Surefire reports
  (`if: always()`). Runs on every pull request and on pushes to main, next to the existing `Lint models` and `Plan check` workflows.
  Part B adds job `frontend-tests` (Node 24, `npm ci`, `npm test`, `npm run build`).
- The same Maven command runs locally (Docker Desktop must be running). The first run pulls `camunda/camunda:8.10.1`.
- The BPA-25 JUnit checks need a Camunda with **basic auth and authorization enabled** (the CPT container has neither by default), so Part B starts its own Testcontainers `camunda/camunda:8.10.1`
  configured like `docker-compose.yml` (API protected, authorizations on, the demo admin initialisation from BPA-41). They are tagged `integration` and run in the `backend-tests` job; if they make CI too slow they move to a separate job, but stay in CI (Q9).

## Part A: backend skeleton (all tests green before Part B)
Order of work, each test written and shown failing before its code:
1. Maven project and wrapper, empty `BpaApplication`, a context-load test that fails (no datasource) for the expected reason, then datasource and Flyway config.
   `BPA43_applicationStartsAndFlywayAppliesV1AndSeed`: Testcontainers PostgreSQL 17; asserts the Flyway history shows V1 and the repeatable seed succeeded, 31 tables, 12 workers.
2. `BPA43_everyTableInBpaHasSorComment` (S17): every base table in schema `bpa` except `flyway_schema_history` has a table comment (same query as BPA-42). It fails first against a
   temporarily uncommented table in the test's own setup (a deliberate negative case), then passes against V1.
3. pom resource directories `../models` and `../templates`; `BPA43_modelsAndTemplatesAreReadFromTheTopLevelFolders`: the classpath holds `bpmn/Process_PPC_WorkPackAssignment.bpmn`,
   the other two BPMN files and `dmn/Decision_WorkpackReleaseCheck.dmn`, and `backend/` contains no `.bpmn`, `.dmn` or `templates` copy.
4. Auto-deploy: `@Deployment(resources = {"classpath*:bpmn/*.bpmn", "classpath*:dmn/*.dmn"})` on the application. `BPA43_modelsDeployToCamunda` (CPT): the three processes and the decision are deployed.
5. `BPA43_releaseCheckDecisionDeploysAndEvaluates` (CPT, L10): the DMN is deployed and `Decision_WorkpackReleaseCheck` returns its three documented results for the three input combinations
   in the DMN as written (the test reads the table; it does not extend it).
6. `BPA43_createHourJournalHeaderJobIsHandledByTheWorker` (CPT, one test): start `Process_PPC_WorkPackAssignment`, complete `UserTask_CreateWorkPack`, complete the `save-workpack` job from the test
   (BPA-6's worker does not exist), let the real skeleton worker handle `create-hour-journal-header`, assert `ServiceTask_CreateHourJournalHeader` completed and the token reached the next element.
7. `backend/Dockerfile` (multi-stage) and enabling `bpa-app` in `docker-compose.yml`; manual proof: `docker compose up -d --build`, app healthy, Flyway applied (throwaway database for the schema check), models deployed (Operate shows them).
8. `.github/workflows/backend.yml`; local full run `./mvnw -B verify`.
Part A gate: all of the above green locally and in CI on the pushed branch.

## Part B: login and identity (starts after the Part A gate)
9. BPA-25 checks 1-5 as JUnit (L18), `BPA25_...`, against a Testcontainers Camunda 8.10.1 with basic auth, using the `bpa-service` permissions BPA-25 recorded:
   PROCESS_DEFINITION `READ_USER_TASK`, `UPDATE_USER_TASK`, `COMPLETE_USER_TASK`, `UPDATE_PROCESS_INSTANCE` and `CREATE_PROCESS_INSTANCE` (needed for message-start processes) on the real processes
   (`Process_PPC_WorkPackAssignment`, `Process_PPC_WorkerReassignment`, `Process_PPC_DrawingRevisionNotice`), and MESSAGE `CREATE` on `*`. Shown to work in BPA-25, not proven minimal.
   Check 4 (BPMN error loop-back) and check 5 use the real model with the test acting as the missing story workers. Search endpoints are eventually consistent, so assertions poll.
10. S22 persona sync (`@Profile({"local","showcase"})`): tests first (`BPA43_personaSyncCreatesUsersGroupsAndScopeRows`, `..IsIdempotent`, `..AcceptsAnExistingBpaService`, `..DoesNotRunOutsideLocalAndShowcase`,
    `BPA43_personasYamlHoldsNoPasswords`). 9 users per L9: one per group for the seven S6 groups, a second `production-team` user in another department, a second `time-method-analyst`.
    Passwords come from `DEMO_PASSWORD` and `BPA_SERVICE_PASSWORD` in `.env`; `.env.example` gets placeholders only. Writes `user_role_scope` (the only runtime writer, L2).
11. `api/openapi.yaml` first (S20, ADR 0011), the generated interfaces, then login, `/me`, logout, CSRF, sessions: tests first (`BPA43_loginSetsAnHttpOnlySessionCookie`, `..WrongPasswordIsRefused`, `..MeReturnsUsernameAndGroups`, `..LogoutEndsTheSession`,
    `..StateChangingCallWithoutCsrfTokenIsRejected`, `..IdleSessionReturns401`, `BPA43_startWorkPackIsDeniedToOtherGroups403` and `BPA43_startWorkPackReturns501ForTimeMethodAnalyst` on `POST /api/v1/workpacks`, which checks the group only in the skeleton). Spring Security: `POST /api/v1/auth/login` verifies with Camunda basic auth
    and reads the groups once (BPA-25 check 3); HttpOnly, `SameSite=Lax` session cookie, in-memory sessions with `server.servlet.session.timeout` 30 minutes returning 401 on expiry (S27);
    `XSRF-TOKEN` cookie and `X-XSRF-TOKEN` header on every state-changing call, the login included, the token obtained from a prior `GET` (S26).
12. Minimal React login screen in `frontend/` (log in, see `/me`, log out) with a component test (`frontend-tests`); Vite dev server proxying `/api` to `bpa-app` (Q11; no compose `frontend` service until the showcase environment, 08 Gate 6). Manual proof: persona logs in through React on localhost, sees `/me`, logs out; the denied call returns 403.
13. Final: all tests locally, qa-reviewer (second run), REVIEW.md, plan Closed, push, PR, watch CI. Stop before merging.
Reviews (Q12): one qa-reviewer run after the Part A gate and one at the end, both recorded in REVIEW.md.

## Files to create or change
Part A
| File | Change |
|---|---|
| `backend/pom.xml` | new: Boot 3.5.16 parent, Java 21, deps per P1/P2/P4, resource directories `../models` and `../templates` |
| `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/maven-wrapper.properties` | new: Maven Wrapper |
| `backend/src/main/java/com/lcs/star/bpa/BpaApplication.java` | new: application and `@Deployment` |
| `backend/src/main/java/com/lcs/star/bpa/worker/SkeletonCreateHourJournalHeaderWorker.java` | new: the pass-through worker (P3) |
| `backend/src/main/java/com/lcs/star/bpa/{api,task,domain,persistence,adapter,config}/package-info.java` | new: one-line package docs so the S23 layout exists in git (only where no class lives yet) |
| `backend/src/main/resources/application.yml` | new: datasource (`bpa` database), Flyway, Camunda client (self-managed, basic auth as `bpa-service`), session timeout |
| `backend/src/test/java/com/lcs/star/bpa/...` | new: the tests in steps 1-6, plus test support (PostgreSQL container, CPT config) |
| `backend/src/test/resources/application-test.yml` | new |
| `backend/Dockerfile`, `.dockerignore` (repo root) | new: multi-stage build; build context is the repo root and `models/` and `templates/` are COPYed into the image build stage beside `backend/`, so the pom's `../models` and `../templates` resolve (Q10; L1 forbids copies in the `backend/` source tree, not in the image) |
| `docker-compose.yml` | change: `bpa-app` enabled; `build: {context: ., dockerfile: backend/Dockerfile}`; drop the `./backend/exchange` volume (S19 watched folder, replaced by D58); env from `.env` |
| `.env.example` | change: add `DEMO_PASSWORD`, `BPA_SERVICE_PASSWORD` placeholders (Part B uses them; added with Part A so the app boots) |
| `.github/workflows/backend.yml` | new: `backend-tests` (P4) |
| `docs/adr/0002-application-stack.md` | change: one line recording P1 under Consequences |
| `backend/README.md` | new: how to build, test and run (short) |
Part B
| File | Change |
|---|---|
| `backend/src/main/resources/personas.yaml` | new: 9 users, groups, scope; no passwords |
| `backend/src/main/java/com/lcs/star/bpa/config/` (security, persona sync), `adapter/` (Camunda REST client), `api/` (auth, me, one guarded call) | new |
| `backend/src/main/resources/application-local.yml`, `application-showcase.yml` | new: profile settings |
| `backend/src/test/java/com/lcs/star/bpa/...` | new: BPA-25 JUnit checks, persona sync, login tests |
| `frontend/` (`package.json`, `package-lock.json`, `index.html`, `vite.config.ts` with the `/api` proxy, `tsconfig*.json`, `src/*`, tests) | new: Vite + React + TypeScript login screen; `openapi-typescript` + `openapi-fetch` (S21). Vite is Ahsan's choice (Q8); the sources are silent |
| `.github/workflows/backend.yml` | change: add `frontend-tests` |
| `api/openapi.yaml` | new (Q7): OpenAPI 3.1 with only POST `/api/v1/auth/login`, POST `/api/v1/auth/logout`, GET `/api/v1/me`, POST `/api/v1/workpacks` (403 and 501); session-cookie security scheme; RFC 9457 errors; the `workpacks` request body marked `x-assumption: defined by BPA-6` |
| `backend/pom.xml` | also: `openapi-generator-maven-plugin` (`spring`, interface-only) generating the interfaces from `../api/openapi.yaml` |
| `.github/workflows/backend.yml` | also: a Redocly lint step for `api/openapi.yaml` |
Both
| File | Change |
|---|---|
| `intent/BPA-3-work-pack-assignment/plans/BPA-43.md` | this plan; Approved first, Closed last |
| `REVIEW.md` | one review-log entry (two entries: after Part A and at the end, Q12) |
| `intent/README.md` | regenerated by `intent/generate-index.sh` |
Not changed: `models/`, `templates/`, V1 and the seed, `docs/data-model.md`, Jira.

## Risks
- **No JDK or Maven on this machine.** Nothing can be built or tested until Q1 is answered. Mitigation: install JDK 21 with winget and use the wrapper (needs your yes).
- **Spring Boot 3.5 is out of OSS support (June 2026)** while 09 S21 / ADR 0011 lock Boot 3 (accepted by Ahsan, Q2: it matters for production, not the POC). Camunda ships the Boot 3 variants at 8.10.x. **Boot 4 migration is a production item**; it would change the two Camunda artifacts and the openapi-generator target, and needs an ADR 0011 amendment. For Faraz.
- **Worker start-up order.** The worker authenticates as `bpa-service`, which the S22 job creates; until Part B, and on a fresh stack before the job has run, the user does not exist. Mitigation: Part A's tests use their own Camunda; in the compose stack the Camunda client retries and the job creates the user at start-up (Q4).
- **Camunda search is eventually consistent** and the Camunda container is slow to start in CI. Mitigation: polling assertions with a time limit; one container per test class where possible; timeouts stated in the tests.
- **CI Docker pulls** (`camunda/camunda:8.10.1`, PostgreSQL 17) make the first run slow. Mitigation: Maven and Docker layer caching are not relied on for correctness.
- **Windows and Git Bash:** `MSYS_NO_PATHCONV=1` for every docker command that passes paths; line endings (`.sh`, `mvnw`) forced to LF through `.gitattributes` if the repo lacks it (checked while doing Part A step 1).
- **Size.** Part B is large (login, sync, BPA-25 checks, React). The Part A gate keeps a stop point in the middle; the plan is updated in the same commit as any change of scope.

## Proof
- Part A: `./mvnw -B verify` green locally and in CI; `docker compose up -d --build` brings up Camunda, PostgreSQL and `bpa-app` (healthy), Flyway applies V1 and the seed (checked on a throwaway database, not `down -v`), models deployed (Operate/REST list the three processes and the decision).
- Part B: BPA-25 JUnit checks green; persona sync run twice with the same result; a persona logs in through React on localhost, sees `/me`, logs out; the guarded call returns 403 for a user outside the group; no password string in `personas.yaml` or in git (a test and a grep).
- Test-first evidence: for each test the failing run and its reason are shown in the session and summarised in the PR.
- Review: qa-reviewer run and recorded in REVIEW.md; CI green on the PR; PR left unmerged.

## Questions and answers (Ahsan, 29 Oct 2026)
- **Q1 (toolchain): yes.** Install OpenJDK 21 with `winget` (Microsoft.OpenJDK.21); use the committed Maven Wrapper; no separate Maven install.
- **Q2 (Spring Boot): stay on Boot 3 (3.5.x)** with `camunda-spring-boot-3-starter` 8.10.1. S21 is locked to Boot 3 (ADR 0011); changing it is an ADR change, not a plan choice.
  The end of Boot 3.5's open-source support matters for production, not this POC. Recorded as a risk here and in the ADR 0002 line ("Boot 4 migration is a production item") so Faraz sees it.
- **Q3 (worker): accepted.** `create-hour-journal-header` as a pass-through that logs and completes, no variables, no database writes. Marked in code: "skeleton stub; BPA-7 replaces the body test-first". Its process test proves deploy, activation and completion only.
- **Q4 (`bpa-service` and the sync job's identity): decided.** ADR 0005: the S22 job creates `bpa-service` (password `BPA_SERVICE_PASSWORD`) with the least-privilege authorizations BPA-25 recorded.
  The job creates users, so it runs as the Camunda admin from config (`CAMUNDA_ADMIN_USER` / `CAMUNDA_ADMIN_PASSWORD`, defaulting to `demo`/`demo` per S29), only in the `local` and `showcase` profiles.
  `bpa-service` never gets admin rights. If `bpa-service` already exists it is left as it is and the fact is logged. `.env.example` gets placeholders for all four variables.
- **Q5 (personas): decided.** 9 users per L9. Usernames are in no source, so readable fakes marked ASSUMPTION in `personas.yaml`: `tma-1`, `tma-2`, `pe-1`, `planner-1`, `wpo-1`, `ppc-1`, `supervisor-1`, `pt-1`, `pt-2`.
  Scope (data model section 5.11): `workpack-owner` by department, `project-engineer` by project, others as needed. Only refs that exist in the seed (departments Fabrication and Painting; projects PRJ-139 and PRJ-140).
  `pt-1` is Fabrication and `pt-2` Painting (the L9 scope demo).
- **Q6 (the denied call): decided.** The real S20 endpoint `POST /api/v1/workpacks` (process start; actor `time-method-analyst`, D52). In the skeleton it checks the group only: 403 for anyone else, 501 Not Implemented for a `time-method-analyst`.
  BPA-6 implements it test-first later. Both responses are proved in tests.
- **Q7 (OpenAPI): yes.** Contract first (S20, ADR 0011): `api/openapi.yaml` (OpenAPI 3.1) with only the skeleton's endpoints, the `workpacks` request body marked `x-assumption` "defined by BPA-6", a session-cookie security scheme and RFC 9457 errors.
  Backend interfaces generated by the `openapi-generator` Maven plugin (`spring`, interface-only); Redocly lint added to CI. The full contract stays with S20's owner (Usama drafts, Ahsan reviews); stories add their endpoints in their own PRs.
- **Q8 (React scaffold): decided.** Vite + React + TypeScript, minimal, `openapi-typescript` + `openapi-fetch` (S21). Vite is Ahsan's choice; the sources are silent.
- **Q9 (BPA-25 JUnit checks): decided.** Testcontainers `camunda/camunda:8.10.1` configured like `docker-compose.yml` (API protected, authorizations on, the demo admin initialisation from BPA-41); not the Process Test container.
  Tagged `integration` and run in the `backend-tests` CI job; if CI gets too slow they move to a separate job but stay in CI.
- **Q10 (Docker build context): decided.** Repo root as build context (compose: context `.`, dockerfile `backend/Dockerfile`); `models/` and `templates/` are COPYed into the image at build time. L1 forbids copies in the `backend/` source tree, not in the image.
- **Q11 (React runtime): decided.** The Vite dev server for local work, proxying `/api` to `bpa-app` so the session cookie stays same-origin. A compose `frontend` service comes with the showcase environment (08 Gate 6), not now.
- **Q12 (reviews): decided.** Two qa-reviewer runs, one after Part A is green and one at the end, both recorded in REVIEW.md. One PR.

## Human-gap log
- 29 Oct: Jira BPA-43 read through the claude.ai Atlassian connector (the repo's `atlassian` server needs an interactive sign-in).
- 29 Oct: no JDK or Maven on the machine; the Part A start depends on Q1.
- 29 Oct: Maven Central checked for the Camunda 8.10.1 artifacts and their Spring Boot bases (3.5.16 for the `-3-` and `-boot-3` artifacts, 4.1.1 for the plain ones).
