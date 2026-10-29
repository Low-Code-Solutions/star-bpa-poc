# 0009 Assignment file intake

- Status: Accepted (S19 confirmed by Khitaba as D58, 27 Oct 2026)
- Date: 2026-10-27 (S18, S19, L6, L20; 09 LOCKED); S19 confirmed 2026-10-27 (D58); written 2026-10-29 (BPA-37)
- Deciders: Ahsan (Build Lead), Usama (Lead Developer) for S18, L6, L20; Khitaba (BA) for S19 (D58) and the
  template layout (D59)
- Informed: Faraz (Product Owner and Architect)
- 09 rows: S18, S19; L6, L20

**Status note.** 09 L3 sets this ADR to Proposed "until Khitaba confirms" S19, and 09 §2 still lists S19
as awaiting her. She confirmed it on 27 Oct as **D58** (06; 10 header "CONFIRMED by Khitaba"), and
00-context and 03-sor-matrix already rely on it. The 09 §2 row is stale; this ADR is Accepted.

## Context

Today Worker assignments are kept in Excel and handed to FMS by hand (Int-5; 00-context). The POC needs a
way in for that file. The first design used a watched exchange folder (D33, with a 5-second stability
rule and processed/failed/results folders), which came from a setup choice rather than a business rule.
The sources describe uploads through an approved Excel template (10 §1). No FRD defines a
worker-to-Work Pack assignment file, so the layout is an ASSUMPTION until STAR sends the real FMS
template (10 §2.1).

## Options considered

1. **Watched exchange folder** with a watcher, stability check and processed/failed/results folders
   (D33 rules 8, 11; 08 Gate 1, proposal for ADR 0005). Retired (S19, D58).
2. **Upload button in the portal** with the result on screen (S19, D58). Chosen.
3. For the template: **wait for STAR's real FMS template**, or **build a working template now from the
   FRD fields, marked ASSUMPTION** (10 §2.4; S18, L6). The working template is chosen; the request to STAR
   stands.

## Decision

- **Intake (S19, D58).** The Production Team user uploads the Excel file with an **Upload** button in the
  portal (`POST /api/v1/assignment-files`); results are shown on screen and downloadable
  (`GET /api/v1/assignment-files/{id}/result`), rebuilt from `assignment_file_row` (D49; ADR 0008). No
  watched folder. Only the `production-team` group may upload (S6, ADR 0005). The real Int-5 hand-off to
  FMS stays batch/file.
- **Kept from D33:** rules 1-7, 9, 10 and 12 (the `assignment_file` intake log), with D32, D49, D50 and
  G10. **Retired:** rule 8 (5-second stability) and rule 11 (processed/failed folders) (D58, as corrected
  28 Oct).
- **Files (S18).** One official template in the repo, `templates/worker-assignment-template.xlsx`,
  downloadable from the portal ("Download template"). Demo files (good and broken) live in the repo for the
  showcase and manual testing. Tests use their own fixtures under `src/test/resources/` or an
  `AssignmentFileBuilder` (Apache POI), never the demo files. BPA-13 files wait for Epic 2 (S18).
- **Template layout (L6, D59).** Built from 10 §2.4: Workpack number, Badge No (D59; 09 L6 lists this
  column as "Employee ID"), Estimated hours, Employee name (ignored on import), Remarks. Marked ASSUMPTION
  (D33 rule 1) in a `README` sheet in the workbook and in `templates/README.md`. **Created and uploaded to
  `templates/` when the build plan is made with Claude Code**, not before. The real FMS template from STAR
  replaces it when it arrives.
- **Demo files (L20).** About seven: one good (uploading it again shows "File already processed"), unknown
  Work Pack, wrong layout, empty file, duplicate worker, hours out of range, unknown employee. Exhaustive
  coverage stays in tests via `AssignmentFileBuilder`.

## Consequences

- BPA-12 is reworked for the upload (S19), which is expected to reduce its overrun risk (S10); Ahsan
  confirms its estimate after the rework (L19).
- The condition in L12 for adding the `assignment-files` endpoints to `api/openapi.yaml` is met
  (ADR 0011).
- `templates/` is empty until the build plan for the intake creates the template (L6).
- Faraz asks STAR for the real FMS assignment template (09 §2).

## Sources

09 S6, S10, S18, S19, L6, L12, L19, L20, §2; 10 §1, §2.1, §2.4; 06 D32, D33, D49, D50, D58, D59;
08 Gate 1; 00-context.
