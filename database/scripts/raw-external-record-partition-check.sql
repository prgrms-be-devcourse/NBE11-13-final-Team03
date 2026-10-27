-- 원본 파티셔닝 전용 읽기 점검. 행동 로그 테이블을 요구하지 않는다.
-- application_user 또는 조회 권한이 있는 계정으로 psql -v ON_ERROR_STOP=1 -f 실행.
SET search_path TO application, pg_catalog;

-- relkind=p, LIST (source_id). 롤백 뒤에는 r.
SELECT relname, relkind, pg_get_partkeydef(oid) AS partition_key
FROM pg_class WHERE oid = 'raw_external_record'::regclass;

-- DEFAULT 0행, 소유자 application_user, 출처별 fillfactor=70.
SELECT child.relname, pg_get_expr(child.relpartbound, child.oid) AS bound,
       pg_get_userbyid(child.relowner) AS owner, child.reloptions,
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %s', child.oid::regclass),
                                          false, true, '')))[1]::text::bigint AS rows,
       pg_size_pretty(pg_total_relation_size(child.oid)) AS size
FROM pg_inherits i JOIN pg_class child ON child.oid = i.inhrelid
WHERE i.inhparent = 'raw_external_record'::regclass ORDER BY child.relname;

-- old와 건수 일치는 전환 직후만 기대한다. 서비스 재개 뒤에는 달라질 수 있다.
SELECT c.relname, pg_get_userbyid(c.relowner) AS owner,
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %s', c.oid::regclass),
                                          false, true, '')))[1]::text::bigint AS rows
FROM pg_class c WHERE c.relnamespace = 'application'::regnamespace
AND c.relname IN ('raw_external_record', 'raw_external_record_old') ORDER BY c.relname;

-- null_source=0, duplicate_ids=0. 롤백 후 옛 앱이 쓴 요청은 source_id가 NULL일 수 있다.
SELECT count(*) FILTER (WHERE source_id IS NULL) AS null_source FROM raw_normalization_request;
SELECT count(*) AS duplicate_ids FROM (SELECT id FROM raw_external_record GROUP BY id HAVING count(*) > 1) d;
SELECT conname, pg_get_constraintdef(oid) AS definition FROM pg_constraint
WHERE conrelid = 'raw_normalization_request'::regclass AND contype = 'f' AND conparentid = 0;

-- next_id > max_id (일반 IDENTITY increment=1 기준, 시퀀스 소비 없음).
SELECT (SELECT max(id) FROM raw_external_record) AS max_id,
       last_value + CASE WHEN is_called THEN 1 ELSE 0 END AS next_id
FROM application.raw_external_record_id_seq;
SELECT tgname, tgenabled FROM pg_trigger
WHERE tgrelid = 'raw_normalization_request'::regclass AND NOT tgisinternal;
