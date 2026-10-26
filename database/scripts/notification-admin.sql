-- Apply once as notification_user after database-boundaries.sql, before enabling the administrator Consumer.
-- Existing consumed_messages keeps its user_id NOT NULL constraint and is never used for admin messages.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL search_path TO notification, pg_catalog;
DO $$
BEGIN
    IF current_user <> 'notification_user' THEN
        RAISE EXCEPTION 'Run notification-admin.sql as notification_user';
    END IF;
END $$;

CREATE TABLE admin_consumed_messages (
    message_id uuid PRIMARY KEY,
    event_type varchar(40) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    consumed_at timestamptz NOT NULL,
    CONSTRAINT ck_admin_consumed_event_type CHECK(event_type = 'COLLECTION_COMPLETED'),
    CONSTRAINT ck_admin_consumed_fingerprint CHECK(request_fingerprint ~ '^[0-9a-f]{64}$')
);
-- No user ID, FK or application-schema access. Receipts are immutable and survive job/user cleanup.
COMMIT;
