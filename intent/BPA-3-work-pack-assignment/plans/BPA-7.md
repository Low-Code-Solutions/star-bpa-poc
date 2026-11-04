# Plan: BPA-7 PPC Team can track a Work Pack's estimated man hours in a Project Hour Journal Header
Jira: BPA-7 (Story, epic BPA-3)
Story last updated: 2026-11-02T16:13:09.864+0400
Status: Approved (Ahsan, Build Lead, 4 Nov 2026, in the Claude Code session)
Approver: Ahsan (Build Lead)
Branch: BPA-7-hour-journal-header, from main 0a6a144


## Context
After BPA-6 saves a Work Pack, `ServiceTask_CreateHourJournalHeader` must create its Project Hour Journal Header with the
estimated man hours (the sum of the phases). The same job type on `ServiceTask_UpdateHourJournalHeader` keeps it in step with
corrections while the Work Pack is Draft (D21). Once Released, the header is locked (D8). The PPC Team reads it through
`GET /api/v1/hour-journal-headers?workpackNumber=` (D22; already in `api/openapi.yaml` from BPA-46). The worker is built
with the `new-job-worker` skill. It replaces the BPA-43 skeleton stub (`SkeletonCreateHourJournalHeaderWorker`: "BPA-7
replaces the body test-first").

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met |
| Gherkin, testable, unhappy paths | Met: 7 scenarios (1 outline) |
| BPMN IDs exist | Met: both service tasks, job type `create-hour-journal-header` |
| SOR per field | Met (data model §5.5; `hour_journal_header` in V1) |
| DMN | None |
| No blocking question | Met; allowed groups are an ASSUMPTION in the story |
| ≤ 8 points | 3 |
| intent.md / spec.md accepted | Met |
| PO approved | Met (Faraz 28 Oct); status Ready |

**Reported, not resolved silently:**
- The Description says the backend "enforces group **and scope**". Scope was removed on 2 Nov (BPA-44, PO decision; 09 S6
  amended). I build group-only, as the contract says.
- Two scenarios depend on unbuilt stories. They are tested now with stand-ins, as set out below.

## Design (per the `new-job-worker` skill)
**Worker `worker/HourJournalHeaderWorker`** (`@JobWorker(type = "create-hour-journal-header", autoComplete = false)`) on
both elements:
1. Find the Work Pack by `process_instance_key` (`WorkPackRepository.find`). If there is none, throw an exception: Camunda
   default retries, then an incident (S7, ADR 0007). It cannot happen after a successful `save-workpack`.
2. `HourJournalHeaderRepository.upsert(workpackId, username)` in one transaction:
   - `estimated` is `SUM(workpack_phase.manhours)` in SQL (numeric, so 150.5 + 249.5 = 400 and 0.01 stay exact).
   - No header yet: `INSERT … ON CONFLICT (workpack_id) DO NOTHING`, then re-read (UNIQUE `workpack_id`, D65).
   - Header **locked**: change nothing (data model §5.5, "skipped once `locked`").
   - Unlocked and the sum differs: update `estimated_manhours`.
   - Equal: change nothing.
   - Audit (D64, §6.1): on insert, rows for `workpack_id`, `estimated_manhours` and `locked`; on update, one row with
     old → new. Values in the canonical form; the audit columns and `changed_at` come from the injected clock.
   - User: `completedBy`, else `bpa-service` (D63, B12).
3. Complete the job. Variables: none. Nothing downstream reads them (ASSUMPTION, kept minimal).

Idempotency comes from the unique key and the "equal or locked, change nothing" rule. A re-run never makes a second header
and never fails on data that has changed since (skill, step 3).

**Read endpoint `api/HourJournalHeaderController`** (implements the generated `HourJournalHeadersApi`):
- `SecurityConfig`: `GET /api/v1/hour-journal-headers` is limited to `ppc-team` and `time-method-analyst`. Others get 403
  with the contract's Forbidden text (the same handler as `POST /workpacks`).
- A `workpackNumber` that breaks the pattern answers 400 "Workpack number must match WP-######" (contract
  `InvalidWorkpackNumber`; the same exception mapping as BPA-26's task key).
- No Work Pack with that number answers 404 "Work Pack not found".
- Otherwise 200 with a list of the header (`workpackNumber`, `estimatedManhours`, `locked`). If the worker has not run yet,
  the list is empty (ASSUMPTION; the contract says "exactly one once the Work Pack is saved").

**Remove** `SkeletonCreateHourJournalHeaderWorker`. `ProcessTestApplication` leaves out the new worker, because it needs
the database. `SkeletonWorkerProcessTest` (BPA-43) gets one line, `processTestContext.completeJob("create-hour-journal-header")`,
as it already does for `save-workpack`; its assertions stay unchanged. This test change is said in the PR (L15).

## Scenario-to-test table
Class `HourJournalHeaderApiTest`, built on the `WorkPackApiTest` pattern: app on the integration Camunda with workers on,
`TestClock` at 21 Oct 2026 06:00 UTC, `@DirtiesContext`. I'll copy its small portal-step helpers rather than edit BPA-6's test.

| Scenario | Test | How it runs now |
|---|---|---|
| Header is created with the estimated man hours | BPA7_headerIsCreatedWithEstimatedManHours | Real flow: TMA starts a request and completes Create Work Pack with Cutting 150 and Welding 250; poll GET → one header, 400, locked false |
| Header is not duplicated on re-run | BPA7_headerIsNotDuplicatedOnRerun | Save a Work Pack with `WorkPackRepository.save` (key 4503599627370501, partition 2); call the worker's `handle` twice with a mocked `JobClient`; GET → exactly one header; the job completes twice, no error |
| Decimal man hours are totalled exactly (2 rows) | BPA7_decimalManHoursAreTotalledExactly | Real flow per row: Cutting 150.5 + Welding 249.5 → 400; Engineering 0.01 → 0.01 (one material in Engineering) |
| Header follows corrections while Draft | BPA7_headerFollowsCorrectionsWhileDraft | **Stand-in for BPA-23**, below |
| Header is locked once Released | BPA7_headerIsLockedOnceReleased | **Stand-in for BPA-9**, below |
| Unknown Work Pack returns not found | BPA7_unknownWorkPackReturnsNotFound | GET WP-999999 as ppc-team → 404 "Work Pack not found" |
| Other roles cannot read the Header | BPA7_otherRolesCannotReadHeader | A real WP-… exists; production-planner → 403 with no header fields in the body; ppc-team gets 200 for the same number (proving the 403 is the group) |

### Corrections scenario: how it is tested now, and what changes with BPA-23
- **Today, the real path cannot run.** Completing Confirm Workpack triggers `ServiceTask_SaveWorkPackCorrections`, and
  `save-workpack` fails those jobs with an incident ("Save corrections is built by BPA-23", BPA-6 decision).
- **Now:**
  1. Real flow to Confirm Workpack: WP Draft, header 400.
  2. The test stands in for BPA-23's save. It updates `workpack_phase` Welding 250 → 300 in the database, as the
     corrections save will.
  3. Process instance modification (admin, Camunda REST) terminates the Confirm task and activates
     `ServiceTask_UpdateHourJournalHeader`. The **real** worker runs on the **real** element.
  4. GET → exactly one header, estimated 450, locked false.

  The token then waits at `ServiceTask_EvaluateReleaseInputs` (no worker until BPA-8). If modification misbehaves on
  8.10.1, the fallback is calling `handle` with elementId `ServiceTask_UpdateHourJournalHeader`, recorded in the plan.
- **When BPA-23 lands, re-check:**
  1. Replace steps 2–3 with completing Confirm Workpack through the task API with Welding 300.
  2. The test then runs fully through the process: SaveWorkPackCorrections, then UpdateHourJournalHeader.
  3. Same assertions, plus one `audit_log` row 400 → 450 by time-method-analyst.
  4. BPA-23 removes the incident rule from `SaveWorkPackWorker` and the `new-job-worker` skill.

  I'll add this as a `// BPA-23:` comment in the test and a Jira comment on BPA-23 (shown to you first).

### Release-lock scenario: how it is tested now, and what changes with BPA-9
- **Today, sign-off cannot run.** `record-release-signoff` (BPA-9), the step that sets `locked` and `Released` (D8), has no
  worker, and BPA-8's release check sits in front of it.
- **Now:**
  1. Continue from the corrections stand-in: header 450.
  2. The test stands in for BPA-9's sign-off. It sets `workpack.status = 'Released'` and `hour_journal_header.locked = true`
     in the database, and moves the token past Confirm by modification (terminate the active element, activate
     `UserTask_ScheduleWorkPack`).
  3. GET → locked true, estimated 450.
  4. There is no `UserTask_ConfirmWorkPack` task for the instance (task search as admin, state CREATED).
  5. BPA-7's own part of D8: the worker runs again on the locked header after `workpack_phase` changes (`handle`, elementId
     Update). The header stays 450, so locked is never overwritten.
- **When BPA-9 lands, re-check:**
  1. Replace step 2 with the real path: Confirm (BPA-8/23) → release check → the Project Engineer approves Sign off
     release through the task API → `record-release-signoff` sets locked.
  2. Assert the same three things.
  3. BPA-9's own tests own the lock writing.

  I'll add a `// BPA-9:` comment in the test and a Jira comment on BPA-9 (shown first).

## Files that change
- New: `worker/HourJournalHeaderWorker.java`, `persistence/HourJournalHeaderRepository.java`,
  `api/HourJournalHeaderController.java`.
- Deleted: `worker/SkeletonCreateHourJournalHeaderWorker.java`.
- `config/SecurityConfig.java`: one rule.
- Tests: new `HourJournalHeaderApiTest.java`; `SkeletonWorkerProcessTest.java` (one line); `ProcessTestApplication.java`
  (exclude the new worker).
- No BPMN, DMN, migration, contract or frontend change. `REVIEW.md` and this plan.

## Order of work
1. Commit this plan. Ask before Ready → In Progress.
2. Write the 7 tests; run them; show each failing for the expected reason (404 on the endpoint; no header yet).
3. Repository → worker (remove the skeleton) → controller + security; adjust the BPA-43 test and ProcessTestApplication.
4. `./mvnw -B verify`.
5. Local stack: create a WP in the portal; GET as ppc-team → 400 hours, locked false; audit rows; Operate shows the
   header step completed.
6. qa-reviewer → REVIEW.md → fixes → plan Closed → PR → Jira linkage comment; BPA-23 and BPA-9 re-check comments (texts
   shown first).

## Risks
- **Process modification** request shape on 8.10.1 is not yet proven (BPA-25 did not cover it). Fallback stated above.
- **Shared integration Camunda:** the new worker class runs only while this class's context is up (`@DirtiesContext`).
  Bpa25 and SkeletonWorker tests complete header jobs themselves or use the Process Test engine.

## Proof
`./mvnw -B verify` output (red first, then green); the local-stack GET; audit rows; an Operate screenshot of the header step.

## Questions
- Q1 (Khitaba, ASSUMPTIONs for the comment): an empty list when the header is not there yet; no job output variables;
  the audit columns logged on insert.

## Human-gap log
- Local Maven build uses the `subst S:` work-round; `npm.cmd` in PowerShell.
- 4 Nov, test-first (L15). Red run: all 7 BPA7 tests failed for the expected reason.
  - 5 failed on `GET /api/v1/hour-journal-headers` → 404 "No static resource".
  - BPA7_unknownWorkPackReturnsNotFound got that 404's detail instead of "Work Pack not found".
  - BPA7_headerIsNotDuplicatedOnRerun failed on the compile-only stub's "not built yet" (a stub with no `@JobWorker`,
    said in the PR).
- 4 Nov, green: 7/7 BPA7 tests pass; `./mvnw -B verify` 59 tests. Process instance modification
  (`POST /v2/process-instances/{key}/modification`, terminate plus activate, as the demo admin) works on 8.10.1 and answers
  204. The fallback was not needed.
- As built: `HourJournalHeaderRepository.upsert` returns CREATED, UPDATED, UNCHANGED or LOCKED (logged); the worker
  completes with no variables. The header worker throws when an instance has no Work Pack, so leftover header jobs from
  other test classes (BPA-25 checks) end as incidents in the shared test Camunda, where the skeleton used to complete them.
