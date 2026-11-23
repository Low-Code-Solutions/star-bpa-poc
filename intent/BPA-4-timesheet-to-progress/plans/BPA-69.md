# Plan: BPA-69 Supervisor can only book hours to a CBS that is open for productive hours
Jira: BPA-69 (Story, epic BPA-4)
Story last updated: 2026-11-23T10:24:38.195+0400
Status: Closed (23 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-69-closed-cbs-blocks-hours, from main e453cc6

> Written in plan mode and approved by Ahsan on 23 Nov 2026; committed as the branch's first commit.
> Checked read-only: main e453cc6 is the merge of PR #61 (BPA-68). `BPA-69-closed-cbs-blocks-hours` is at e453cc6 with no commits of its own.

## Context
Cost Control can close a CBS for FMS productive hours (`cbs_block`, FRD-22 §22.3.1). Today nothing reads that flag: `HourLineService.checkBudget` and `PrepareHoursSubmissionWorker` both send `cbsClosed = false` to `Decision_HoursWithinBudget`. BPA-69 makes that flag real, so a line on a closed CBS is refused:
- at save (form and upload);
- again at send, when the CBS was closed after the line was saved.

The text is "CBS <code> is closed for productive hours". It is an LCS suggestion (option A, F6); the enforcement point and the message are ASSUMPTION in Jira. **Backend only, plus one expression in the BPMN** (see Design 3); no frontend change. The screens already show 400 details, upload row outcomes and `validationMessages`.

## Readiness check (Definition of Ready, docs/02, against Jira 20 Nov 21:50)
| Check | State |
|---|---|
| Jira status | **Ready** (Faraz, 17 Nov; comment 10560). Ready → In Progress only when you say so (Q5) |
| Template, persona, outcome | Met (Supervisor = Yard Supervisor, comment 10627) |
| Gherkin incl. unhappy paths | Met: 5 scenarios |
| BPMN element IDs exist | Met: `UserTask_BookHours`, `ServiceTask_PrepareHoursSubmission`, `Gateway_AreHoursWithinBudget`. No new element; one output expression changes (Q3) |
| Every field has an SOR | Met: `cbs_block` (POC master data, real SOR D365 F&O CCR, read only); `digital_timesheet.cbs_code_ref` (BPA-15) |
| DMN | "None needed": `Decision_HoursWithinBudget` rule 1 (`cbsClosed` → Blocked, CBS_CLOSED) already exists (FIRST hit policy, before leave, daily and phase) |
| No blocking question | Met (F6 decided 10 Nov). Q1 to Q3 shape the build |
| intent.md / spec.md accepted | **Not met** (same as every Epic 2 story) |
| Story-specific: `cbs_block` table and seed | Met: V2 table; seed CBS-139-PIP-01 FmsTimesheet open, CBS-139-PIP-99 FmsTimesheet closed (`R__seed_reference_data.sql:268-276`) |
| Estimate ≤ 8 | 2 |

## What already exists (code; closed plans BPA-50 to 53 are history only)
- **`Decision_HoursWithinBudget`** (FIRST): rule 1 `cbsClosed = true` → Blocked / CBS_CLOSED; rule 2 `workerOnLeave` → WORKER_ON_LEAVE; rule 3 daily limit; rule 4 phase budget; then Allowed.
- **Save:** `HourLineService.checkBudget` (`timesheet/HourLineService.java:229`) puts `cbsClosed = false` (:231). Its Blocked switch maps DAILY_LIMIT_EXCEEDED and PHASE_BUDGET_EXCEEDED to the `HourLineChecks` texts; the default branch gives "Hours are blocked (<reason>)" (:246).
  - `checkBudget` is called by `add` (:121; form and upload), `change` (:142; the Supervisor's PUT) and `amend` (:171; the Timekeeper, BPA-53).
  - `add` checks, in this order: Completed; Cancelled; in approval; values; future date; month; week; phase on the Work Pack; Worker assigned; duplicate; then the decision.
- **Upload (BPA-52):** `TimesheetFileService.check` checks the required columns, date and number parsing, Work Pack found, Worker's time sheet / assigned, Cancelled, then calls `HourLineService.add`. The row outcome is the `Refused` text, so the CBS text becomes the row outcome with no extra code.
- **Send (BPA-51):** `PrepareHoursSubmissionWorker` builds one `lineChecks` element per Draft or Returned line. It sets `cbsCodeRef` (from `digital_timesheet.cbs_code_ref`, null with no CBS), `cbsClosed` false (:69), and `dailyLimitMessage` and `phaseBudgetMessage`.
  - `BusinessRuleTask_CheckHoursWithinBudget` runs the decision per line. Its `outputElement` maps **only** DAILY_LIMIT_EXCEEDED and PHASE_BUDGET_EXCEEDED to a message (BPMN line 96). Any other Blocked reason gets `message: null`.
  - `Gateway_AreHoursWithinBudget` returns to Book hours when any line is Blocked.
  - `TaskService.budgetMessages` shows each non-null `message` as `validationMessages`. **So with today's model a CBS_CLOSED at send would return to Book hours with no text** (Design 3).
- **`HourLineChecks`** has `dailyLimitMessage` and `phaseBudgetMessage` (:96, :101), the builders BPA-51 shares between save and send.
- **Sheet data:** `DigitalTimesheetLineRepository.Sheet` (:30) does not carry `cbs_code_ref`; `SendLine` (:82) does.
- **Nothing reads `cbs_block` yet.** The data model §13.4.3 says BPA-69 reads it.
- **Test helpers:**
  - `BookHoursFixture.reset()`: WP-000457 (A-2201, Cutting 300, Welding 200; E1021 and E1099 assigned; E1150 has a time sheet but is not assigned; 12 Nov CutOff).
  - `fmsBackground()`: CBS-139-PIP-01 on E1021's and E1099's time sheets; WP-000470 (A-2299, Welding 100) with a time sheet for E1150, no CBS, **no assignment**.
  - `HoursBudgetApiTest` (BPA-51; today 18 Nov 09:00): `BPA51_sendRechecksTheBudget` and `startAtBookHours()` are the pattern for the send test.
  - `TimesheetFileBuilder` builds upload files.
  - `reset()` does not touch `cbs_block`, so a test that closes CBS-139-PIP-01 must open it again.

## Design
### 1. One shared lookup
- **New `persistence/CbsBlockRepository.closedForProductiveHours(String cbsCodeRef)`.** It returns true only when `cbsCodeRef` is not null and `cbs_block` has `is_closed = true` for (`cbsCodeRef`, `'FmsTimesheet'`). No CBS (D40) or no row gives false (no row = open, ASSUMPTION in Jira). It is read only and runs one query.
- **Both hard-coded `false` values are replaced by this one call, with no second copy:**
  - `HourLineService.checkBudget` gets the CBS from the sheet. `Sheet` gains `cbsCodeRef`, read from `t.cbs_code_ref` in the same query.
  - `PrepareHoursSubmissionWorker` already has `line.cbsCodeRef()`.
- The comments that say "false until BPA-69" (HourLineService :30, :227; worker :25, :68; the BPMN documentation of `ServiceTask_PrepareHoursSubmission`) are updated. `workerOnLeave` stays false (BPA-75).

### 2. The message
- **`HourLineChecks.cbsClosedMessage(String cbsCodeRef)`** builds "CBS " + code + " is closed for productive hours" (Jira's text). It sits next to `dailyLimitMessage` and `phaseBudgetMessage` and is used in all three places:
  - **At save:** `checkBudget`'s switch gains `case "CBS_CLOSED" -> HourLineChecks.cbsClosedMessage(sheet.cbsCodeRef())`, a **400**.
  - **Upload row outcome:** the same `Refused` text, through `add`.
  - **At send:** the worker adds `cbsClosedMessage` to each line check (null when the time sheet has no CBS).

### 3. The send re-check (one BPMN expression)
- The decision already puts CBS_CLOSED first. To show the text, `BusinessRuleTask_CheckHoursWithinBudget`'s `outputElement` gains one branch: `if budgetCheck.budgetReason = "CBS_CLOSED" then lineCheck.cbsClosedMessage else …`, ahead of the two existing ones (Q3).
- No new element, no gateway change. A CBS closed after a line was saved makes that line Blocked, and the instance returns to Book hours with "CBS <code> is closed for productive hours" in `validationMessages` (the BPA-51 path). The other lines are not sent either, as with any Blocked line today.

### 4. Where the check sits in the order (Q2)
- **Form and upload:** the CBS check is part of the decision, so it comes **after** BPA-50's and BPA-52's own checks:
  - form: Completed, Cancelled, in approval, values, future date, month, week, phase, assigned, duplicate;
  - upload: required columns, parsing, Work Pack found, assigned, Cancelled.
  - Among the decision's rules it is first, before leave, daily limit and phase budget (DMN rule 1).
- Jira's "checked at save and again at send (before Decision_HoursWithinBudget)" is read as "before the daily and phase rules", which is what rule 1 gives. No separate check is added ahead of the decision, because that would be a second copy.

### 5. Change and amend (Q1)
- `checkBudget` also runs on the Supervisor's change (PUT) and the Timekeeper's amend (BPA-53). With a real `cbsClosed`, both are refused on a closed CBS with the same 400 text.
- Recommended: keep it so, ASSUMPTION; Jira is silent on both.

### 6. Docs and contract
- `api/openapi.yaml`: the 400 text on the lines endpoints (add, change), the amend endpoint, and the upload outcomes list.
- Data model §13.4.3: an "As built by BPA-69" note, saying who reads `cbs_block`, the `FmsTimesheet` type only, and that no row means open.
- No ADR change.

## Files that change
| Area | Files |
|---|---|
| Backend, new | `persistence/CbsBlockRepository.java` |
| Backend, changed | `timesheet/HourLineService.java` (lookup, CBS_CLOSED case), `domain/HourLineChecks.java` (`cbsClosedMessage`), `persistence/DigitalTimesheetLineRepository.java` (`Sheet.cbsCodeRef`), `worker/PrepareHoursSubmissionWorker.java` (lookup, `cbsClosedMessage`) |
| Model | `models/bpmn/Process_PPC_TimesheetToProgress.bpmn`: the `outputElement` branch and the documentation text of `ServiceTask_PrepareHoursSubmission` (no new element; lint) |
| Contract | `api/openapi.yaml` (texts only) |
| Tests | new `CbsBlockApiTest`; `BookHoursFixture` gains a helper for WP-000490 (if it fits there; else in the test class) |
| Docs | data model note; plan; REVIEW.md; `intent/README.md` regenerated |
| Frontend | none |

## Order of work
1. Approve the plan and commit it (`Approved`).
2. Write `CbsBlockApiTest`'s five tests (and the extras), run them **red before any production code**, and commit. Compile stubs only.
3. Build: the repository, the `Sheet` field, the message helper, the two lookups, the BPMN branch. Then green, then `HoursBudgetApiTest`, `BookHoursApiTest`, `TimesheetFileApiTest`, `HoursValidationApiTest` (the decision's neighbours).
4. Contract text and data model note; `npm run lint:bpmn` (the model changed); full `./mvnw -B verify`; the local-stack check; `qa-reviewer`; fixes; REVIEW.md; plan Closed. Push, PR and Jira comments only when you ask.

## Scenario-to-test table
One class, **`CbsBlockApiTest`**. It runs on the integration stack (per-class Postgres, shared Camunda for the decision and the send), as `HoursBudgetApiTest`.

**Background (each test):**
- today 18 Nov 2026 09:00 Riyadh (`TestClock`);
- `BookHoursFixture.reset()` + `fmsBackground()` (WP-000457 with CBS-139-PIP-01 on E1021 and E1099; WP-000470 with E1150, no CBS);
- **WP-000490 built by the test:** A-2201, Cutting 300; E1021 assigned (`worker_assignment`); a time sheet with `cbs_code_ref = 'CBS-139-PIP-99'`; it is not seeded;
- E1150 assigned to WP-000470;
- `cbs_block` reset to the seed: PIP-01 open, PIP-99 closed. An `@AfterEach` puts PIP-01 back to open.

| # | Scenario | Test | Act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | Hours on a closed CBS are refused | `BPA69_hoursOnClosedCbsAreRefused` | supervisor POSTs E1021 / WP-000490, 17 Nov, Cutting, 8 | 400, detail "CBS CBS-139-PIP-99 is closed for productive hours"; no line saved | **red** (201: `cbsClosed` is false) |
| 2 | An upload row on a closed CBS is rejected | `BPA69_uploadRowOnClosedCbsIsRejected` | supervisor uploads a file with row WP-000490, E1021, 17/11/2026, Cutting, 4 (`TimesheetFileBuilder`) | that row's outcome is "CBS CBS-139-PIP-99 is closed for productive hours"; no line saved | **red** (outcome "Saved") |
| 3 | A time sheet with no CBS is never blocked (D40) | `BPA69_noCbsIsNeverBlocked` | E1150 / WP-000470, 17 Nov, Welding, 8 | 201, the line saved | **green before code** (a guard against an over-wide check) |
| 4 | An open CBS accepts hours | `BPA69_openCbsAcceptsHours` | E1021 / WP-000457 (CBS-139-PIP-01), 17 Nov, Cutting, 8 | 201, the line saved | **green before code** (a guard) |
| 5 | A CBS closed after a line was saved stops the send | `BPA69_cbsClosedAfterSavingStopsTheSend` | E1021 Draft line on WP-000457 for 17 Nov saved through the API; then `UPDATE cbs_block SET is_closed = true` for CBS-139-PIP-01 / FmsTimesheet; the timesheet instance started at Book hours (`startAtBookHours` pattern); supervisor completes Book hours | the instance returns to `UserTask_BookHours` (a new task); its `validationMessages` (task API) contain "CBS CBS-139-PIP-01 is closed for productive hours"; the line is still Draft | **red** (the send goes on to Validate hours) |

**Extras (not scenarios):**
- `aChangeOnAClosedCbsIsRefused`: a Draft line saved while open, the CBS closed, then PUT → 400 with the text (Q1).
- `anAmendOnAClosedCbsIsRefused`: the Timekeeper's amend → 400 (Q1).
- `theCbsCheckComesBeforeTheDailyLimit`: a closed-CBS line that would also exceed 16 h gets the CBS text (DMN rule 1).
- `anotherBlockTypeDoesNotBlock`: a `DigitalTimesheet` row closed for PIP-01, `FmsTimesheet` open → saved (only `FmsTimesheet` counts).

`grep -c "void BPA69_"` = 3 red + 2 green before code; the plan and the PR say so.

## Proof (one per Definition of Done line)
| DoD line | Evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt` |
| 5 tests passing in CI | `./mvnw -B -Dtest=CbsBlockApiTest verify`; `grep -c "void BPA69_"` = 5; full `./mvnw -B verify`; `gh pr checks <n>` (Actions may not start: billing) |
| Process runs end to end locally | rebuild `bpa-app`; on the local stack set `cbs_block` PIP-01 FmsTimesheet `is_closed = true` (psql), then as supervisor **in the portal**, Book hours on a Work Pack whose time sheets carry CBS-139-PIP-01 → the line is refused with the text (the 400 shown on the screen); a Draft line saved before the close → Send → back to Book hours with the message; then open it again |
| BPMN and DMN match deployed | the deployed `Process_PPC_TimesheetToProgress` equals `models/` (whitespace-normalised hash, as BPA-67); `Decision_HoursWithinBudget` unchanged |
| Open questions resolved or logged | Jira comments (shown first, posted on your yes) |
| Plan Closed | `grep '^Status: Closed'` |
| Demoed at sprint review | Human gap |

## Risks
- **The BPMN changes** (one FEEL branch). A typo would make every send fail. `lint:bpmn` and test 5 cover it, and `HoursBudgetApiTest`'s send tests guard the existing branches.
- **The change and amend refusals (Q1)** go beyond Jira's "line refused at save". They come from the same decision; if you say no, `checkBudget` needs a flag, which is a second path.
- **A test that closes CBS-139-PIP-01** must open it again: per-class databases keep it local to this class, and `@AfterEach` restores it.

## Questions
1. **Change and amend on a closed CBS.** `checkBudget` runs on the Supervisor's change (PUT) and the Timekeeper's amend (BPA-53) too, so with a real `cbsClosed` both are refused with "CBS <code> is closed for productive hours". Jira is silent.
   - **Recommended:** keep it (one rule everywhere a line's hours are set; it also stops a Timekeeper moving hours onto a closed line), ASSUMPTION, with a Jira comment.
   - Alternative: the change and amend skip the CBS rule (needs a flag into `checkBudget`).
2. **Order of the check.** The form and upload check the CBS inside the decision, so it comes after BPA-50's and BPA-52's own checks (Work Pack found, assigned, Cancelled, future date, month, week, phase, duplicate) and first among the decision's rules (before leave, daily limit and phase budget).
   - **Recommended:** as built: no extra check ahead of the decision. Jira's "before Decision_HoursWithinBudget" is read as "before the daily and phase rules".
3. **The BPMN expression.** Without a model change the send would return to Book hours with **no** message for CBS_CLOSED (the model maps only daily and phase).
   - **Recommended:** add the CBS_CLOSED branch to `BusinessRuleTask_CheckHoursWithinBudget`'s `outputElement` and `cbsClosedMessage` to the worker's line check (no new element, as Jira's BPMN Reference says).
   - Alternative: `TaskService` fills a missing message from `budgetReason` (backend only, but a second copy of the mapping).
4. **Texts and data (ASSUMPTION):**
   - "CBS <code> is closed for productive hours" is Jira's (marked ASSUMPTION there);
   - only `block_type = 'FmsTimesheet'` counts (Jira: "FMS productive hours");
   - no `cbs_block` row = open (Jira);
   - WP-000490 is built by the test (A-2201, Cutting 300, E1021) and is not seeded.
5. **Jira:** BPA-69 is Ready. I'll ask before moving it to In Progress and before any other transition.

## Resolutions (Ahsan, 23 Nov 2026, in plan mode)
- **Q1:** change and amend on a closed CBS are refused with the same 400 text (ASSUMPTION, Jira comment).
- **Q2:** the check stays inside the decision (DMN rule 1), after BPA-50's and BPA-52's own checks; no separate check.
- **Q3:** one CBS_CLOSED branch in `BusinessRuleTask_CheckHoursWithinBudget`'s `outputElement`, plus `cbsClosedMessage` in the worker's line check. No new element.
- **Q4, Q5:** as written; I ask before any Jira transition.

## Progress and departures (23 Nov 2026)
- **Red first (07b52d8):** `CbsBlockApiTest` has 9 tests (the 5 of the Definition of Done and 4 extras). Before any production code **6 failed for the expected reasons** and 3 passed as the plan said:
  - red: `BPA69_hoursOnClosedCbsAreRefused` (201 instead of the 400), `BPA69_uploadRowOnClosedCbsIsRejected` (the row read "Saved"), `BPA69_cbsClosedAfterSavingStopsTheSend` (the send went on: no return to Book hours within the wait), `aChangeOnAClosedCbsIsRefused`, `anAmendOnAClosedCbsIsRefused`, `theCbsCheckComesBeforeTheDailyLimit`;
  - green before code (guards): `BPA69_noCbsIsNeverBlocked`, `BPA69_openCbsAcceptsHours`, `anotherBlockTypeDoesNotBlock`.
- **Built (3bcec40):** `CbsBlockRepository.closedForProductiveHours` (the one lookup), `Sheet.cbsCodeRef` (read in both Sheet selects), `HourLineChecks.cbsClosedMessage`, the CBS_CLOSED case in `HourLineService.checkBudget`, the lookup and `cbsClosedMessage` in `PrepareHoursSubmissionWorker`, and the CBS_CLOSED branch in the send step's `outputElement` (Q3). The comments that said "false until BPA-69" are updated. OpenAPI texts (add, change, amend, upload outcomes) and the data model note ("As built by BPA-69") are written. No schema change, no frontend change.
- **No departure from the plan's file list.** `BookHoursFixture` was not changed: `CbsBlockApiTest` builds WP-000490 itself.
- **Green:** `CbsBlockApiTest` 9/9 and the decision's neighbours `HoursBudgetApiTest` 11, `BookHoursApiTest` 28, `TimesheetFileApiTest` 21, `HoursValidationApiTest` 14 (83 tests, 0 failures). `npm run lint:bpmn` is clean.
- **Local-stack check (23 Nov, over the API as `supervisor`; the portal screens were not clicked):** on WP-000001 (A-2301, CBS-139-PIP-01 on the time sheets) an open CBS accepted a Draft line (201); after closing CBS-139-PIP-01 for FmsTimesheet a new line got 400 "CBS CBS-139-PIP-01 is closed for productive hours"; with that Draft line saved while open, **Send** returned the instance to Book hours with `validationMessages` ["CBS CBS-139-PIP-01 is closed for productive hours"] and the line stayed Draft. Then the CBS was opened again, the test line deleted, and the stack left as it was. The upload row was not tried by curl (the test covers it).
- **Deploy note found in the check:** an instance that was already waiting at Book hours before the new model version is deployed keeps the old `outputElement` (Camunda runs an instance on the version it started on). At send it is still returned to Book hours (the decision is the same), but with no CBS text. I cancelled the old instance on the local stack and started a new one on version 2 to see the message. Camunda instances in flight at deploy on QA: new instances get the text; old ones return without it.
- **Deployed vs repo BPMN:** the latest deployed `Process_PPC_TimesheetToProgress` (whitespace-normalised) equals `models/bpmn/Process_PPC_TimesheetToProgress.bpmn`; `Decision_HoursWithinBudget` is unchanged.

## Full-suite results (23 Nov 2026)
- `./mvnw -B verify` at 80a259c: **710 tests, 0 failures**.
- After the review: `CbsBlockApiTest` 10/10 (the tenth is `aClosedCbsLineStopsTheWholeSendUntilItIsDeleted`, added after the full run started).
- Frontend: untouched (no change under `frontend/`), not rerun. `npm run lint:bpmn` clean.
- Deployed `Process_PPC_TimesheetToProgress` (version 2, local stack) equals `models/bpmn/Process_PPC_TimesheetToProgress.bpmn` (whitespace-normalised hash).

## After the `qa-reviewer` pass (23 Nov 2026; REVIEW.md "BPA-69 at 80a259c")
- **Decided (Ahsan, 23 Nov 2026):** keep **"a closed-CBS line stops the whole send"** (Important 1): it is what any Blocked line does since BPA-51, and Jira says the send stops "for that line" without saying the others go on. BPA-67 chose to skip locked-month lines; here the choice is to stay with BPA-51. **ASSUMPTION.** The way out is deleting the line (`delete` accepts a Draft or Returned line; a re-opened CBS is BPA-70, parked); other Workers' valid lines wait until then. A test shows both halves: `aClosedCbsLineStopsTheWholeSendUntilItIsDeleted`. The data model note says so.
- **Fixed after the review:** the plan's file list no longer names a documentation change to `BusinessRuleTask_CheckHoursWithinBudget`; the data model note states the whole-send behaviour.
- **Accepted for the POC:** one `cbs_block` query per line at send; the class comment of `HourLineService`.
- **Deploy note:** instances already waiting at Book hours when the new model version is deployed return without the CBS text (the old `outputElement`); new instances show it. For the PR and the Jira comment.
- **For Ahsan and Jira (shown first, posted only on a yes):** (1) Q1: change and amend on a closed CBS are refused with the same 400 (ASSUMPTION). (2) Q3: the send step's `outputElement` gained a CBS_CLOSED branch (no new element). (3) the whole-send decision above. (4) the deploy note. (5) the ASSUMPTIONs: the message text, only `FmsTimesheet` counts, no row means open, the check after BPA-50's and BPA-52's own checks. (6) Jira status: Ahsan moved BPA-69 to In Progress on 23 Nov 10:24 (changelog); no content field changed since 20 Nov.
- **Recorded (plan vs diff):** the diff holds `CbsBlockRepository`, `DigitalTimesheetLineRepository`, `HourLineChecks`, `HourLineService`, `PrepareHoursSubmissionWorker`, `Process_PPC_TimesheetToProgress.bpmn`, `api/openapi.yaml`, `docs/data-model.md`, `CbsBlockApiTest`, the plan, `REVIEW.md` and `intent/README.md`; nothing else.

## Human-gap log
- **DoR:** intent.md and spec.md not accepted by the PO.
- **DoD, local run:** done over the API as `supervisor` (and the Camunda REST for the new model version); nobody has clicked the portal screens for a closed-CBS Work Pack, and the upload row was not tried by hand (the test covers it). Khitaba or Faraz can do one click-through.
- **DoD:** "Demoed at sprint review". People.
- **DoD:** "passing in CI". GitHub Actions may not start (billing); local runs are the evidence until it does.
- **Jira:** the Q1 and Q3 decisions and the ASSUMPTIONs go in a comment, shown to you first.
