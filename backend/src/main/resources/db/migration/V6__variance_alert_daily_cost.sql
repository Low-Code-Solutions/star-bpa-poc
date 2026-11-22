-- V6__variance_alert_daily_cost.sql (BPA-65, plan Q2 and Q3; ADR 0010; docs/data-model.md section 13.5).
-- V1 to V5 are frozen, so the change is a new file. The cost alert is sent by the weekly check (inside a progress period) and by the daily close check (FRD-22 section 22.10,
-- outside any period), and the story allows at most one cost alert per CBS, channel and recipient per day, whichever check sends it. variance_alert had
-- progress_period_id NOT NULL and a once-per-period key, so a daily alert could not be stored and "once per day" could not be enforced.
-- Now: progress_period_id may be null for a Cost alert only (a Manhour alert always belongs to its week); alert_date is the business date (Asia/Riyadh) the alert is for;
-- the one key is two partial unique indexes: Manhour once per period (BPA-64, unchanged) and Cost once per CBS, recipient, channel and day.
ALTER TABLE bpa.variance_alert ALTER COLUMN progress_period_id DROP NOT NULL;
ALTER TABLE bpa.variance_alert ADD COLUMN alert_date date;
UPDATE bpa.variance_alert SET alert_date = (sent_at AT TIME ZONE 'Asia/Riyadh')::date;
ALTER TABLE bpa.variance_alert ALTER COLUMN alert_date SET NOT NULL;
ALTER TABLE bpa.variance_alert ADD CONSTRAINT variance_alert_manhour_has_period CHECK (alert_type = 'Cost' OR progress_period_id IS NOT NULL);
DROP INDEX bpa.variance_alert_once;
CREATE UNIQUE INDEX variance_alert_manhour_once ON bpa.variance_alert (progress_period_id, cbs_code_ref, alert_type, (coalesce(recipient_username, recipient_email)), channel)
    WHERE alert_type = 'Manhour';
CREATE UNIQUE INDEX variance_alert_cost_once ON bpa.variance_alert (cbs_code_ref, (coalesce(recipient_username, recipient_email)), channel, alert_date)
    WHERE alert_type = 'Cost';
COMMENT ON COLUMN bpa.variance_alert.alert_date IS 'Business date (Asia/Riyadh) the alert is for; POC database';
