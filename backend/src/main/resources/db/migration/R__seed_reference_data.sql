-- R__seed_reference_data.sql (BPA-42; ADR 0010; 09 S17, L2, L8; data model section 3 and section 11).
-- Reference-data copies only, every table labelled with the system that owns the data after go-live.
-- All values are obviously fake. created_by / updated_by are the literal 'seed' (section 1.2).
-- Repeatable: Flyway re-runs this file when its checksum changes, so every statement is idempotent (A8):
--   * tables with a UNIQUE business key: INSERT ... ON CONFLICT (<key>) DO UPDATE, only when a value differs;
--   * cutting_sheet and material_reservation have no UNIQUE in the model: INSERT ... WHERE NOT EXISTS on the
--     whole row (every non-key column is part of the match, so there is nothing for an UPDATE to change);
--   * nothing is deleted; drawing_revision rows written at runtime from DrawingRevised events are never touched
--     (only the (drawing_ref, revision) pairs below are written).
-- Not seeded here: app_user and user_project (S22 job, L2; user_project has a foreign key to app_user), Work Pack, assignment, hours,
-- notification and audit tables, scenario data (A6, A7, B7). Named rows come from data model section 11 and, for Epic 2, section 8 of
-- the v0.7 delta (BPA-48). Values for Epic 2 that no source names are synthetic and marked ASSUMPTION where they appear.

-- -----------------------------------------------------------------------------------------------------
-- department. SOR: D365 F&O Organisation (financial dimension). 3 rows (L8).
-- department_ref carries the readable value used in the stories (section 3.5, ASSUMPTION).
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.department (department_ref, name, created_by, updated_by)
VALUES ('Fabrication', 'Synthetic Fabrication Department', 'seed', 'seed'),
       ('Painting',    'Synthetic Painting Department',    'seed', 'seed'),
       ('Assembly',    'Synthetic Assembly Department',    'seed', 'seed')
ON CONFLICT (department_ref) DO UPDATE
    SET name = EXCLUDED.name, updated_at = now(), updated_by = 'seed'
    WHERE bpa.department.name IS DISTINCT FROM EXCLUDED.name;

-- -----------------------------------------------------------------------------------------------------
-- project_job_code. SOR: D365 F&O Project Management (project_ref); job_code_ref is FMS. 2 rows (L8).
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.project_job_code (project_ref, job_code_ref, created_by, updated_by)
VALUES ('PRJ-139', 'JC-139', 'seed', 'seed'),
       ('PRJ-140', 'JC-140', 'seed', 'seed')
ON CONFLICT (project_ref) DO UPDATE
    SET job_code_ref = EXCLUDED.job_code_ref, updated_at = now(), updated_by = 'seed'
    WHERE bpa.project_job_code.job_code_ref IS DISTINCT FROM EXCLUDED.job_code_ref;

-- -----------------------------------------------------------------------------------------------------
-- wbs. SOR: D365 F&O Project Management (P1: D365 F&O is the target-state holder of the WBS). 3 rows (at least 2).
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.wbs (wbs_ref, project_ref, created_by, updated_by)
VALUES ('11031', 'PRJ-139', 'seed', 'seed'),
       ('11032', 'PRJ-139', 'seed', 'seed'),
       ('14001', 'PRJ-140', 'seed', 'seed')
ON CONFLICT (wbs_ref) DO UPDATE
    SET project_ref = EXCLUDED.project_ref, updated_at = now(), updated_by = 'seed'
    WHERE bpa.wbs.project_ref IS DISTINCT FROM EXCLUDED.project_ref;

-- -----------------------------------------------------------------------------------------------------
-- activity. SOR: P6 via D365 PMS (wbs_ref is D365 F&O Project Management). 11 rows (L8: about 10; A-2301 added by BPA-48).
-- A-2201 is the named row (section 11); A-2299 has no plan data (BPA-6).
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.activity (activity_ref, activity_name, wbs_ref, fac_crpo, fac_type, fac_name, discipline,
                          baseline_start, baseline_end, budgeted_units, created_by, updated_by)
