-- V1 baseline schema, generated from docs/data-model.md v0.5 (BPA-42; ADR 0010; 09 S16, S17).
-- Schema bpa already exists (backend/postgres/init/01-create-databases.sql); objects are schema-qualified.
-- Enumerations are text + CHECK (Q-DM1). Every FK is ON DELETE NO ACTION (A1).
-- Audit columns (created_at, created_by, updated_at, updated_by, section 1.2) sit last on every table except audit_log.
-- COMMENT ON uses the canonical SOR label only (section 1.3, A9). Sections follow dependency order: A, B, C, D, F, E, G.

CREATE SEQUENCE bpa.workpack_number_seq MAXVALUE 999999 NO CYCLE;

-- =====================================================================================================
-- Domain A: reference data copies (seeded by R__seed_reference_data.sql)
-- =====================================================================================================

-- A.1 project_job_code (3.1)
CREATE TABLE bpa.project_job_code (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    project_ref  text        NOT NULL,
    job_code_ref text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   text        NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (project_ref)
);
COMMENT ON TABLE bpa.project_job_code IS 'D365 F&O Project Management';
COMMENT ON COLUMN bpa.project_job_code.job_code_ref IS 'FMS';

-- A.2 wbs (3.2)
CREATE TABLE bpa.wbs (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    wbs_ref     text        NOT NULL,
    project_ref text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (wbs_ref),
    FOREIGN KEY (project_ref) REFERENCES bpa.project_job_code (project_ref) ON DELETE NO ACTION
);
COMMENT ON TABLE bpa.wbs IS 'D365 F&O Project Management';

-- A.3 activity (3.3)
CREATE TABLE bpa.activity (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    activity_ref   text         NOT NULL,
    activity_name  text         NOT NULL,
    wbs_ref        text         NOT NULL,
    fac_crpo       text,
    fac_type       text,
    fac_name       text,
    discipline     text,
    baseline_start date,
    baseline_end   date,
    budgeted_units numeric(9,2),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    created_by     text         NOT NULL,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    updated_by     text         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (activity_ref),
    FOREIGN KEY (wbs_ref) REFERENCES bpa.wbs (wbs_ref) ON DELETE NO ACTION
);
COMMENT ON TABLE bpa.activity IS 'P6 via D365 PMS';
COMMENT ON COLUMN bpa.activity.wbs_ref IS 'D365 F&O Project Management';

-- A.4 cbs_activity (3.4)
CREATE TABLE bpa.cbs_activity (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    activity_ref text        NOT NULL,
    cbs_code_ref text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   text        NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (activity_ref),
    FOREIGN KEY (activity_ref) REFERENCES bpa.activity (activity_ref) ON DELETE NO ACTION
);
COMMENT ON TABLE bpa.cbs_activity IS 'P6 via D365 PMS';
COMMENT ON COLUMN bpa.cbs_activity.cbs_code_ref IS 'D365 F&O Cost Control Report';

-- A.5 department (3.5)
CREATE TABLE bpa.department (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    department_ref text        NOT NULL,
    name           text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     text        NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    updated_by     text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (department_ref)
);
COMMENT ON TABLE bpa.department IS 'D365 F&O Organisation';

-- A.6 material (3.6)
CREATE TABLE bpa.material (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    material_ref text        NOT NULL,
    description  text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   text        NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (material_ref)
);
COMMENT ON TABLE bpa.material IS 'D365 F&O Product Information Management';

-- A.7 drawing (3.7)
CREATE TABLE bpa.drawing (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    drawing_ref text        NOT NULL,
    title       text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (drawing_ref)
);
COMMENT ON TABLE bpa.drawing IS 'D365 F&O Document Control';

-- A.8 drawing_revision (3.8)
CREATE TABLE bpa.drawing_revision (
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    drawing_ref   text        NOT NULL,
    revision      text        NOT NULL,
    status        text        NOT NULL,
    registered_at timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    text        NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    updated_by    text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (drawing_ref, revision),
    FOREIGN KEY (drawing_ref) REFERENCES bpa.drawing (drawing_ref) ON DELETE NO ACTION,
    CHECK (status IN ('IFR', 'IFA', 'IFI', 'IFC', 'As-Built', 'Superseded'))
);
COMMENT ON TABLE bpa.drawing_revision IS 'D365 F&O Document Control';

