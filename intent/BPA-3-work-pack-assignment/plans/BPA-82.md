# Plan: BPA-82 Portal date checks follow the QA business date set by BPA_CLOCK_TODAY
Jira: BPA-82 (task, epic BPA-3)
Story last updated: 2026-11-11T17:44:46.997+0400 (re-read before the PR: status In Progress, no comments; description as read at the start)
Status: Closed (11 Nov 2026)
Approver: Ahsan (Build Lead)
Branch: BPA-82, from main 4cbfa56 (the branch already existed locally with no commits of its own, so it was used and fast-forwarded to main; CLAUDE.md: use the existing branch)
This file is the first commit on the branch. Plan mode was read-only, so the git steps ran after approval. I check `git branch --show-current` before every commit.

## Readiness check
| Item | State |
|---|---|
| Task, changes the repo | Yes |
| Estimate | None in Jira; suggested 2 (Q4) |
| Gherkin / done-when | Jira gives one done-when; scenarios below are my ASSUMPTION |
| SOR, data, BPMN, DMN | No change; the API gains one read (contract first) |
| PO approval | Not given: status In Progress (moved by Ahsan 11 Nov), approach awaits Faraz (Q1); Ahsan said to start |
| Blocking open question | None; Q1 to Q3 carry recommendations |

