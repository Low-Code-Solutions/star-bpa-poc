-- V2__epic2_timesheet_to_progress.sql (BPA-48; ADR 0010; 09 S16, S17; docs/data-model.md v0.7, from the delta rev. 2 of 11 Nov 2026).
-- Epic 2 (Timesheet to Progress): changes to worker and digital_timesheet_line, and 20 new tables.
-- V1 is frozen (Q-DM21, Ahsan, 11 Nov 2026: the QA dev server already runs it), so none of this is in V1.
-- Not in this file, parked: hours_approval and hours_submission.approval_route (BPA-71, D2), cbs_reopen_request (BPA-70, F7).
-- Seed rows are in R__seed_reference_data.sql (it runs after this file); the one DML statement here is the employee_id backfill that
-- SET NOT NULL needs on a database that already holds Workers.
-- Every new table has the four audit columns (data model section 1.2) and an SOR comment (S17); a column comment is added where a
-- column's SOR differs from its table's. ASSUMPTION markers are as in the delta.

-- =====================================================================================================
-- worker (data model 1A): six HR fields from FRD-5-2 5.2.6.2. SOR myPeople (C11; FRD-5-2 names D365 HR, recorded as C14).
-- employee_id is added NULL, filled for the Workers already there, then made NOT NULL, so existing rows stay valid.
-- Badge No stays the key (employee_ref, D59).
-- =====================================================================================================
ALTER TABLE bpa.worker ADD COLUMN employee_id text;
UPDATE bpa.worker SET employee_id = 'EMP-' || employee_ref WHERE employee_id IS NULL;
ALTER TABLE bpa.worker
    ALTER COLUMN employee_id SET NOT NULL,
    ADD CONSTRAINT worker_employee_id_key UNIQUE (employee_id),                      -- Q-DM22 (ASSUMPTION, decided 11 Nov)
    ADD COLUMN department_ref    text,
    ADD COLUMN employee_category text,
    ADD COLUMN work_location     text,
    ADD COLUMN grade             text,
    ADD COLUMN aramco_id         text,
    ADD CONSTRAINT worker_employee_category_check CHECK (employee_category IN ('Staff', 'Worker', 'Contractor'));
COMMENT ON COLUMN bpa.worker.employee_id IS 'myPeople';
COMMENT ON COLUMN bpa.worker.department_ref IS 'myPeople';
COMMENT ON COLUMN bpa.worker.employee_category IS 'myPeople';
COMMENT ON COLUMN bpa.worker.work_location IS 'myPeople';
COMMENT ON COLUMN bpa.worker.grade IS 'myPeople';
COMMENT ON COLUMN bpa.worker.aramco_id IS 'myPeople';

-- =====================================================================================================
-- Domain D, Hours (data model 2)
-- =====================================================================================================

-- 2.1 hours_submission: one send of a Work Pack's lines by the Supervisor; holds the approval stage (E2-05, E2-D2).
CREATE TABLE bpa.hours_submission (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    workpack_id          uuid        NOT NULL,
    process_instance_key bigint      NOT NULL,
    round_no             integer     NOT NULL,
    submitted_by         text        NOT NULL,
    submitted_at         timestamptz NOT NULL,
    stage                text        NOT NULL,
    outcome              text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    created_by           text        NOT NULL,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    updated_by           text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (process_instance_key, round_no),
    FOREIGN KEY (workpack_id) REFERENCES bpa.workpack (id) ON DELETE NO ACTION,
    CHECK (round_no > 0),
    CHECK (stage IN ('Timekeeper', 'ProductionSupervisor', 'Ppc', 'Closed')),
    CHECK (outcome IN ('Approved', 'Returned', 'Rejected')),
    CHECK ((stage = 'Closed') = (outcome IS NOT NULL))
);
COMMENT ON TABLE bpa.hours_submission IS 'POC database';
COMMENT ON COLUMN bpa.hours_submission.submitted_by IS 'Camunda identity';

