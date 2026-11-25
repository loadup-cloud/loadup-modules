ALTER TABLE notification_delivery
    DROP CONSTRAINT notification_delivery_channel_check,
    ALTER COLUMN recipient_id TYPE VARCHAR(512),
    ADD COLUMN route_json TEXT,
    ADD CONSTRAINT notification_delivery_channel_check CHECK (channel IN ('IN_APP', 'EMAIL', 'SMS', 'PUSH'));

ALTER TABLE IF EXISTS gotone_notification_record ALTER COLUMN receiver TYPE VARCHAR(512);
