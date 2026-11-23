-- V7__notification_hours_reminder.sql (BPA-74, plan Q1; ADR 0010; docs/data-model.md section 5.9).
-- V1 to V6 are frozen, so the change is a new file. notification held only drawing-revision notices (BPA-16): drawing_ref and revision were NOT NULL, there was no
-- message column and the one key was per drawing revision. An hours reminder (FRD-5-12 section 5.12.10) has no drawing, carries a message and is sent once per
-- timer firing. Now: drawing_ref and revision may be null; kind says which sort of notice a row is (existing rows are DrawingRevision); message holds the text of
-- a reminder; reminder_key (process instance key, step and timer firing) makes a reminder idempotent. A CHECK keeps each kind complete, and the reminder's own
-- key is a partial unique index; the existing UNIQUE key stays for the drawing notices (its NULLs never collide, so a reminder row cannot trip it).
ALTER TABLE bpa.notification ALTER COLUMN drawing_ref DROP NOT NULL;
ALTER TABLE bpa.notification ALTER COLUMN revision DROP NOT NULL;
ALTER TABLE bpa.notification ADD COLUMN kind text NOT NULL DEFAULT 'DrawingRevision';
ALTER TABLE bpa.notification ADD COLUMN message text;
ALTER TABLE bpa.notification ADD COLUMN reminder_key text;
ALTER TABLE bpa.notification ADD CONSTRAINT notification_kind_valid CHECK (kind IN ('DrawingRevision', 'HoursReminder'));
ALTER TABLE bpa.notification ADD CONSTRAINT notification_kind_complete CHECK (
    (kind = 'DrawingRevision' AND drawing_ref IS NOT NULL AND revision IS NOT NULL)
    OR (kind = 'HoursReminder' AND message IS NOT NULL AND reminder_key IS NOT NULL));
CREATE UNIQUE INDEX notification_reminder_once ON bpa.notification (reminder_key, recipient_username, channel) WHERE kind = 'HoursReminder';
COMMENT ON COLUMN bpa.notification.kind IS 'DrawingRevision (BPA-16) or HoursReminder (BPA-74); POC database';
COMMENT ON COLUMN bpa.notification.message IS 'The text of an hours reminder; POC database';
COMMENT ON COLUMN bpa.notification.reminder_key IS 'Process instance key, step and timer firing of an hours reminder; POC database';