VALUES ('A-2201', 'Synthetic piping module M-01',  '11031', '139', 'Module', 'M-01', 'Piping',
        DATE '2026-11-01', DATE '2026-11-30', 1200.00, 'seed', 'seed'),
       ('A-2202', 'Synthetic piping module M-02',  '11031', '139', 'Module', 'M-02', 'Piping',
        DATE '2026-11-05', DATE '2026-12-05',  800.00, 'seed', 'seed'),
       ('A-2203', 'Synthetic structural frame S-01', '11031', '139', 'Skid',  'S-01', 'Structural',
        DATE '2026-11-10', DATE '2026-12-10',  950.50, 'seed', 'seed'),
       ('A-2204', 'Synthetic structural frame S-02', '11032', '139', 'Skid',  'S-02', 'Structural',
        DATE '2026-11-15', DATE '2026-12-15',  640.00, 'seed', 'seed'),
       ('A-2205', 'Synthetic vessel shell V-01',   '11032', '139', 'Vessel', 'V-01', 'Mechanical',
        DATE '2026-12-01', DATE '2027-01-15', 1500.00, 'seed', 'seed'),
       ('A-2206', 'Synthetic piping module M-03',  '14001', '140', 'Module', 'M-03', 'Piping',
        DATE '2026-11-20', DATE '2026-12-20',  720.00, 'seed', 'seed'),
       ('A-2207', 'Synthetic platform P-01',       '14001', '140', 'Platform', 'P-01', 'Structural',
        DATE '2026-12-05', DATE '2027-01-05',  510.25, 'seed', 'seed'),
       ('A-2208', 'Synthetic coating package C-01', '14001', '140', 'Package', 'C-01', 'Coating',
        DATE '2026-12-10', DATE '2027-01-10',  300.00, 'seed', 'seed'),
       ('A-2209', 'Synthetic tie-in T-01',         '11031', '139', 'Module', 'T-01', 'Piping',
        DATE '2026-12-15', DATE '2027-01-20',  410.00, 'seed', 'seed'),
       ('A-2299', 'Synthetic activity without plan data', '11031', NULL, NULL, NULL, NULL,
        NULL, NULL, NULL, 'seed', 'seed'),
       -- BPA-48 (comment 10481): budgeted units 1200 and no Work Packs, for the end-to-end demo (BPA-68). Name, WBS and dates are an
       -- ASSUMPTION: no source names them.
       ('A-2301', 'Synthetic end-to-end demo module M-04', '11031', '139', 'Module', 'M-04', 'Piping',
        DATE '2026-11-16', DATE '2026-12-31', 1200.00, 'seed', 'seed')
ON CONFLICT (activity_ref) DO UPDATE
    SET activity_name = EXCLUDED.activity_name, wbs_ref = EXCLUDED.wbs_ref, fac_crpo = EXCLUDED.fac_crpo,
        fac_type = EXCLUDED.fac_type, fac_name = EXCLUDED.fac_name, discipline = EXCLUDED.discipline,
        baseline_start = EXCLUDED.baseline_start, baseline_end = EXCLUDED.baseline_end,
        budgeted_units = EXCLUDED.budgeted_units, updated_at = now(), updated_by = 'seed'
    WHERE (bpa.activity.activity_name, bpa.activity.wbs_ref, bpa.activity.fac_crpo, bpa.activity.fac_type,
           bpa.activity.fac_name, bpa.activity.discipline, bpa.activity.baseline_start,
           bpa.activity.baseline_end, bpa.activity.budgeted_units)
          IS DISTINCT FROM
          (EXCLUDED.activity_name, EXCLUDED.wbs_ref, EXCLUDED.fac_crpo, EXCLUDED.fac_type, EXCLUDED.fac_name,
           EXCLUDED.discipline, EXCLUDED.baseline_start, EXCLUDED.baseline_end, EXCLUDED.budgeted_units);

