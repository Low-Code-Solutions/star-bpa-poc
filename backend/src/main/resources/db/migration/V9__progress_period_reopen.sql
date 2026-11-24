-- V9__progress_period_reopen.sql (BPA-83; ADR 0010; docs/data-model.md section 3.1). V1 to V8 are frozen.
-- A Project Manager may reopen a Locked week (FRD-21 §21.7, FRD-10 §10.10) when bpa.progress.allow-reopen is true (C-E3-3).
-- SOR of the three columns: POC database (decision 12 Nov 2026, Khitaba for the PO). They hold the latest reopen; every reopen is in audit_log.
ALTER TABLE bpa.progress_period DROP CONSTRAINT progress_period_status_check;
ALTER TABLE bpa.progress_period ADD CONSTRAINT progress_period_status_check CHECK (status IN ('CutOff', 'Locked', 'Reopened'));
ALTER TABLE bpa.progress_period
    ADD COLUMN reopened_by   text,
    ADD COLUMN reopened_at   timestamptz,
    ADD COLUMN reopen_reason text;
ALTER TABLE bpa.progress_period ADD CONSTRAINT progress_period_reopen_check
    CHECK (status <> 'Reopened' OR (reopened_by IS NOT NULL AND reopened_at IS NOT NULL AND reopen_reason IS NOT NULL AND btrim(reopen_reason) <> ''));
COMMENT ON COLUMN bpa.progress_period.reopened_by IS 'POC database';
COMMENT ON COLUMN bpa.progress_period.reopened_at IS 'POC database';
COMMENT ON COLUMN bpa.progress_period.reopen_reason IS 'POC database';
