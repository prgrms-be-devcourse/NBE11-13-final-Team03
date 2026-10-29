-- Existing Notification databases: stop Notification, back up, then apply as notification_user.
-- Optional field only. Do not infer/backfill a threshold day from receipt/created timestamps.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SET LOCAL search_path TO notification, pg_catalog;
DO $$ BEGIN
    IF current_user <> 'notification_user' THEN
        RAISE EXCEPTION 'Run notification-push-expiry.sql as notification_user';
    END IF;
END $$;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS push_expires_at timestamptz;
COMMIT;