-- -----------------------------------------------------------------------------------------------------
-- cbs_activity. SOR: P6 via D365 PMS (activity_ref); cbs_code_ref is D365 F&O Cost Control Report.
-- At most one row per activity; A-2206 to A-2209 and A-2299 have none (BPA-15: blank CBS). CBS-139-PIP-99 below maps to no activity yet (BPA-69).
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.cbs_activity (activity_ref, cbs_code_ref, created_by, updated_by)
VALUES ('A-2201', 'CBS-139-PIP-01', 'seed', 'seed'),
       ('A-2202', 'CBS-139-PIP-02', 'seed', 'seed'),
       ('A-2203', 'CBS-139-STR-01', 'seed', 'seed'),
       ('A-2204', 'CBS-139-STR-02', 'seed', 'seed'),
       ('A-2205', 'CBS-139-MEC-01', 'seed', 'seed'),
       -- BPA-48 (ASSUMPTION): the demo activity shares CBS-139-PIP-01 with A-2201, the CBS that has a forecast and an actual cost below.
       ('A-2301', 'CBS-139-PIP-01', 'seed', 'seed')
ON CONFLICT (activity_ref) DO UPDATE
    SET cbs_code_ref = EXCLUDED.cbs_code_ref, updated_at = now(), updated_by = 'seed'
    WHERE bpa.cbs_activity.cbs_code_ref IS DISTINCT FROM EXCLUDED.cbs_code_ref;

-- -----------------------------------------------------------------------------------------------------
-- material. SOR: D365 F&O Product Information Management. 10 rows (L8: about 10).
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.material (material_ref, description, created_by, updated_by)
VALUES ('MAT-PIPE-06',  'Synthetic pipe 6 inch',        'seed', 'seed'),
       ('MAT-FLG-02',   'Synthetic flange 2 inch',      'seed', 'seed'),
       ('MAT-PLATE-12', 'Synthetic plate 12 mm',        'seed', 'seed'),
       ('MAT-PIPE-08',  'Synthetic pipe 8 inch',        'seed', 'seed'),
       ('MAT-VLV-01',   'Synthetic gate valve',         'seed', 'seed'),
       ('MAT-BOLT-04',  'Synthetic bolt set M16',       'seed', 'seed'),
       ('MAT-ELB-03',   'Synthetic elbow 90 degree',    'seed', 'seed'),
       ('MAT-GSK-05',   'Synthetic gasket',             'seed', 'seed'),
       ('MAT-BEAM-07',  'Synthetic steel beam',         'seed', 'seed'),
       ('MAT-WELD-09',  'Synthetic welding rod',        'seed', 'seed')
ON CONFLICT (material_ref) DO UPDATE
    SET description = EXCLUDED.description, updated_at = now(), updated_by = 'seed'
    WHERE bpa.material.description IS DISTINCT FROM EXCLUDED.description;

-- -----------------------------------------------------------------------------------------------------
-- drawing. SOR: D365 F&O Document Control. 8 rows (L8: about 8). DWG-999 must not exist (section 11).
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.drawing (drawing_ref, title, created_by, updated_by)
VALUES ('DWG-118', 'Synthetic drawing 118', 'seed', 'seed'),
       ('DWG-119', 'Synthetic drawing 119', 'seed', 'seed'),
       ('DWG-121', 'Synthetic drawing 121', 'seed', 'seed'),
       ('DWG-130', 'Synthetic drawing 130', 'seed', 'seed'),
       ('DWG-140', 'Synthetic drawing 140', 'seed', 'seed'),
       ('DWG-141', 'Synthetic drawing 141', 'seed', 'seed'),
       ('DWG-150', 'Synthetic drawing 150', 'seed', 'seed'),
       ('DWG-151', 'Synthetic drawing 151', 'seed', 'seed')
