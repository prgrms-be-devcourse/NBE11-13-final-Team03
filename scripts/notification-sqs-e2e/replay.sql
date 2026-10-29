-- Dedicated E2E replay only. NEVER reset production PUBLISHED rows this way.
BEGIN;
SET LOCAL search_path TO application, pg_catalog;
DO $$ BEGIN
  IF current_database() <> 'yon_sqs_e2e' OR current_user <> 'application_user' THEN
    RAISE EXCEPTION 'Use only yon_sqs_e2e as application_user';
  END IF;
END $$;
UPDATE outbox_events SET status = 'PENDING', published_at = NULL, next_retry_at = CURRENT_TIMESTAMP,
  lease_token = NULL, processing_started_at = NULL, last_error = NULL
WHERE message_id = '90000000-0000-4000-8000-000000000001' AND status = 'PUBLISHED';
COMMIT;
