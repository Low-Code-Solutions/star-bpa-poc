# Plan: BPA-37 Write ADRs 0001-0014 and 0016

Jira: BPA-37 (Task, epic BPA-3)
Story last updated: 2026-10-28T21:39:14.876+0400
Status: Closed (2026-10-29)
Approver: Faraz (Product Owner and Architect)
Branch: BPA-37-write-adrs, from main 119a557

## Readiness check (Task, so a light check)
- Goal, changes, rules and done criteria stated in Jira: yes. Jira status In Progress (set by the team).
- Dependency "the docs Task, so 09 is in the repo to cite": BPA-35 is Done; `docs/09-architecture-decisions.md`
  is on main (merged in PR #6).
- Decisions behind it: 09 L3 (ADR list, MADR short form, index maps every 09 row), 09 §4 item 1,
  ADR 0015 numbering note (0016 reserved for BPA-19), BPA-19 description (content of 0016).
- Rule from Jira: **no new decisions.** Each ADR restates 09, cites its rows, lists options as recorded in
  07, 08 or 09; anything not in the sources is marked "not recorded in the sources".

## Sources (read 29 Oct 2026)

| Source | Where | Used for |
|---|---|---|
| 09 architecture decisions (LOCKED, change log to 28 Oct) | repo `docs/09-architecture-decisions.md` | Decisions, rows, deciders, dates |
| 07 Epic 1 design pack v0.5 | Claude Project `claude/07-epic1-design-pack.md` §5, §6, §8, §11 (G1-G12) | Options and original proposals |
| 08 dev-readiness plan v0.1 | Claude Project `claude/08-epic1-dev-readiness-plan.md` Gate 1, R1-R8 | Options (first ADR proposals, superseded per 09 §3) |
| 06 decision log | Claude Project `claude/06-epic1-decisions-draft.md` D56, D58-D63, D70 | Amendments to 09 rows cited by 09's change log |
| 10 assignment file | Claude Project `claude/10-s18-s19-assignment-file.md` §1 | S19 rationale (confirmed as D58) |
| Data model v0.4 | repo `docs/data-model.md` §8 | assignment_state transitions (G7 as amended) |
| ADR 0015 | repo `docs/adr/0015-delivery-artifact-chain.md` | Format; amendments to L1 and L14 |
| BPA-19 (Done) | Jira | Content of 0016 |
| CLAUDE.md | repo | How 0016 is applied today |

## Evaluation findings (reported, not resolved silently)
1. **0009 status.** 09 L3 keeps 0009 Proposed until Khitaba confirms S19, and 09 §2 still lists S19 as
   awaiting her. She confirmed it on 27 Oct as **D58** (06; 10 header "CONFIRMED"); 00-context and 03 already
   rely on it. So 0009 is **Accepted**, as BPA-37 allows. The 09 §2 row is stale; not edited here (out of scope).
2. **G2 is not a 09 row.** L3 lists G2 under 0006, but 09 §1 has no G2 row: G2 is in 07 §11, decided as D56,
   and its form part is superseded by S14. 0006 cites 07 G2 / D56 plus S14, and the index notes it.
3. **Rows L3 does not place.** L3 predates S25-S29 and does not map most L rows. Mapping below; S25-S27 go to
   0005 as BPA-37 says. Five rows are not architecture decisions and get "no ADR" with the reason, as L3
   already does for S10: S10, S13, L3, L16, L19. L13 (protect main) is mapped to 0016 with L7 (see Q1).
4. **No S11 or S12** in 09 (numbering gap); nothing to map.
5. **Amendments after 09.** G7 is amended by D61, G10 by D70, and D62 replaced the PT1H TTL. The ADRs state
   the current decision and cite the amendment; they do not re-decide.

## Row-to-ADR mapping (the index)

| ADR | Title | 09 rows |
|---|---|---|
| 0001 | Runtime and versions | G1; S24 (consequence) |
| 0002 | Application stack | Spring Boot workers (09 §1 note, closes 08 R7); S23 |
| 0003 | Repo layout | L1 (amended by 0015); L17 |
| 0004 | UI channel and task API | S2, S4, S14; S15 (rejected option) |
| 0005 | Identity and authorisation | S5, S6, G9, S22, S25, S26, S27, S28, S29; L2, L4, L9, L18 |
| 0006 | Validation pattern | G2 (07 §11, D56); S14 conditions |
| 0007 | Reliability and idempotency | S7; G10 (amended by D70) |
| 0008 | Messaging and instance state | G3, G6, G7 (amended by D61); L10 (assignment_state part) |
| 0009 | Assignment file intake | S18, S19 (D58); L6, L20 |
| 0010 | Data model, SOR and migrations | S16, S17; L5, L8, L11 |
| 0011 | API contract and tooling | S20, S21; L12 |
| 0012 | Print and notifications | S1, S3, S8 |
| 0013 | Time and dates | S9 |
| 0014 | Test strategy | L15; L10 (lint and CI part) |
| 0015 | Delivery artifact chain (exists) | L1 (amended); L14 (plan.md sentence replaced) |
| 0016 | Jira as source of truth for requirements | BPA-19; L7, L13, L14 (`.mcp.json` part) |
| none | | S10 (stays in Jira, L3); S13 (review work, 09 §2); L3 (the ADR scheme: this index); L16 (docs/04); L19 (assigned work) |

## Files that change

| File | Change |
|---|---|
| docs/adr/0001-runtime-and-versions.md … 0014-test-strategy.md | New (14 files), MADR short form like 0015 |
| docs/adr/0016-jira-source-of-truth.md | New |
| docs/adr/README.md | Status Planned → Accepted for 0001-0014 and 0016 with links; a row-by-row table mapping every 09 row to its ADR |
| intent/BPA-3-work-pack-assignment/spec.md | Design sources row "ADRs": all written |
| intent/BPA-3-work-pack-assignment/plans/BPA-37.md | This plan |
| intent/README.md | Regenerated |

Not changed: `docs/09-architecture-decisions.md` (finding 1 is raised, not fixed), ADR 0015.

## Order of work
1. This plan, committed as Approved (approval given in the session before the first commit).
2. ADRs 0001-0014, then 0016.
3. README index; spec.md row; intent/README.md regenerated.
4. Proof checks; qa-reviewer pass in a fresh context; open the PR; Jira comment with PR, plan path, SHA.
5. Review fixes; final commit sets this plan Closed before Ahsan merges.
6. After merge (not on this branch): add an ADR column to the Claude Project's `06-epic1-decisions-draft.md`
   (09 §4 item 1) and copy the ADRs into the Project's `adr/` folder.

## Risks
- **Restating drifts from 09** (wording changes meaning). Mitigation: each ADR quotes 09's values (versions,
  names, numbers) exactly; the proof script checks every 09 row ID appears in the index and in its ADR.
