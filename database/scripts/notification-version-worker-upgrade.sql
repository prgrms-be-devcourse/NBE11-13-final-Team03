-- Manual, reviewed rollout only; stop backend before applying as application_user.
-- Preserves rows. Replaces enum allow-list CHECKs and the obsolete single-tracking UNIQUE.
-- The single-tracking UNIQUE removal applies only to DBs provisioned from the initial PR #70 schema.
-- Fresh databases use the composite UNIQUE directly; its removal loop is then a no-op.
-- Unknown/custom status constraints or standalone unique indexes require manual review, never silent removal.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SET LOCAL search_path TO application, pg_catalog;

DO $$
DECLARE
    target record;
    item record;
    relation regclass;
    status_att smallint;
    tracking_att smallint;
    rule_att smallint;
BEGIN
    IF current_user <> 'application_user' OR current_schema() <> 'application' THEN
        RAISE EXCEPTION 'Run as application_user in application schema';
    END IF;
    IF to_regclass('application.opportunity_events') IS NULL THEN
        RAISE EXCEPTION 'Apply base/date-tracking schema first';
    END IF;
    FOR target IN SELECT * FROM (VALUES
        ('opportunity_events', 'processing_status', 'ck_worker_event_status',
         '''PENDING'',''PROCESSING'',''WAITING_POLICY'',''COMPLETED'',''FAILED'''),
        ('opportunity_version_tracking', 'status', 'ck_worker_version_status',
         '''BASELINE_SKIPPED'',''NO_CHANGE'',''NORMALIZATION_VERSION_CHANGED'',''READY_FOR_EVENT_ANALYSIS'',''PROCESSED_WITH_DEFERRED_DEADLINE'',''PROCESSED''')
    ) AS spec(table_name, column_name, constraint_name, allowed_values)
    LOOP
        relation := to_regclass(format('application.%I', target.table_name));
        IF relation IS NULL THEN
            RAISE NOTICE 'Absent table %, new code must create it before activation', target.table_name;
            CONTINUE;
        END IF;
        SELECT attnum INTO STRICT status_att FROM pg_attribute
          WHERE attrelid = relation AND attname = target.column_name AND NOT attisdropped;
        FOR item IN SELECT conname, conkey, pg_get_constraintdef(oid) AS definition
          FROM pg_constraint WHERE conrelid = relation AND contype = 'c' AND status_att = ANY(conkey)
        LOOP
            IF item.conkey <> ARRAY[status_att]::smallint[]
                OR position('ANY' in item.definition) = 0 OR position('ARRAY[' in item.definition) = 0
                OR position(' AND ' in item.definition) > 0 OR position(' OR ' in item.definition) > 0 THEN
                RAISE EXCEPTION 'Review custom status CHECK % on % before rollout', item.conname, target.table_name;
            END IF;
            EXECUTE format('ALTER TABLE application.%I DROP CONSTRAINT %I', target.table_name, item.conname);
        END LOOP;
        EXECUTE format('ALTER TABLE application.%I ADD CONSTRAINT %I CHECK (%I IN (%s))',
                       target.table_name, target.constraint_name, target.column_name, target.allowed_values);
    END LOOP;

    relation := to_regclass('application.opportunity_version_analysis');
    IF relation IS NOT NULL THEN
        SELECT attnum INTO STRICT tracking_att FROM pg_attribute WHERE attrelid = relation AND attname = 'tracking_id' AND NOT attisdropped;
        SELECT attnum INTO STRICT rule_att FROM pg_attribute WHERE attrelid = relation AND attname = 'analysis_version' AND NOT attisdropped;
        IF EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = relation AND i.indisunique
                   AND i.indkey::smallint[] @> ARRAY[tracking_att]::smallint[] AND i.indnkeyatts = 1
                   AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid)) THEN
            RAISE EXCEPTION 'Review standalone UNIQUE tracking_id index before rollout';
        END IF;
        FOR item IN SELECT conname FROM pg_constraint WHERE conrelid = relation AND contype = 'u'
                    AND conkey = ARRAY[tracking_att]::smallint[]
        LOOP
            EXECUTE format('ALTER TABLE application.opportunity_version_analysis DROP CONSTRAINT %I', item.conname);
        END LOOP;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = relation AND contype = 'u'
                       AND cardinality(conkey) = 2 AND conkey @> ARRAY[tracking_att, rule_att]::smallint[]) THEN
            ALTER TABLE application.opportunity_version_analysis ADD CONSTRAINT uk_version_analysis_rule UNIQUE (tracking_id, analysis_version);
        END IF;
    END IF;
END $$;
COMMIT;
