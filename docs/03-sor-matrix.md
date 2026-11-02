# 03 SOR matrix: STAR BPA POC

Status: **ACTIVE, 28 Oct 2026 (Epic 1 rows; updated for 09-architecture-decisions, D58, D59, and PO decision P1 on WBS of 28 Oct).** Applied under PO delegation (D55, confirmed by Khitaba); open to PO challenge.
Amended 2 Nov 2026 (PO decision 2 Nov 2026, BPA-44): `user_role_scope` removed; test users are the seven personas, access by group only.
Owner: Faraz. Decision references (C, D) are in claude/06-epic1-decisions-draft.md; P1–P4 in claude/11-data-model.md §0.
Column-level detail (types, constraints, SOR per column) is in claude/11-data-model.md (repo docs/data-model.md, 09 S16).

Rule of thumb: the workflow owns the flow and its state; existing systems keep owning their data.
One SOR per field. "Real SOR" is the system that owns the data after go-live; in the POC every such row is a seeded copy labelled with that SOR.
This matrix labels each **entity**. Column-level labels (including foreign-key columns, which carry the SOR of the entity they identify, e.g. `lookahead_item.design_dwg_ref` → D365 F&O Document Control) live in `docs/data-model.md` §1.3 (A12, 29 Oct 2026).

## Known SOR positions (from the FRD review)
| Data domain | SOR | Integration pattern | Note |
| --- | --- | --- | --- |
| Employee / person | myPeople | Lookup by ID; mocked in POC | Retained 1 Oct 2026. Covers STAR employees and agency workers; agency workers carry a tag in myPeople (C11, PO decision 23 Oct 2026; tag field name and values are an ASSUMPTION). Contradicted by FRD-5-2 and FRD-7 §7.7 (D365 HR), recorded as C14 |
| Finance and procurement | D365 F&O (on-prem) | (confirm per entity) | |
| FMS-held data | FMS | Batch, file-based via Cloudera/Informatica | Retained |
| SAP-held data | SAP | (confirm) | Retained 1 Oct 2026 |
| Inventory, asset maintenance, quality, production planning | Partner-hosted legacy system **today**; **D365 F&O after cutover** for material reservation (incl. Traceability Number) and the material master | Undocumented today | D54: the POC models the post-cutover landscape and labels this data with D365 F&O (FRD-7 §7.2.3, §7.7.1; FRD-6-1 §6.1.2.3, §6.1.8.1) |

