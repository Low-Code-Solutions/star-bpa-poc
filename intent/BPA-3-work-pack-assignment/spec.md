# Spec: Work Pack Assignment (Epic 1, BPA-3)

Status: Draft, awaiting Product Owner acceptance
Backfilled under BPA-33 (ADR 0015).
Reads with: `intent.md` (this folder) and the BPA-3 epic in Jira.

This file **links** to the design; it does not copy it. Its own content is the Delivery order and the
shared-contract table, which exist nowhere else. Where this file and a linked source disagree, the
precedence in CLAUDE.md applies.

## Design sources

| Topic | Source | Repo location (when copied) |
|---|---|---|
| Architecture decisions (LOCKED) | Claude Project `claude/09-architecture-decisions-2026-10-27.md` | `docs/09-architecture-decisions.md` (L17, repo-restructure work) |
| ADRs | `docs/adr/` (index in `docs/adr/README.md`; 0001–0014 planned) | here |
| Business decisions D1–D65 | Claude Project `claude/06-epic1-decisions-draft.md` | not copied (rationale; cited by D-number) |
| Process design (elements, workers, messages, errors) | Claude Project `claude/07-epic1-design-pack.md` §2–§7 | not copied; §1 forms, §8, §9 user_role, §10 and Flyway names are superseded by 09 |
| Domain data model, SOR per field, assignment_state transitions | Claude Project `claude/11-data-model.md` v0.4 (awaiting approval) | `docs/data-model.md` (S16) |
| Assignment file layout | Claude Project `claude/10-s18-s19-assignment-file.md` | `templates/README.md` (L6) |
| SOR matrix | Claude Project `03-sor-matrix.md` (28 Oct) | `docs/03-sor-matrix.md` (repo copy is still the template) |
| Models | SharePoint `04 Requirements snapshots/Epic 1 design 2026-10-27/models/` | `models/bpmn/`, `models/dmn/` (L1) |
| Field specs for React screens | the four `.form` files in the same snapshot | `docs/form-specs/` (L1, S14) |
| Stories and acceptance criteria | Jira BPA-6 to BPA-16, BPA-22, BPA-23, BPA-26; Spike BPA-25 | Jira only |

Processes: `Process_PPC_WorkPackAssignment` (main), `Process_PPC_WorkerReassignment` (BPA-13),
`Process_PPC_DrawingRevisionNotice` (BPA-16). Decision: `Decision_WorkpackReleaseCheck` (BPA-8).

## Delivery order

From the BPA-3 epic (proposed sprints), 08 Gate 5 (Ready order) and 09 §4 (follow-up work).
Items marked PROPOSED are delivery sequencing by Claude, not business rules; the team may reorder.

**Before any story (repo readiness, BPA-3 DoR row "Repo readiness")**
1. Repo restructure to 09 L1 as written (WireMock mocks and the Postgres init stay under `backend/`;
   decided by Ahsan, 28 Oct 2026); docs copies (00, 03, 09, data model);
   models into `models/`; ADRs 0001–0014.
2. Data model approved (BA checks SORs; PO and build team approve) → full `V1__baseline_schema.sql`
   and `R__seed_reference_data.sql` (S17, L5, L11).
3. `api/openapi.yaml` drafted after the data model (S20, L12).
4. BPA-25 Spike: the four Camunda 8.10.1 API checks (S15, L18).
5. Walking skeleton: login (L4), `workpack` table only (L5), one worker, one Camunda Process Test,
   CI with bpmnlint and tests (L10). Then the BPA-21 pilot (L7: on BPA-14).

**Sprint 1 (proposed in BPA-3):** BPA-6 → BPA-7 → BPA-8 → BPA-23 → BPA-9, and BPA-26 once BPA-25
is done.
**Sprint 2 (proposed in BPA-3):** BPA-11 → BPA-10 → BPA-12 → BPA-22 → BPA-14 → BPA-15, then
BPA-13 and BPA-16 (separate processes).

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
| `api/openapi.yaml` | Drafted after the data model (S20) | Each story implements its endpoints; stories adding an endpoint add it to the YAML in the same PR (PROPOSED) | Error texts from the stories; unknown texts get `x-assumption: true` (L12) |
| BPMN and DMN in `models/` | Repo-restructure work (copied with the 09 §4 item 2 fixes) | A story changes a model only if its acceptance criteria require it; the plan names the element IDs (PROPOSED) | Lint with `.bpmnlintrc`; Modeler review for structural changes (S13) |
| `workpack.assignment_state` | Data model §8 transitions (D61) | Set by: BPA-10 (AwaitingFile), BPA-22 (Assigning, or AwaitingFile when all rows are rejected), BPA-15 (Assigned); a replacement upload sets Replacing and BPA-13 resets Assigned on both outcomes; read by BPA-12 | Not a process variable (L10); each worker element documents its transition |
| Messages (`AssignmentFileReceived`, `AssignmentFileReplaced`, `DrawingRevised`) | BPA-12, BPA-13 and BPA-16 respectively | none | Message IDs and TTLs per G10/D70 and D62 |
| Backend task API (`/api/v1/tasks…`) | BPA-26 (after BPA-25) | User-task stories BPA-6, 8, 9, 11, 23 use it | Per-task variable allow-list in config (S14) |
| `audit_log` (D64) | In V1 | Written by every save worker | |
| `personas.yaml` and the sync job | Walking skeleton (S22, L9) | Stories needing an extra persona (PROPOSED) | Only writer of Camunda users, groups and `user_role_scope` (L2) |

## Open points for this spec
- Sprint 1 places BPA-6, 8, 9 and 23 before BPA-26 (task API). Whether those stories are first built
  and tested through Camunda Process Tests without the portal, with the React screens following
  BPA-26, is not stated in the sources. Question for the build team and PO; not decided here.
- `docs/data-model.md`, `docs/09-…` and the models are not yet in the repo; the links above point
  to the Claude Project and SharePoint until the repo-restructure work lands.
