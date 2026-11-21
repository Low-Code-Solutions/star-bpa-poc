# Plan: BPA-62 System can calculate each activity's Earned man-hours for the week
Jira: BPA-62 (Story, epic BPA-4)
Story last updated: 2026-11-20T18:44:28.634+0400
Status: Closed (20 Nov 2026)
Approver: Ahsan, Build Lead
Branch: BPA-62-earned-manhours, from main edfe7b5

> Checked read-only: branch `BPA-62-earned-manhours` at edfe7b5 (the PR #54 merge = BPA-61), no commits of its own. Backend only: no frontend change (`frontend/src/services/schema.d.ts` is generated and git-ignored, so the contract change needs no frontend commit).

## Context
After BPA-61, a week whose Work Packs are all Accepted waits at `ServiceTask_CalculateEarnedManhours` (`calculate-earned-manhours`), where no worker exists. BPA-62 builds that worker and a read endpoint:
- **Worker:** per activity, an hours-weighted % from its Work Packs' latest % and Earned man-hours = % × budgeted units, stored in `activity_progress` for the week.
- **Endpoint:** `GET /api/v1/progress-periods/{weekEnding}/earned-manhours` shows them with the week's increase.
- **Next step:** the instance then goes on along `Flow_ToPrepareVariance` to `ServiceTask_PrepareVarianceCheck` (BPA-64, no worker yet).

## Readiness check (Definition of Ready, docs/02, against Jira 20 Nov 18:44)
| Check | State |
|---|---|
| Jira status | **In Progress** already. The development panel shows one merged PR, probably BPA-61's #54. No transition without asking (Q6) |
| Template, persona, outcome | Met ("As the System") |
| Gherkin incl. unhappy paths | Met: 8 scenarios (no budgeted units; a Locked week is not recalculated) |
| BPMN element IDs exist | Met. `Flow_ProgressAccepted` (default of `Gateway_ProgressOutcome`) → `ServiceTask_CalculateEarnedManhours` → `Flow_ToPrepareVariance` → `ServiceTask_PrepareVarianceCheck` |
| Every field has an SOR | Met (SOR Notes; delta §3.6) |
| DMN | None needed |
| No blocking question | F2 (roll-up) and F3 (rounding) were decided on 10 Nov (comment 10483). Q1 (rounding order) changes one expected value |
| intent.md / spec.md accepted | **Not met** (Jira DoR box unchecked; same as BPA-58 to BPA-61) |
| Estimate ≤ 8 | Met (3) |

## What already exists (closed plans BPA-60, BPA-61; history only)
- **`activity_progress` (V2):**
  - `activity_ref` FK; `progress_period_id` FK; UNIQUE (activity_ref, progress_period_id);
  - `actual_pct` numeric(5,2) NOT NULL, CHECK 0–100;
  - `budgeted_units` numeric(9,2) NULL (snapshot);
  - `earned_manhours` numeric(11,2) NULL;
  - audit columns.
  - Nothing writes it yet.
- **Reference data:**
  - `activity.budgeted_units` (seed): A-2201 1200.00, A-2299 NULL, A-2301 1200.00 with no Work Packs (BPA-48 addendum, for BPA-68).
  - `workpack.status` CHECK: Draft, Released, In Progress, Completed.
  - `workpack_phase.manhours`.
- **Test fixture `BookHoursFixture`:** WP-000457 on A-2201 with phases 300 + 200 = 500 h; WP-000461 on A-2201 with 100 h; WP-000470 on A-2299 with 100 h. These match the Jira Background.
- **BPA-60:**
  - `workpack_progress.approved_pct` and `previous_pct` (the last locked %).
  - `ProgressWeekRepository.lastLockedPct(workpackId, weekEnding)` = `approved_pct` in the latest `Locked` period before the week, else 0 (decision (c)).
  - `CompletionApprovalService.record` refuses a Locked week ("Week ending <d MMM yyyy> is locked").
- **BPA-61:** `ProgressCheckService.periodOf(instanceKey, progressPeriodId)`. Its job workers fail with no retries for a week with no period.
- **API:** `ProgressPeriodController` implements the generated `ProgressPeriodsApi`; a week with no period gives 404 "Week not found". `SecurityConfig:104` lets `/api/v1/progress-periods/**` through for ppc-team and project-manager only.
- **Delta §7:** the new path is for ppc-team and cost-controller.

## Design
### 1. `calculate-earned-manhours` (CalculateEarnedManhoursWorker → EarnedManhoursService)
For the week of the instance (`periodOf`, as BPA-61):
1. **Locked week:** fail with no retries, "Week ending <d MMM yyyy> is locked" (as BPA-60's record step). Nothing is written.
2. **Counted Work Packs:** status Released, In Progress or Completed (Draft is excluded), grouped by `activity_ref`.
3. **Each Work Pack's %:**
   - this week's `workpack_progress.approved_pct`;
   - else the last locked % (`lastLockedPct`, latest Locked period before the week);
   - else 0 (Q3).
4. **Weight:** Σ `workpack_phase.manhours` of the Work Pack (0 when it has no phases).
5. **Activity % (unrounded)** = Σ(% × weight) ÷ Σ weight.
   - `actual_pct` = that value half-up to 2 dp.
   - Σ weight = 0: see Q2.
6. **Earned MH (cumulative):**
   - = activity % (unrounded) ÷ 100 × `activity.budgeted_units`, half-up to 2 dp (Q1).
   - NULL when `budgeted_units` is NULL.
   - `budgeted_units` is copied onto the row (snapshot, Q4).
7. **Upsert** on (activity_ref, week): inserted or updated.
   - A week the Project Manager sends back before the lock is recalculated (BPA-66). The same inputs give the same rows; an unchanged row is not touched.
   - A row of this week whose activity no longer has a counted Work Pack is deleted (ASSUMPTION, Q2).
   - All in one transaction.
8. **Audit:** one `audit_log` row per changed business column (`actual_pct`, `budgeted_units`, `earned_manhours`; D64). Insert, update and delete are audited like BPA-60's approve.
9. **Activities with no counted Work Pack** (e.g. A-2301): no row (Q2).

Re-run key: (activity_ref, progress_period_id) and the values (ADR 0007). Business dates are not needed here; no clock read beyond `updated_at` (injected `Clock`, ADR 0013).

### 2. `GET /api/v1/progress-periods/{weekEnding}/earned-manhours` (contract first)
- **Schema `EarnedManhoursWeek`:** `{weekEnding, activities: [EarnedManhoursRow]}`, ordered by `activityRef`.
- **`EarnedManhoursRow`:**
  - `activityRef`, `activityName`;
  - `actualPct`;
  - `budgetedUnits` (null);
  - `earnedManhours` (null);
  - `lastLockedEarnedManhours` (null);
  - `weekIncrease` (null).
- **`weekIncrease`** = this week's `earned_manhours` − the activity's `earned_manhours` in the latest Locked period before the week. Derived, not stored. 0 is used when there is no Locked row, so the first week's increase = its Earned MH. It is null when this week's Earned MH is null. **ASSUMPTION**, Q7.
- **Groups:** ppc-team and cost-controller (delta §7). A new, more specific matcher goes before the `progress-periods/**` rule, so project-manager is refused here and cost-controller is still refused on `/workpacks`.
- **Responses:**
  - 404 "Week not found" when the week has no period (same text as BPA-60, **ASSUMPTION**).
  - 200 with an empty list when the week is not calculated yet.
  - 401 and 403 as elsewhere.
- **Built in:** `ProgressPeriodController` (one more method) over an `EarnedManhoursReadService`.

## Files that change
| Area | Files |
|---|---|
| Contract | `api/openapi.yaml`: path + `EarnedManhoursWeek`, `EarnedManhoursRow` |
| Backend | `worker/CalculateEarnedManhoursWorker.java`; `progress/EarnedManhoursService.java` (roll-up, rounding, upsert, audit); `progress/EarnedManhoursReadService.java`; `persistence/ActivityProgressRepository.java`; `api/ProgressPeriodController.java` (new method); `config/SecurityConfig.java` (matcher) |
| Test support | `bpatest/ProcessTestApplication` excludes the new worker; `BookHoursFixture` resets `activity_progress` |
| Existing tests (instance no longer rests at `ServiceTask_CalculateEarnedManhours`) | `ProgressRuleApiTest.BPA61_allAcceptedGoesToEarnedManhours` waits for `EARNED` or `ServiceTask_PrepareVarianceCheck`; `CompletionApprovalApiTest.AFTER_RECORD` gains `ServiceTask_PrepareVarianceCheck`; both classes' `cancelWaiting` lists gain it. To be said in the PR |
| Tests | new `EarnedManhoursApiTest` (8 `BPA62_` + extras) |
| Docs | data model §13.3.6 "As built by BPA-62" + the Earned formula text (Q1), also in `intent/BPA-4-timesheet-to-progress/data-model-v0.7-delta.md` §3.6; plan; REVIEW.md; `intent/README.md` |

Not changed: BPMN (Q5), DMN, database schema, frontend.

## Order of work
1. Approve the plan and commit it (`Approved`). No Jira transition without asking (Q6).
2. Contract first: `api/openapi.yaml` (the generated interface compiles; nothing implements the new method yet).
3. Tests (`EarnedManhoursApiTest`). Run them red **before any production code** and commit. Expected failures:
   - the scenario tests time out waiting for `activity_progress` rows (no worker);
   - the GET scenario gets 501 or 404 (not implemented).
4. Worker, services, repository, controller, security; then green: the class, then `ProgressRuleApiTest`, `CompletionApprovalApiTest`, `ModelDeployTest`, `ModelResourcesTest`.
5. Docs; full `./mvnw -B verify`; local-stack proof; `qa-reviewer`; fixes; REVIEW.md; plan Closed. Push, PR and Jira comments only when asked.

## Scenario-to-test table
**`EarnedManhoursApiTest` setup** (as `ProgressRuleApiTest`):
- **Clock and fixture:** 20 Nov 2026 Asia/Riyadh. `BookHoursFixture.reset()` + `completionBackground` + `fmsBackground`: WP-000457 (500 h) and WP-000461 (100 h) on A-2201, both In Progress; WP-000470 (100 h) on A-2299. Other Work Packs on A-2201 or A-2299, if any, are set to Draft by SQL.
- **Weeks:** 12 Nov is a Locked period (SQL); 19 Nov has a period owned by the instance.
- **Approvals and statuses:** set by SQL on `workpack_progress` and `workpack`.
- **How the calculation runs:** an instance is started **at `ServiceTask_CalculateEarnedManhours`** (`startInstructions`), and the test waits for `ServiceTask_PrepareVarianceCheck`. So each scenario runs the real worker on seeded rows; this is the "service-level" test on seeded rows, without a red-by-compile-error (Q8).

| # | Scenario | Test | Seed / act | Asserts | Expected first run |
|---|---|---|---|---|---|
| 1 | Roll-up is hours-weighted | `BPA62_rollUpIsHoursWeighted` | 457 approved 40, 461 approved 100 (19 Nov) | A-2201: actual 50.00, Earned 600.00 | red: no row (times out at the task) |
| 2 | One Work Pack on an activity | `BPA62_earnedManhoursFromOneWorkPack` | 461 Draft; 457 approved 40 | A-2201: 40.00, 480.00 | red |
| 3 | No budgeted units, no Earned | `BPA62_noBudgetedUnitsGivesNoEarned` | 470 approved 10 | A-2299: actual 10.00, `budgeted_units` and `earned_manhours` NULL | red |
| 4 | Rounding half-up | `BPA62_roundingHalfUp` | A-2201 budgeted 1000.01 (SQL, restored after); 461 Draft; 457 approved 33.33 | Earned 333.30 | red |
| 5 | Locked week not recalculated | `BPA62_lockedWeekIsNotRecalculated` | 12 Nov Locked with an A-2201 row Earned 360.00 (SQL); 457 approved 40, 461 100 | 12 Nov row still 360.00, `updated_at` unchanged | red: no 19 Nov run (the 12 Nov assertion alone would pass, so the test also awaits the 19 Nov row) |
| 6 | Completed keeps counting at 100% | `BPA62_completedWorkPackKeepsCountingAt100` | 461 Completed, `approved_pct` 100 in Locked 12 Nov, no 19 Nov row; 457 approved 40 | A-2201: 50.00, 600.00 | red |
| 7 | Not started counts at 0% | `BPA62_notStartedWorkPackCountsAtZero` | 461 Released, no locked or approved %; 457 approved 40 | A-2201: 33.33, Earned **400.00** (Q1) | red |
| 8 | The week's increase is shown | `BPA62_weeklyIncreaseIsShown` | Locked 12 Nov A-2201 Earned 360.00; 19 Nov calculated at 600.00 (457 40, 461 100) | `GET …/2026-11-19/earned-manhours` as ppc-team: A-2201 `earnedManhours` 600.00, `weekIncrease` 240.00 | red: 404 or 501 |

**Extra tests** (not scenarios):
- `aRerunGivesTheSameRows`: the job twice; the same rows, no new audit rows.
- `aSentBackWeekIsRecalculated`: a changed approved % updates the row, with audit.
- `aLockedWeekIsRefused`: the job fails, nothing is written.
- `anActivityWithNoCountedWorkPackHasNoRow`: A-2301, and a row removed when its last Work Pack goes back to Draft (Q2).
- `earnedManhoursEndpointIsForPpcAndCostController`: 200 for ppc-team and cost-controller; 403 for project-manager; cost-controller still gets 403 on `/workpacks`.
- `unknownWeekIs404`.

## Proof (one command per Definition of Done line)
| DoD line | Command / evidence |
|---|---|
| Merged through a reviewed PR | `gh pr view <n> --json state,mergedAt,title` |
| One test per scenario, passing in CI | `./mvnw -B -Dtest=EarnedManhoursApiTest test`; `grep -c "void BPA62_" backend/src/test/java/com/lcs/star/bpa/EarnedManhoursApiTest.java` = 8; `gh pr checks <n>` |
| GET … earned-manhours documented | `grep -n "earned-manhours" api/openapi.yaml`; `ContractTest` / the generated interface compiles |
| Process runs end to end locally | the run below |
| BPMN and DMN match deployed | deployed `Process_PPC_WeeklyProgressPeriod` equals `models/` (whitespace-normalised, as BPA-61) |
| Open questions resolved or logged | the Questions below, as Jira comments (shown first) |
| Plan matches the diff, Closed | `grep '^Status: Closed' intent/BPA-4-timesheet-to-progress/plans/BPA-62.md` |
| Demoed at sprint review | Human gap |

**End-to-end run in the local stack:**
1. Rebuild `bpa-app` (plain `docker build` + `docker compose up -d --no-build`).
2. Reset and seed as BPA-61's proof: a Locked 12 Nov week with approved %; start the weekly process at `ServiceTask_OpenProgressPeriod`.
3. As ppc-team: "Send hours to FMS", upload `completion-demo-all-read.csv`, then approve all → Accepted.
4. **`psql`:** `activity_progress` rows for 19 Nov, one per activity with a Released, In Progress or Completed Work Pack. The expected A-2201 values are worked out by hand from the local Work Packs first (there are many seeded Work Packs on A-2201), and A-2301 has no row.
5. `GET …/2026-11-19/earned-manhours` as ppc-team and cost-controller (200), and as project-manager (403).
6. **Operate:** the instance waits at `ServiceTask_PrepareVarianceCheck` (BPA-64, no worker yet).

## Risks
- **After this step the instance waits at `ServiceTask_PrepareVarianceCheck`** with no worker (BPA-64). Demos stop there.
- **Existing tests change:** two of BPA-60's and BPA-61's waits must accept the new resting point (listed above).
- **Many seeded Work Packs on A-2201 in the local stack:** the demo figures for A-2201 mix them. A-2301 exists for the BPA-68 demo, but no Work Pack sits on it yet.
- **The BPMN documentation says "stored per week, never overwritten"**, but Jira says a week sent back before the lock is recalculated. Jira wins (precedence); the documentation is not changed here (Q5).
- **Unrounded vs stored %:** Earned is not reproducible from the stored `actual_pct` (399.96 vs 400.00 in scenario 7). The data-model text is corrected (Q1).

## Questions
1. **Rounding order.**
   - Scenario 7 expects 33.33% and Earned 400.00. That needs the unrounded % (33.333… × 1200 ÷ 100 = 400.00); the data model's formula on the stored `actual_pct` gives 33.33 × 1200 ÷ 100 = 399.96.
   - **Recommended:** compute Earned from the unrounded %, store `actual_pct` rounded half-up to 2 dp, and correct the data-model text (§13.3.6 and delta §3.6) to "activity % (unrounded) ÷ 100 × budgeted_units".
   - **Check of the other 7 scenarios** (same either way): 1 and 6: 50 exactly → 600.00; 2: 40 → 480.00; 3: 10, no Earned; 4: 33.33 exact (one Work Pack) × 1000.01 = 333.303 → 333.30; 5: unchanged 360.00; 8: 600.00 − 360.00 = 240.00. Only scenario 7 differs.
2. **Which activities get a row.**
   - **Recommended:** only activities with at least one Released, In Progress or Completed Work Pack. An activity with none (e.g. A-2301, or A-2201 if all its Work Packs were Draft) gets **no row**, not a 0% row.
   - On a recalculation (BPA-66), this week's row of an activity that no longer has a counted Work Pack is **deleted**, so the week shows what the rule gives now (**ASSUMPTION**).
   - When the counted Work Packs' Σ weight is 0 (no phase hours), the activity gets **0.00%** (and Earned 0.00 when it has budgeted units) instead of a division by zero (**ASSUMPTION**; no source covers it).
3. **"Last locked %".** **Recommended:** BPA-60's choice. That is `approved_pct` in the latest **Locked** period before the week (`ProgressWeekRepository.lastLockedPct`), else 0; it is **not** the stored `previous_pct`. The reason: a Completed or Released Work Pack has no row for this week, so it has no `previous_pct`. Where a row exists, both give the same value.
4. **"Latest budgeted units".** **Recommended:** the current `activity.budgeted_units` (the seeded copy, "latest forecast budgeted units", spec E2-D11), copied onto each week's row when it is calculated. A Locked week's snapshot never changes; an unlocked week takes the then-current value on recalculation.
5. **BPMN:** no model change is needed (the task, its job type and both flows exist). The task's documentation ("stored per week, never overwritten") disagrees with Jira (recalculated before the lock, BPA-66). **Recommended:** leave the BPMN as is (a doc-only change redeploys the process) and note it; or fix the text in this PR if you prefer. In Risks: after this step the instance waits at `ServiceTask_PrepareVarianceCheck` with no worker (BPA-64).
6. **Jira:** BPA-62 is already In Progress. The merged PR in its development panel is probably BPA-61's #54. I will ask before any transition.
7. **The week's increase (ASSUMPTION).** It is this week's Earned MH − the Earned MH of the latest **Locked** week before it. When the activity has no Locked row the base is 0, so the increase = this week's Earned MH. It is null when this week has no Earned MH. The response also carries `lastLockedEarnedManhours`, so a later screen can show both.
8. **Test approach.** The scenario tests start an instance **at `ServiceTask_CalculateEarnedManhours`** on seeded rows and wait for `ServiceTask_PrepareVarianceCheck`. This runs the real worker and avoids a red run that is only a compile error. Scenario 8 reads the endpoint. Agree?
9. **Screen (out of scope, backend only):** BPA-62 builds no screen; the endpoint is for a later story (the PM review, BPA-66, or BPA-68's demo). Who shows Earned MH to PPC and the Cost Controller is open.
10. **Audit and endpoint texts (ASSUMPTION):**
    - one `audit_log` row per changed column of `activity_progress` (D64), deletes included;
    - 404 "Week not found";
    - the Locked-week failure text "Week ending <d MMM yyyy> is locked" (as BPA-60).

## Human-gap log
- None yet. No inputs were staged before this plan.

## Resolutions (20 Nov 2026)
Approved with the recommended answers:
- Q1: Earned from the unrounded %; `actual_pct` stored rounded; the data-model text is corrected.
- Q2: no row for an activity with no counted Work Pack; a stale row of an unlocked week is deleted; Σ weight 0 gives 0.00%.
- Q3: last locked % from the latest Locked period (`lastLockedPct`).
- Q4: the current `activity.budgeted_units`, snapshotted per week.
- Q5: no BPMN change.
- Q7: increase against the latest Locked week, base 0.
- Q8: the scenario tests start at `ServiceTask_CalculateEarnedManhours`.
- Q10: texts as written.
- ASSUMPTIONs to comment in Jira after a yes.

## Progress and departures
- **Red run (9fd70da):** 13 of 14 failed for the expected reasons (11 tests timed out at `ServiceTask_CalculateEarnedManhours` because there is no worker; the two endpoint tests got the 501 stub). Green after the implementation (81f73e4): `EarnedManhoursApiTest` 14/14.
- **Departure, a stub:** adding the path to `api/openapi.yaml` makes the generated interface method abstract, so the commit with the contract and the red tests also holds a `ProgressPeriodController` method that answers 501, so the tests compile. The implementation replaced it.
- **Weak test, said plainly:** `aLockedWeekIsRefused` passed in the red run (no worker writes anything, so "no row" held). It still proves no row is written for a Locked week once the worker exists.
- **First red attempt invalid:** the setup inserted the 12 Nov period, which `BookHoursFixture.reset()` already creates; the 14 tests all failed with a duplicate key. Fixed in the test (it locks the fixture's period, as BPA-61 does) and re-run; the red result above is the second run.
- **Tests after code, no red run:** `EarnedManhoursServiceTest` (pure roll-up and rounding, 5 tests).
- **Test support:** `ProcessTestApplication` excludes `CalculateEarnedManhoursWorker`; `BookHoursFixture` clears `activity_progress` before `progress_period`.
- **Edits to existing tests (the instance now moves on to `ServiceTask_PrepareVarianceCheck`; no stored-value assertion changed):** `ProgressRuleApiTest.BPA61_allAcceptedGoesToEarnedManhours` accepts `EARNED` or `ServiceTask_PrepareVarianceCheck`; `CompletionApprovalApiTest.AFTER_RECORD` and both classes' `cancelWaiting` lists gain the new step. To be said in the PR.
- **Workers fail, no BPMN error:** `calculate-earned-manhours` fails with no retries for a week with no period or a Locked week (as the BPA-61 workers), not with a BPMN error and `validationMessages`.
- **Data model:** §13.3.6 and the delta §3.6 formula now say Earned comes from the unrounded %; §13.3.6 has the "As built by BPA-62" note.
- **Result:** `EarnedManhoursApiTest` 14/14, `EarnedManhoursServiceTest` 5/5, `ProgressRuleApiTest` 13/13, `CompletionApprovalApiTest` 22/22, `ModelDeployTest` 2/2, `ModelResourcesTest` 2/2.

**Local-stack proof (20 Nov 2026, new `bpa-app` image, process definition version 8; the BPMN and DMN are unchanged by this story, so the deployed models still equal `models/`):**
- Reset the week by SQL, seeded a Locked 12 Nov week (approved 30 / 60 / 10), started the weekly process at `ServiceTask_OpenProgressPeriod`, then as ppc-team through the portal API: "Send hours to FMS", upload `templates/demo/completion-demo-all-read.csv`, approve all at the pre-filled % (WP-000011 45, WP-000030 100, WP-000031 20, WP-000032 0).
- `activity_progress` for the week ending 19 Nov: A-2201 11.07% / 1200.00 / **132.89**; A-2202 20.00% / 800.00 / 160.00; A-2203 0.00% / 950.50 / 0.00; A-2299 0.00% and no Earned (no budgeted units); **no row for A-2301** (no Work Pack). Audit rows written (`actual_pct`, `budgeted_units`, `earned_manhours`, `bpa-service`).
- Worked out by hand from the local Work Packs: A-2201 Σ(% × hours) = 45 × 400 + 100 × 150 = 33000 over Σ hours 2980 = 11.0738% → 11.07; × 1200 ÷ 100 = 132.886 → 132.89. A-2202 20% × 800 = 160.
- `GET /api/v1/progress-periods/2026-11-19/earned-manhours`: 200 as ppc-team and cost-controller (week increase equals Earned: no Locked earned rows); 403 as project-manager; cost-controller still 403 on `/workpacks`; an unknown week is 404 "Week not found".
- **Operate:** the instance waits at `ServiceTask_PrepareVarianceCheck` (BPA-64, no worker yet).
- Full `./mvnw -B verify`: 601 tests, 0 failures. No frontend change, so no frontend lint, test or build.
- Side effect on the local stack: the week ending 19 Nov holds these rows and the instance at `ServiceTask_PrepareVarianceCheck`; a Locked 12 Nov seed period (`process_instance_key` 1).

## After the `qa-reviewer` pass (20 Nov 2026; REVIEW.md "BPA-62 at d10a8ac")
- **No Important findings.** Nits:
  - The Locked check in `calculate` is not atomic with the writes (no lock on the `progress_period` row), so a concurrent lock could interleave. Left (POC): BPA-66 and BPA-67 should take `SELECT ... FOR UPDATE` on the period when they land.
  - **Q7 text made exact (ASSUMPTION):** `lastLockedEarnedManhours` is the activity's row in the latest Locked week that has a row for it, not strictly the latest Locked week's: an activity with no row in the most recent Locked week takes an older Locked week as the base.
  - `BPA62_lockedWeekIsNotRecalculated` cannot fail on its 12 Nov assertion alone; the guard is `aLockedWeekIsRefused` (said above).
  - The delta file is now in "Files that change".
- **To say in the PR:** the edited existing tests, the 501 stub, `EarnedManhoursServiceTest` written after the code.
- **Not done:** the plan's ASSUMPTION comments are not posted in Jira yet (shown to Ahsan first, posted on his yes).