ON CONFLICT (drawing_ref) DO UPDATE
    SET title = EXCLUDED.title, updated_at = now(), updated_by = 'seed'
    WHERE bpa.drawing.title IS DISTINCT FROM EXCLUDED.title;

-- -----------------------------------------------------------------------------------------------------
-- drawing_revision. SOR: D365 F&O Document Control. Read-only copy; written at runtime only from DrawingRevised
-- events (D45, D53). One superseded revision (DWG-130 A, latest B is IFC, BPA-16); IFR, IFA and IFI each present
-- (BPA-8); DWG-151 has no revision (BPA-8). Upsert on (drawing_ref, revision) only; runtime rows are not touched.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.drawing_revision (drawing_ref, revision, status, registered_at, created_by, updated_by)
VALUES ('DWG-118', 'A', 'IFC',        TIMESTAMPTZ '2026-09-01 08:00:00+00', 'seed', 'seed'),
       ('DWG-119', 'A', 'IFA',        TIMESTAMPTZ '2026-09-02 08:00:00+00', 'seed', 'seed'),
       ('DWG-121', 'A', 'IFI',        TIMESTAMPTZ '2026-09-03 08:00:00+00', 'seed', 'seed'),
       ('DWG-130', 'A', 'Superseded', TIMESTAMPTZ '2026-08-15 08:00:00+00', 'seed', 'seed'),
       ('DWG-130', 'B', 'IFC',        TIMESTAMPTZ '2026-10-01 08:00:00+00', 'seed', 'seed'),
       ('DWG-140', 'A', 'As-Built',   TIMESTAMPTZ '2026-09-10 08:00:00+00', 'seed', 'seed'),
       ('DWG-141', 'A', 'IFR',        TIMESTAMPTZ '2026-09-11 08:00:00+00', 'seed', 'seed'),
       ('DWG-150', 'A', 'IFC',        TIMESTAMPTZ '2026-09-12 08:00:00+00', 'seed', 'seed')
ON CONFLICT (drawing_ref, revision) DO UPDATE
    SET status = EXCLUDED.status, registered_at = EXCLUDED.registered_at, updated_at = now(), updated_by = 'seed'
    WHERE (bpa.drawing_revision.status, bpa.drawing_revision.registered_at)
          IS DISTINCT FROM (EXCLUDED.status, EXCLUDED.registered_at);

-- -----------------------------------------------------------------------------------------------------
-- material_reservation. SOR: D365 F&O PPC (drawing_ref is Document Control, material_ref is Product Information
-- Management). Keyed by drawing + material (C5). Partial on purpose: for example MAT-PLATE-12 has no reservation
-- on DWG-118 and DWG-130 has none for MAT-FLG-02 (BPA-8). No UNIQUE in the model: INSERT ... WHERE NOT EXISTS.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.material_reservation (traceability_number, drawing_ref, material_ref, created_by, updated_by)
SELECT v.traceability_number, v.drawing_ref, v.material_ref, 'seed', 'seed'
FROM (VALUES ('TR-5531', 'DWG-118', 'MAT-PIPE-06'),
             ('TR-5532', 'DWG-118', 'MAT-FLG-02'),
             ('TR-5533', 'DWG-130', 'MAT-PIPE-06'),
             ('TR-5534', 'DWG-119', 'MAT-PLATE-12'),
             ('TR-5535', 'DWG-140', 'MAT-PIPE-08')) AS v (traceability_number, drawing_ref, material_ref)
WHERE NOT EXISTS (SELECT 1 FROM bpa.material_reservation m
                  WHERE m.traceability_number = v.traceability_number
                    AND m.drawing_ref = v.drawing_ref
                    AND m.material_ref = v.material_ref);

