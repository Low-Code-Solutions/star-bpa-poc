# Plan: BPA-81 Settable business date for QA (BPA_CLOCK_TODAY, qa profile only)
Jira: BPA-81 (task, epic BPA-3)
Story last updated: 2026-11-11T12:05:49.452+0400
Status: Approved (Ahsan, Build Lead, 2026-11-11)
Approver: Ahsan (Build Lead)
Branch: BPA-81-qa-business-date, from main f285571


## Context
Many Epic 1 scenarios start "Given today is … (Asia/Riyadh)" (ADR 0013, 09 S9). Developers' tests replace the injected clock;
independent black-box QA (Zain; claude/14-qa-independent-test-strategy.md v0.3, PO decision 11 Nov 2026) cannot, so those
scenarios cannot be exercised without a setting. `ClockConfig.clock()` returns `Clock.systemUTC()` unconditionally. This task adds
a QA-only way to set the business date for a running stack: profile `qa` plus `BPA_CLOCK_TODAY=<yyyy-MM-dd>`.

## Session start: Jira against the request (Jira wins)
Read BPA-81 and its one comment (10517, Faraz, 11 Nov: "the one decision for you is the ASSUMPTION ... offset (moving) clock rather
than Clock.fixed ... if you prefer a frozen clock, please say so here before planning"). `updated` = 2026-11-11T12:05:49.452+0400
(in the header). Status Ready; labels agent-task, assumption, claude-generated; no assignee.
- **The ASSUMPTION has no answer in Jira.** I asked Ahsan in the session (11 Nov): **offset (moving) clock, confirmed.** It goes into the Jira comment
  I draft (not posted until you agree).
- **No contradiction of substance between the request and Jira.** The request adds detail Jira lacks (zone read from `app.business-zone`,
  boundary-case tests, empty-string rule, `qa` combined with other profiles, compose and `.env.example` text, close-out steps); all consistent.
- **Repo fact that differs from the text of both:** Jira and the request say "ten" test classes replace the clock; I count **14** (list below). The 11 Nov check
  predates BPA-13 to BPA-16 landing. The rule "they must pass untouched" is the same.

## Readiness check
| Item | State |
|---|---|
| Task, changes the repo | Yes |
| Estimate ≤ 8 | 2 |
| Done-when written (a Task has no Gherkin) | Yes (Jira 4 items; request adds boundary and zone cases) |
| No SOR, data, BPMN, DMN or OpenAPI change | Confirmed |
| PO approval | Moved to Ready 11 Nov (Faraz, comment 10517) |
| The ASSUMPTION | Answered by Ahsan in the session: offset clock |
| Blocking open question | None. Q1 needs your decision before the compose change; the rest have a recommendation |

## What the code looks like today (verified)
- **`ClockConfig`** (`config/ClockConfig.java`): `@Bean Clock clock()` returns `Clock.systemUTC()`; `@Bean ZoneId businessZone(@Value("${app.business-zone:Asia/Riyadh}"))`. `application.yml` has `app.business-zone: Asia/Riyadh`.
- **How tests replace the clock:** they add an extra `@Primary` bean (`TestClockConfig.testClock()`, bean name `testClock`), they do **not** override `clock`. So `ClockConfig.clock()` is still built in every one of those test contexts. The new code must therefore build without failing and without logging when `qa` is not active and the variable is unset. The test mechanism is not touched.
- **The 14 classes** (7 define their own `TestClockConfig`: AssignmentFileApiTest, HourJournalHeaderApiTest, PrintApiTest, ReleaseCheckApiTest, ScheduleApiTest, SignOffApiTest, WorkPackApiTest; 7 import one: ConfirmWorkPackApiTest and DigitalTimesheetApiTest, DrawingRevisionApiTest, DrawingRevisionMailDownApiTest, HourJournalLineApiTest, WorkerAssignmentApiTest, WorkerReplacementApiTest). Three unit tests (SendRevisionNoticeWorkerTest, AssignmentFileServiceTest, WorkerAssignmentServiceTest) build their own `Clock.fixed` and are not affected.
- **Profiles:** the repo has `local` and `showcase` (S22 persona sync: `@Profile({"local","showcase"})` in `PersonaSyncConfig`) and tests use `test`. **Nothing activates a profile named `qa`.** Compose hard-codes `SPRING_PROFILES_ACTIVE: local` (docker-compose.yml bpa-app).
- **Where the clock is read:** 15 main classes inject it (services, repositories, workers, `DrawingRevisionService`, `SendRevisionNoticeWorker`).
- **Not the app clock:** `DEFAULT now()` on 59 columns of V1, plus `updated_at = now()` in `PersonaSync.java:125` and `AssignmentFileRepository.java:114`; the engine's own timestamps.

## Design
**`config/QaClock.java`** (new, pure, package-private, easy to test):
- `static LocalDate parse(String raw)`: trims, `LocalDate.parse` (ISO, strict). A bad value throws `IllegalStateException("BPA_CLOCK_TODAY must be a date in the form yyyy-MM-dd (ISO 8601); received '<value>'")`, so the application fails at start-up naming the variable and the value (an impossible date such as 2026-02-30 is rejected by the strict ISO parser).
- `static Clock offsetTo(LocalDate target, Clock base, ZoneId zone)`: `days = ChronoUnit.DAYS.between(LocalDate.ofInstant(base.instant(), zone), target)`; returns `Clock.offset(base, Duration.ofDays(days))`. The offset is whole days, so the time of day is kept and keeps moving; the business date, read in `zone`, equals `target` at start-up.

**`ClockConfig.clock(Environment, ZoneId businessZone, @Value("${BPA_CLOCK_TODAY:}") String today)`** (same bean name and return type, so nothing that uses or replaces it changes):
- value unset or empty (after trim): `Clock.systemUTC()`, no log. Same as today.
- value set, profile `qa` not active (`environment.acceptsProfiles(Profiles.of("qa"))`): `Clock.systemUTC()` and a **WARN**: BPA_CLOCK_TODAY is set but profile qa is not active, so it is ignored. The value is not parsed in this case.
- value set, `qa` active: `QaClock.parse`, then `QaClock.offsetTo(target, Clock.systemUTC(), businessZone)`, and a **WARN** at start-up: "QA business date is <date> (BPA_CLOCK_TODAY, profile qa): the clock runs <n> days from the system clock in <zone>; time keeps moving; for QA only".
- The zone is the existing `businessZone` bean (`app.business-zone`); nothing is hard-coded.

**Compose and environment:**
- `docker-compose.yml` bpa-app: `BPA_CLOCK_TODAY: ${BPA_CLOCK_TODAY:-}` (empty when absent, treated as unset, so behaviour is unchanged).
- Profile activation (**Q1, needs your decision**): `SPRING_PROFILES_ACTIVE: ${BPA_PROFILES:-local}`. Without it the `qa` profile cannot be switched on from compose, so the setting would be unusable by Zain. QA sets `BPA_PROFILES=local,qa` (`qa` alone would lose the persona sync, which needs `local`). Default stays `local`, so nothing changes unless it is set.
- `.env.example`: commented placeholders `# BPA_PROFILES=local,qa` and `# BPA_CLOCK_TODAY=2026-12-01`, with the note: QA only; the clock keeps moving from that date; changing it means restarting bpa-app (`docker compose up -d bpa-app` re-creates it when the environment changed).

## Decisions the request asks for (ASSUMPTION unless said)
1. **Offset computed once at start-up, whole days, keeps the current time of day in the business zone (ASSUMPTION; Jira says nothing).** Recommendation: yes. It is simple and the time of day then behaves as in production. After midnight in the zone the date rolls on like a real clock; a QA run that must hold a date restarts `bpa-app`. A negative offset (a past date) works the same way.
2. **Empty string = unset (recommendation, ASSUMPTION).** Compose passes an empty value when the variable is not in `.env`.
3. **Variable set without `qa`: ignored with a WARN, and not parsed** (so a malformed value without `qa` does not fail start-up). Recommendation. Alternative: fail. ASSUMPTION.
4. **`qa` combines with other profiles; nothing activates `qa` today.** `qa` is only a switch; `local,qa` is the intended QA setting (see Q1). Not combined with `showcase` by default.
5. **Whitespace around the value is trimmed** (ASSUMPTION).

## Done-when to test
New class `ClockConfigBpa81Test` (JUnit, `ApplicationContextRunner` on `ClockConfig`, no Docker, no database). Tests first: they are written and run against the unchanged `ClockConfig`, and fail because the variable is ignored; `QaClock` is a compile-only stub that throws until the build.

| # | Done when | Test (all prefixed `BPA81_`) |
|---|---|---|
| 1 | `qa` + `BPA_CLOCK_TODAY=2026-12-01`: business date is 2026-12-01; two reads are ordered | `qaWithADateGivesThatBusinessDateAndAnOrderedClock` (reads `LocalDate.now(clock)` in the business zone; second read ≥ first) |
| 2 | Year boundary and leap day | `businessDateAcrossYearBoundaryAndLeapDay` (2026-12-31, 2027-01-01, 2028-02-29) |
| 3 | The date is read in `app.business-zone`, not UTC, when the two dates differ | `dateIsReadInTheBusinessZoneNotUtc`: `QaClock.offsetTo` with a fixed base at 21:00:00Z and 23:59:59Z (already the next day in Riyadh): the result's Riyadh date equals the target and its UTC date does not; and the same base instant with `Asia/Kolkata` gives a different offset (zone comes from config, not hard-coded) |
| 4 | `local` (or no profile) + variable set: system clock and a WARN | `withoutQaTheVariableIsIgnoredAndWarned` (clock within a few seconds of the system clock; WARN asserted with `OutputCaptureExtension`) |
| 5 | Unset or empty: system clock, no WARN | `unsetOrEmptyVariableKeepsTheSystemClockWithoutAWarning` |
| 6 | Malformed value fails start-up with a clear message | `malformedValueFailsStartUpNamingTheVariableAndValue` (2026-02-30, 2026-13-01, 01-12-2026, tomorrow, 2026-12-01T00:00:00Z: the runner's `getStartupFailure()` message names BPA_CLOCK_TODAY and the value) |
| 7 | Offset active: start-up WARN names the date | `offsetActiveLogsAWarningNamingTheDate` |
| 8 | `qa` combines with `local` | `qaCombinesWithLocal` |
| 9 | Time of day kept; past dates work | `offsetIsWholeDaysSoTheTimeOfDayIsKept`, `aPastDateGivesANegativeOffset` |
| 10 | Existing tests unchanged and green; CI green | The 14 classes and the unit tests untouched (`git diff` shows no change under their files); `./mvnw -B verify` green; the PR's six checks read by me |

## Files
- New: `backend/src/main/java/com/lcs/star/bpa/config/QaClock.java`, `backend/src/test/java/com/lcs/star/bpa/config/ClockConfigBpa81Test.java`, `intent/BPA-3-work-pack-assignment/plans/BPA-81.md`.
- Changed: `backend/src/main/java/com/lcs/star/bpa/config/ClockConfig.java`, `docker-compose.yml`, `.env.example`, `REVIEW.md`, `intent/README.md` (generated).
- Not changed: how the 14 classes replace the clock, `application.yml`, Camunda or engine time, data, BPMN, DMN, OpenAPI, the frontend, CLAUDE.md, ADR 0013.

## Order of work
1. Confirm branch `BPA-81-qa-business-date` and main at f285571; commit this plan (Approved) as the first commit.
2. Tests first: `ClockConfigBpa81Test` plus a compile-only `QaClock`; run and show the failures and why (red).
3. Build `QaClock` and the `ClockConfig` change; green; run the 14 classes' unit-level neighbours and then the full `./mvnw -B verify` in the background (about 30 min).
4. Compose and `.env.example`; `docker compose config` shows the variables render (no stack launched).
5. `qa-reviewer`; fix its findings, updating the plan in the same commit when the work departs from it; `REVIEW.md`; plan `Closed`; push; PR; read CI myself; draft the Jira comment for you (not posted).

## Risks
- **The React portal does not follow the QA date.** `utils/dates.ts` `todayInRiyadh()` reads the browser's clock and drives the Create Work Pack "Date to complete" minimum and the "cannot be in the past" message (`WorkPackFields.tsx`, `fieldMessages.ts`). With `BPA_CLOCK_TODAY` set, the browser still uses the real date, while the backend re-check (D20) uses the QA date: a QA tester can see the screen accept a date the server then rejects, or the reverse. Fixing it needs the backend to expose its date (an API change) and a frontend change, both out of scope here (Q6).
- **Mixed timelines in the database.** App-written timestamps follow the QA clock; DB-side `now()` (two statements plus the V1 defaults) and Camunda's own times (task creation, instance start) stay real. "Audit timestamps stay ordered" holds for the app clock only. Accepted and noted for QA.
- **Session expiry (S27) is not a differentiator.** The 30-minute idle timeout is Tomcat's, on the JVM's real time, so it works under any app clock. The offset is chosen for ordering (two reads ordered, created-before logic), not for sessions.
- **A QA switch in a production-shaped image.** Mitigations: needs the `qa` profile, never defaults on, WARN at every start-up, and a malformed value stops the app.
- **All 14 integration classes build `ClockConfig.clock()`.** It must not fail or log in them; proven by the full verify, and test 5.
- **Compose profile variable** (Q1): a mistake there could drop `local` and with it the persona sync. Default is `local`.

## Proof
Red-then-green output of `ClockConfigBpa81Test`; `git diff --stat main` showing no test file of the 14 classes changed; full `./mvnw -B verify`; `docker compose config` rendering `BPA_CLOCK_TODAY` and the profile variable; CI read from `gh pr view`.

## Questions
- **Q1 (needs your decision):** add `SPRING_PROFILES_ACTIVE: ${BPA_PROFILES:-local}` to compose so `qa` can be switched on from `.env`? Recommended yes; without it the feature cannot be used through compose. This is a small addition to the compose scope the task lists.
- **Q2:** ASSUMPTION confirmed by you today: offset clock. To be recorded in the Jira comment (draft only).
- **Q3 to Q5:** the recommendations in "Decisions" (empty = unset; variable without `qa` ignored and not parsed; whitespace trimmed). Please confirm or change.
- **Q6:** the portal's browser-based "today" will disagree with the QA date. Propose a follow-up Jira item (backend exposes its business date, portal uses it). I will not create it without your confirmation.
- **Q7:** README or ADR 0013 line about the QA date? Not in scope as written; a README line for Zain may be worth adding (your call).
- **Info:** the clock-replacing test classes number 14, not ten. BPA-80's PR #39 was merged while its backend-tests were still running; I am watching that run and will report its result.

## Human-gap log
- **Manual compose check (Ahsan), not done in a test:** in `.env` set `BPA_PROFILES=local,qa` and `BPA_CLOCK_TODAY=2026-12-01`; run `docker compose up -d bpa-app`; confirm the start-up WARN naming 2026-12-01 appears (`docker compose logs bpa-app | grep -i "business date"`). Then remove both lines, run it again, and confirm there is no such WARN and `GET /api/v1/...` dates are today's.
- **Zain:** how to set the date: the two `.env` lines above and `docker compose up -d bpa-app`; changing the date means restarting `bpa-app`.
- **Browser check (cannot be run here):** with the date set, the Create Work Pack screen's date checks (see the risk above).
- Docker Desktop and the stack are not needed for the unit tests; the full `./mvnw -B verify` needs Docker (Testcontainers) and `subst S:` plus `JAVA_HOME` per the local build notes.
