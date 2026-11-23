# Plan: BPA-74 Approver can be reminded when hours wait more than 48 hours for their decision
Jira: BPA-74 (Story, epic BPA-4)
Story last updated: 2026-11-23T17:53:51.988+0400
Status: Approved (Ahsan, 23 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-74-approval-reminders, from main 3e9ff9d

> Written in plan mode and approved by Ahsan on 23 Nov 2026; committed as the branch's first commit.
> Checked read-only: the branch `BPA-74-approval-reminders` is at **3e9ff9d** (the merge of PR #64, BPA-73), not at 8fc497d as the brief says. BPA-73's code is on the branch, but nothing here depends on it (Q7).

## Context
Submitted hours can sit at an approval step (Timekeeper, Production Supervisor, PPC) and stall the weekly cut-off. FRD-5-12 §5.12.10 asks for a reminder after 48 hours. The PO decided on 12 Nov: the first reminder after 48 calendar hours, then once a day until the task is decided (the daily repeat is ASSUMPTION).

**The BPMN already has the timers:** a non-interrupting `R/PT24H` boundary event on each of the three approval tasks, leading to `ServiceTask_SendHoursReminder*` (job `send-hours-reminder`, headers `step` and `recipientGroup`). The job has **no worker yet**, so the timers fire into nothing. BPA-74 builds the worker, a place to store the in-system notice, and the group permissions.

It is backend only, unless Q3 says the notice needs a screen.

## Readiness check (Definition of Ready, docs/02, against Jira 23 Nov 17:53)
| Check | State |
|---|---|
| Jira status | **In Progress** (moved on 23 Nov 17:53; no content field changed since 17 Nov). Any other transition only when you say so (Q6) |
| Template, persona, outcome | Met (Timekeeper, Production Supervisor, PPC Team) |
| Gherkin incl. unhappy paths | Met: 5 scenarios (before 48 h; once decided) |
| BPMN element IDs exist | Met: `BoundaryEvent_HoursWaiting{Timekeeper,ProductionSupervisor,Ppc}` (non-interrupting, `R/PT24H`) and `ServiceTask_SendHoursReminder*` are in the model; the documentation names the rule and "idempotent on process instance key + step + timer firing" |
| **Jira DoR "BPMN change (boundary timers on the three approval tasks) agreed with Ahsan"** | **The timers are already in the model** (Q5): no BPMN change is needed; you tick the box |
| Every field has an SOR | Met (SOR Notes: `hours_submission.stage, submitted_at`, `hours_decision.decided_at`; writes notification rows; email via Mailpit; recipients from Camunda groups) |
| No blocking question | Q1 (where the notice is stored) changes the schema; Q3 may add a screen |
| intent.md / spec.md accepted | **Not met** (as every Epic 2 story) |
| Estimate ≤ 8 | 3 |

## What already exists (code; closed plans BPA-16, 53, 56, 57 and 64 are history only)
**The timers and the job** (`Process_PPC_TimesheetToProgress.bpmn` :232-296):
- Each timer fires every 24 h **from when the task is created** (+24 h, +48 h, +72 h, …).
- Each firing runs a new instance of `ServiceTask_SendHoursReminder<Step>` (job `send-hours-reminder`, `retries=3`) and ends at `EndEvent_HoursReminderSent<Step>`.
- With a 48 h check, the +24 h firing does nothing, the +48 h firing sends the first reminder, and each later firing sends again (daily). A decision completes the task, which cancels its timer.

**Data** (`docs/data-model.md`, V1/V2):
- `hours_submission(workpack_id, process_instance_key, round_no, submitted_by, submitted_at, stage [Timekeeper, ProductionSupervisor, Ppc, Closed], outcome)`.
- `hours_decision(hours_submission_id, step, decision [Validated, Approved, Returned, Rejected], decided_by, decided_at)`.
- A Return or Rejection closes the round; a re-send opens a new submission (new `round_no`, new `submitted_at`).

**Notices today (BPA-16):**
- `notification(workpack_id, drawing_ref NOT NULL, revision NOT NULL, recipient_username → app_user, channel [Email, InSystem], sent_at)`.
- It has `UNIQUE(workpack_id, drawing_ref, revision, recipient_username, channel)` and **no message column**.
- `NotificationRepository.noticesOf` (drawing revisions only, by recipient) backs `GET /api/v1/me/notifications`; the schema has `workpackNumber, drawingRef, revision, sentAt`.
- `emails(usernames)` reads `app_user.email`.
- **No portal screen shows these notices** (only the API).
- The email goes through `JavaMailSender` to Mailpit (`SendRevisionNoticeWorker`): the in-system row is committed first, a mail failure is not caught (3 tries, then an incident), and the sender is `bpa.mail.from`.

**Alerts (BPA-64):** `variance_alert` (own table, own `GET /api/v1/me/variance-alerts`, the "My alerts" panel `MyVarianceAlerts` on Home).

**Group members:**
- `CamundaGroupClient` (`POST /v2/groups/{groupId}/users/search` as bpa-service) needs **GROUP READ** per group (`PersonaSync` :57-64).
- Today it is granted on workpack-owner, project-engineer (BPA-16), cost-controller and project-manager (BPA-64). **timekeeper, production-supervisor and ppc-team are missing.**
- `PersonaSyncTest.expectedServicePermissions` pins the list; the `Bpa16GroupReadChecksTest` probe shows that without the permission Camunda answers 200 with an empty list.

**Tests:**
- Camunda Process Test runs via `ProcessTestApplication` (no database; DB workers excluded), as in `SkeletonWorkerProcessTest` and `Epic2DecisionTablesProcessTest`. `increaseTime` is not used yet, but it is available.
- The integration API tests call a worker's `handle` with a mocked job (BPA-67) and use `TestClock` and `Mailpit`.

## Design
### 1. `send-hours-reminder` (new `worker/SendHoursReminderWorker`, `timesheet/HoursReminderService`)
Inputs:
- headers `step` (Timekeeper, ProductionSupervisor, Ppc) and `recipientGroup`;
- the job's `processInstanceKey`, and `elementInstanceKey` (one per timer firing; a retry of the same job keeps it);
- the variable `workpackNumber`.

The rule, from the BPMN documentation and Jira:
1. **The open submission at this step:** the newest `hours_submission` of this process instance whose `stage` = the header's step. If there is none, the hours have moved on or were decided, so it **does nothing** and completes.
2. **When the hours reached the step:**
   - Timekeeper: `submitted_at` of that submission;
   - ProductionSupervisor: `decided_at` of its `hours_decision` (step Timekeeper, decision Validated);
   - Ppc: `decided_at` of its `hours_decision` (step ProductionSupervisor, decision Approved).
3. **The check:** if `now` (injected `Clock`) − reached < 48 h, it does nothing. Otherwise it sends to every user of `recipientGroup` (`CamundaGroupClient`):
   - an **in-system notice** (stored per Q1);
   - an **email** (Mailpit) to the user's `app_user.email`.
4. **The text** (Jira): **"Hours for <workpackNumber> are waiting for your decision since <d MMM yyyy>"**, the reached date in Asia/Riyadh (ADR 0013), for example "since 17 Nov 2026".
   - Email subject **ASSUMPTION**: "Hours for <WP> are waiting for your decision".
   - Body: the same sentence.
5. **Idempotent** on (process instance key, step, `elementInstanceKey`), stored with the notice:
   - a re-run of the same firing sends nothing new (no second row, no second email);
   - the next day's firing has a new element instance, so it sends again.
6. **Order and failures** (as BPA-16): the in-system row is committed first; then the email is sent and recorded (an Email row).
   - A mail failure is not caught: Camunda retries (3), then raises an incident.
   - "Any Email row for this key and recipient means the email went" (at-least-once if the process stops between the send and the record, ASSUMPTION as BPA-16).
7. **A return and re-send** (Q2): the new submission has its own `submitted_at`, and its new task has a new timer, so the 48 h count restarts.

### 2. Where the notice is stored (Q1). Recommended: **V7, extend `notification`** (the SOR Notes say "writes notification rows")
- `drawing_ref` and `revision` become NULL-able.
- New columns:
  - `kind` text NOT NULL DEFAULT 'DrawingRevision' CHECK IN ('DrawingRevision', 'HoursReminder');
  - `message` text NULL;
  - `reminder_key` text NULL.
- A CHECK: a DrawingRevision row has its drawing and revision; an HoursReminder row has a message and a key.
- A **partial unique index** `(reminder_key, recipient_username, channel) WHERE kind = 'HoursReminder'`. The existing UNIQUE stays (for drawing rows; NULLs do not collide).
- Existing rows default to `DrawingRevision`.
- **BPA-16 keeps working:**
  - `noticesOf` returns both kinds, newest first;
  - `emailRecorded` filters `kind = 'DrawingRevision'`;
  - the `Notification` schema gains `kind` and `message`, and `drawingRef` and `revision` become optional (absent on a reminder). That is a contract change; there is no screen to break.
  - `DrawingRevisionApiTest` and `DrawingRevisionMailDownApiTest` must stay green.
- The alternative is a separate `hours_reminder` table: BPA-16 is untouched, but it departs from the SOR Notes and adds a second notices read.
- **I add the migration only after your answer.**

### 3. Recipients and permissions
- `PersonaSync` gains `GROUP:timekeeper`, `GROUP:production-supervisor` and `GROUP:ppc-team` READ, as BPA-16 and BPA-64 did.
- `PersonaSyncTest.expectedServicePermissions` gains the same three. That is an existing test edit, said in the PR.
- **One notice and one email per user** of the group (Q4); with one persona per group it is the same as one per group.
- A member with no `app_user` email gets the in-system notice only, logged (as BPA-16).

### 4. Where the notice shows (Q3)
- **Recommended:** the API only, `GET /api/v1/me/notifications` (as BPA-16's notices, which no screen shows either). Backend only; a "My notices" panel can be its own story.
- Alternatives:
  - add a small "My notices" list on Home (frontend; it would show BPA-16's drawing notices too);
  - show reminders in "My alerts" (BPA-64), which is a different table and contract.

### 5. Docs
- Data model: §5.9 `notification` V7 columns, and an "As built by BPA-74" note.
- OpenAPI: the `Notification` schema and `listMyNotifications` text.
- ADR 0012 (notifications), if it describes the notification table, gets a one-line amendment.
- No BPMN change.

## Files that change (after the Q1 answer)
| Area | Files |
|---|---|
| Migration | `V7__notification_hours_reminder.sql` (Q1) |
| Backend | new `worker/SendHoursReminderWorker`, new `timesheet/HoursReminderService` (the rule), `persistence/NotificationRepository` (reminder writes, `kind` filter on `emailRecorded`, `noticesOf` with kind and message), a read of the open submission and its reached time (`HoursSubmissionRepository`), `config/PersonaSync` (three GROUP READ), `api/NotificationController` (kind, message) |
| Contract | `api/openapi.yaml` (`Notification`: `kind`, `message`, optional drawing fields) |
| Tests | new `HoursReminderApiTest` (integration, worker `handle` with a mocked job, `TestClock`, Mailpit), new `HoursReminderProcessTest` (Camunda Process Test, time travel); `PersonaSyncTest` (expected permissions, existing test edit); `ProcessTestApplication` leaves the new worker out of its scan |
| Docs | data model; plan; REVIEW.md; `intent/README.md` |
| Frontend | none (unless Q3 says a screen) |

## Order of work
1. Approve the plan and commit it (`Approved`).
2. Write the tests and run them **red before any production code** (compile stubs only). Commit.
3. V7 (after Q1), the repository, the service and worker, and `PersonaSync`. Then green, then the BPA-16 tests (`DrawingRevisionApiTest`, `DrawingRevisionMailDownApiTest`, `Bpa16GroupReadChecksTest`, `PersonaSyncTest`) and `FlywayBaselineTest` (V7).
4. Contract and data model; full `./mvnw -B verify`; the local check; `qa-reviewer`; fixes; REVIEW.md; plan Closed. Push, PR and Jira comments only when you ask.

## Scenario-to-test table
**`HoursReminderApiTest`** (integration: per-class Postgres, shared Camunda for the group read, Mailpit, `TestClock`):
- The worker's `handle` is called with a **mocked job**: headers `step` and `recipientGroup`, `processInstanceKey`, a fresh `elementInstanceKey` per firing, and the variable `workpackNumber` WP-000457.
- The submission and decisions are inserted by SQL with the Gherkin times.
- There is no real waiting: the clock is set to "when it is …".

**`HoursReminderProcessTest`** (Camunda Process Test with **time travel** `increaseTime`, the worker mocked) proves the timers: no reminder job before +24 h; a job at +24 h with the right headers; the next at +48 h; none after the task is completed.

| # | Scenario | Test | Arrange / act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | Reminder after 48 hours at the Timekeeper step | `BPA74_reminderAfter48HoursAtTimekeeper` | submission sent 17 Nov 09:00 Riyadh, stage Timekeeper; clock 19 Nov 09:00; job step Timekeeper, group timekeeper | an InSystem notice for `timekeeper` with "Hours for WP-000457 are waiting for your decision since 17 Nov 2026"; one email in Mailpit to timekeeper's address with that text | red (no worker) |
| 2 | No reminder before 48 hours | `BPA74_noReminderBefore48Hours` | same, clock 19 Nov 08:59 | no notice row, no email; the job completes | red: the stub fails the job |
| 3 | No reminder once decided | `BPA74_noReminderOnceDecided` | the Timekeeper validated at 18 Nov 10:00 (stage now ProductionSupervisor); clock 19 Nov 09:00; job step Timekeeper | no notice or email to timekeeper | red (stub) |
| 4 | Reminder follows the hours to the next step | `BPA74_reminderFollowsHoursToNextStep` | Validated at 18 Nov 10:00, stage ProductionSupervisor; clock 20 Nov 10:00; job step ProductionSupervisor, group production-supervisor | production-supervisor gets the notice and the email "… since 18 Nov 2026" | red |
| 5 | Reminder repeats daily until decided | `BPA74_reminderRepeatsDaily` | stage Ppc reached 17 Nov 09:00; a firing at 19 Nov 09:00 (element instance A), then a firing at 20 Nov 09:00 (element instance B) | two notices and two emails to ppc-team, one per firing | red |

**Extras:**
- `aRerunOfTheSameFiringSendsNothingNew` (the same element instance twice: one row, one email).
- `aResentSubmissionCountsFromItsOwnSubmittedAt` (Q2: round 2 sent 20 Nov; clock 21 Nov: nothing).
- `aMemberWithNoEmailGetsTheNoticeOnly`.
- `myNotificationsListsDrawingNoticesAndReminders` (BPA-16 not broken; kind and message).
- The CPT timer tests (`timerFiresEvery24HoursWhileWaiting`, `noTimerAfterTheDecision`).
- `PersonaSyncTest` (the three GROUP READ).

## Proof (one per Definition of Done line)
| DoD line | Evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest='HoursReminderApiTest,HoursReminderProcessTest' verify`; full `./mvnw -B verify`; `gh pr checks <n>` (Actions may not start) |
| Process runs end to end locally | rebuild `bpa-app`. The engine clock cannot be moved forward on the local stack (no controlled clock in compose), so instead:<br>1. As supervisor send hours for a Work Pack; its time sheet instance waits at `UserTask_ValidateHours`.<br>2. In psql set that submission's `submitted_at` 49 h back.<br>3. As demo/demo, on that **same** instance, activate `ServiceTask_SendHoursReminderTimekeeper` with a process instance modification (`POST /v2/process-instances/{key}/modification`); that is what the 24 h timer firing does.<br>4. See the email in Mailpit (http://localhost:8025) and the notice in `GET /api/v1/me/notifications` as timekeeper.<br>5. Run step 3 again: a second reminder for the new firing.<br>6. Set `submitted_at` back to now and run step 3: nothing (under 48 h). |
| BPMN and DMN match deployed | no model change; the deployed `Process_PPC_TimesheetToProgress` equals `models/` (hash) |
| Open questions resolved or logged | the Jira comments (shown first, posted on your yes) |
| Plan Closed | `grep '^Status: Closed'` |
| Demoed at sprint review | Human gap |

## Risks
- **The local check:**
  - the worker looks up the submission by the job's process instance key, so the reminder must run in the **real** time sheet instance;
  - the proof activates the reminder service task there with a process instance modification as demo/demo (not yet tried on this stack). If Camunda refuses it, the proof is the integration test plus the email in Mailpit from a test run against the compose stack's Mailpit, and the plan says so.
  - The Zeebe controlled clock (`ZEEBE_CLOCK_CONTROLLED=true`, `PUT /actuator/clock`) is not set up in compose; adding it is a compose change, out of scope.
- **V7 changes a BPA-16 table:** existing rows and both BPA-16 worker paths must keep working; covered by their tests.
- **The +24 h firing does nothing** by design (48 h rule); one wasted job per task per day.
- **Shared Camunda in tests:** the CPT test runs on its own engine; the API test calls `handle` directly, so no timer of the shared engine is involved.

## Questions
1. **Where the reminder is stored.**
   - **Recommended:** V7 extends `notification`:
     - `drawing_ref` and `revision` NULL-able;
     - `kind`, `message`, `reminder_key`;
     - a CHECK per kind;
     - a partial unique index on `(reminder_key, recipient_username, channel)` for reminders;
     - BPA-16's queries filter or include by `kind`;
     - the `Notification` contract gains `kind` and `message`.
   - Alternative: a separate `hours_reminder` table (BPA-16 untouched, but the SOR Notes say notification rows).
   - **No migration until you answer.**
2. **"Reached this step" after a return and re-send.**
   - **Recommended:** the new submission's own `submitted_at` (the clock restarts, as the timer does with the new task).
   - Alternative: the first round's time.
3. **Where the in-system notice shows.**
   - **Recommended:** `GET /api/v1/me/notifications` only, as BPA-16's drawing notices (no screen shows either today); a "My notices" panel can be its own story.
   - Alternatives: add that panel now (frontend), or use "My alerts" (BPA-64).
4. **One reminder per user or per group.**
   - **Recommended:** per user (a row and an email for each member of the group); with one persona per group it is the same.
5. **The Jira DoR box "BPMN change agreed with Ahsan":** the three non-interrupting `R/PT24H` timers and the `send-hours-reminder` service tasks are already in the model (BPA-48), so BPA-74 changes no BPMN. You tick it.
6. **Jira:** BPA-74 is In Progress. I'll ask before any transition.
7. **Branch base:** the branch is at 3e9ff9d (with BPA-73), not 8fc497d. Nothing here depends on BPA-73. Keep it, or rebuild from 8fc497d?
8. **ASSUMPTIONs:**
   - the email subject "Hours for <WP> are waiting for your decision";
   - the email body = the notice text;
   - the date format "d MMM yyyy" (17 Nov 2026) in Asia/Riyadh;
   - the +24 h firing does nothing;
   - idempotency on the timer firing's element instance key;
   - at-least-once email as BPA-16;
   - a member with no email gets the notice only.

## Resolutions (Ahsan, 23 Nov 2026, in plan mode)
- **Q1:** V7 extends `notification`:
  - `drawing_ref` and `revision` NULL-able;
  - `kind`, `message`, `reminder_key`;
  - a CHECK per kind;
  - a partial unique index for reminders;
  - BPA-16's queries keep working;
  - the `Notification` contract gains `kind` and `message`.
- **Q2, Q4:** the 48 h count restarts from the new submission's `submitted_at`; one notice and one email per member of the group.
- **Q3:** the API only (`GET /api/v1/me/notifications`), as BPA-16; no screen in this story.
- **Q5:** the timers are already in the model; you tick the DoR box.
- **Q7:** the branch stays at 3e9ff9d.
- **Q6, Q8:** as written. I ask before any Jira transition.

## Human-gap log
- **DoR:** "BPMN change agreed with Ahsan" is yours to tick (Q5); intent.md and spec.md not accepted.
- **DoD:** "Demoed at sprint review". People.
- **DoD:** "passing in CI". Actions may not start (billing).