-- -----------------------------------------------------------------------------------------------------
-- cutting_sheet. SOR: FMS (nesting); drawing_ref is Document Control. Linked by drawing (D30).
-- DWG-119, DWG-121, DWG-141, DWG-150 and DWG-151 have none (BPA-10). No UNIQUE in the model: INSERT ... WHERE NOT EXISTS.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.cutting_sheet (cutting_sheet_ref, drawing_ref, created_by, updated_by)
SELECT v.cutting_sheet_ref, v.drawing_ref, 'seed', 'seed'
FROM (VALUES ('CS-3310', 'DWG-118'),
             ('CS-3311', 'DWG-130'),
             ('CS-3312', 'DWG-140')) AS v (cutting_sheet_ref, drawing_ref)
WHERE NOT EXISTS (SELECT 1 FROM bpa.cutting_sheet c
                  WHERE c.cutting_sheet_ref = v.cutting_sheet_ref
                    AND c.drawing_ref = v.drawing_ref);

-- -----------------------------------------------------------------------------------------------------
-- lookahead_item. SOR: D365 F&O Project Control (PCON); design_dwg_ref is Document Control.
-- One row per drawing (section 3.11). 6 of 8 drawings, plan dates spread over three weeks (week of 2 Nov, 9 Nov and
-- 16 Nov 2026); DWG-150 and DWG-151 have no row (BPA-11). DWG-118 has a row.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.lookahead_item (design_dwg_ref, shop_drg_mark_plan_date, assembly_plan_date, weld_map_plan_date,
                                created_by, updated_by)
VALUES ('DWG-118', DATE '2026-11-02', DATE '2026-11-04', DATE '2026-11-06', 'seed', 'seed'),
       ('DWG-119', DATE '2026-11-02', DATE '2026-11-04', DATE '2026-11-06', 'seed', 'seed'),
       ('DWG-121', DATE '2026-11-09', DATE '2026-11-11', DATE '2026-11-13', 'seed', 'seed'),
       ('DWG-130', DATE '2026-11-09', DATE '2026-11-11', DATE '2026-11-13', 'seed', 'seed'),
       ('DWG-140', DATE '2026-11-16', DATE '2026-11-18', DATE '2026-11-20', 'seed', 'seed'),
       ('DWG-141', DATE '2026-11-16', DATE '2026-11-18', DATE '2026-11-20', 'seed', 'seed')
ON CONFLICT (design_dwg_ref) DO UPDATE
    SET shop_drg_mark_plan_date = EXCLUDED.shop_drg_mark_plan_date,
        assembly_plan_date = EXCLUDED.assembly_plan_date,
        weld_map_plan_date = EXCLUDED.weld_map_plan_date,
        updated_at = now(), updated_by = 'seed'
    WHERE (bpa.lookahead_item.shop_drg_mark_plan_date, bpa.lookahead_item.assembly_plan_date,
           bpa.lookahead_item.weld_map_plan_date)
          IS DISTINCT FROM
          (EXCLUDED.shop_drg_mark_plan_date, EXCLUDED.assembly_plan_date, EXCLUDED.weld_map_plan_date);

-- -----------------------------------------------------------------------------------------------------
-- worker. SOR: myPeople. 12 rows (L8: about 12). employee_ref holds the Badge No (D59).
-- Named rows: E1021, E1099, E1150 (no cost centre), E2001 (agency); E9999 must not exist.
-- "Inactive" is an ordinary worker and "another department" cannot be seeded (section 11, Q-DM5, ASSUMPTION).
-- agency_flag: field name and values are an ASSUMPTION (C11).
-- BPA-48 (comment 10518; data model 1A): the six HR fields of FRD-5-2 5.2.6.2. employee_id is EMP-<badge> (delta section 8); one Worker
-- of each category at least (E2001, the agency Worker, is a Contractor); aramco_id is NULL where not applicable. department_ref,
-- work_location, grade and aramco_id values are synthetic ASSUMPTIONs: no source names them.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.worker (employee_ref, employee_id, name, role, designation, agency_flag, cost_centre_ref,
                       department_ref, employee_category, work_location, grade, aramco_id, created_by, updated_by)
