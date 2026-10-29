-- Apply once as notification_user after notification-user-api.sql, with Notification stopped (ddl-auto=validate).
-- Binds each push Endpoint to the login session that registered it so a logout (USER_SESSION_ENDED) can
-- deactivate that session's Endpoints only. Existing rows keep session_id NULL: the user's next logout deactivates
-- them as well, and the next registration binds them to a session. No data migration is needed.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL search_path TO notification, pg_catalog;
DO $$ BEGIN
    IF current_user <> 'notification_user' THEN
        RAISE EXCEPTION 'Run notification-session-end.sql as notification_user';
    END IF;
END $$;
ALTER TABLE notification_endpoints ADD COLUMN session_id varchar(36);
CREATE INDEX idx_notification_endpoints_user_session ON notification_endpoints(user_id, session_id)
    WHERE session_id IS NOT NULL;
COMMIT;