- **An "option considered" gets invented.** Mitigation: options are only the alternatives named in 07, 08 or
  09 (e.g. 08 Gate 1 proposals, Keycloak, Tasklist forms, watched folder); otherwise "not recorded in the sources".
- **Size**: 15 files in one PR. Mitigation: same template; review is restatement against 09, not design.

## Proof
- Script: every row ID in 09 (S*, G*, L*) appears in the README index; every ADR it maps to contains that ID.
- Every ADR has the header fields (Status, Date, Deciders, 09 rows) and the four sections.
- `grep` for the partner name, client names and people from source documents: none.
- Plan check script passes for this branch; hook tests still pass.

## Questions
- **Q1 (for the approver):** 09 does not place L7 (how Claude delivers) and L13 (protect main) in any ADR.
  Plan: put both in 0016 with BPA-19, as "how requirements reach the repo and how changes come back". The
  alternative is "no ADR" for L13. Recommended: 0016.
  **Resolved 29 Oct (Faraz): both in 0016.**
- Q2 (non-blocking): 0016's options. BPA-19 records none. The Project memory records that the team chose Jira as
  source of truth "over linkage-only"; 0016 lists that one option and cites it as a PO statement. Confirm.
  **Resolved 29 Oct (Faraz): cite it.**

- Found while writing (raised in the PR and in Jira, not resolved here):
  - Q3: with TTL 0 (D62) the G10 message ID likely no longer stops a double publish (Camunda docs: uniqueness
    applies only to buffered messages). Proposed: add a check to BPA-25. ADR 0007 records it as an open point.
  - Q4: 09 L6 names the template column "Employee ID"; D59 (later) names it "Badge No". ADR 0009 follows D59
    and notes the difference.
  - Q5: 09 L15 is "CLAUDE.md wording" but CLAUDE.md does not contain it; CLAUDE.md's branch example still says
    "ADR 0001" for Jira as source of truth (now 0016). Follow-up item proposed.
  - Q6: 09 §2 still lists S19 as awaiting Khitaba (stale since D58); L7's subtask scheme differs from how the
    09 §4 work was created (Tasks BPA-34 to 37). Both noted in the ADRs.

## Review (qa-reviewer, fresh context, 29 Oct)
4 Important, 8 nits. Fixed: 0007 message-ID quoted from 09 and 07 §5 verbatim, and the TTL-0 point made an
open point (checked against the Camunda messages docs); 0009 upload-group rule cited to D58 and BPA-12 with
its ASSUMPTION label; 0016 date and deciders limited to what the sources record; 0001 S28 removed from the
S24 trigger; 0012 cites the data-model unique key; 0005 notes the missing `.env.example` placeholders;
README notes the unnumbered 09 §2 rows; this plan's row count and step 1 corrected.
Not fixed: CLAUDE.md example (outside this item; Q5).

## Human-gap log
- 29 Oct: the 06, 07, 08 and 10 sources live only in the Claude Project; Claude read them there (no copy needed).
- 29 Oct: plan approved by Faraz in the claude.ai session, with Q1 and Q2 answered there.
- 29 Oct: PR #10 merged by Ahsan (07:45 UTC) before the plan was set to Closed; closed in a follow-up PR
  (same route as BPA-38, PR #9). After-merge step 6 (06 ADR column, ADRs copied to the Project) done the same day.
