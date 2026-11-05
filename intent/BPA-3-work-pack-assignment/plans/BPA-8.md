# Plan: BPA-8 Time & Method Analyst can confirm a Work Pack only when materials are reserved and the drawing is approved
Jira: BPA-8 (Story, epic BPA-3)
Story last updated: 2026-11-02T16:13:10.077+0400
Status: Approved (Ahsan, Build Lead, 2026-11-05)
Approver: Ahsan (Build Lead)
Branch: BPA-8-release-check, from main 270c194


## Context
Today, after a Confirm Workpack correction is saved (BPA-23) and the header is updated (BPA-7), the token stops at
`ServiceTask_EvaluateReleaseInputs`: nothing handles `evaluate-release-inputs`. BPA-8 builds that worker (skill
`new-job-worker`). It sets `materialsReserved` and `drawingApproved` from the POC database; the existing DMN
`Decision_WorkpackReleaseCheck` decides, and `Gateway_IsReleaseReady` either creates the Project Engineer's sign-off task or
sends the Work Pack back to Confirm Workpack with the reason. The reason shows on the existing Confirm screen ("Returned:",
D51, BPA-23); no new screen.

## Readiness check
| DoR item | State |
|---|---|
| Template, persona, outcome | Met |
| Gherkin, testable, unhappy paths | Met: 11 scenarios (1 outline, 3 rows) |
| BPMN IDs exist | Met: Gateway_MergeConfirm … ServiceTask_EvaluateReleaseInputs (`evaluate-release-inputs`), BusinessRuleTask_ReleaseCheck (`calledDecision Decision_WorkpackReleaseCheck`, result `releaseCheck`; outputs `releaseResult` and `confirmMessage` = `releaseReason`), Gateway_IsReleaseReady (`=releaseResult = "Ready"`, default Not ready → Gateway_MergeConfirm) |
| SOR per field | Met (material_reservation, drawing_revision: seeded copies, D54, C15) |
| DMN complete | Met. **Checked against the SOR Notes table: no difference.** FIRST; boolean inputs `materialsReserved`, `drawingApproved`; string outputs `releaseResult`, `releaseReason`; the 3 rules and both reason texts are identical; `null` reason when Ready |
| No blocking question | Met |
| ≤ 8 points | 3 |
| PO approved | Met (Faraz 28 Oct); status Ready |

**Reported, not resolved silently: the seed does not match the AC's Givens.**

| Scenario | AC says | Seed (BPA-42) has |
|---|---|---|
| Material not reserved | DWG-119 latest IFC; MAT-PLATE-12 **not** reserved | DWG-119 A **IFA**; TR-5534 **does** reserve PLATE-12 |
| Drawing not approved | DWG-121 IFR | DWG-121 A **IFI** |
| IFA / IFI outline | DWG-140 IFA, IFI, IFC | DWG-140 A **As-Built** |
| No revision | DWG-141 has no rows | DWG-141 A **IFR** (DWG-151 has none) |
| As-Built | DWG-130 As-Built | DWG-130 B **IFC** |
| Latest revision | DWG-118 B Superseded, C IFR | DWG-118 A **IFC** |

The AC can't all hold at once anyway: DWG-118 is IFC in scenario 1 but IFR in the latest-revision scenario.
**Ahsan decided, 5 Nov:** each test arranges exactly its AC Given and restores the seed afterwards. The seed file is not
changed, and the mismatch is reported to Khitaba in the Jira comment.

## Design: worker (skill `new-job-worker`)
`worker/EvaluateReleaseInputsWorker` (`@JobWorker(type = "evaluate-release-inputs", autoComplete = false)`), read-only:
1. Find the Work Pack by process instance key (`WorkPackRepository.find`); a missing one throws (Camunda default retries,
   then an incident, S7).
2. `ReleaseInputsRepository` (new, in `persistence/`), as the SOR Notes define:
   - `materialsReserved`: true when **every** `workpack_material` line has a `material_reservation` for
     (`workpack.drawing_ref`, `material_ref`). The ASSUMPTION "all must be reserved" is the story's own. Every Work Pack
     has at least one material (D20), so the check is never vacuous.
   - `drawingApproved`: the status of the latest revision (by `registered_at`) that is not Superseded is `IFC` or
     `As-Built` (D1, D10). False when the drawing has no revision rows (ASSUMPTION in the story), or only Superseded ones.
3. Complete the job with `{materialsReserved, drawingApproved}`.
- **Idempotent:** it only reads, so the same data always gives the same result and nothing is written (D24, 09 S7). No audit
  rows, no `created_by`, no BPMN error: the job has no user input to validate.

The DMN, gateway and loop-back already exist in the model: no BPMN or DMN change. `confirmMessage` is set from
`releaseReason` and is cleared (null) when the result is Ready.

## Design: screen (no new screen)
The Confirm Workpack screen already shows "Returned: <confirmMessage>" (BPA-23, D51). The task list's "sent back" watch
already reopens the returned Confirm task. **Risk:** the chain before the task returns is now longer (corrections →
header → evaluate → DMN), possibly beyond the list's 5-second watch (500 ms × 10). I'll time it on the local stack. If it
is longer, I'll raise `appConfig.taskPolling.attempts` to cover it (ASSUMPTION value) and record it here; otherwise no
frontend code changes.

## Scenario-to-test table
Backend class `ReleaseCheckApiTest` follows the BPA-23 pattern: app on the integration Camunda with workers on, the TestClock
at 21 Oct, `@DirtiesContext`. Shared steps come from `CamundaSteps`.

