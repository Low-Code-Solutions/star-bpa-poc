# 0010 Data model, SOR and migrations

- Status: Accepted
- Date: 2026-10-27 (decided, 09 LOCKED); written 2026-10-29 (BPA-37)
- Deciders: Ahsan (Build Lead), Usama (Lead Developer)
- Informed: Faraz (Product Owner and Architect), Khitaba (BA)
- 09 rows: S16, S17; L5, L8, L11

## Context

D365 F&O, SAP, FMS and myPeople stay the systems of record; the POC holds seeded copies of their data,
each labelled with its real SOR, and masters only the data no system holds (03-sor-matrix). The design
pack planned a per-epic schema and seed (`db/migration/V1__epic1_schema.sql`, `V2__epic1_seed.sql`,
07 §1, §9), and stories each carried their own SOR Notes. With 13 stories touching the same tables,
per-story or per-epic models would drift.

## Options considered

1. **Data model per epic or per story**, delivered as epic-named migrations (07 §1, §9). Rejected:
   superseded by S16 and S17 (09 §3).
2. **One domain data model, delivered by Flyway** (S16). Chosen.
3. **An enterprise canonical data model** shared across STAR systems, with app schemas derived from it.
   Considered and set aside by the PO on 28 Oct 2026 (00-context, out of scope).

## Decision

- **One domain data model** (S16), not per epic or story: `docs/data-model.md` is the single source,
  with the ERD, every table/column/type/constraint, and the SOR per field linked to 03-sor-matrix. Grouped
  by domain: reference data copies; Work Pack; Assignment; Hours; Notification; Access scope. Stories' SOR
  Notes reference it. The ERD is Mermaid inside `docs/data-model.md` (L11).
- **Flyway only delivers it** (S16, S17): versioned files named by change, starting with
  `V1__baseline_schema.sql` (generated from the approved data model by Usama), later `V2__<change>.sql`;
  repeatable seed `R__seed_reference_data.sql` (Usama).
- **SOR labelling** (S17): per table with `COMMENT ON TABLE` (plus column comments where a column's SOR
  differs), a header block per table in the seed file, and obviously fake test values. A CI test checks
  that every table has an SOR comment.
- **Walking skeleton exception** (L5): the skeleton's `V1__baseline_schema.sql` holds only the `workpack`
  table (with its SOR comment). When the data model is approved, V1 is regenerated in full and developers
  reset with `docker compose down -v`. V1 is **frozen** from the first shared or showcase environment
  onward; after that, changes are `V2__…`.
- **Seed scope** (L8): reference-data copies only, sized so every Gherkin boundary and unhappy path has a
  row: departments 3, projects 2, activities ~10, drawings ~8 (one with a superseded revision),
  materials ~10, look-ahead 3 weeks per project, workers ~12 (one agency, one inactive, one in another
  department). All values obviously fake. Users and scope rows are written by the S22 job, not the seed
  (L2, ADR 0005).

## Consequences

- Full V1 waits for the approval chain (L11; 09 §4 item 3): BA checks SORs, then PO and Ahsan/Usama
  approve, then Usama generates V1 and the seed. As of 29 Oct, `docs/data-model.md` is v0.4 with all items
  decided and awaiting that approval.
- 04-conventions' `V<n>__<description>` and the top-level `db/migration/` are superseded by S16 and L1;
  the Flyway naming is to be added to 04-conventions (L16).
- Shared-contract rules for V1 and the seed are in `intent/BPA-3-work-pack-assignment/spec.md`.

## Sources

09 S16, S17, L1, L2, L5, L8, L11, L16, §3, §4 item 3; 07 §1, §9; 00-context (out of scope);
03-sor-matrix; `docs/data-model.md`.
