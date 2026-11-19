-- V3__completion_row_source_raw.sql (BPA-59, plan Q1; ADR 0010; docs/data-model.md section 13.3.3).
-- V1 and V2 are frozen (Q-DM21: the QA dev server already runs them), so the change is a new file.
-- completion_report_row.source was NOT NULL CHECK (FMS, PMS) (Addendum 3, C-E2-3), so a row with a blank or unknown Source could not be logged as it was
-- written. The intake log keeps every row raw (data model 13.3.3): source_raw holds the text of the Source cell as written (NULL when the cell is empty),
-- and source holds only the two values the POC understands, FMS or PMS (NULL otherwise). The CHECK stays and accepts NULL.
ALTER TABLE bpa.completion_report_row ADD COLUMN source_raw text;
ALTER TABLE bpa.completion_report_row ALTER COLUMN source DROP NOT NULL;
COMMENT ON COLUMN bpa.completion_report_row.source_raw IS 'FMS';
