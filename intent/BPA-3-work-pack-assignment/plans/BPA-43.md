# Plan: BPA-43 Walking skeleton: Spring Boot backend, Flyway, model deploy, one worker, login and CI
Jira: BPA-43 (Task, epic BPA-3)
Story last updated: 2026-10-29T19:19:11.850+0400
Status: Closed (2026-10-30)
Approver: Ahsan (Build Lead); approved 29 Oct 2026 (answers to Q1-Q12 below)
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
3. pom resource directories `../models` and `../templates`; `BPA43_modelsAreReadFromTheTopLevelFolders` (as built; planned as one test `..ModelsAndTemplates..`): the classpath holds `bpmn/Process_PPC_WorkPackAssignment.bpmn`,
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
| `backend/pom.xml` | new: Boot 3.5.16 parent, Java 21, deps per P1/P2/P4, resource directories `../models` and `../templates`, `micrometer-core` excluded and banned by `maven-enforcer-plugin` |
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
| `docs/adr/0002-application-stack.md` | change: Consequences record P1, the Boot 4 production item and the Micrometer finding |
| `backend/README.md` | new: how to build, test and run (short) |
| `.gitattributes` | new: LF for `mvnw` and `.sh` (Risks, Windows line endings) |
| `backend/src/test/java/com/lcs/star/bpatest/ProcessTestApplication.java` | new: Camunda Process Test application (workers and models only, no database), in its own package as the CPT documentation asks |
| `backend/src/test/java/com/lcs/star/bpa/SorComments.java` | new: test support, the S17 table-comment query shared by the SOR tests |
Part B
| File | Change |
|---|---|
| `backend/src/main/resources/personas.yaml` | new: 9 users, groups, scope; no passwords |
| `backend/src/main/java/com/lcs/star/bpa/config/` (security, persona sync), `adapter/` (Camunda REST client), `api/` (auth, me, one guarded call) | new |
| `backend/src/main/resources/application-local.yml`, `application-showcase.yml` | **not needed (as built)**: the profiles change nothing but whether `PersonaSyncConfig` loads (`@Profile`); every setting is in `application.yml` with `.env` overrides |
| `backend/src/test/java/com/lcs/star/bpa/...` | new: BPA-25 JUnit checks, persona sync, login tests |
| `frontend/` (`package.json`, `package-lock.json`, `index.html`, `vite.config.ts` with the `/api` proxy, `tsconfig*.json`, `src/*`, tests) | new: Vite + React + TypeScript login screen; `openapi-typescript` + `openapi-fetch` (S21). Vite is Ahsan's choice (Q8); the sources are silent |
| `.github/workflows/backend.yml` | change: add `frontend-tests` |
| `api/openapi.yaml` | new (Q7): OpenAPI 3.1 with only POST `/api/v1/auth/login`, POST `/api/v1/auth/logout`, GET `/api/v1/me`, POST `/api/v1/workpacks` (403 and 501); session-cookie security scheme; RFC 9457 errors; the `workpacks` request body marked `x-assumption: defined by BPA-6` |
| `backend/pom.xml` | also: `openapi-generator-maven-plugin` (`spring`, interface-only) generating the interfaces from `../api/openapi.yaml` |
| `.github/workflows/backend.yml` | also: a Redocly lint step for `api/openapi.yaml` |
| `backend/src/main/java/com/lcs/star/bpa/adapter/CamundaIdentityClient.java`, `CamundaAdminClient.java` | new (as built): login check (`GET /v2/authentication/me`) and the admin calls of the S22 sync |
| `backend/src/main/java/com/lcs/star/bpa/config/PersonaFile.java`, `PersonaSync.java`, `PersonaSyncConfig.java`, `SecurityConfig.java` | new (as built) |
| `backend/src/main/java/com/lcs/star/bpa/api/AuthController.java`, `MeController.java`, `WorkPackController.java` | new (as built); the `api`, `config` and `adapter` `package-info.java` files removed now that classes live there |
| `backend/src/test/java/com/lcs/star/bpa/{IntegrationCamunda,CamundaRest}.java`, `api/TestBrowser.java` | new (as built): test support |
| `backend/Dockerfile`, `.dockerignore` | also: `api/` copied into the build stage (the generator reads `../api/openapi.yaml`) |
| `docker-compose.yml`, `.env.example` | also: the Part A admin identity removed; `SPRING_PROFILES_ACTIVE=local`, `CAMUNDA_ADMIN_*` passed to the app |
| `docs/adr/0005-identity-and-authorisation.md` | change (as built): Consequences: `.env.example` placeholders done; `bpa-service` deploys the models and holds RESOURCE CREATE (ASSUMPTION) |
| `frontend/README.md`, `frontend/.gitignore` | new (as built); `src/api/schema.d.ts` is generated, not committed |
| `frontend/.gitkeep` | deleted (as built): the folder now holds the app |
Both
| File | Change |
|---|---|
| `intent/BPA-3-work-pack-assignment/plans/BPA-43.md` | this plan; Approved first, Closed last |
| `REVIEW.md` | one review-log entry (two entries: after Part A and at the end, Q12) |
| `intent/README.md` | regenerated by `intent/generate-index.sh` |
| `models/bpmn/Process_PPC_WorkPackAssignment.bpmn` | change (Q13, as built): `validationMessages` output mapping on `BoundaryEvent_WorkPackInvalid`, `BoundaryEvent_CorrectionInvalid`, `BoundaryEvent_ScheduleInvalid` |
Not changed: `templates/`, V1 and the seed, `docs/data-model.md`, Jira. (`models/` was listed here until Q13.)

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

