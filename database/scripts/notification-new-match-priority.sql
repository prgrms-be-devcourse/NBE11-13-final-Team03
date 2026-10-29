-- Existing slot databases only: stop Notification, back up, then apply as notification_user before accepting NEW_MATCH.
-- Fresh databases created with the updated notification-push-slots.sql already allow priority 6.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SET LOCAL search_path TO notification, pg_catalog;
DO $$
DECLARE
    priority_att smallint;
BEGIN
    IF current_user <> 'notification_user' THEN
        RAISE EXCEPTION 'Run notification-new-match-priority.sql as notification_user';
    END IF;
    IF to_regclass('notification.notification_push_pending') IS NULL THEN
        RAISE EXCEPTION 'Apply notification-push-slots.sql first';
    END IF;
    SELECT attnum INTO STRICT priority_att FROM pg_attribute
      WHERE attrelid = 'notification.notification_push_pending'::regclass
        AND attname = 'priority' AND NOT attisdropped;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'notification.notification_push_pending'::regclass
                     AND conname = 'notification_push_pending_priority_check'
                     AND contype = 'c' AND conkey = ARRAY[priority_att]::smallint[]) THEN
        RAISE EXCEPTION 'Review notification_push_pending priority CHECK before rollout';
    END IF;
END $$;
ALTER TABLE notification_push_pending DROP CONSTRAINT notification_push_pending_priority_check;
ALTER TABLE notification_push_pending ADD CONSTRAINT notification_push_pending_priority_check
    CHECK (priority IS NULL OR priority BETWEEN 0 AND 6);
COMMIT;