-- A.9 material_reservation (3.9)
CREATE TABLE bpa.material_reservation (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    traceability_number text       NOT NULL,
    drawing_ref        text        NOT NULL,
    material_ref       text        NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    created_by         text        NOT NULL,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    updated_by         text        NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (drawing_ref) REFERENCES bpa.drawing (drawing_ref) ON DELETE NO ACTION,
    FOREIGN KEY (material_ref) REFERENCES bpa.material (material_ref) ON DELETE NO ACTION
);
COMMENT ON TABLE bpa.material_reservation IS 'D365 F&O PPC';
COMMENT ON COLUMN bpa.material_reservation.traceability_number IS 'D365 F&O PPC';
COMMENT ON COLUMN bpa.material_reservation.drawing_ref IS 'D365 F&O Document Control';
COMMENT ON COLUMN bpa.material_reservation.material_ref IS 'D365 F&O Product Information Management';

-- A.10 cutting_sheet (3.10)
CREATE TABLE bpa.cutting_sheet (
    id                uuid        NOT NULL DEFAULT gen_random_uuid(),
    cutting_sheet_ref text        NOT NULL,
    drawing_ref       text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        text        NOT NULL,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        text        NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (drawing_ref) REFERENCES bpa.drawing (drawing_ref) ON DELETE NO ACTION
);
COMMENT ON TABLE bpa.cutting_sheet IS 'FMS';
COMMENT ON COLUMN bpa.cutting_sheet.drawing_ref IS 'D365 F&O Document Control';

-- A.11 lookahead_item (3.11)
CREATE TABLE bpa.lookahead_item (
    id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
    design_dwg_ref          text        NOT NULL,
    shop_drg_mark_plan_date date,
    assembly_plan_date      date,
    weld_map_plan_date      date,
    created_at              timestamptz NOT NULL DEFAULT now(),
    created_by              text        NOT NULL,
    updated_at              timestamptz NOT NULL DEFAULT now(),
    updated_by              text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (design_dwg_ref),
    FOREIGN KEY (design_dwg_ref) REFERENCES bpa.drawing (drawing_ref) ON DELETE NO ACTION
);
COMMENT ON TABLE bpa.lookahead_item IS 'D365 F&O Project Control (PCON)';
COMMENT ON COLUMN bpa.lookahead_item.design_dwg_ref IS 'D365 F&O Document Control';

-- A.12 worker (3.12)
CREATE TABLE bpa.worker (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    employee_ref    text        NOT NULL,
    name            text        NOT NULL,
    role            text        NOT NULL,
    designation     text        NOT NULL,
    agency_flag     boolean     NOT NULL,
    cost_centre_ref text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      text        NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (employee_ref)
);
COMMENT ON TABLE bpa.worker IS 'myPeople';

-- =====================================================================================================
-- Domain B: Work Pack
-- =====================================================================================================

-- B.1 workpack (4.1)
CREATE TABLE bpa.workpack (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    workpack_number      text        NOT NULL,
    activity_ref         text        NOT NULL,
    drawing_ref          text        NOT NULL,
    department_ref       text        NOT NULL,
    completion_date      date        NOT NULL,
    status               text        NOT NULL DEFAULT 'Draft',
    assignment_state     text,
    process_instance_key bigint      NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    created_by           text        NOT NULL,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    updated_by           text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (workpack_number),
    UNIQUE (process_instance_key),
    FOREIGN KEY (activity_ref) REFERENCES bpa.activity (activity_ref) ON DELETE NO ACTION,
    FOREIGN KEY (drawing_ref) REFERENCES bpa.drawing (drawing_ref) ON DELETE NO ACTION,
    FOREIGN KEY (department_ref) REFERENCES bpa.department (department_ref) ON DELETE NO ACTION,
    CHECK (workpack_number ~ '^WP-[0-9]{6}$'),
    CHECK (status IN ('Draft', 'Released', 'In Progress', 'Completed')),
    CHECK (assignment_state IN ('AwaitingFile', 'Assigning', 'Assigned', 'Replacing'))
);
COMMENT ON TABLE bpa.workpack IS 'POC database';
COMMENT ON COLUMN bpa.workpack.activity_ref IS 'P6 via D365 PMS';
COMMENT ON COLUMN bpa.workpack.drawing_ref IS 'D365 F&O Document Control';
COMMENT ON COLUMN bpa.workpack.department_ref IS 'D365 F&O Organisation';

