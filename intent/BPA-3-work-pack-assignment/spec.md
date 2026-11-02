# Spec: Work Pack Assignment (Epic 1, BPA-3)

Status: Accepted (Faraz, Product Owner, 2 Nov 2026; BPA-3 comment 10462; recorded under BPA-45)
Backfilled under BPA-33 (ADR 0015).
Reads with: `intent.md` (this folder) and the BPA-3 epic in Jira.

This file **links** to the design; it does not copy it. Its own content is the Delivery order and the
shared-contract table, which exist nowhere else. Where this file and a linked source disagree, the
precedence in CLAUDE.md applies.

## Design sources

| Topic | Master | Notes |
|---|---|---|
| Architecture decisions (LOCKED) | `docs/09-architecture-decisions.md` | verbatim copy of the Project file (L17), master in the repo since BPA-35 |
| ADRs | `docs/adr/` (index in `docs/adr/README.md`) | 0001–0016 written (0001–0014 and 0016 under BPA-37); the index maps every 09 row |
| Business decisions D1–D65 | Claude Project `claude/06-epic1-decisions-draft.md` | not copied (rationale; cited by D-number) |
| Process design (elements, workers, messages, errors) | Claude Project `claude/07-epic1-design-pack.md` §2–§7 | not copied; §1 forms, §8, §9 user_role, §10 and Flyway names are superseded by 09 |
| Domain data model, SOR per field, assignment_state transitions | `docs/data-model.md` (S16) | v0.5, approved 29 Oct 2026 (BPA-40, PR #14); V1 and the R__ seed are generated from it (09 §4 item 3) |
| Assignment file layout | Claude Project `claude/10-s18-s19-assignment-file.md` | `templates/README.md` (L6) |
| SOR matrix | `docs/03-sor-matrix.md` | ACTIVE 28 Oct, master in the repo since BPA-35 |
| Models | `models/bpmn/`, `models/dmn/` | master in the repo since BPA-36; linted on every PR (`npm run lint:bpmn`) |
| Field specs for React screens | `docs/form-specs/` | moved unchanged under BPA-36 (L1, S14); see its README |
| Stories and acceptance criteria | Jira BPA-6 to BPA-16, BPA-22, BPA-23, BPA-26; Spike BPA-25 | Jira only |

Processes: `Process_PPC_WorkPackAssignment` (main), `Process_PPC_WorkerReassignment` (BPA-13),
`Process_PPC_DrawingRevisionNotice` (BPA-16). Decision: `Decision_WorkpackReleaseCheck` (BPA-8).

## Delivery order

Story order set by the Product Owner on 2 Nov 2026 (BPA-3 comment 10462), matching BPA Sprint 2 and
the delivery focus rule of 29 Oct. It replaces the sprint split first proposed in the BPA-3 epic.
Items still marked PROPOSED are delivery sequencing by Claude, not business rules.

**Before any story (repo readiness, BPA-3 DoR row "Repo readiness")**
1. Done: repo restructure to 09 L1, docs copies, models into `models/` (BPA-34, BPA-35, BPA-36);
   ADRs 0001–0014 and 0016 (BPA-37).
2. Done: data model v0.5 approved (BPA-40) → full `V1__baseline_schema.sql` and
   `R__seed_reference_data.sql` (BPA-42; V1 amended by BPA-44).
3. Done: BPA-25 Spike, the four Camunda 8.10.1 API checks (S15, L18).
4. Done: walking skeleton (BPA-43): login, Flyway, model deploy, one worker, CI.
5. Open: full `api/openapi.yaml` (S20, L12; 09 §4 item 4) under **BPA-46**, owner Faraz (PO decision
   2 Nov 2026). Done before BPA-26 planning starts.
6. Superseded: the separate BPA-21 pilot (L7); BPA-14 is built in its normal turn (focus rule, 29 Oct).

**BPA Sprint 2 (commit):** BPA-26 → BPA-6 → BPA-7 → BPA-8 → BPA-23 → BPA-9 → BPA-11 → BPA-10.
**Stretch:** BPA-12 → BPA-22.
**Then:** BPA-14 → BPA-15 → BPA-13 → BPA-16 (BPA-13 and BPA-16 are separate processes).

Dependencies stated in the sources: BPA-26 depends on BPA-25; BPA-13 needs a completed Epic 1
instance (D37); BPA-22 consumes BPA-12's upload; BPA-14 needs BPA-22's assignments; BPA-15 needs
BPA-14's lines; Epic 2 starts only after an Epic 1 instance completes.

## Shared contracts: who creates, who extends

Several stories touch the same files. A story extends a contract only through its own plan; a change
to a contract's shape is recorded in the plan and, if it changes a decision, in an ADR.

| Contract | Created by | Extended by | Rule |
|---|---|---|---|
| `V1__baseline_schema.sql` | Walking skeleton (`workpack` only), then regenerated in full from the approved data model (S17, L5) | none while V1 is unfrozen; regenerate from `docs/data-model.md` | Frozen from the first shared or showcase environment; after that each change is `V2__<change>.sql` in the story that needs it |
| `R__seed_reference_data.sql` | Data-model work (S17; L8 sizes) | Stories that need an extra boundary row (PROPOSED) | Reference-data copies only; personas are written by the S22 startup job (L2) |
| `api/openapi.yaml` | Skeleton paths by BPA-43; every S20 endpoint, including the reference lists (activities, drawings, materials, departments, workpacks/{n}/look-ahead), by BPA-46 (Faraz; PO decision 2 Nov 2026) | Each story implements its endpoints against the contract; a change to a path or schema is made in the story's PR and recorded in its plan | Error texts from the stories; unknown texts get `x-assumption: true` (L12) |
| BPMN and DMN in `models/` | Repo-restructure work (copied with the 09 §4 item 2 fixes) | A story changes a model only if its acceptance criteria require it; the plan names the element IDs (PROPOSED) | Lint with `.bpmnlintrc`; Modeler review for structural changes (S13) |
| `workpack.assignment_state` | Data model §8 transitions (D61) | Set by: BPA-10 (AwaitingFile), BPA-22 (Assigning, or AwaitingFile when all rows are rejected), BPA-15 (Assigned); a replacement upload sets Replacing and BPA-13 resets Assigned on both outcomes; read by BPA-12 | Not a process variable (L10); each worker element documents its transition |
| Messages (`AssignmentFileReceived`, `AssignmentFileReplaced`, `DrawingRevised`) | BPA-12, BPA-13 and BPA-16 respectively | none | Message IDs and TTLs per G10/D70 and D62 |
| Backend task API (`/api/v1/tasks…`) | BPA-26 (after BPA-25) | User-task stories BPA-6, 8, 9, 11, 23 use it | Per-task variable allow-list in config (S14) |
| `audit_log` (D64) | In V1 | Written by every save worker | |
| `personas.yaml` and the sync job | Walking skeleton (S22, L9) | Stories needing an extra persona (PROPOSED) | Only writer of Camunda users, groups and `app_user` (L2); seven personas, no scope (BPA-44) |

## Open points for this spec
- None. Closed 2 Nov 2026: the earlier point (BPA-6, 8, 9 and 23 placed before the task API) is
  answered by the PO's order above, which builds BPA-26 first (BPA-3 comment 10462).
