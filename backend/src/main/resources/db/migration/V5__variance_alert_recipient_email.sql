-- V5__variance_alert_recipient_email.sql (BPA-64, plan Q2; ADR 0010; docs/data-model.md section 13.5).
-- V1 to V4 are frozen, so the change is a new file. The PCON Manager has no login and no app_user row (D3: an email address, alerts.pconManagerEmail), but
-- variance_alert.recipient_username was NOT NULL with a foreign key to app_user, so the once-only guard of the PCON Manager's email could not be recorded.
-- A row now names exactly one recipient: a user (recipient_username, as before) or an address (recipient_email). The once-per-period, CBS, type, recipient and
-- channel key is a unique index over whichever of the two is set; the old UNIQUE (its generated name is long, so it is looked up) is dropped.
ALTER TABLE bpa.variance_alert ALTER COLUMN recipient_username DROP NOT NULL;
ALTER TABLE bpa.variance_alert ADD COLUMN recipient_email text;
ALTER TABLE bpa.variance_alert ADD CONSTRAINT variance_alert_one_recipient CHECK ((recipient_username IS NULL) <> (recipient_email IS NULL));
DO $$
DECLARE
    unique_name text;
BEGIN
    SELECT conname INTO unique_name FROM pg_constraint WHERE conrelid = 'bpa.variance_alert'::regclass AND contype = 'u';
    EXECUTE format('ALTER TABLE bpa.variance_alert DROP CONSTRAINT %I', unique_name);
END $$;
CREATE UNIQUE INDEX variance_alert_once ON bpa.variance_alert (progress_period_id, cbs_code_ref, alert_type, (coalesce(recipient_username, recipient_email)), channel);
COMMENT ON COLUMN bpa.variance_alert.recipient_email IS 'Configured address (alerts.pconManagerEmail)';
