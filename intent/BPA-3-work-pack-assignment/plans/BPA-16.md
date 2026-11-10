# Plan: BPA-16 Production / Workpack Owner can be notified when a drawing linked to their Work Pack is revised
Jira: BPA-16 (Story, epic BPA-3)
Story last updated: 2026-11-10T11:15 (status moved to In Progress and assignee set; the content last changed 2 Nov 13:04)
Status: Approved (Ahsan, Build Lead, 2026-11-10)
Approver: Ahsan (Build Lead)
Branch: BPA-16-drawing-revision-notice, from main 831155a


## Context
D365 Document Control is the SOR for drawings. When a new revision is registered there, the Work Pack owners and the Project
Engineer must hear of it, so that no one works against a superseded drawing.

The POC has no way to receive that event and no notices. BPA-16 builds the self-contained `Process_PPC_DrawingRevisionNotice`,
started by a mock D365 endpoint `POST /api/v1/drawing-revisions`:
- the endpoint records the revision in the read-only `drawing_revision` copy, applies the Superseded status the event reports
  (never decided by the POC), and starts the process;
- `find-revision-recipients` finds the users of the groups `workpack-owner` and `project-engineer`, for Work Packs that are
  Released or In Progress on that drawing;
- `send-revision-notice` stores an in-system notice per recipient and sends one email per recipient per revision to Mailpit,
  with times in Asia/Riyadh;
- `GET /api/v1/me/notifications` shows the logged-in user their own notices.

Nothing here is a task-API flow, so `new-task-screen` does not apply.

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met (Production / Workpack Owner) |
| Gherkin, testable, unhappy paths | Met: 12 scenarios (2 outlines: 3 and 4 rows) |
| BPMN IDs exist | Met: StartEvent_DrawingRevised (message `DrawingRevised`), ServiceTask_FindRecipients (`find-revision-recipients`, retries 3; outputs `recipients`), Gateway_HasRecipients (`=count(recipients) > 0`, default no), SendTask_NotifyRecipients (`send-revision-notice`, retries 3, parallel multi-instance over `recipients` as `recipient`), EndEvent_RecipientsNotified / EndEvent_NoRecipients |
| SOR per field | Met: drawing_revision (D365 Document Control copy), notification (POC database; drawing_ref and revision D365; recipient_username Camunda identity), app_user email (corporate email, ASSUMPTION), groups (Camunda identity). Data model §5.9, §5.10 |
| DMN complete | n/a |
| No blocking question | Met: C15, C16, D11, D41–D46, D53, D60, D63, D68 decided; STAR question 4 non-blocking |
| ≤ 8 points | 3. **My estimate is about 6:** mail infrastructure, a new Camunda group read, two workers, two endpoints, 12 scenarios in two test classes |
| Spec accepted | Met (Faraz, 2 Nov) |
| PO approved | Faraz, 28 Oct (comment 10388). **The 2 Nov re-approval is recorded only by Ahsan's edit** of the DoR line (same as BPA-15); no Faraz comment on BPA-16 |

Disagreements, reported rather than resolved silently:
- **The BPMN documentation is stale.** ServiceTask_FindRecipients says "owners by department role, PE by project role (D43)".
  The story (PO, 2 Nov: seven personas, no scope) says "users in Camunda group workpack-owner, plus users in group
  project-engineer". The story wins (CLAUDE.md precedence). The model is not changed: a model changes only when the AC
  require it, and the IDs and flow are right. The PR names the stale text.
- **The AC Background still names project PRJ-139 and department Fabrication.** These are leftovers from before scope was
  removed; no scenario uses them.
- **CSRF (your question):** it is **not** set up. BPA-46 only documented the exemption in the contract (`security: []`,
  x-assumption "BPA-16 implements the exemption in SecurityConfig") and left `SecurityConfig` unchanged. So today a POST without a
  session would get 401, and one with a session but no token 403. BPA-16 adds both `permitAll` and the CSRF exemption for
  `POST /api/v1/drawing-revisions` only (09 S28).