Each test:
1. arranges its Given: insert or replace `drawing_revision` rows for the AC's drawing, and add or remove
   `material_reservation` rows;
2. creates the Work Pack through the real Create flow (the AC's drawing and materials);
3. completes Confirm Workpack unchanged through the task API (BPA-23's unchanged-correction rule completes the corrections
   step, then the header, evaluate and DMN run);
4. asserts the outcome.

An `@AfterEach` restores every row the test touched, so the seed is as it was.

| # | Scenario | Test | Asserts |
|---|---|---|---|
| 1 | Work Pack meeting the release condition is confirmed | BPA8_workPackMeetingReleaseConditionIsConfirmed | `releaseResult` Ready; WP stays Draft; a sign-off task for project-engineer is open |
| 2 | Material not reserved blocks confirmation | BPA8_materialNotReservedBlocksConfirmation | a new Confirm task, GET shows `confirmMessage` "Materials are not reserved for this Workpack"; no sign-off task |
| 3 | Drawing not approved blocks confirmation | BPA8_drawingNotApprovedBlocksConfirmation | the returned task shows "Drawing is not approved (IFC or As-Built required)"; no sign-off |
| 4 | IFA and IFI drawings are not approved (3 rows) | BPA8_ifaAndIfiDrawingsAreNotApproved | IFA → Not ready, IFI → Not ready, IFC → Ready (`releaseResult` process variable) |
| 5 | Drawing without any revision is not approved | BPA8_drawingWithoutRevisionsIsNotApproved | DWG-141 with no rows → the returned task shows the drawing reason |
| 6 | One unreserved material is enough to block | BPA8_oneUnreservedMaterialBlocks | PIPE reserved, PLATE not → Not ready |
| 7 | Both conditions missing returns the first reason | BPA8_bothConditionsMissingReturnsFirstReason | no reservation and IFR → "Materials are not reserved for this Workpack" |
| 8 | The task waits until the master data is fixed | BPA8_taskWaitsUntilMasterDataIsFixed | Not ready first; then reserve PLATE-12 for DWG-119, complete the returned task again → Ready, sign-off task |
| 9 | As-Built drawing counts as approved | BPA8_asBuiltDrawingCountsAsApproved | DWG-130 latest As-Built → Ready |
| 10 | The check uses the drawing's latest revision | BPA8_checkUsesLatestRevision | DWG-118 B Superseded, C IFR (latest) → the drawing reason |
| 11 | Evaluating the inputs twice gives the same result | BPA8_evaluatingInputsTwiceGivesSameResult | `handle` twice with a mocked `JobClient` → both complete with true/true; no row and no `updated_at` changed, no audit row |

React: one screen test, `BPA8_materialNotReservedBlocksConfirmation` in the task-list flow. A Confirm task that comes back
with `confirmMessage` "Materials are not reserved for this Workpack" reopens and shows it after "Returned:". It is
expected to **pass already**, because BPA-23 built D51; I'll say so in the PR rather than claim a red run. The 11 backend
tests are the failing-first set: today the token waits at Evaluate, so no outcome appears.

## Re-checks from earlier plans (BPA-7, BPA-23)
- `HourJournalHeaderApiTest` (BPA-7) and `ConfirmWorkPackApiTest` (BPA-23): with the new worker their Work Packs (DWG-118,
  reserved materials) go Ready → sign-off. Their lock stand-ins terminate whatever is active, so they should stay green
  unchanged. I'll run both, and record the result in their plans' re-check notes.
- `ProcessTestApplication` leaves out the new worker, because it needs the database.

## Files that change
- New: `worker/EvaluateReleaseInputsWorker.java`, `persistence/ReleaseInputsRepository.java`.
- Tests: new `ReleaseCheckApiTest.java`; `ProcessTestApplication.java` (exclude); possibly `CamundaSteps.java` (a
  variable-read helper is already there).
- React: one test in `tests/integration/` or beside the Confirm screen. `app.config.ts` only if the timing check needs it.
- `REVIEW.md`, this plan. No BPMN, DMN, migration, contract or seed change.

## Order of work
1. Commit this plan. You move statuses.
2. Write the 11 backend tests and the React test; run them. The backend ones fail for the expected reason (no outcome:
   the token waits at Evaluate).
3. Repository, then worker; ProcessTestApplication. Green, then `./mvnw -B verify` (BPA-7 and BPA-23 classes included).
4. Local stack:
   - a Ready Work Pack reaches Sign off release;
   - a Not ready one returns to Confirm showing the reason in the portal (time the loop-back; adjust polling only if
     needed);
   - Operate history.
5. qa-reviewer → REVIEW.md → fixes → plan Closed → PR → Jira comment (text shown first).

## Risks
- **Test data isolation:** the tests change shared master-data rows (for example DWG-118's revisions). The restore runs in
  `@AfterEach`; the classes run one after another in one JVM.
- **The longer chain before Confirm returns** (above).
- **The flaky timeout seen in BPA-23** (job not taken in 60 s): watch it.

## Proof
`./mvnw -B verify` (red first, then green), `npm test`, the local-stack run and Operate.

## Questions
- Q1 (Khitaba): the seed vs AC drawing states above. Should the seed follow the AC, or should the AC name seed drawings?
  Tests arrange their own data meanwhile.

## Human-gap log
- Local Maven build uses the `subst S:` work-round; `npm.cmd` in PowerShell.