-- 2.2 hours_decision: one row per decision at a step; the history of returns and rejections.
CREATE TABLE bpa.hours_decision (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    hours_submission_id uuid        NOT NULL,
    step                text        NOT NULL,
    decision            text        NOT NULL,
    reason              text,
    task_key            bigint      NOT NULL,
    decided_by          text        NOT NULL,
    decided_at          timestamptz NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          text        NOT NULL,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (task_key),
    FOREIGN KEY (hours_submission_id) REFERENCES bpa.hours_submission (id) ON DELETE NO ACTION,
    CHECK (step IN ('Timekeeper', 'ProductionSupervisor', 'Ppc')),
    CHECK (decision IN ('Validated', 'Approved', 'Returned', 'Rejected')),
    CHECK (decision <> 'Validated' OR step = 'Timekeeper'),
    CHECK (decision <> 'Rejected' OR step <> 'Timekeeper'),
    CHECK (decision IN ('Validated', 'Approved') OR btrim(coalesce(reason, '')) <> '')
);
COMMENT ON TABLE bpa.hours_decision IS 'POC database';
COMMENT ON COLUMN bpa.hours_decision.decided_by IS 'Camunda identity';

-- 2.3 timesheet_file and timesheet_file_row: bulk upload intake log (E2-03b), the pattern of assignment_file (D49, ADR 0009).
CREATE TABLE bpa.timesheet_file (
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
CREATE INDEX timesheet_file_sha256_idx ON bpa.timesheet_file (sha256);
COMMENT ON TABLE bpa.timesheet_file IS 'POC database';
COMMENT ON COLUMN bpa.timesheet_file.uploaded_by IS 'Camunda identity';

CREATE TABLE bpa.timesheet_file_row (
    id                uuid        NOT NULL DEFAULT gen_random_uuid(),
    timesheet_file_id uuid        NOT NULL,
    row_number        integer     NOT NULL,
    workpack_number   text,
    badge_no          text,
    work_date_raw     text,
    phase_raw         text,
    hours_raw         text,
    remarks           text,
    outcome           text        NOT NULL,
    timesheet_line_id uuid,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        text        NOT NULL,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        text        NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (timesheet_file_id) REFERENCES bpa.timesheet_file (id) ON DELETE NO ACTION,
    FOREIGN KEY (timesheet_line_id) REFERENCES bpa.digital_timesheet_line (id) ON DELETE NO ACTION
);
COMMENT ON TABLE bpa.timesheet_file_row IS 'POC database';

-- hours_month_lock (addendum 3, S5): the month the hours period is locked for FMS posting; lock_day is 1 or 16 (FRD-5-3).
CREATE TABLE bpa.hours_month_lock (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    month      date        NOT NULL,
    locked_at  timestamptz NOT NULL,
    lock_day   smallint    NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (month),
    CHECK (lock_day IN (1, 16))
);
COMMENT ON TABLE bpa.hours_month_lock IS 'POC database';

-- =====================================================================================================
-- Domain H, Progress (data model 3)
-- =====================================================================================================

-- 3.1 progress_period: the PMS weekly reporting period, simulated (E2-D15, E2-16, E2-19).
CREATE TABLE bpa.progress_period (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    week_ending          date        NOT NULL,
    cut_off_at           timestamptz NOT NULL,
    status               text        NOT NULL,
    locked_at            timestamptz,
    week_approved_by     text,
    week_approved_at     timestamptz,
    week_decision_reason text,
    process_instance_key bigint      NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    created_by           text        NOT NULL,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    updated_by           text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (week_ending),
    UNIQUE (process_instance_key),
    CHECK (status IN ('CutOff', 'Locked'))
);
COMMENT ON TABLE bpa.progress_period IS 'POC database';
COMMENT ON COLUMN bpa.progress_period.week_approved_by IS 'Camunda identity';

-- 3.2 fms_hours_export (with addendum 2): the file handed to FMS, one per week and month (S5).
CREATE TABLE bpa.fms_hours_export (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    progress_period_id  uuid        NOT NULL,
    period_month        date        NOT NULL,
    file                bytea       NOT NULL,
    sha256              text        NOT NULL,
    row_count           integer     NOT NULL,
    generated_at        timestamptz NOT NULL,
    sent_by             text,
    sent_at             timestamptz,
    fms_rejected_at     timestamptz,
    fms_rejected_reason text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          text        NOT NULL,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (progress_period_id, period_month),
    FOREIGN KEY (progress_period_id) REFERENCES bpa.progress_period (id) ON DELETE NO ACTION,
    CHECK (row_count >= 0),
    CHECK ((fms_rejected_at IS NULL) = (fms_rejected_reason IS NULL))
);
COMMENT ON TABLE bpa.fms_hours_export IS 'POC database';
COMMENT ON COLUMN bpa.fms_hours_export.sent_by IS 'Camunda identity';

-- 3.3 completion_report and completion_report_row: the FMS completion report as uploaded (E2-09). The values are FMS's, the rows
-- are the POC's intake log; layout is an ASSUMPTION until E2-17.
CREATE TABLE bpa.completion_report (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    progress_period_id uuid        NOT NULL,
    file_name          text        NOT NULL,
    sha256             text        NOT NULL,
    uploaded_by        text        NOT NULL,
    received_at        timestamptz NOT NULL,
    outcome            text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    created_by         text        NOT NULL,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    updated_by         text        NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (progress_period_id) REFERENCES bpa.progress_period (id) ON DELETE NO ACTION
);
COMMENT ON TABLE bpa.completion_report IS 'POC database';
COMMENT ON COLUMN bpa.completion_report.uploaded_by IS 'Camunda identity';

CREATE TABLE bpa.completion_report_row (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    completion_report_id uuid        NOT NULL,
    row_number           integer     NOT NULL,
    workpack_number      text,
    completion_pct_raw   text,
    actual_hours_raw     text,
    outcome              text        NOT NULL,
    source               text        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    created_by           text        NOT NULL,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    updated_by           text        NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (completion_report_id) REFERENCES bpa.completion_report (id) ON DELETE NO ACTION,
    CHECK (source IN ('FMS', 'PMS'))
);
COMMENT ON TABLE bpa.completion_report_row IS 'POC database';
COMMENT ON COLUMN bpa.completion_report_row.workpack_number IS 'FMS';
COMMENT ON COLUMN bpa.completion_report_row.completion_pct_raw IS 'FMS';
COMMENT ON COLUMN bpa.completion_report_row.actual_hours_raw IS 'FMS';

-- 3.4 workpack_progress (with the 10 Nov addendum: override_remark): one row per Work Pack and week.
CREATE TABLE bpa.workpack_progress (
    id                 uuid         NOT NULL DEFAULT gen_random_uuid(),
    workpack_id        uuid         NOT NULL,
    progress_period_id uuid         NOT NULL,
    reported_pct       numeric(5,2),
    approved_pct       numeric(5,2),
    previous_pct       numeric(5,2) NOT NULL DEFAULT 0,
    rule_result        text,
    approved_by        text,
    approved_at        timestamptz,
    override_remark    text,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         text         NOT NULL,
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    updated_by         text         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (workpack_id, progress_period_id),
    FOREIGN KEY (workpack_id) REFERENCES bpa.workpack (id) ON DELETE NO ACTION,
    FOREIGN KEY (progress_period_id) REFERENCES bpa.progress_period (id) ON DELETE NO ACTION,
    CHECK (approved_pct BETWEEN 0 AND 100),
    CHECK (rule_result IN ('Accepted', 'Blocked', 'NeedsRetraction')),
    CHECK (char_length(override_remark) <= 250)
);
COMMENT ON TABLE bpa.workpack_progress IS 'POC database';
COMMENT ON COLUMN bpa.workpack_progress.reported_pct IS 'FMS';
COMMENT ON COLUMN bpa.workpack_progress.approved_by IS 'Camunda identity';

-- 3.5 progress_retraction (with the 10 Nov addendum: approved_pct): FRD-21 7 "documented justification and full audit trail".
CREATE TABLE bpa.progress_retraction (
    id                   uuid         NOT NULL DEFAULT gen_random_uuid(),
    workpack_progress_id uuid         NOT NULL,
    requested_by         text         NOT NULL,
    reason               text         NOT NULL,
    approved_pct         numeric(5,2) NOT NULL,
    decision             text,
    decision_reason      text,
    decided_by           text,
    decided_at           timestamptz,
    task_key             bigint,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    created_by           text         NOT NULL,
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    updated_by           text         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (task_key),
    FOREIGN KEY (workpack_progress_id) REFERENCES bpa.workpack_progress (id) ON DELETE NO ACTION,
    CHECK (btrim(reason) <> ''),
    CHECK (decision IN ('Approved', 'Declined')),
    CHECK (decision IS DISTINCT FROM 'Declined' OR btrim(coalesce(decision_reason, '')) <> '')   -- required when Declined (ASSUMPTION)
);
COMMENT ON TABLE bpa.progress_retraction IS 'POC database';
COMMENT ON COLUMN bpa.progress_retraction.requested_by IS 'Camunda identity';
COMMENT ON COLUMN bpa.progress_retraction.decided_by IS 'Camunda identity';

-- 3.6 activity_progress: the hours-weighted roll-up per activity and week; earned man-hours (E2-D10, Q-DM17, Q-DM18).
CREATE TABLE bpa.activity_progress (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    activity_ref       text          NOT NULL,
    progress_period_id uuid          NOT NULL,
    actual_pct         numeric(5,2)  NOT NULL,
    budgeted_units     numeric(9,2),
    earned_manhours    numeric(11,2),
    created_at         timestamptz   NOT NULL DEFAULT now(),
    created_by         text          NOT NULL,
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    updated_by         text          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (activity_ref, progress_period_id),
    FOREIGN KEY (activity_ref) REFERENCES bpa.activity (activity_ref) ON DELETE NO ACTION,
    FOREIGN KEY (progress_period_id) REFERENCES bpa.progress_period (id) ON DELETE NO ACTION,
    CHECK (actual_pct BETWEEN 0 AND 100)
);
COMMENT ON TABLE bpa.activity_progress IS 'POC database';
COMMENT ON COLUMN bpa.activity_progress.activity_ref IS 'P6 via D365 PMS';
COMMENT ON COLUMN bpa.activity_progress.budgeted_units IS 'P6 via D365 PMS';

-- addendum 4 (D5): the monthly man-hour snapshot by CBS and activity, written once at the month lock, never updated (FRD-22 8.1).
CREATE TABLE bpa.cbs_manhour_snapshot (
    id                uuid          NOT NULL DEFAULT gen_random_uuid(),
    month             date          NOT NULL,
    cbs_code_ref      text          NOT NULL,
    activity_ref      text          NOT NULL,
    approved_manhours numeric(10,2) NOT NULL,
    frozen_at         timestamptz   NOT NULL,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    created_by        text          NOT NULL,
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    updated_by        text          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (month, cbs_code_ref, activity_ref)
);
COMMENT ON TABLE bpa.cbs_manhour_snapshot IS 'POC database';
COMMENT ON COLUMN bpa.cbs_manhour_snapshot.cbs_code_ref IS 'D365 F&O Cost Control Report';
COMMENT ON COLUMN bpa.cbs_manhour_snapshot.activity_ref IS 'P6 via D365 PMS';

-- =====================================================================================================
-- digital_timesheet_line (data model 1): status, links to the submission and files, remarks, FMS export, one line per Worker,
-- date and phase (E2-D4, E2-D7). Epic 1 creates no rows; BPA-13 tests seed rows, which take the defaults.
-- =====================================================================================================
ALTER TABLE bpa.digital_timesheet_line
    ADD COLUMN status                  text         NOT NULL DEFAULT 'Draft',
    ADD COLUMN hours_submission_id     uuid,
    ADD COLUMN entry_source            text         NOT NULL DEFAULT 'Form',
    ADD COLUMN timesheet_file_id       uuid,
    ADD COLUMN remarks                 text,
    ADD COLUMN exported_at             timestamptz,
    ADD COLUMN fms_hours_export_id     uuid,
    ADD COLUMN reported_completion_pct numeric(5,2),
    ADD CONSTRAINT digital_timesheet_line_status_check CHECK (status IN ('Draft', 'Submitted', 'Approved', 'Returned', 'Rejected')),
    ADD CONSTRAINT digital_timesheet_line_entry_source_check CHECK (entry_source IN ('Form', 'Upload')),
    ADD CONSTRAINT digital_timesheet_line_remarks_check CHECK (char_length(remarks) <= 250),                 -- ASSUMPTION, same limit as D59
    ADD CONSTRAINT digital_timesheet_line_reported_completion_pct_check CHECK (reported_completion_pct BETWEEN 0 AND 100),
    ADD CONSTRAINT digital_timesheet_line_hours_check CHECK (hours >= 0),                                    -- entry is checked > 0 by the worker (Q-DM16)
    ADD CONSTRAINT digital_timesheet_line_hours_submission_id_fkey FOREIGN KEY (hours_submission_id) REFERENCES bpa.hours_submission (id) ON DELETE NO ACTION,
    ADD CONSTRAINT digital_timesheet_line_timesheet_file_id_fkey FOREIGN KEY (timesheet_file_id) REFERENCES bpa.timesheet_file (id) ON DELETE NO ACTION,
    ADD CONSTRAINT digital_timesheet_line_fms_hours_export_id_fkey FOREIGN KEY (fms_hours_export_id) REFERENCES bpa.fms_hours_export (id) ON DELETE NO ACTION;
-- One line per Worker, date and phase (E2-D4), ignoring Rejected lines: a Rejected line stays Rejected and the Supervisor may book a
-- new line for the same Worker, date and phase (E2-06 c; epic spec A5, Khitaba 10 Nov 2026). The delta rev. 2 shows a plain UNIQUE;
-- this departs from it on purpose, see the BPA-48 plan (Resolutions, Q5).
CREATE UNIQUE INDEX digital_timesheet_line_one_per_worker_date_phase
    ON bpa.digital_timesheet_line (digital_timesheet_id, work_date, phase) WHERE status <> 'Rejected';

-- =====================================================================================================
-- Domain A, cost reference copies (data model 4), seeded by R__seed_reference_data.sql
-- =====================================================================================================

-- 4.1 cbs_forecast: Current Forecast MH and Revised Budget cost per CBS (FRD-22 5.3.2, 5.4).
CREATE TABLE bpa.cbs_forecast (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    cbs_code_ref        text          NOT NULL,
    current_forecast_mh numeric(11,2),
    revised_budget_cost numeric(15,2),
    currency            text          NOT NULL DEFAULT 'SAR',                                        -- ASSUMPTION
    created_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          text          NOT NULL,
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          text          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (cbs_code_ref)
);
COMMENT ON TABLE bpa.cbs_forecast IS 'D365 F&O Cost Control Report';

-- 4.2 cbs_actual_cost: actual cost per CBS and month (FRD-22 5.3.1); new SOR label D365 F&O Finance.
CREATE TABLE bpa.cbs_actual_cost (
    id           uuid          NOT NULL DEFAULT gen_random_uuid(),
    cbs_code_ref text          NOT NULL,
    period_month date          NOT NULL,
    actual_cost  numeric(15,2) NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   text          NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   text          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (cbs_code_ref, period_month)
);
COMMENT ON TABLE bpa.cbs_actual_cost IS 'D365 F&O Cost Control Report';
COMMENT ON COLUMN bpa.cbs_actual_cost.actual_cost IS 'D365 F&O Finance';

-- 4.3 cbs_block: whether a CBS is closed for a type of booking (FRD-22 3.1 list); read by BPA-69. POC master data (Khitaba,
-- 10 Nov 2026), real SOR D365 F&O Cost Control Report. No row means open (ASSUMPTION).
CREATE TABLE bpa.cbs_block (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    cbs_code_ref text        NOT NULL,
    block_type   text        NOT NULL,
    is_closed    boolean     NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   text        NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (cbs_code_ref, block_type),
    CHECK (block_type IN ('DigitalTimesheet', 'FmsTimesheet', 'Procurement', 'Reimbursement', 'AssetUtilization'))
);
COMMENT ON TABLE bpa.cbs_block IS 'D365 F&O Cost Control Report';

-- =====================================================================================================
-- Domain E, alerts and domain H, review (data model 5, 5.1)
-- =====================================================================================================

-- 5 variance_alert: the man-hour and cost variance alerts; the unique key is the idempotency key of send-variance-alerts.
-- Not notification: its drawing_ref and revision are NOT NULL (BPA-16).
CREATE TABLE bpa.variance_alert (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    progress_period_id uuid          NOT NULL,
    cbs_code_ref       text          NOT NULL,
    alert_type         text          NOT NULL,
    actual_value       numeric(15,2) NOT NULL,
    baseline_value     numeric(15,2) NOT NULL,
    variance_pct       numeric(7,2)  NOT NULL,
    recipient_username text          NOT NULL,
    channel            text          NOT NULL,
    sent_at            timestamptz   NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    created_by         text          NOT NULL,
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    updated_by         text          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (progress_period_id, cbs_code_ref, alert_type, recipient_username, channel),
    FOREIGN KEY (progress_period_id) REFERENCES bpa.progress_period (id) ON DELETE NO ACTION,
    FOREIGN KEY (recipient_username) REFERENCES bpa.app_user (username) ON DELETE NO ACTION,
    CHECK (alert_type IN ('Manhour', 'Cost')),
    CHECK (channel IN ('Email', 'InSystem'))
);
COMMENT ON TABLE bpa.variance_alert IS 'POC database';
COMMENT ON COLUMN bpa.variance_alert.cbs_code_ref IS 'D365 F&O Cost Control Report';
COMMENT ON COLUMN bpa.variance_alert.recipient_username IS 'Camunda identity';

-- 5.1 cbs_review: the Cost Controller's review of the week per CBS (FRD-22 4, 5.6.5 Remarks; E2-14).
CREATE TABLE bpa.cbs_review (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    progress_period_id uuid        NOT NULL,
    cbs_code_ref       text        NOT NULL,
    remark             text,
    reviewed_by        text        NOT NULL,
    reviewed_at        timestamptz NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    created_by         text        NOT NULL,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    updated_by         text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (progress_period_id, cbs_code_ref),
    FOREIGN KEY (progress_period_id) REFERENCES bpa.progress_period (id) ON DELETE NO ACTION,
    CHECK (char_length(remark) <= 500)                                                               -- ASSUMPTION
);
COMMENT ON TABLE bpa.cbs_review IS 'POC database';
COMMENT ON COLUMN bpa.cbs_review.cbs_code_ref IS 'D365 F&O Cost Control Report';
COMMENT ON COLUMN bpa.cbs_review.reviewed_by IS 'Camunda identity';

-- =====================================================================================================
-- Addendum 5 (11 Nov 2026): access scope and leave
-- =====================================================================================================

-- user_project: which portal user may see which project (BPA-76 to BPA-79). POC database (ASSUMPTION): no STAR system holds it.
-- Brings back project scope only; department and role scope stay out (ADR 0005). V1 has no project table, so project_ref
-- references project_job_code(project_ref), its UNIQUE project key.
CREATE TABLE bpa.user_project (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    username    text        NOT NULL,
    project_ref text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  text        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (username, project_ref),
    FOREIGN KEY (username) REFERENCES bpa.app_user (username) ON DELETE NO ACTION,                   -- Q-DM24 (ASSUMPTION, decided 11 Nov)
    FOREIGN KEY (project_ref) REFERENCES bpa.project_job_code (project_ref) ON DELETE NO ACTION
);
COMMENT ON TABLE bpa.user_project IS 'POC database';
COMMENT ON COLUMN bpa.user_project.username IS 'Camunda identity';
COMMENT ON COLUMN bpa.user_project.project_ref IS 'D365 F&O Project Management';

-- worker_leave: approved leave of a Worker, read by Decision_HoursWithinBudget (BPA-75). myPeople (ASSUMPTION), read-only seeded
-- copy. Only approved leave is stored, so there is no status column (Q-DM23, decided 11 Nov).
CREATE TABLE bpa.worker_leave (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    worker_id  uuid        NOT NULL,
    leave_from date        NOT NULL,
    leave_to   date        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by text        NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (worker_id) REFERENCES bpa.worker (id) ON DELETE NO ACTION,
    CHECK (leave_to >= leave_from)
);
COMMENT ON TABLE bpa.worker_leave IS 'myPeople';
