# Plan: BPA-40 Data model v0.5: approval fixes

Jira: BPA-40 (Task, epic BPA-3)
Story last updated: 2026-10-29T13:32:00+0400 (comment 10411)
Status: Approved (Faraz, 29 Oct 2026, in the claude.ai session: "Approve A, apply, start v0.5"; scope = BPA-40 description and comments 10408, 10409, 10411)
Approver: Faraz (Product Owner and Architect)
Branch: BPA-40-data-model-v0.5, from main 598814a

## Readiness check (Task, so a light check)
- Scope, rules and done criteria are in Jira BPA-40; every decision is recorded in its comments and in the Claude
  Project's `audits/data-model-v0.4-approval-2026-10-29.md`.
- Rule: **no new business rules.** Every change traces to an ID below; anything else is marked (A) or raised.
- Story updates made on 29 Oct that v0.5 must match: BPA-6 (D74 scenario), BPA-12 (D73 scenario, worker_name),
  BPA-15 and BPA-16 (per-column SOR labels).

## Changes to `docs/data-model.md`

| ID | Section | Change | Source |
|---|---|---|---|
| — | header, §0 | v0.5 status and change log | this Task |
| A1 | §1.1, §1.2 | DB defaults `gen_random_uuid()`, `now()`; ON DELETE NO ACTION on every FK | Usama |
| A2 | §4.1 | `workpack_number_seq`, formatted by `save-workpack` | mechanical |
| A3, A15 | §6.1 | audit columns not logged; bytea text form (A); index `(table_name, record_id)`; split merged bullet | mechanical |
| A4 | §4.3 | rows matched by `id` carried in task variables; `id` joins the S14 allow-list | Usama |
| A5 | §4.2 | duplicate phase checked by the worker first; UNIQUE is the backstop | mechanical |
| A6, A7, B8 | §11 | tests own scenario data; seed holds the named reference rows; PRJ-140 definite | Usama |
| A8 | §3 intro | R__ seed upserts on business keys; never touches runtime `drawing_revision` rows | mechanical |
| A9 | §1.3, §3–§6, §7.2 | one SOR label per system | mechanical |
| A10, B13 | §2 | ERD cardinalities aligned with the tables | mechanical |
| A11, A12 | §1.3, §3.11 | per-column labels kept; row-level reason stays; look-ahead note | Khitaba, PO |
| A14 | §1.1 | Camunda usernames carry no `_ref` (exception; 04 entry belongs to L16) | Usama |
| B1, C1 | §5.2, §7.3 | `wp_outcome` column + CHECK; `outcome` nullable only when `wp_outcome` is set | Ahsan, PO |
| B2, B3 | §0, §5.1, new §5.12 | remove "message ID still prevents a double publish"; new `published_message` table | Usama |
| C2 | §5.1, §8 | failed or uncorrelated publish deletes the claim (compensating transaction) | PO |
| B4, C3 | §8 | compare-and-set, commit, synchronous correlate, revert on no-correlate; D61 unchanged | Usama, PO |
| B5 | §5.2 | partial unique index on synthetic rows | mechanical |
| B6 | §5.2, §7.3 | writer named for every outcome | mechanical |
| B7 | §5.10, §5.11 | test fixtures write `app_user` / `user_role_scope`; L2 scoped to runtime | Usama |
| B10, B14 | §8, §10.1, header, §5.3 | stale lines and citations | mechanical |
| B12 | §1.2, §6.1 | `bpa-service` when no user is in context | Usama |
| D73 | §5.1, §7.3, §8 | duplicate check per `(sha256, workpackNumber)`; new row outcome | Khitaba, PO |
| B15 | §5.2 | `worker_name` in the result (built, not stored) | Khitaba, PO |
| N1, D74 | §4.3, §9 | UNIQUE `(workpack_id, phase, material_ref)` + message; amends Q-DM4 | Usama, PO |
| N2 / C5 | §5.4 | `source_file` **kept** (BPA-13 asserts it); documented as a snapshot | PO |
| N3, N4 | §1.1 | deliberate snapshots and candidate keys documented | Usama |
| L8 gap | §11 | "worker in another department" cannot be seeded: no department column (A) | mechanical |

Also: `docs/03-sor-matrix.md` gets one footnote line (A12). This plan; `intent/README.md` regenerated.

## Order of work
1. This plan.
2. All model edits in one scripted pass with an assert per replacement (no hand retyping of the 67 KB file).
3. 03 footnote.
4. Proof checks; fresh-context review against Jira and the brief; PR; Jira linkage comment.
5. Review fixes; plan Closed before Ahsan merges.
6. After merge: Claude Project `claude/11-data-model.md` refreshed; Jira batch (confirmed first) for BPA-13 SOR Notes
   (`wp_outcome`) and BPA-12 result columns (`wpOutcome`), per B1's ripple.

## Risks
- **An edit drifts from a decision's wording.** Mitigation: each change cites its ID; the reviewer checks against the brief.
- **Accidental damage elsewhere in a large file.** Mitigation: scripted replacements with exact-match asserts; diff reviewed.
- **B12 read too widely.** `bpa-service` applies only where no user is in context; rows written as the result of a user
  task keep that user's username, as BPA-6 / D69 require. Flagged in the PR.

## Proof
- Every ID in the table above appears in the diff (script).
- Every table section still has the SOR column; no `TODO`.
- Plan check and hook tests pass.

## Review (qa-reviewer, fresh context, 29 Oct)
0 BLOCKER, 6 IMPORTANT, 8 NIT. Fixed: §5.12 claim limited to groups that are sent; failure messages per path; "or fails"
marked (A) and raised; `outcome` start value and writer wording; `published_message` out of audit scope; Q-DM14 row;
sequence MAXVALUE; N1 UNIQUE deferrable (A); `published_at` default; §7.3 CHECK note. Story conflicts (BPA-12, BPA-13
text vs B1/C3) logged in §10.2 for a confirmed Jira batch. spec.md "v0.4" line updated after merge.

## Questions
- Q1 (PO): BPA-13 and BPA-12 text still describe "publish … in the same transaction"; confirm the Jira batch in §10.2.
- Q2: resolved 29 Oct by the PO as D75, "Replacement could not be started; try again" (wording ASSUMPTION).

## Human-gap log
- 29 Oct: plan approved by Faraz in the claude.ai session as part of "start v0.5"; the scope was set by his
  BPA-40 decisions, so no separate plan review took place.