VALUES ('E1021', 'EMP-E1021', 'Worker E1021', 'Welder',  'Grade 3', false, 'STRP01', 'Fabrication', 'Worker',     'Yard A', 'G3', 'AR-70021', 'seed', 'seed'),
       ('E1099', 'EMP-E1099', 'Worker E1099', 'Fitter',  'Grade 2', false, 'STRP01', 'Fabrication', 'Worker',     'Yard A', 'G2', NULL,       'seed', 'seed'),
       ('E1150', 'EMP-E1150', 'Worker E1150', 'Rigger',  'Grade 1', false, NULL,     'Assembly',    'Worker',     'Yard B', 'G1', NULL,       'seed', 'seed'),
       ('E2001', 'EMP-E2001', 'Worker E2001', 'Helper',  'Grade 1', true,  'STRP02', 'Assembly',    'Contractor', 'Yard B', 'G1', NULL,       'seed', 'seed'),
       ('E1201', 'EMP-E1201', 'Worker E1201', 'Welder',  'Grade 2', false, 'STRP01', 'Fabrication', 'Worker',     'Yard A', 'G2', NULL,       'seed', 'seed'),
       ('E1202', 'EMP-E1202', 'Worker E1202', 'Fitter',  'Grade 3', false, 'STRP01', 'Fabrication', 'Staff',      'Yard A', 'G3', 'AR-70202', 'seed', 'seed'),
       ('E1203', 'EMP-E1203', 'Worker E1203', 'Welder',  'Grade 1', false, 'STRP02', 'Painting',    'Worker',     'Yard B', 'G1', NULL,       'seed', 'seed'),
       ('E1204', 'EMP-E1204', 'Worker E1204', 'Painter', 'Grade 2', false, 'STRP02', 'Painting',    'Worker',     'Yard B', 'G2', NULL,       'seed', 'seed'),
       ('E1205', 'EMP-E1205', 'Worker E1205', 'Fitter',  'Grade 1', false, 'STRP01', 'Fabrication', 'Worker',     'Yard A', 'G1', NULL,       'seed', 'seed'),
       ('E1206', 'EMP-E1206', 'Worker E1206', 'Rigger',  'Grade 2', false, 'STRP02', 'Assembly',    'Contractor', 'Yard B', 'G2', NULL,       'seed', 'seed'),
       ('E1207', 'EMP-E1207', 'Worker E1207', 'Welder',  'Grade 3', false, 'STRP01', 'Fabrication', 'Staff',      'Yard A', 'G3', 'AR-70207', 'seed', 'seed'),
       ('E1208', 'EMP-E1208', 'Worker E1208', 'Painter', 'Grade 1', false, 'STRP02', 'Painting',    'Worker',     'Yard B', 'G1', NULL,       'seed', 'seed')
ON CONFLICT (employee_ref) DO UPDATE
    SET employee_id = EXCLUDED.employee_id, name = EXCLUDED.name, role = EXCLUDED.role, designation = EXCLUDED.designation,
        agency_flag = EXCLUDED.agency_flag, cost_centre_ref = EXCLUDED.cost_centre_ref, department_ref = EXCLUDED.department_ref,
        employee_category = EXCLUDED.employee_category, work_location = EXCLUDED.work_location, grade = EXCLUDED.grade,
        aramco_id = EXCLUDED.aramco_id, updated_at = now(), updated_by = 'seed'
    WHERE (bpa.worker.employee_id, bpa.worker.name, bpa.worker.role, bpa.worker.designation, bpa.worker.agency_flag,
           bpa.worker.cost_centre_ref, bpa.worker.department_ref, bpa.worker.employee_category, bpa.worker.work_location,
           bpa.worker.grade, bpa.worker.aramco_id)
          IS DISTINCT FROM
          (EXCLUDED.employee_id, EXCLUDED.name, EXCLUDED.role, EXCLUDED.designation, EXCLUDED.agency_flag, EXCLUDED.cost_centre_ref,
           EXCLUDED.department_ref, EXCLUDED.employee_category, EXCLUDED.work_location, EXCLUDED.grade, EXCLUDED.aramco_id);