## Slice entities: Epic 1 Work Pack Assignment
| Entity | Key fields | Real SOR | POC access | Real integration (if made live) | Decision |
| --- | --- | --- | --- | --- | --- |
| Project | project_ref | D365 F&O Project Management | Reference (seeded copy) | Real-time | C8a |
| Job Code | job_code_ref | FMS (created by PCON Planner) | Reference (seeded copy) | Batch/file (partner-side) | C8b, D13 |
| WBS | wbs_ref | D365 F&O Project Management | Reference (seeded copy) | D365 F&O sources WBS data from FMS (batch/file, partner-side); D365 F&O → FMS integration carries updates (PO, 28 Oct, P1) | C8c, P1 |
| Activity, incl. FAC CRPO/Type/Name, Discipline, baseline dates, budgeted units | activity_ref | P6, read through the D365 PMS database | Reference (seeded copy) | Batch (PMS upload) | C13, D17 |
| CBS code of an activity | cbs_code_ref | D365 F&O Cost Control Report | Reference (seeded copy) | Real-time | D40 |
| Drawing and revision, incl. status | drawing_ref, revision | D365 F&O Document Control | Read-only copy, changed only by the DrawingRevised event; the Superseded status of the previous revision is taken from the event, never decided by the POC (D45); supersededRevision is carried as a process variable, not stored (P3) | Real-time event, mocked by POST /api/v1/drawing-revisions | C15, D23, D41, D42, D53, D60 |
| Material master | material_ref | D365 F&O Product Information Management | Reference (seeded copy) | Real-time | C12, D54 |
| Material reservation, incl. Traceability Number | traceability_number; drawing_ref + material_ref | D365 F&O (PPC) | Reference (seeded copy) | Real-time | C12, C5, D54 |
| Department | department_ref | D365 F&O (organisation / financial dimension) | Reference (seeded copy) | Real-time | D18 |
| Look-Ahead PPC plan dates (Shop Drg Mark, Assembly / Integration, Weld Map Drawing) | drawing_ref | D365 F&O **Project Control (PCON)**, shown in the Engineering Feasibility Look-Ahead register (FRD-2 §2.5.11 #9-11, §2.8; verified 28 Oct) | Reference (seeded copy) | Real-time | D48 |
| Cutting sheet | cutting_sheet_ref, drawing_ref | FMS nesting | Reference (seeded copy) | Batch/file (partner-side) | D30 |
| Worker, incl. role, designation, agency flag | employee_ref (holds the Badge No, D59) | myPeople | Reference (seeded copy) | Lookup by ID | C1, C11, C14, C17 |
| Worker's personal cost centre | worker.cost_centre_ref | myPeople (ASSUMPTION: myPeople carries it; the code values are D365 financial-dimension codes) | Reference (seeded copy) | Lookup by ID | D40 |
| System user identity and email | app_user.employee_ref, email | myPeople (identity); corporate email system (email, ASSUMPTION) | Reference (seeded copy) | n/a | D19 |
| System user login, group membership (role) | Camunda username, Camunda group | Camunda identity (POC; OIDC later) | Seeded from personas.yaml at start-up (S22) | n/a | 09 S5, S6, S22 |
| Work Pack, incl. phases, man hours, materials, status, assignment_state | workpack_number | FMS in production (copied to D365 via Int-4.1); **mastered in the POC database** | Write | Batch/file (partner-side) | C2, D47 |

## POC-mastered data
| Entity | Why no existing system holds it in the POC | What happens at end of POC |
| --- | --- | --- |
| Process state (Camunda) | Owned by the workflow engine by design | Discarded |
| workpack, workpack_phase, workpack_material, workpack_schedule | The POC plays FMS's role for Work Packs (C2, D47) | Discarded |
| hour_journal_header, hour_journal_line | Simulates the D365 Project Hour Journal (Int-4.1, Int-5) | Discarded |
| release_signoff | Approval record of the POC workflow | Discarded |
| workpack_print | Record of the print issued to the shop floor (FRD-7 §7.3.3 audit trail) | Discarded |
| assignment_file, assignment_file_row | Intake log of uploaded assignment files and their row outcomes (D49, D58) | Discarded |
| worker_assignment, worker_assignment_history | POC workflow state; the real equivalent is FMS via Int-5 (D38) | Discarded |
| digital_timesheet, digital_timesheet_line | Simulates the D365 timesheet / digital tab (D36) | Discarded |
| notification | Output of the drawing-revision notice (D44) | Discarded |
| audit_log | Audit trail of POC-mastered data (FRD-7 §7.3.3, D64) | Discarded |

## Mocks
| System | Mock type | Location in repo |
| --- | --- | --- |
| myPeople | API mock (WireMock); Epic 1 uses the seeded Worker copy, no live lookup | backend/mocks/ |
| FMS (Worker assignment, Int-5) | Portal upload of the Excel file (POST /api/v1/assignment-files); result on screen and downloadable (D58). Template templates/worker-assignment-template.xlsx (D59, 09 S18); demo files in the repo, test fixtures under src/test/resources/ | backend (API), templates/ |
| D365 Document Control (drawing revisions) | In-app endpoint POST /api/v1/drawing-revisions publishing DrawingRevised (D41) | backend (API) |
| Corporate email | Mailpit (catch-only; SMTP 1025, web 8025) (09 S8) | docker compose |
| D365, P6, FMS master data | Repeatable Flyway seed R__seed_reference_data.sql; SOR per table via COMMENT ON TABLE (09 S16, S17); schema V1__baseline_schema.sql from docs/data-model.md | backend/src/main/resources/db/migration/ (09 L1) |
| myPeople / identity for test users | personas.yaml → Camunda users and groups + app_user; seven personas, username = group ID (09 S22; BPA-44) | backend config |
