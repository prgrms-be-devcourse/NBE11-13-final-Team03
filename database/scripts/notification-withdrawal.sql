-- Apply once as notification_user after notification-inbox.sql and notification-user-api.sql.
-- Stop Notification first. No Application tables, permissions or existing receipt fingerprints are changed.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL search_path TO notification, pg_catalog;
DO $$ BEGIN
    IF current_user <> 'notification_user' THEN
        RAISE EXCEPTION 'Run notification-withdrawal.sql as notification_user';
    END IF;
END $$;
CREATE TABLE withdrawn_users (
    user_id bigint PRIMARY KEY CHECK (user_id > 0),
    withdrawn_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL
);
-- No TTL/cleanup job until queue retention and replay limits are confirmed.
COMMIT;