-- -----------------------------------------------------------------------------------------------------
-- cbs_forecast. SOR: D365 F&O Cost Control Report (BPA-48; delta section 8; values synthetic).
-- CBS-139-PIP-01 has a forecast; CBS-139-PIP-02 has none, to prove "no forecast, no alert" (ASSUMPTION: the delta does not name the CBS).
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.cbs_forecast (cbs_code_ref, current_forecast_mh, revised_budget_cost, created_by, updated_by)
VALUES ('CBS-139-PIP-01', 1000.00, 250000.00, 'seed', 'seed')
ON CONFLICT (cbs_code_ref) DO UPDATE
    SET current_forecast_mh = EXCLUDED.current_forecast_mh, revised_budget_cost = EXCLUDED.revised_budget_cost,
        updated_at = now(), updated_by = 'seed'
    WHERE (bpa.cbs_forecast.current_forecast_mh, bpa.cbs_forecast.revised_budget_cost)
          IS DISTINCT FROM (EXCLUDED.current_forecast_mh, EXCLUDED.revised_budget_cost);

-- -----------------------------------------------------------------------------------------------------
-- cbs_actual_cost. SOR: D365 F&O Finance for the amount (FRD-22 5.3.1); the table is labelled D365 F&O Cost Control Report.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.cbs_actual_cost (cbs_code_ref, period_month, actual_cost, created_by, updated_by)
VALUES ('CBS-139-PIP-01', DATE '2026-11-01', 180000.00, 'seed', 'seed')
ON CONFLICT (cbs_code_ref, period_month) DO UPDATE
    SET actual_cost = EXCLUDED.actual_cost, updated_at = now(), updated_by = 'seed'
    WHERE bpa.cbs_actual_cost.actual_cost IS DISTINCT FROM EXCLUDED.actual_cost;

-- -----------------------------------------------------------------------------------------------------
-- cbs_block. POC master data, real SOR D365 F&O Cost Control Report (delta 4.3; comments 10487, 10508).
-- No row means open. CBS-139-PIP-99 is closed for FmsTimesheet, for BPA-69; no activity maps to it yet.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.cbs_block (cbs_code_ref, block_type, is_closed, created_by, updated_by)
VALUES ('CBS-139-PIP-01', 'FmsTimesheet', false, 'seed', 'seed'),
       ('CBS-139-PIP-99', 'FmsTimesheet', true,  'seed', 'seed')
ON CONFLICT (cbs_code_ref, block_type) DO UPDATE
    SET is_closed = EXCLUDED.is_closed, updated_at = now(), updated_by = 'seed'
    WHERE bpa.cbs_block.is_closed IS DISTINCT FROM EXCLUDED.is_closed;

-- -----------------------------------------------------------------------------------------------------
-- worker_leave. SOR: myPeople (ASSUMPTION, delta addendum 5). One Worker on approved leave in the demo month, for BPA-75
-- "on leave". Which Worker and which dates are an ASSUMPTION: E1021, 23 to 26 Nov 2026. No UNIQUE key, so insert when the row is not there.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO bpa.worker_leave (worker_id, leave_from, leave_to, created_by, updated_by)
SELECT w.id, DATE '2026-11-23', DATE '2026-11-26', 'seed', 'seed'
FROM bpa.worker w
WHERE w.employee_ref = 'E1021'
  AND NOT EXISTS (SELECT 1 FROM bpa.worker_leave l
                  WHERE l.worker_id = w.id AND l.leave_from = DATE '2026-11-23' AND l.leave_to = DATE '2026-11-26');