## Context
BPA-81 (PR #40) lets QA set the backend business date (`qa` profile + `BPA_CLOCK_TODAY`). The portal does not follow it:
`todayInRiyadh()` in `frontend/src/utils/dates.ts` formats the **browser's** `new Date()` in Asia/Riyadh. It drives (a) the "Date to
complete" `min` in `WorkPackFields.tsx:136` and (b) the "Date to complete cannot be in the past" message in `fieldMessages.ts:34`. With the
QA date set, the screen can accept a date the server then rejects (`WorkPackValidator`, D20, `isBefore(today)` on the injected clock) or the
reverse, so Zain cannot test the "Given today is … (Asia/Riyadh)" scenarios through the screen. Fix: the backend tells the portal its
business date, and the portal uses it.

## Session start: Jira against the request
- Read BPA-82 (view evidence). Status **In Progress** (you moved it 11 Nov 17:37), no comments, no estimate, labels claude-generated,
  assumption, forms. Parent BPA-3. `updated` is in the header.
- **No Gherkin and no PO decision yet** on the approach (the item says so; it is my own proposal from BPA-81 Q6). Definition of Ready for a
  Task is not formally met (no estimate, no Gherkin, approach unconfirmed by Faraz). You told me to start, so I proceed with the scenarios
  below marked ASSUMPTION; I will not edit the Jira fields. I will comment the ASSUMPTIONs (shown to you first) and estimate in the PR.
- The epic's `intent/BPA-3-work-pack-assignment/spec.md` has a Delivery order and shared contracts; I read it at build time for any rule on
  new endpoints (none found for a business-date read).

## What the code looks like today (verified)
- `GET /api/v1/me` (`MeController`, `api/openapi.yaml:123`, schema `Me`) returns username and groups only. The portal loads it once at start-up
  and at login (`authService.fetchMe`), so a date placed there would go stale across midnight in a long-lived tab.
- `WorkPackValidator.validate(input, today, …)` takes today from the injected `Clock` + `businessZone` (the QA clock when BPA-81 is active).
  `SaveWorkPackScheduleWorker` does the same (`ScheduleValidator`, lead time). The schedule screen shows the server's messages, no local date rule.
- Only two portal uses of `todayInRiyadh()` (above); both flow through the shared `useWorkPackForm` (Create Work Pack BPA-6 and Confirm Workpack
  BPA-23). `showDate` is formatting only, not a clock read.
- Existing test `BPA6_dateToCompleteBoundary` fakes the browser clock (`vi.setSystemTime`) and expects the Riyadh date from it, including the
  21:30Z case. Reference lists use TanStack Query hooks in `features/reference-data/hooks/useReferenceLists.ts` over `referenceService`.
- No existing "business date" API. Controllers do not inject `Clock` today (workers and services do).

## Design
**API (contract first, `api/openapi.yaml`):** `GET /api/v1/business-date` (tag `business-date`, operationId `getBusinessDate`), open to every
logged-in user (401 without a session, like `/departments`; x-assumption), response `BusinessDate { date: string (format date),
zone: string }`, for example `{ "date": "2026-12-01", "zone": "Asia/Riyadh" }`. Source: BPA-82, 09 S9, ADR 0013.
Why a new read and not `/me`: `/me` is identity, fetched once; the date must be fresh when the screen opens and after midnight (ASSUMPTION; the
Jira text allows either; I recommend the separate read).

**Backend:** `BusinessDateController implements BusinessDateApi` (generated): `LocalDate.ofInstant(clock.instant(), businessZone)` using the
injected `Clock` and `businessZone` beans, the same two the validators use, so the portal and the server cannot disagree. No config, no new
bean. No change to `ClockConfig`, `QaClock`, workers, BPMN, DMN, data.

**Frontend:**
- `reference-data` (or `workpacks`) service `getBusinessDate(api)` and hook `useBusinessDate(api)` (TanStack Query, key `['business-date']`,
  `staleTime: 0` so each screen mount refetches; refetch on window focus stays on, covering midnight). Returns the date string.
- **Fallback (ASSUMPTION):** if the call fails or has not answered yet, use `todayInRiyadh()` (today's behaviour), because the save step
  re-checks the date anyway (S14). `todayInRiyadh()` stays in `utils/dates.ts` for that.
- `useWorkPackForm(api)` calls `useBusinessDate(api)`, exposes `today`, and `check: () => fieldMessages(form, today)`. `fieldMessages(form, today)` compares
  with that date (see Build notes for the changes after review). `WorkPackFields` uses `state.today` for `min`. `CreateWorkPack` and `ConfirmWorkPack` call `useBusinessDate(api)` and pass it.
- Generated types: `npm run gen:api` regenerates `src/services/schema.d.ts` (git-ignored, never edited by hand).
- Docs: the `new-task-screen` skill line "Business dates: `todayInRiyadh()`" is a repo skill file under `.claude/`; **I do not edit `.claude/`**
  (guard rules); I list the stale line in the PR for you. The README QA paragraph (BPA-81) says the portal uses the browser date: I update that
  sentence in this PR.

## Scenarios to test (ASSUMPTION: written by me; Jira has none; named `BPA82_`)
| # | Scenario | Test |
|---|---|---|
| 1 | The date is the business date in Asia/Riyadh from the clock, not UTC (21:30Z gives the next day) | backend `BPA82_businessDateIsTheRiyadhDateFromTheClock` |
| 2 | With the clock set to 2026-12-01 (the QA case) it answers 2026-12-01 and the zone | backend `BPA82_businessDateFollowsAReplacedClock` |
| 3 | No session: 401 | backend `BPA82_businessDateNeedsALogin` |
| 4 | Backend says 2026-12-01, browser clock says 2026-10-21: the picker `min` is 2026-12-01, 2026-11-30 is refused with "Date to complete cannot be in the past", 2026-12-01 is accepted | frontend `BPA82_screenUsesTheBackendDateWhenItIsLater` |
| 5 | Backend date earlier than the browser's: a date the browser would call past is accepted | frontend `BPA82_screenUsesTheBackendDateWhenItIsEarlier` |
| 6 | The call fails (500): the screen falls back to the browser's Riyadh date and still works | frontend `BPA82_screenFallsBackToTheBrowserDateWhenTheCallFails` |
| 7 | Confirm Workpack follows the same date | frontend `BPA82_confirmWorkpackUsesTheBackendDate` |
| 8 | Existing `BPA6_dateToCompleteBoundary` and BPA-23 tests still pass; their stub answers the new path with the Riyadh date of the faked clock | unchanged assertions; any stub change said in the PR |

Test-first: each test is written and run red for the expected reason (404 for the missing path; the screen using the browser date), then the code.
Backend test follows the existing API test pattern (Testcontainers Postgres, shared Camunda, `AssignmentFlow.loggedInAs`, a `@Primary` clock).

## Files
- New: `backend/.../api/BusinessDateController.java`, `backend/src/test/.../BusinessDateApiTest.java`, frontend service/hook and tests, plan file.
- Changed: `api/openapi.yaml`, `frontend/src/services/schema.d.ts` (generated), `fieldMessages.ts`, `useWorkPackForm.ts`, `WorkPackFields.tsx`,
  `CreateWorkPack.tsx`, `ConfirmWorkPack.tsx` and their tests, `README.md` (one sentence), `REVIEW.md`, `intent/README.md` (generated).
- Not changed: `ClockConfig`, `QaClock`, `.claude/`, BPMN, DMN, data, Camunda, the schedule screen.

## Order of work
1. Branch from latest main (`git switch main && git pull`); commit this plan (Approved) first.
2. Contract: edit `api/openapi.yaml`; run api lint locally if available; `npm run gen:api`.
3. Tests first: backend `BusinessDateApiTest` and the frontend tests; run, show red and why.
4. Build controller, service/hook, wiring; green; run the frontend suite (`npm test`, lint, format, `tsc`) and the new backend test, then the full
   `./mvnw -B verify` in the background (~30 min).
5. Manual check: the compose stack with `BPA_PROFILES=local,qa` and `BPA_CLOCK_TODAY=2026-12-01`, a browser on the portal: the Create Work Pack
   date picker minimum is 01 Dec 2026 (I can drive the API; the browser look is yours or via a browser tool if available). Restore defaults after.
6. `qa-reviewer`; fix; `REVIEW.md`; plan `Closed`; push; PR (title "BPA-82: …", body per CLAUDE.md); read the six CI checks myself.
7. Draft the Jira comments (linkage + ASSUMPTIONs); show; post on your yes. Ask before any status move.

## Risks
- **Second source of "today" in the UI:** a stale or failed read falls back to the browser date, so the original mismatch can reappear in that
  case. Accepted: the server re-check is the authority (S14), and the fallback equals today's behaviour.
- **Extra request on each Create/Confirm screen open:** one small read, cached per mount; no load concern at POC scale.
- **Test stubs:** existing screen tests that stub `fetch` return 404 for the new path; the fallback keeps them green, but I add the path to the stubs
  so they exercise the real route.
- **Generated client drift:** `schema.d.ts` must be regenerated and committed, or `frontend-tests`/api-lint fail.
- **Midnight in the zone:** the date refreshes on screen open and window focus, not on a timer; a screen left open across midnight keeps the old
  date until focus. The server still decides.
- DB `now()` and Camunda times remain real (unchanged from BPA-81).

## Proof
Red-then-green output of the new tests; `npm test`, lint, format, `tsc`; full `./mvnw -B verify`; the compose check with the QA date; the six CI
checks from `gh pr checks`.

## Proof (11 Nov 2026)
- **Red first (1f0f48d):** backend `BusinessDateApiTest`: 2 of 3 failed with 404 (no endpoint); the 401 test passes at once (it guards the login rule). Frontend: 3 of 17 failed for the expected reason (the picker `min` stayed at the browser's 21 Oct); `BPA82_screenFallsBackToTheBrowserDateWhenTheCallFails` passed at once (it guards today's behaviour).
- **Green (a78fdec, 2061b5e):** `BusinessDateApiTest` 3 of 3; frontend 56 of 56, `tsc --noEmit`, ESLint and Prettier clean; full `./mvnw -B verify`: **328 tests, 0 failures, 0 errors** (run at a78fdec; 2061b5e touches only the frontend and the line endings of two new Java files).
- **Compose (manual, done):** rebuilt `bpa-app` and `frontend` with `BPA_PROFILES=local,qa BPA_CLOCK_TODAY=2026-12-01`: logged in as `time-method-analyst` through http://localhost:3000 (the portal's own origin) and `GET /api/v1/business-date` answered `{"date":"2026-12-01","zone":"Asia/Riyadh"}`; the log showed the QA WARN; the served bundle contains the call. Defaults restored afterwards. **Not done:** a look at the date picker in a browser (no browser tool here).

## Build notes
- Departures from the design above: (1) `schema.d.ts` is git-ignored and generated by `npm run gen:api`, not committed. (2) `useWorkPackForm(api)` calls `useBusinessDate(api)` itself instead of receiving `today`; the hook state exposes `today`. (3) `refetchOnWindowFocus: true` is set on this one query because the app's default is off. (4) After review: while the first answer is pending, `useBusinessDate` returns undefined, so the screen shows no minimum and skips the local past-date check; only a failed call falls back to the browser's Riyadh date. (5) The fallback test waits for the minimum to appear, so it can go red.
- The branch `BPA-82` already existed locally with no commits of its own; it was fast-forwarded to main (4cbfa56) and the plan is its first commit.
- The new Java files use CRLF like their neighbours.

## Review (qa-reviewer at 69d6de4, 11 Nov 2026) and what was done
0 Important, 1 Medium, 6 Low (details in REVIEW.md). Fixed: the Medium (fallback while pending, point 4 above); the fallback test's weakness; CRLF; this plan's stale text. Recorded, not changed: the singular path `/api/v1/business-date` against docs/04's plural rule (`/me` is a precedent); no format check on the 200 body (a contract break only); the date is not refreshed by a timer across midnight (it refreshes on screen open and window focus); the `new-task-screen` skill line (`.claude/skills/new-task-screen/SKILL.md:51`) still names `todayInRiyadh()`: `.claude/` is not edited here, for Ahsan.

## Questions
- **Q1 (Faraz, PO):** confirm the approach: a separate `GET /api/v1/business-date` read rather than a field on `/me` (my recommendation above).
- **Q2:** confirm the fallback to the browser date when the read fails (alternative: show no minimum and rely on the server). Built as the fallback; no minimum only while the first answer is pending.
- **Q3:** the scenarios above are mine (Jira has none). Khitaba may want to rewrite them as Gherkin in Jira; I do not edit the item.
- **Q4:** estimate: I suggest 2 points; for Faraz/Ahsan to set in Jira.
- **Info:** the `new-task-screen` skill (`.claude/skills/new-task-screen/SKILL.md:51`) will mention `todayInRiyadh()`; I will not edit `.claude/`.

## Human-gap log
- Browser check of the Create Work Pack and Confirm Workpack date pickers with the QA date set (needs a person; the API and bundle were checked, the screen was not looked at).
- Faraz's decision on Q1, and the skill-file line in `.claude/`.
- PR merge and the Jira status moves: Ahsan.