## Design: `POST /api/v1/drawing-revisions` (contract exists; `api/DrawingRevisionController`)
1. **Validate:**
   - a required field missing gives 400 "drawingRef, revision, status and registeredAt are required" (the contract's
     x-assumption);
   - a status outside IFR, IFA, IFI, IFC, As-Built, Superseded gives 400 "Unknown drawing status" (D60). The generated enum
     fails on read, so a handler maps it.
2. **Unknown drawing** (not in `drawing`) gives 404 "Drawing not found", with nothing written or sent.
3. **One transaction** (`persistence/DrawingRevisionRepository`, `drawing/DrawingRevisionService`):
   - insert the revision with `ON CONFLICT (drawing_ref, revision) DO NOTHING`. Nothing inserted means a duplicate: 200
     `{"message":"Revision already received"}`, and nothing is sent (D46);
   - if `supersededRevision` is given, set that revision's status to Superseded. If no such row exists, write nothing and log
     the warning "Superseded revision Z9 not found for DWG-118" (D45, D53). The POC never decides Superseded itself;
   - **send `DrawingRevised` inside the transaction, before the commit** (correlate call):
     - correlation key `drawingRef/revision`, so revisions of one drawing never block each other;
     - variables `drawingRef`, `revision`, `status`, `registeredAt`, and `supersededRevision` when given (D68: a process
       variable only, never stored).

     If the engine cannot be reached, the transaction rolls back and the answer is 503 (the contract's EngineUnavailable).
     D365 can then send again, and it is not "already received" (09 G6: the revision is recorded synchronously).
   - 201.
4. **Not restricted** (09 S28): no session, group or CSRF token.
5. **User and audit:** `drawing_revision` is a reference-data copy, out of `audit_log` scope (data model 6.1). Its
   `created_by` / `updated_by` is `bpa-service` (D63: no user in context).

"Latest revision" is the one with the most recent `registered_at` (D42); the label is text, so X1 after B is fine.

## Design: the workers (skill `new-job-worker`)
**`find-revision-recipients` (`worker/FindRevisionRecipientsWorker`):**
- **Work Packs:** those with `drawing_ref` = the event's drawing and status Released or In Progress (D11).
- **No such Work Pack:** `recipients = []`.
- **Otherwise, recipients:** the usernames of Camunda groups `workpack-owner` and `project-engineer`, deduplicated (D43 as
  simplified 2 Nov; D46), each with its email from `app_user` keyed by username (D63). A user with no `app_user` row is
  skipped with a warning.
- **Empty recipients** (either way): log "No recipients for DWG-118 C". The gateway then ends at EndEvent_NoRecipients.
- **Outputs:**
  - `recipients`, a list of `{username, email}`;
  - `workpacks`, a list of `{id, number}`, which the send step uses.
- **Pure read:** it writes nothing, so a retry is safe.

**Reading group members, a decision for you to approve:** `bpa-service` has no identity read today. Its permissions
(PersonaSync, S25) cover process definitions, messages and resources only. Plan:
- a new `adapter/CamundaGroupClient.usernames(groupId)` (`POST /v2/groups/{groupId}/users/search`, as `bpa-service`);
- add **GROUP READ for the two groups only** (`workpack-owner`, `project-engineer`, least privilege) to PersonaSync's
  `SERVICE_PERMISSIONS`. PersonaSync adds missing permissions on start-up, so an existing stack gets them too.

A probe test records Camunda's answer first, without and then with the permission, as in BPA-13. If a different permission
name turns out to be needed, I record it and tell you before building on it. This changes the S25 permission set, so it is an
ASSUMPTION and a Jira question.

**`send-revision-notice` (`worker/SendRevisionNoticeWorker`, one job per recipient; BPMN: store the in-system notice, then
send the email):**
1. Insert one `InSystem` notification per Work Pack for the recipient, with `ON CONFLICT DO NOTHING` on the unique key
   (workpack, drawing, revision, recipient, channel). It commits on its own before the email is sent, so the notice survives a
   mail failure (scenario "Mail server unavailable").
2. **If the recipient already has the `Email` rows for this revision, do nothing** (a retry after the email went out; D44, 09
   S7). Otherwise send one email (`JavaMailSender`) to the `app_user` email:
   - subject "Drawing DWG-118 revised to C";
   - plain-text body: the Work Pack numbers, the drawing, the previous revision (`supersededRevision`, or "none"), the new
     revision, the status, and "registered at 28 Oct 2026 09:30 (Asia/Riyadh)" (`registeredAt` in the `ZoneId` bean, 09 S9).

   Then insert one `Email` row per Work Pack.
3. **If the mail server is down**, the exception propagates and the Camunda default applies: 3 retries, immediate, then an
   incident (09 S7: no custom backoff).
- `sent_at` comes from the injected `Clock`. `created_by` / `updated_by` is `bpa-service` (no user in context, D63).
- Notification rows are POC workflow output and are not audited (they are themselves the record, as the result rows). This is
  an ASSUMPTION and a question.
- The from-address is `star-bpa@example.com` (ASSUMPTION, in config).

## Design: `GET /api/v1/me/notifications` (contract exists; `api/NotificationController`)
- The session user's `InSystem` notices only: `recipient_username` = the authenticated name. They are joined to `workpack` for
  `workpackNumber` and sorted newest `sent_at` first (ASSUMPTION order).
- No parameter is read, so `?recipientUsername=…` or `?username=…` changes nothing (09 S6).
- Every logged-in persona may call it (`authenticated()`); anonymous callers get 401.

## Mail infrastructure (09 S8)
- **`backend/pom.xml`:** `spring-boot-starter-mail` (managed by Spring Boot).
- **`application.yml`:** `spring.mail.host` and `port` from env, defaulting to `mailpit:1025`, plus the from-address.
- **`docker-compose.yml`:** a `mailpit` service (`axllent/mailpit`, an exact version pinned and checked on Docker Hub at build
  time; SMTP 1025, web 8025). `bpa-app` points its mail settings at it.
- **Tests:** a Mailpit Testcontainer (`GenericContainer`, same pinned image). Assertions read Mailpit's HTTP API
  (`/api/v1/messages`) and filter by subject.

## Scenario-to-test table
Two classes:
- **`DrawingRevisionApiTest`:** the integration Camunda with workers on, Mailpit, `@DirtiesContext`, and the `@BeforeAll`
  clean-up.
- **`DrawingRevisionMailDownApiTest`:** the same, but mail pointed at a closed port, with a spy on `JavaMailSender` that counts
  attempts.

Test data:
- Work Packs are taken to Released the real way (`AssignmentFlow.released()`, which uses DWG-118); In Progress and Completed are
  set by hand (Epic 2 sets them).
- **Every test posts its own revision label, and DWG-118 revisions are always IFC**, so the release check of later Work Packs
  stays green. The release check reads the latest non-Superseded revision and needs IFC or As-Built.
- Scenario 1's fixed `registeredAt` (28 Oct 06:30Z) runs first (`@Order(1)`); later tests use increasing times after it, so each
  new revision is the latest when it is asserted.
- The Background's "latest revision B" is seeded by hand (insert B; the copy is otherwise only changed by events).

| # | Scenario | Test |
|---|---|---|
| 1 | Revision notifies owner and PE | BPA16_revisionNotifiesOwnersAndProjectEngineer: a Released Work Pack on DWG-118; POST C IFC superseding B, 28 Oct 06:30Z. 201; C is the latest; B is Superseded; Mailpit has one email each for `workpack-owner@example.com` and `project-engineer@example.com` with subject "Drawing DWG-118 revised to C" and a body listing the Work Pack, DWG-118, previous B, new C, IFC, "28 Oct 2026 09:30 (Asia/Riyadh)"; each user's `GET /me/notifications` has the notice; the instance ends at EndEvent_RecipientsNotified |
| 2 | No supersededRevision | BPA16_eventWithoutSupersededRevisionKeepsStatus: DWG-130 (B IFC in the seed); POST C IFR without it. C is the latest and B is still IFC |
| 3 | Named revision not in copy | BPA16_supersededRevisionNotInCopyIsLogged: DWG-118 new revision IFC superseding Z9. 201, it is the latest, no revision becomes Superseded, and the log shows "Superseded revision Z9 not found for DWG-118" (`OutputCaptureExtension`) |
| 4 | Same revision twice | BPA16_sameRevisionTwiceIsIgnored: the same body again gives 200 "Revision already received"; no new email and no new notification row |
| 5 | Nobody to notify | BPA16_nobodyToNotify: a spy on `CamundaGroupClient` answers no users (removing real memberships in the shared Camunda would break other classes; said in the test). No email, the log shows "No recipients for DWG-118 X", and the instance ends at EndEvent_NoRecipients |
| 6 | Drawing not linked | BPA16_unlinkedDrawingNotifiesNobody: DWG-121 (no Work Pack) revision B. No notification, no email, EndEvent_NoRecipients |
| 7 | Released or In Progress only (outline) | BPA16_onlyReleasedOrInProgressWorkPacksAreNotified: one revision; Work Packs on DWG-118 that are Released, In Progress (by hand), Draft and Completed (by hand). Notices exist for the first two, not for the others, and the email lists only those two |
| 8 | Label taken as given | BPA16_revisionLabelIsTakenAsGiven: DWG-130 X1 after B, with a later `registeredAt`. X1 is the latest |
| 9 | Unknown drawing | BPA16_unknownDrawingIsRejected: DWG-999 gives 404 "Drawing not found"; nothing written, no instance, no email |
| 10 | Status values (outline) | BPA16_unknownStatusIsRejected: DWG-130 D IFA, E IFI and F As-Built give 201; G DRAFT gives 400 "Unknown drawing status" and nothing written |
| 11 | Own notices only | BPA16_userSeesOnlyOwnNotices: after a revision, `workpack-owner` sees only their own notice, also with `?recipientUsername=project-engineer` and `?username=project-engineer` |
| 12 | Mail server down | BPA16_mailServerDown (mail-down class): a revision on a Released Work Pack. Both in-system notices are stored; each recipient's job is tried 3 times (spy: 6 sends for 2 recipients) and ends in an incident; no `Email` row |

Other tests:
- **The probe:** group members as `bpa-service`.
- **CSRF / session:** a POST with no session and no token is accepted (S28), while another POST endpoint still requires the
  token.
- **400 for a missing field.**
- **503 when Camunda is unreachable:** a spy on `CamundaProcessClient`; nothing is recorded.
- **Re-run:** `send-revision-notice` run twice for the same recipient sends one email and writes no second rows (mocked
  `JobClient`).
- **The email is sent once per recipient per revision**, even with two Work Packs (the body lists both).
- **Anonymous `GET /me/notifications`** gives 401.

All 12 scenario tests fail first: there is no endpoint, worker or mail setup, and the `drawing-revisions` POST gets 401. Any
test that passes against a stub is named in the PR.

## Files that change
- Backend:
  - new `api/DrawingRevisionController`, `api/NotificationController`, `drawing/DrawingRevisionService`,
    `persistence/DrawingRevisionRepository`, `persistence/NotificationRepository`, `adapter/CamundaGroupClient`,
    `worker/FindRevisionRecipientsWorker`, `worker/SendRevisionNoticeWorker`;
  - `config/SecurityConfig` (permitAll and CSRF exemption for the POST);
  - `config/PersonaSync` (GROUP READ for the two groups);
  - `pom.xml` (mail);
  - `application.yml` (mail).
- Tests:
  - new `DrawingRevisionApiTest`, `DrawingRevisionMailDownApiTest`, `Bpa16GroupReadChecksTest` (probe), a Mailpit test helper;
  - `PersonaSyncTest`, if it asserts the exact permission set;
  - `ProcessTestApplication` (exclude the two DB/mail workers).
- Repo: `docker-compose.yml` (Mailpit); `README.md` (Mailpit web on 8025).
- Docs: `REVIEW.md`, this plan, `intent/README.md` (generated).
- No BPMN, DMN, migration, seed, contract or portal change.

## Order of work
1. Commit this plan (Approved). You move statuses.
2. **Probe first:** group members as `bpa-service`, without and with GROUP READ. Record the answer in the plan; stop and tell you
   if it needs more than that.
3. Tests first: the 12 scenario tests and the others, against stubs. Run them and show they fail for the expected reason.
4. Build:
   - mail infrastructure;
   - the endpoint, the security rule and the PersonaSync permission;
   - the two workers;
   - the notices endpoint.

   Get green, then run the full `./mvnw -B verify`.
5. Local stack (`docker compose up -d --build`, with Mailpit):
   - a Released Work Pack on DWG-118, then POST a revision with curl and no session;
   - the email in Mailpit's inbox at http://localhost:8025;
   - `GET /me/notifications` as `workpack-owner`;
   - in Operate, the instance at EndEvent_RecipientsNotified.
6. qa-reviewer → REVIEW.md → fixes → plan Closed → push → PR → read the CI result myself, wait for all checks green → tell you →
   Jira comments (text shown first). You merge.

## Risks
- **The permission name for reading group members is unknown**, so the probe comes first.
- **Shared seed drawings across tests:** posting a non-IFC DWG-118 revision would break the release check of later Work Packs.
  The tests keep DWG-118 at IFC and use DWG-130 for the status outline.
- **Test time:** two new classes with Released Work Packs (about 15 s each), so about 4 to 5 more minutes on a suite already at
  about 30.
- **Mail-down class:** retries happen quickly, so the spy count is the evidence of "3 times".

## Proof
The probe recorded, `./mvnw -B verify` (red first, then green), the local-stack run with Mailpit and Operate, CI.

## Questions (for Khitaba, ASSUMPTIONs; Jira comment, text shown first)
- Q1: `bpa-service` gets GROUP READ on `workpack-owner` and `project-engineer` to find recipients (a change to the S25 set).
- Q2: the `DrawingRevised` correlation key is `drawingRef/revision`, and the message is sent before the commit, so a failed send
  answers 503 and nothing is recorded.
- Q3: notification rows are not audited; notices are listed newest first.
- Q4: the from-address `star-bpa@example.com`, and the plain-text body layout.
- Q5: a recipient with no `app_user` row is skipped with a warning.
- For Faraz: confirm on BPA-16 the 2 Nov re-approval that the DoR line records.
- The stale BPMN documentation for ServiceTask_FindRecipients: a model change for Khitaba and Faraz to approve separately.

## Human-gap log
- Local Maven build: map `subst S: "D:\LCS CLaude"` first (lost at reboot); JAVA_HOME per command; Docker Desktop must be running.

## Build notes
- **Probe result (10 Nov 2026, `Bpa16GroupReadChecksTest`, Camunda 8.10.1):** `POST /v2/groups/{groupId}/users/search` as `bpa-service`
  answers **200 with an empty list** when it has no GROUP READ (Camunda filters the search by authorization; it is not an error).
  After GROUP READ on that group, and without USER READ, it returns the members (`{"items":[{"username":"workpack-owner"}]}`), a few
  seconds after the permission is created. The plan holds: GROUP READ for the two groups only, no other permission needed.
  Consequence for the worker: an empty answer cannot tell "no members" from "no permission", so the recipients worker logs a warning
  when a group answers empty ("group X returned no members; check bpa-service GROUP READ").
