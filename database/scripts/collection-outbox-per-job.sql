-- Existing collection-outbox.sql installations only. Apply before upgrading data-service.
-- Retains every existing messageId/payload/dedup key; prevents another message for the same job.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL search_path TO application, pg_catalog;
DO $$
BEGIN
    IF current_user <> 'application_user' THEN
        RAISE EXCEPTION 'Run collection-outbox-per-job.sql as application_user';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'collection_outbox_events'::regclass
                     AND conname = 'uk_collection_outbox_job') THEN
        ALTER TABLE collection_outbox_events ADD CONSTRAINT uk_collection_outbox_job UNIQUE(sync_job_id);
    END IF;
END $$;
COMMIT;
