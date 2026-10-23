-- Stop both applications and back up the database before running as administrator.
-- Move only known Y tables; unknown tables abort instead of silently moving unrelated data.
-- ALTER TABLE SET SCHEMA preserves data, indexes, constraints and owned sequences.
BEGIN;
SET LOCAL lock_timeout = '5s';

DO $$
DECLARE
    known_tables text[] := ARRAY[
        'users', 'refresh_tokens', 'user_interests', 'user_opportunity_preferences',
        'external_source', 'sync_history', 'raw_external_record', 'raw_normalization_request',
        'raw_ingest_states', 'regions', 'eligibility_profiles', 'eligibility_profile_special_targets',
        'eligibility_set_results', 'eligibility_results', 'condition_sets', 'opportunities',
        'opportunity_versions', 'opportunity_sources', 'opportunity_conditions', 'opportunity_regions',
        'application_windows', 'training_course_details', 'youth_policy_details',
        'opportunity_events', 'event_scan_history', 'outbox_events'
    ];
    item record;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'application') THEN
        RAISE EXCEPTION 'Run database-boundaries.sql first';
    END IF;
    FOR item IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') LOOP
        IF NOT item.relname = ANY(known_tables) THEN
            RAISE EXCEPTION 'Unknown public table %: review migration inventory before proceeding', item.relname;
        END IF;
        IF to_regclass(format('application.%I', item.relname)) IS NOT NULL THEN
            RAISE EXCEPTION 'Both schemas contain %; refusing to merge or overwrite data', item.relname;
        END IF;
    END LOOP;
    -- Unsupported standalone objects must be reviewed; table-owned indexes/sequences move automatically.
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'public' AND c.relkind IN ('v', 'm', 'f')) THEN
        RAISE EXCEPTION 'Public views/materialized views/foreign tables require an explicit migration';
    END IF;
    FOR item IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') ORDER BY c.oid LOOP
        EXECUTE format('ALTER TABLE public.%I SET SCHEMA application', item.relname);
        EXECUTE format('ALTER TABLE application.%I OWNER TO application_user', item.relname);
    END LOOP;
    IF to_regprocedure('public.notify_raw_normalization_request()') IS NOT NULL THEN
        IF to_regprocedure('application.notify_raw_normalization_request()') IS NOT NULL THEN
            RAISE EXCEPTION 'Duplicate normalization notification functions; review before proceeding';
        END IF;
        ALTER FUNCTION public.notify_raw_normalization_request() SET SCHEMA application;
        ALTER FUNCTION application.notify_raw_normalization_request() OWNER TO application_user;
        REVOKE ALL ON FUNCTION application.notify_raw_normalization_request() FROM PUBLIC, notification_user;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'public' AND c.relkind = 'S')
       OR EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                  WHERE n.nspname = 'public') THEN
        RAISE EXCEPTION 'Standalone public sequences/functions remain; review migration inventory';
    END IF;
    -- Never carry pre-existing cross-schema foreign keys into the new layout.
    IF EXISTS (
        SELECT 1 FROM pg_constraint fk
        JOIN pg_class src ON src.oid = fk.conrelid
        JOIN pg_class dst ON dst.oid = fk.confrelid
        JOIN pg_namespace sn ON sn.oid = src.relnamespace
        JOIN pg_namespace dn ON dn.oid = dst.relnamespace
        WHERE fk.contype = 'f' AND sn.oid <> dn.oid
          AND (sn.nspname IN ('application', 'notification') OR dn.nspname IN ('application', 'notification'))
    ) THEN
        RAISE EXCEPTION 'Cross-schema foreign keys found; migration rolled back';
    END IF;
END $$;

REVOKE ALL ON ALL TABLES IN SCHEMA application FROM PUBLIC, notification_user;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA application FROM PUBLIC, notification_user;
COMMIT;
