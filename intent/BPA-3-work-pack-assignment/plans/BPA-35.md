# Plan: BPA-35 Copy design docs into the repo and make it master: 00, 01, 03, 09, data model

Jira: BPA-35 (Task, epic BPA-3)
Story last updated: 2026-10-28T20:08:53.772+0400
Status: Draft (awaiting approval by Ahsan)
Approver: Ahsan (Build Lead)
Branch: BPA-35-copy-design-docs, from main 5b446a9

## Readiness check (Task, so a light check)
- Goal, changes and done criteria stated in Jira: yes. Jira status In Progress (set by the team).
- Decisions behind it: 09 L17 (verbatim copy of 09), 09 S16 (`docs/data-model.md`), ADR 0015
  (repo is master; interim exception ends here).
- Blocking questions: Q1 (how the data model gets into the repo) needs an answer before step 4.

## Sources (Claude Project, read 28 Oct 2026 ~21:10 PKT)

| Repo file | Source | Version |
|---|---|---|
| docs/00-context.md | Project `00-context.md` (the ACTIVE one; the Project holds an older duplicate) | ACTIVE, 28 Oct 2026 |
| docs/01-glossary.md | Project `01-glossary.md` | DRAFT, 16 Oct 2026 |
| docs/03-sor-matrix.md | Project `03-sor-matrix.md` | ACTIVE, 28 Oct 2026 |
| docs/09-architecture-decisions.md | Project `claude/09-architecture-decisions-2026-10-27.md` | LOCKED; change log to 28 Oct (incl. ADR 0015 note) |
| docs/data-model.md | Project `claude/11-data-model.md` | v0.4, 28 Oct 2026, awaiting the approval chain |

## Files that change

| File | Change |
|---|---|
| docs/00-context.md | New. Copy, with: "Master copy" line changed to the repo (ADR 0015); partner name replaced by "the partner"/"partner-side" |
| docs/01-glossary.md | New. Copy, with the partner name replaced (2 places) |
| docs/03-sor-matrix.md | Replaced: the template becomes the ACTIVE Project version, with the partner name replaced and the Mocks row `mocks/` → `backend/mocks/` |
| docs/09-architecture-decisions.md | New. **Verbatim** copy (L17), no edits; checked for partner name first (none expected) |
| docs/data-model.md | New. Copy of v0.4, with the partner name replaced in §3.2 (1 place); status line unchanged ("awaiting approval") |
| CLAUDE.md | Reference section: interim exception removed; repo is master for all design artifacts; imports add `@docs/00-context.md`. (09 and data model are linked, not imported, to keep the always-loaded context small.) |
| intent/BPA-3-work-pack-assignment/spec.md | "Design sources" table: repo locations now live (09, data model, 03) |
| intent/BPA-3-work-pack-assignment/plans/BPA-35.md | This plan |
| intent/README.md | Regenerated |

Copy rule: content is copied as read from the Project; the only edits are the ones listed above, each
listed in the PR. 09 is verbatim (L17). Every other difference from the source is a defect.

## Order of work
1. This plan (Draft), then approval recorded.
2. 00, 01, 03 (small; written from the Project text with the listed edits).
3. 09 (verbatim).
4. Data model, by the route chosen in Q1.
5. CLAUDE.md Reference section and spec.md links.
6. Checks; qa-reviewer pass (compares each repo file against its Project source); open PR.
7. After merge: refresh the SharePoint "Project knowledge" copy of 00, 01, 03; put a dated snapshot
   of 09, the data model and the ADRs into "04 Requirements snapshots/Repo master 2026-10-28/";
   tell the team that 09, the data model and 03 now change only by PR.
8. Final commit before merge: plan Closed.

## Risks
- **Transcription errors** in the 68 KB data model and the 28 KB 09 (there is no direct file transfer
  from the Claude Project to the repo). Mitigation: Q1; byte and structure checks (heading count,
  table-row count per section) against the source; qa-reviewer compares line by line.
- **People keep editing the Project copies** after the repo becomes master, and the two drift.
  Mitigation: CLAUDE.md and 00-context say so; the PR asks Faraz and Khitaba to edit by PR from now on.
- **The data model is not yet approved.** Making the repo master does not approve it; its status line
  says so, and the approval chain (09 §4 item 3) continues on the repo file.

## Proof
- `grep -i` on the five files for the partner name, client names, people from source documents and
  signed-document file names: none.
- 09: identical to the Project text (qa-reviewer diff, byte count).
- Data model: section headings and table rows match the source (scripted count); byte count noted.
- Hook tests still pass.

## Questions
- **Q1 (blocks step 4): how should the data model and 09 get into the repo?**
  (a) Recommended: you download `claude/11-data-model.md` and `claude/09-architecture-decisions-2026-10-27.md`
  from the Claude Project and attach them in chat; I commit the exact bytes (plus the listed edits).
  (b) I retype both from the Project text and verify by structure and byte counts. Slower and riskier.
- Q2 (non-blocking): the Project has two `00-context.md` docs. After merge, delete the older one
  (only a Project owner can).

## Human-gap log
- 28 Oct: no tool moves a file from the Claude Project into the repo; Q1 asks a human to bridge it.
