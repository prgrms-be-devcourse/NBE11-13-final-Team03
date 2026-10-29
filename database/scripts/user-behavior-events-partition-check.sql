-- 행동 로그 전용 읽기 점검. raw_external_record 파티셔닝과 독립적으로 실행한다.
SET search_path TO application, pg_catalog;
SELECT relname, relkind, pg_get_partkeydef(oid) AS partition_key
FROM pg_class WHERE oid='user_behavior_events'::regclass;
SELECT child.relname, pg_get_expr(child.relpartbound, child.oid) AS bound,
       pg_get_userbyid(child.relowner) AS owner,
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %s', child.oid::regclass),
                                          false, true, '')))[1]::text::bigint AS rows,
       pg_size_pretty(pg_total_relation_size(child.oid)) AS size
FROM pg_inherits i JOIN pg_class child ON child.oid=i.inhrelid
WHERE i.inhparent='user_behavior_events'::regclass ORDER BY child.relname;
-- old/new 건수 일치는 전환 직후만 기대한다. 서비스 재개 뒤에는 달라진다.
SELECT c.relname, pg_get_userbyid(c.relowner) AS owner,
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %s', c.oid::regclass),
                                          false, true, '')))[1]::text::bigint AS rows
FROM pg_class c WHERE c.relnamespace='application'::regnamespace
AND c.relname IN ('user_behavior_events','user_behavior_events_old');
SELECT conname, pg_get_constraintdef(oid) AS definition FROM pg_constraint
WHERE conrelid='user_behavior_events'::regclass AND conparentid=0 ORDER BY conname;
SELECT indexname, indexdef FROM pg_indexes
WHERE schemaname='application' AND tablename='user_behavior_events';
SELECT data_type FROM information_schema.columns
WHERE table_schema='application' AND table_name='user_behavior_events' AND column_name='received_at';
SELECT count(*) AS duplicate_ids FROM (SELECT id FROM user_behavior_events GROUP BY id HAVING count(*)>1) d;
SELECT (SELECT max(id) FROM user_behavior_events) AS max_id,
       last_value + CASE WHEN is_called THEN 1 ELSE 0 END AS next_id
FROM application.user_behavior_events_id_seq;