- **Q13 (open, 30 Oct): the real model's error boundary events lose `validationMessages`.** BPA-25 check 4 fails on the real model (Human-gap log, 30 Oct).
  Options: (a) add `<zeebe:ioMapping><zeebe:output source="=validationMessages" target="validationMessages" /></zeebe:ioMapping>` to the three
  boundary events on this branch (as the BPA-25 spike model had); (b) a new Jira item for the model, with check 4 failing or disabled here until then.
  **Answer (Usama, 30 Oct): (a), fix it on this branch.** Done: the three boundary events carry the output mapping; bpmnlint 0 errors (the 4 form
  warnings are intended, S14); `BPA25_check4` and the model tests pass. Outside the plan's original scope (`models/` was "not changed"), so the PR
  says so for Ahsan.

## Human-gap log
- 29 Oct: Jira BPA-43 read through the claude.ai Atlassian connector (the repo's `atlassian` server needs an interactive sign-in).
- 29 Oct: no JDK or Maven on the machine; the Part A start depends on Q1.
- 29 Oct: Maven Central checked for the Camunda 8.10.1 artifacts and their Spring Boot bases (3.5.16 for the `-3-` and `-boot-3` artifacts, 4.1.1 for the plain ones).
- 29 Oct: Part A pieces were committed (1a1b9b4) while the worker process test was a known failure, not disabled. **Root cause found the same day:** with `micrometer-core`
  on the classpath (it comes with `spring-boot-starter-actuator`), the Camunda Spring starter 8.10.1 (Boot 3 variant) activates the job and enters `JobHandlerInvokingBeans.handle`, but never
  invokes the `@JobWorker` method or completes the job, and logs nothing. Reproduced with a plain Testcontainers Camunda 8.10.1 and no Process Test library; the same worker completes once
  `micrometer-core` and `micrometer-jakarta9` are excluded from the actuator starter in `backend/pom.xml`. Dependency versions were clean (Boot 3.5.16, Framework 6.2.19, `-boot-3` Camunda artifacts).
  Workaround in place; the underlying defect (starter metrics path or its executor wrapper) is not diagnosed and should be reported to Camunda or rechecked on a later 8.10.x patch. Consequence for the
  app: no Micrometer metrics (actuator health is unaffected).
- 29 Oct: Ahsan accepted the Micrometer exclusion and added three items: (1) a `maven-enforcer-plugin` `bannedDependencies` rule for `io.micrometer:micrometer-core` and
  `micrometer-jakarta9` (`ban-micrometer-core`, runs in `validate`; checked: passes on the clean pom, fails with the message when the exclusion is removed) in place of keeping the probe test;
  (2) one line in ADR 0002 Consequences and in the PR description: "camunda-spring-boot-3-starter 8.10.1: workers never run while micrometer-core is on the classpath (found under BPA-43).
  Micrometer is excluded and banned; recheck on the next 8.10.x patch and then report it to Camunda."; (3) the Part B integration Camunda matches `docker-compose.yml` as closely as practical:
  API protected, authorizations on, the demo admin initialisation and **PostgreSQL** secondary storage (ADR 0001). H2 only if PostgreSQL makes the setup unreasonable; if so it is stated here.
  Rule from Ahsan: any single failure that takes more than 20 minutes is stopped and reported.
- 30 Oct (Usama with Claude Code): Jira re-read; `updated` 29 Oct 19:19 is newer than this plan, but the only change is the assignee (Ahsan → Usama). Scope unchanged.
- 30 Oct: this session runs on Linux with no JDK and no sudo, so Q1's winget install does not apply. Maven runs through the wrapper inside `eclipse-temurin:21-jdk`
  with the host Docker socket for Testcontainers (command in `backend/README.md`). Part A `./mvnw -B verify`: 8 tests, 0 failures, BUILD SUCCESS.
- 30 Oct: step 7 proved: `docker compose up -d --build` from a390017 brings `bpa-app` up (`/actuator/health` UP); Flyway applied V1 and the seed to an empty `bpa`
  schema; the three processes and `Decision_WorkpackReleaseCheck` deployed; the `create-hour-journal-header` worker started. `backend/mvnw` was committed
  without the executable bit (the Dockerfile calls `sh ./mvnw`, CI calls `./mvnw`); fixed. The S23 `package-info.java` files and `backend/README.md` added.
- 30 Oct: qa-reviewer review 1 (Part A, at a8bb6f2): 0 blocker, 2 Important, 10 nits. Handled:
  (1) **Deviation, recorded:** a390017 (Ahsan) makes `bpa-app` connect to Camunda as the admin (`CAMUNDA_CLIENT_AUTH_USERNAME/PASSWORD` from `CAMUNDA_ADMIN_*`),
  not `bpa-service` (S25, ADR 0005), because the S22 job that creates `bpa-service` is Part B. The env vars override `application.yml` permanently, so the
  compose comment now says so, and **Part B deletes both lines** and proves the stack runs as `bpa-service`. Step 7's proof was therefore obtained as admin.
  (2) CI gate: `backend.yml` runs on PRs only; a draft PR is opened for the Part A CI run (Usama, 30 Oct). Part B starts after it is green.
  Nits: header `Story last updated` bumped; step 3 test names and the file table brought in line with the code; the copy check also catches `.xlsx`;
  `camunda.process-test.camunda-docker-image-version` confirmed in the 8.10.1 starter's configuration metadata.
- 30 Oct: **disagreement reported, not resolved here:** the epic `spec.md` (Delivery order item 5 and the V1 row) still says the walking skeleton has the
  `workpack` table only (L5), while Jira BPA-43 and this plan apply the full merged V1 and seed from BPA-42 (31 tables). Jira wins by precedence; spec.md
  needs updating by its owner in its own change.
- Open nits for later (not fixed): Dockerfile runs as root; no compose healthcheck for `bpa-app`; `../models` as a resource root puts any non-model file at
  the jar root; the CPT container logs "Expected to handle request, but an unexpected error occurred" during green runs (not investigated).
- 30 Oct: **Part A CI gate not yet met when Part B started.** Usama chose a draft PR for the Part A CI run, but `gh` is not installed here and the
  session may not read the stored GitHub credential, so the PR could not be opened from the session. On Usama's instruction ("just get it done")
  Part B went ahead on the local green run; CI runs once the PR is opened. Departure from the plan's "Part B starts only when every Part A test is
  green locally and in CI".
- 30 Oct: Part B built test-first. Every new test was run against stubs first and failed for the expected reason (default Spring Security 401
  and no XSRF-TOKEN cookie; `PersonaSyncConfig` without `@Profile`; the stubbed sync created nothing, so `bpa-service` could not deploy, 401),
  except `BPA43_personasYamlHoldsNoPasswords`, which passed at once because `personas.yaml` was written first. One test edit after the first
  green run: `BPA43_personaSyncCreatesUsersGroupsAndScopeRows` expected `planner-1` before `pe-1`; the query sorts by username, so the
  expected order was wrong, not the code. React: 2 Vitest tests failed first (no form), then passed.
- 30 Oct: **Departures from the plan, as built:**
  (1) `bpa-service` also holds RESOURCE `CREATE` on `*` (**ASSUMPTION**): the app auto-deploys the models (Part A step 4) and, connected as
  `bpa-service` (S25), needs it. BPA-25 deployed as admin, so its set lacks it. Proved by `Bpa25CamundaChecksTest` (deploys as `bpa-service`) and
  on the compose stack. Recorded in ADR 0005.
  (2) The sync runs as a `SmartInitializingSingleton`, not a start-up runner, so it finishes before the Camunda client deploys and opens workers.
  (3) `personas.yaml` also carries `name`, `employeeRef` and `email`, because the sync is the only runtime writer of `app_user` (data model
  5.10; NOT NULL columns). Values synthetic, ASSUMPTION.
  (4) The sync makes `bpa-service`'s authorizations complete on every run (adds missing ones; the user and its password are left as they are),
  so an existing `bpa-service` from the BPA-25 kit still gets the full set.
  (5) The integration Camunda uses one PostgreSQL database for Camunda (schema public) and Flyway's schema `bpa`; compose uses two databases.
  (6) Personas get no Camunda authorizations; the backend acts for them as `bpa-service` (S14). `BPA25_check2` grants `tma-1` its own
  permissions in the test, as the BPA-25 kit did.
  (7) `GET /api/v1/me` also hands out the XSRF-TOKEN cookie (every response does), so no extra CSRF endpoint was added to the contract.
- 30 Oct: **Found by `BPA25_check4BpmnErrorReturnsTheTaskWithValidationMessages`: the real model does not carry `validationMessages` back.**
  `BoundaryEvent_WorkPackInvalid` (and `BoundaryEvent_CorrectionInvalid`, `BoundaryEvent_ScheduleInvalid`) in
  `models/bpmn/Process_PPC_WorkPackAssignment.bpmn` have no `zeebe:ioMapping` output for `validationMessages`; the BPA-25 spike model had one.
  On 8.10.1 the error variables stay in the boundary event's scope, so the returned `UserTask_CreateWorkPack` cannot read them (reproduced on
  the compose stack: the variable has the boundary event's `scopeKey`). ADR 0006 / D56 depend on it. The task does return. `models/` is out of
  this plan's scope; see Questions.
- 30 Oct: Proof on the compose stack (rebuilt from this branch): `bpa-app` starts with profile `local`; "Persona sync done: 9 personas, 7 groups,
  bpa-service"; models deployed as `bpa-service`; through the Vite dev server proxy: `/me` 401 anonymous, `tma-1` login 200 with its groups,
  `/me` 200, `POST /api/v1/workpacks` 501, logout 204 then `/me` 401, `pt-1` gets 403 problem+json on the Work Pack start. A real instance started
  by `bpa-service`, task completed on `tma-1`'s behalf, `save-workpack` completed by hand: the app's worker completed
  `ServiceTask_CreateHourJournalHeader` as `bpa-service`. The Part A admin-identity deviation (review 1) is closed. Port 5173 was taken by another
  project on this machine, so Vite ran on 5190. Not done: clicking through the React screen in a browser (covered by the component test and the
  proxy calls).
- 30 Oct: qa-reviewer review 2 (whole Task, at 2ebb550): 0 blocker, 6 Important, 8 nits. Handled in code, test-first:
  login against an unreachable or oddly answering Camunda now returns a 503 problem, not a 401 that React showed as "Wrong username or password"
  (`BPA43_camundaUnreachableAtLoginReturns503`, React `BPA43_loginServiceDownIsNotShownAsWrongPassword`; both failed first: 401, and the generic
  message); `/error` is permitted so error dispatches keep their status; the CSRF token is renewed at login and cleared at logout
  (`BPA43_csrfTokenAndSessionChangeAtLogin` failed first: same token). Tests strengthened, passing at once because the code was already right:
  the 403 test asserts the group reason (not a CSRF 403), and `BPA43_loggedInPostWithoutCsrfTokenIsRejected` covers a logged-in POST without the
  token. Contract: login declares 400 and 503. Login returns the groups sorted, as `/me` does. Check 4 sends `validationMessages` as strings
  (ADR 0006). Files table: the two profile yml files marked "not needed (as built)", `.gitkeep` deletion listed, `intent/README.md` regenerated.
  Documented, not changed: the sync only adds group memberships and never deletes users; removing a persona or one of its groups from
  `personas.yaml` needs a manual step in Camunda Admin (scope rows are pruned).
  **Open, for people with the authority:** RESOURCE CREATE on `*` for `bpa-service` (Faraz and Ahsan to accept, or deploy with another identity);
  Q13 model change (Ahsan to accept in the PR; BPA-6 to be told); the CI-gate departure (Ahsan); Jira comments for the ASSUMPTIONs and the
  spec.md/L5 disagreement (drafted for Usama's yes); CI green on the PR. Left as nits: job activation in checks 4/5 can lock another instance's
  job for 60 s (one test JVM, no clash seen); Dockerfile runs as root; no `bpa-app` healthcheck; `bpa-service` and `demo` can open portal
  sessions (both are Camunda users; no rule excludes them).
- 30 Oct: after the review-2 fixes, a full run had one setup error: Camunda reports healthy before its demo-admin initialisation finishes, so
  the first admin call got 401. `IntegrationCamunda` now waits until the admin can log in (test support only). The same race can make
  `bpa-app` fail its first start on a fresh compose stack; `restart: unless-stopped` retries it. Probe on the compose stack with the fixed model:
  `WORKPACK_INVALID` thrown without `validationMessages` raises no incident, and the task comes back.

- 30 Oct: final run at 511b611: `./mvnw -B verify` in `eclipse-temurin:21-jdk`, 31 tests, 0 failures, BUILD SUCCESS; frontend `npm test`, 3 tests passed.
  Plan closed before the PR. CI has not run yet (no `gh` in the session; Usama opens the PR in the browser). If CI or review needs fixes, they
  update this plan in the same commit and it stays Closed with a dated line here.