-- B.2 workpack_phase (4.2)
CREATE TABLE bpa.workpack_phase (
    id         uuid          NOT NULL DEFAULT gen_random_uuid(),
    workpack_id uuid         NOT NULL,
    phase      text          NOT NULL,
    manhours   numeric(7,2)  NOT NULL,
    created_at timestamptz   NOT NULL DEFAULT now(),
    created_by text          NOT NULL,
    updated_at timestamptz   NOT NULL DEFAULT now(),
    updated_by text          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (workpack_id, phase),
    FOREIGN KEY (workpack_id) REFERENCES bpa.workpack (id) ON DELETE NO ACTION,
    CHECK (phase IN ('Engineering', 'Cutting', 'Welding', 'Finishing')),
    CHECK (manhours > 0 AND manhours <= 99999.99)
);
COMMENT ON TABLE bpa.workpack_phase IS 'POC database';

-- B.3 workpack_material (4.3)
CREATE TABLE bpa.workpack_material (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    workpack_id  uuid        NOT NULL,
    material_ref text        NOT NULL,
    phase        text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   text        NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (workpack_id, phase, material_ref) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (workpack_id) REFERENCES bpa.workpack (id) ON DELETE NO ACTION,
    FOREIGN KEY (material_ref) REFERENCES bpa.material (material_ref) ON DELETE NO ACTION,
    FOREIGN KEY (workpack_id, phase) REFERENCES bpa.workpack_phase (workpack_id, phase)
        ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CHECK (phase IN ('Engineering', 'Cutting', 'Welding', 'Finishing'))
);
COMMENT ON TABLE bpa.workpack_material IS 'POC database';
COMMENT ON COLUMN bpa.workpack_material.material_ref IS 'D365 F&O Product Information Management';

-- B.4 workpack_schedule (4.4)
CREATE TABLE bpa.workpack_schedule (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    workpack_id        uuid        NOT NULL,
    planned_start_date date        NOT NULL,
    planned_end_date   date        NOT NULL,
    scheduled_by       text        NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    created_by         text        NOT NULL,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    updated_by         text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (workpack_id),
    FOREIGN KEY (workpack_id) REFERENCES bpa.workpack (id) ON DELETE NO ACTION,
    CHECK (planned_end_date >= planned_start_date)
);
COMMENT ON TABLE bpa.workpack_schedule IS 'POC database';
COMMENT ON COLUMN bpa.workpack_schedule.scheduled_by IS 'Camunda identity';

-- B.5 workpack_print (4.5)
CREATE TABLE bpa.workpack_print (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    workpack_id  uuid        NOT NULL,
    file         bytea       NOT NULL,
    sha256       text        NOT NULL,
    generated_at timestamptz NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   text        NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (workpack_id),
    FOREIGN KEY (workpack_id) REFERENCES bpa.workpack (id) ON DELETE NO ACTION
);
COMMENT ON TABLE bpa.workpack_print IS 'POC database';

-- B.6 release_signoff (4.6)
CREATE TABLE bpa.release_signoff (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    workpack_id    uuid        NOT NULL,
    task_key       bigint      NOT NULL,
    decision       text        NOT NULL,
    decline_reason text,
    signed_off_by  text        NOT NULL,
    signed_off_at  timestamptz NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     text        NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    updated_by     text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (task_key),
    FOREIGN KEY (workpack_id) REFERENCES bpa.workpack (id) ON DELETE NO ACTION,
    CHECK (decision IN ('Approved', 'Declined')),
    CHECK (decision <> 'Declined' OR btrim(coalesce(decline_reason, '')) <> '')
);
COMMENT ON TABLE bpa.release_signoff IS 'POC database';
COMMENT ON COLUMN bpa.release_signoff.signed_off_by IS 'Camunda identity';

-- =====================================================================================================
-- Domain C: assignment
-- =====================================================================================================

