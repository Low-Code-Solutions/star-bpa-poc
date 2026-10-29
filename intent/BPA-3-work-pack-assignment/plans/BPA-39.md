# Plan: BPA-39 Docs catch-up after the ADRs: CLAUDE.md test-first rule and ADR reference, 09 stale rows

Jira: BPA-39 (Task, epic BPA-3)
Story last updated: 2026-10-29T12:55:26.739+0400
Status: Approved (Faraz, 29 Oct 2026, in the claude.ai session)
Approver: Faraz (Product Owner and Architect)
Branch: BPA-39-docs-catch-up, from main f60294b

## Readiness check (Task, so a light check)
- Goal, changes, rules and done criteria stated in Jira: yes. Status Backlog.
- Rule from Jira: **no new decisions**. 09 is LOCKED, so 09 changes go only in its change log; ADR changes are
  consequence notes only.
- Not touched: `docs/data-model.md` (Ahsan/Usama are deciding the BPA-40 items in their own session; editing it
  here would conflict).

## Files that change

| File | Change |
|---|---|
| CLAUDE.md | New "Tests" section with the 09 L15 test-first rule, word for word. Branch example: "ADR 0001" → "ADR 0016" (Jira as source of truth). |
| docs/09-architecture-decisions.md | One change-log entry dated 29 Oct, covering: S19 confirmed as D58 (27 Oct; §2 row stale); L6 column is "Badge No" per D59; L7 work created as Tasks BPA-34 to BPA-37; S6 `user_role_scope` columns carry per-column SORs (A11, approved by PO 29 Oct). No row edited. |
| docs/adr/0005-identity-and-authorisation.md | Consequence note: A11 per-column SOR labels for `user_role_scope` (the row stays POC-held). |
| docs/adr/0007-reliability-and-idempotency.md | Consequence note: D73 amends D70 (duplicate check per `(sha256, workpackNumber)`; row outcome "Already processed in an earlier upload"). |
| docs/adr/0009-assignment-file-intake.md | Same D73 note; L20's "uploading it again shows File already processed" still holds (every Work Pack published). |
| intent/BPA-3-work-pack-assignment/plans/BPA-39.md | This plan |
| intent/README.md | Regenerated |

## Order of work
1. This plan, approved.
2. CLAUDE.md; 09 change log; three ADR notes.
3. Checks; fresh-context qa-reviewer; PR; Jira comment (PR, plan, SHA).
4. Review fixes; plan Closed in the final commit before Ahsan merges.
5. After merge: refresh the Claude Project copies of 09 and ADRs 0005, 0007, 0009.

## Risks
- **The ADR or 09 notes restate a decision differently from its source.** Mitigation: quote D73 and A11 as written
  in 06 and Jira BPA-12/15/16.
- **L15 wording drifts.** Mitigation: copied verbatim from 09 L15; a diff check proves it.

## Proof
- `grep` shows the L15 sentence in CLAUDE.md is identical to 09 L15.
- `git diff` on 09 touches only the change-log block.
- Plan check and hook tests pass.

## Review (qa-reviewer, fresh context, 29 Oct)
1 Important, 2 nits; all fixed: the ADR 0009 L20 line is now a condition (demo files do not exist yet); the D73
notes mark rule and wording ASSUMPTION as BPA-12 does; the A11 notes cite BPA-15/16 SOR Notes.

## Questions
- None blocking.

## Human-gap log
- 29 Oct: plan approved by Faraz in the claude.ai session.