-- C.1 assignment_file (5.1)
CREATE TABLE bpa.assignment_file (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    file_name   text        NOT NULL,
    sha256      text        NOT NULL,
    uploaded_by text        NOT NULL,
    received_at timestamptz NOT NULL,
    outcome     text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  text        NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX assignment_file_sha256_idx ON bpa.assignment_file (sha256);
COMMENT ON TABLE bpa.assignment_file IS 'POC database';
COMMENT ON COLUMN bpa.assignment_file.uploaded_by IS 'Camunda identity';

-- C.2 assignment_file_row (5.2)
CREATE TABLE bpa.assignment_file_row (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    assignment_file_id   uuid        NOT NULL,
    row_number           integer,
    workpack_number      text,
    badge_no             text,
    estimated_hours_raw  text,
    remarks              text,
    outcome              text,
    wp_outcome           text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    created_by           text        NOT NULL,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    updated_by           text        NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (assignment_file_id) REFERENCES bpa.assignment_file (id) ON DELETE NO ACTION,
    CHECK (outcome IS NOT NULL OR wp_outcome IS NOT NULL),
    CHECK (wp_outcome IS NULL OR wp_outcome = 'Replacement not applied: file has rejected rows')
);
CREATE UNIQUE INDEX assignment_file_row_synthetic_uk
    ON bpa.assignment_file_row (assignment_file_id, workpack_number, badge_no)
    WHERE row_number IS NULL;
COMMENT ON TABLE bpa.assignment_file_row IS 'POC database';

-- C.3 worker_assignment (5.3)
CREATE TABLE bpa.worker_assignment (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    workpack_id        uuid          NOT NULL,
    worker_id          uuid          NOT NULL,
    estimated_hours    numeric(7,2)  NOT NULL,
    remarks            text,
    flag               text,
    assignment_file_id uuid          NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    created_by         text          NOT NULL,
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    updated_by         text          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (workpack_id, worker_id),
    FOREIGN KEY (workpack_id) REFERENCES bpa.workpack (id) ON DELETE NO ACTION,
    FOREIGN KEY (worker_id) REFERENCES bpa.worker (id) ON DELETE NO ACTION,
    FOREIGN KEY (assignment_file_id) REFERENCES bpa.assignment_file (id) ON DELETE NO ACTION,
    CHECK (estimated_hours > 0 AND estimated_hours <= 99999.99),
    CHECK (char_length(remarks) <= 250),
    CHECK (flag IN ('HoursLoadedNotRemoved'))
);
COMMENT ON TABLE bpa.worker_assignment IS 'POC database';
COMMENT ON COLUMN bpa.worker_assignment.worker_id IS 'myPeople';

-- C.4 worker_assignment_history (5.4); worker_assignment_id deliberately has no FK (F3)
CREATE TABLE bpa.worker_assignment_history (
    id                   uuid          NOT NULL DEFAULT gen_random_uuid(),
    worker_assignment_id uuid          NOT NULL,
    workpack_id          uuid          NOT NULL,
    worker_id            uuid          NOT NULL,
    old_estimated_hours  numeric(7,2)  NOT NULL,
    new_estimated_hours  numeric(7,2)  NOT NULL,
    change_type          text          NOT NULL,
    source_file          text          NOT NULL,
    assignment_file_id   uuid          NOT NULL,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    created_by           text          NOT NULL,
    updated_at           timestamptz   NOT NULL DEFAULT now(),
    updated_by           text          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (worker_assignment_id, change_type, assignment_file_id),
    FOREIGN KEY (workpack_id) REFERENCES bpa.workpack (id) ON DELETE NO ACTION,
    FOREIGN KEY (worker_id) REFERENCES bpa.worker (id) ON DELETE NO ACTION,
    FOREIGN KEY (assignment_file_id) REFERENCES bpa.assignment_file (id) ON DELETE NO ACTION,
    CHECK (old_estimated_hours >= 0),
    CHECK (new_estimated_hours >= 0),
    CHECK (change_type IN ('Added', 'Replaced', 'Removed'))
);
COMMENT ON TABLE bpa.worker_assignment_history IS 'POC database';
COMMENT ON COLUMN bpa.worker_assignment_history.worker_id IS 'myPeople';

-- C.5 published_message (5.12)
CREATE TABLE bpa.published_message (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    sha256          text        NOT NULL,
    workpack_number text        NOT NULL,
    published_at    timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      text        NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (sha256, workpack_number)
);
COMMENT ON TABLE bpa.published_message IS 'POC database';

-- =====================================================================================================
-- Domain D: hours
-- =====================================================================================================

-- D.1 hour_journal_header (5.5)
CREATE TABLE bpa.hour_journal_header (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    workpack_id        uuid          NOT NULL,
    estimated_manhours numeric(9,2)  NOT NULL,
    locked             boolean       NOT NULL DEFAULT false,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    created_by         text          NOT NULL,
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    updated_by         text          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (workpack_id),
    FOREIGN KEY (workpack_id) REFERENCES bpa.workpack (id) ON DELETE NO ACTION
);
COMMENT ON TABLE bpa.hour_journal_header IS 'POC database';

-- D.2 hour_journal_line (5.6)
CREATE TABLE bpa.hour_journal_line (
    id              uuid          NOT NULL DEFAULT gen_random_uuid(),
    header_id       uuid          NOT NULL,
    worker_id       uuid          NOT NULL,
    role            text          NOT NULL,
    designation     text          NOT NULL,
    estimated_hours numeric(7,2)  NOT NULL,
    status          text          NOT NULL DEFAULT 'Open',
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      text          NOT NULL,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      text          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (header_id, worker_id),
    FOREIGN KEY (header_id) REFERENCES bpa.hour_journal_header (id) ON DELETE NO ACTION,
    FOREIGN KEY (worker_id) REFERENCES bpa.worker (id) ON DELETE NO ACTION,
    CHECK (status IN ('Open', 'Closed'))
);
COMMENT ON TABLE bpa.hour_journal_line IS 'POC database';
COMMENT ON COLUMN bpa.hour_journal_line.worker_id IS 'myPeople';
COMMENT ON COLUMN bpa.hour_journal_line.role IS 'myPeople';
COMMENT ON COLUMN bpa.hour_journal_line.designation IS 'myPeople';

-- D.3 digital_timesheet (5.7)
CREATE TABLE bpa.digital_timesheet (
    id                uuid        NOT NULL DEFAULT gen_random_uuid(),
    worker_id         uuid        NOT NULL,
    workpack_id       uuid        NOT NULL,
    journal_line_id   uuid        NOT NULL,
    project_ref       text        NOT NULL,
    wbs_ref           text        NOT NULL,
    activity_ref      text        NOT NULL,
    cbs_code_ref      text,
    cost_centre_ref   text,
    submission_status text        NOT NULL DEFAULT 'Draft',
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        text        NOT NULL,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (worker_id, workpack_id),
    FOREIGN KEY (worker_id) REFERENCES bpa.worker (id) ON DELETE NO ACTION,
    FOREIGN KEY (workpack_id) REFERENCES bpa.workpack (id) ON DELETE NO ACTION,
    FOREIGN KEY (journal_line_id) REFERENCES bpa.hour_journal_line (id) ON DELETE NO ACTION,
    CHECK (submission_status IN ('Draft', 'Cancelled'))
);
COMMENT ON TABLE bpa.digital_timesheet IS 'POC database';
COMMENT ON COLUMN bpa.digital_timesheet.worker_id IS 'myPeople';
COMMENT ON COLUMN bpa.digital_timesheet.project_ref IS 'D365 F&O Project Management';
COMMENT ON COLUMN bpa.digital_timesheet.wbs_ref IS 'D365 F&O Project Management';
COMMENT ON COLUMN bpa.digital_timesheet.activity_ref IS 'P6 via D365 PMS';
COMMENT ON COLUMN bpa.digital_timesheet.cbs_code_ref IS 'D365 F&O Cost Control Report';
COMMENT ON COLUMN bpa.digital_timesheet.cost_centre_ref IS 'myPeople';

-- D.4 digital_timesheet_line (5.8)
CREATE TABLE bpa.digital_timesheet_line (
    id                   uuid          NOT NULL DEFAULT gen_random_uuid(),
    digital_timesheet_id uuid          NOT NULL,
    work_date            date          NOT NULL,
    phase                text          NOT NULL,
    hours                numeric(7,2)  NOT NULL,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    created_by           text          NOT NULL,
    updated_at           timestamptz   NOT NULL DEFAULT now(),
    updated_by           text          NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (digital_timesheet_id) REFERENCES bpa.digital_timesheet (id) ON DELETE NO ACTION,
    CHECK (phase IN ('Engineering', 'Cutting', 'Welding', 'Finishing'))
);
COMMENT ON TABLE bpa.digital_timesheet_line IS 'POC database';

-- =====================================================================================================
-- Domain F: access scope (before E: notification references app_user)
-- =====================================================================================================

-- F.1 app_user (5.10); keyed by username (04-conventions exception, D63)
CREATE TABLE bpa.app_user (
    username     text        NOT NULL,
    employee_ref text        NOT NULL,
    email        text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   text        NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   text        NOT NULL,
    PRIMARY KEY (username)
);
COMMENT ON TABLE bpa.app_user IS 'POC database';
COMMENT ON COLUMN bpa.app_user.username IS 'Camunda identity';
COMMENT ON COLUMN bpa.app_user.employee_ref IS 'myPeople';
COMMENT ON COLUMN bpa.app_user.email IS 'Corporate email system';

-- F.2 user_role_scope (5.11)
CREATE TABLE bpa.user_role_scope (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    username       text        NOT NULL,
    group_id       text        NOT NULL,
    department_ref text,
    project_ref    text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     text        NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    updated_by     text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE NULLS NOT DISTINCT (username, group_id, department_ref, project_ref),
    FOREIGN KEY (username) REFERENCES bpa.app_user (username) ON DELETE NO ACTION,
    FOREIGN KEY (department_ref) REFERENCES bpa.department (department_ref) ON DELETE NO ACTION,
    FOREIGN KEY (project_ref) REFERENCES bpa.project_job_code (project_ref) ON DELETE NO ACTION,
    CHECK (group_id IN ('time-method-analyst', 'project-engineer', 'production-planner', 'workpack-owner',
                        'ppc-team', 'supervisor', 'production-team'))
);
COMMENT ON TABLE bpa.user_role_scope IS 'POC database';
COMMENT ON COLUMN bpa.user_role_scope.username IS 'Camunda identity';
COMMENT ON COLUMN bpa.user_role_scope.group_id IS 'Camunda identity';
COMMENT ON COLUMN bpa.user_role_scope.department_ref IS 'D365 F&O Organisation';
COMMENT ON COLUMN bpa.user_role_scope.project_ref IS 'D365 F&O Project Management';

-- =====================================================================================================
-- Domain E: notification
-- =====================================================================================================

-- E.1 notification (5.9)
CREATE TABLE bpa.notification (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    workpack_id        uuid        NOT NULL,
    drawing_ref        text        NOT NULL,
    revision           text        NOT NULL,
    recipient_username text        NOT NULL,
    channel            text        NOT NULL,
    sent_at            timestamptz NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    created_by         text        NOT NULL,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    updated_by         text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (workpack_id, drawing_ref, revision, recipient_username, channel),
    FOREIGN KEY (workpack_id) REFERENCES bpa.workpack (id) ON DELETE NO ACTION,
    FOREIGN KEY (recipient_username) REFERENCES bpa.app_user (username) ON DELETE NO ACTION,
    CHECK (channel IN ('Email', 'InSystem'))
);
COMMENT ON TABLE bpa.notification IS 'POC database';
COMMENT ON COLUMN bpa.notification.drawing_ref IS 'D365 F&O Document Control';
COMMENT ON COLUMN bpa.notification.revision IS 'D365 F&O Document Control';
COMMENT ON COLUMN bpa.notification.recipient_username IS 'Camunda identity';

-- =====================================================================================================
-- Domain G: audit (the only table without the four audit columns, D64)
-- =====================================================================================================

-- G.1 audit_log (6.1)
CREATE TABLE bpa.audit_log (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    table_name  text        NOT NULL,
    record_id   uuid        NOT NULL,
    column_name text        NOT NULL,
    old_value   text,
    new_value   text,
    username    text        NOT NULL,
    changed_at  timestamptz NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX audit_log_table_record_idx ON bpa.audit_log (table_name, record_id);
COMMENT ON TABLE bpa.audit_log IS 'POC database';
COMMENT ON COLUMN bpa.audit_log.username IS 'Camunda identity';
