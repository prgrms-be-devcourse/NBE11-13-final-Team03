-- 통합 검색 성능 인덱스(search-indexes.sql)의 적용 결과를 확인한다. 읽기 전용이며 아무것도 바꾸지 않는다.
-- application_user로 실행한다. 반복 실행해도 된다.
--
-- 실행 (DB 서버 ~/yon-db, application_user로):
--   { echo 'SET ROLE application_user;'; cat sql/search-indexes-check.sql; } | docker compose exec -T postgres-db psql -U postgres -d yon
--
-- 마지막 두 EXPLAIN은 실제 검색이 만드는 SQL을 그대로 실행한다 (ANALYZE라 질의가 실제로 돈다).
-- SELECT뿐이라 데이터는 바뀌지 않지만 S11은 수 초가 걸릴 수 있다. 부하가 적은 시간에 실행한다.
-- statement_timeout으로 상한을 둔다. 여기서 끊기면 인덱스 문제가 아니라 질의가 느린 것이므로
-- 1~5번 결과로 판정하고 필요하면 더 한가한 시간에 다시 실행한다.

SET search_path TO application, pg_catalog;
SET statement_timeout = '120s';

-- 검색 API는 KST 기준 오늘 날짜를 넘긴다. 아래 6번 대표 질의의 CURRENT_DATE를 같은 기준으로 맞춘다.
-- DB 서버 기본 시간대가 무엇이든 동일하게 평가되도록 세션에서 명시한다.
SET TIME ZONE 'Asia/Seoul';

\echo ''
\echo '=== 1. 세 인덱스의 존재 / valid / ready / 정의 / 크기 ==='
\echo '    (indisvalid=f 또는 indisready=f 이면 CONCURRENTLY가 실패한 것이다. 롤백 후 재생성한다)'
SELECT c.relname                                   AS index_name,
       i.indisvalid                                AS is_valid,
       i.indisready                                AS is_ready,
       pg_size_pretty(pg_relation_size(c.oid))     AS size,
       pg_get_indexdef(c.oid)                      AS definition
FROM pg_class c
JOIN pg_index i ON i.indexrelid = c.oid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'application'
  AND c.relname IN ('idx_opportunity_conditions_nationwide',
                    'idx_application_windows_active',
                    'idx_opportunities_type_latest')
ORDER BY c.relname;

\echo ''
\echo '=== 2. 세 인덱스가 모두 정상인지 한 줄로 판정 ==='
SELECT count(*) FILTER (WHERE i.indisvalid AND i.indisready)                AS ok_count,
       3                                                                   AS expected,
       CASE WHEN count(*) FILTER (WHERE i.indisvalid AND i.indisready) = 3
            THEN 'OK: 세 인덱스 모두 valid+ready'
            ELSE 'CHECK: 누락이거나 INVALID. 아래 3번과 rollback-search-indexes.sql 확인' END AS verdict
FROM pg_class c
JOIN pg_index i ON i.indexrelid = c.oid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'application'
  AND c.relname IN ('idx_opportunity_conditions_nationwide',
                    'idx_application_windows_active',
                    'idx_opportunities_type_latest');

\echo ''
\echo '=== 3. application 스키마의 INVALID / NOT READY 인덱스 전체 (0행이어야 정상) ==='
SELECT c.relname AS index_name, t.relname AS table_name, i.indisvalid, i.indisready
FROM pg_index i
JOIN pg_class c ON c.oid = i.indexrelid
JOIN pg_class t ON t.oid = i.indrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'application' AND (NOT i.indisvalid OR NOT i.indisready)
ORDER BY c.relname;

\echo ''
\echo '=== 4. 인덱스 사용 통계 (idx_scan이 0이면 아직 쓰이지 않은 것이다) ==='
\echo '    통계는 마지막 pg_stat_reset 이후 누적이다. 적용 직후에는 0이 정상이다'
SELECT relname AS table_name, indexrelname AS index_name,
       idx_scan, idx_tup_read, idx_tup_fetch
FROM pg_stat_user_indexes
WHERE schemaname = 'application'
  AND indexrelname IN ('idx_opportunity_conditions_nationwide',
                       'idx_application_windows_active',
                       'idx_opportunities_type_latest')
ORDER BY indexrelname;

\echo ''
\echo '=== 5. 대상 테이블의 전체 인덱스 (기존 인덱스가 그대로인지 확인) ==='
SELECT tablename, indexname, pg_size_pretty(pg_relation_size(schemaname||'.'||indexname)) AS size
FROM pg_indexes
WHERE schemaname = 'application'
  AND tablename IN ('opportunities', 'opportunity_conditions', 'application_windows')
ORDER BY tablename, indexname;

\echo ''
\echo '=== 6. 대표 검색 실행 계획 — 실제 Hibernate SQL 그대로 ==='
\echo '    기대: idx_opportunity_conditions_nationwide / idx_application_windows_active /'
\echo '          idx_opportunities_type_latest 가 Index Scan 또는 Index Only Scan 으로 나타난다'
\echo '    주의: 원본 SQL의 날짜 리터럴만 CURRENT_DATE로 바꿨다. 애플리케이션은 KST 기준 오늘을 넘긴다'
\echo ''
-- S9: 지역(서울) + 모집상태 OPEN, 유형 미지정
EXPLAIN (ANALYZE, BUFFERS)
select o1_0.id,o1_0.category,o1_0.created_at,o1_0.current_version_id,o1_0.detail_category,o1_0.detail_url,o1_0.opportunity_type,o1_0.sub_category,o1_0.title,o1_0.updated_at from opportunities o1_0 where o1_0.current_version_id is not null and (o1_0.opportunity_type='YOUTH_POLICY' and (exists(select or1_0.id from opportunity_regions or1_0 where or1_0.version_id=o1_0.current_version_id and or1_0.region_role='RESIDENCE' and or1_0.region_code in ('1100000000','1111000000','1114000000','1117000000','1120000000','1121500000','1123000000','1126000000','1129000000','1130500000','1132000000','1135000000','1138000000','1141000000','1144000000','1147000000','1150000000','1153000000','1154500000','1156000000','1159000000','1162000000','1165000000','1168000000','1171000000','1174000000')) or exists(select oc1_0.id from opportunity_conditions oc1_0 where oc1_0.version_id=o1_0.current_version_id and oc1_0.condition_type='REGION' and oc1_0.condition_field='residenceRegion' and jsonb_extract_path_text(oc1_0.expression,'status')='NATIONAL')) or o1_0.opportunity_type='VOCATIONAL_TRAINING' and exists(select or2_0.id from opportunity_regions or2_0 where or2_0.version_id=o1_0.current_version_id and or2_0.region_role='IMPLEMENTATION' and or2_0.region_code in ('1100000000','1111000000','1114000000','1117000000','1120000000','1121500000','1123000000','1126000000','1129000000','1130500000','1132000000','1135000000','1138000000','1141000000','1144000000','1147000000','1150000000','1153000000','1154500000','1156000000','1159000000','1162000000','1165000000','1168000000','1171000000','1174000000'))) and (o1_0.opportunity_type='YOUTH_POLICY' and exists(select aw1_0.id from application_windows aw1_0 where aw1_0.version_id=o1_0.current_version_id and aw1_0.validation_status='VALID' and aw1_0.period_type='FIXED' and (aw1_0.start_date is not null or aw1_0.end_date is not null) and (aw1_0.start_date is null or aw1_0.start_date<=CURRENT_DATE) and (aw1_0.end_date is null or aw1_0.end_date>=CURRENT_DATE)) or o1_0.opportunity_type='VOCATIONAL_TRAINING' and exists(select tcd1_0.id from training_course_details tcd1_0 where tcd1_0.version_id=o1_0.current_version_id and tcd1_0.training_start_date is not null and tcd1_0.training_start_date>=CURRENT_DATE)) order by o1_0.created_at desc,o1_0.id desc offset 0 rows fetch first 20 rows only;

-- S11: 청년정책 + 서울 + 마감일 우선 정렬
EXPLAIN (ANALYZE, BUFFERS)
select o1_0.id,o1_0.category,o1_0.created_at,o1_0.current_version_id,o1_0.detail_category,o1_0.detail_url,o1_0.opportunity_type,o1_0.sub_category,o1_0.title,o1_0.updated_at from opportunities o1_0 where o1_0.current_version_id is not null and o1_0.opportunity_type='YOUTH_POLICY' and o1_0.current_version_id in ((select ov1_0.id from opportunity_versions ov1_0 where exists(select or1_0.id from opportunity_regions or1_0 where or1_0.version_id=ov1_0.id and or1_0.region_role='RESIDENCE' and or1_0.region_code in ('1100000000','1111000000','1114000000','1117000000','1120000000','1121500000','1123000000','1126000000','1129000000','1130500000','1132000000','1135000000','1138000000','1141000000','1144000000','1147000000','1150000000','1153000000','1154500000','1156000000','1159000000','1162000000','1165000000','1168000000','1171000000','1174000000')) or exists(select oc1_0.id from opportunity_conditions oc1_0 where oc1_0.version_id=ov1_0.id and oc1_0.condition_type='REGION' and oc1_0.condition_field='residenceRegion' and jsonb_extract_path_text(oc1_0.expression,'status')='NATIONAL'))) and 1=1 order by case when exists(select aw1_0.id from application_windows aw1_0 where aw1_0.version_id=o1_0.current_version_id and aw1_0.validation_status='VALID' and aw1_0.period_type='FIXED' and (aw1_0.start_date is not null or aw1_0.end_date is not null) and (aw1_0.start_date is null or aw1_0.start_date<=CURRENT_DATE) and (aw1_0.end_date is null or aw1_0.end_date>=CURRENT_DATE)) then 0 when not exists(select aw2_0.id from application_windows aw2_0 where aw2_0.version_id=o1_0.current_version_id and aw2_0.validation_status='VALID' and aw2_0.period_type='FIXED' and (aw2_0.start_date is not null or aw2_0.end_date is not null) and (aw2_0.start_date is null or aw2_0.start_date<=CURRENT_DATE) and (aw2_0.end_date is null or aw2_0.end_date>=CURRENT_DATE)) and not exists(select aw3_0.id from application_windows aw3_0 where aw3_0.version_id=o1_0.current_version_id and aw3_0.validation_status='VALID' and aw3_0.period_type='ALWAYS') and exists(select aw4_0.id from application_windows aw4_0 where aw4_0.version_id=o1_0.current_version_id and aw4_0.validation_status='VALID' and aw4_0.period_type='FIXED' and aw4_0.start_date is not null and aw4_0.start_date>CURRENT_DATE) then 1 when not exists(select aw5_0.id from application_windows aw5_0 where aw5_0.version_id=o1_0.current_version_id and aw5_0.validation_status='VALID' and aw5_0.period_type='FIXED' and (aw5_0.start_date is not null or aw5_0.end_date is not null) and (aw5_0.start_date is null or aw5_0.start_date<=CURRENT_DATE) and (aw5_0.end_date is null or aw5_0.end_date>=CURRENT_DATE)) and exists(select aw6_0.id from application_windows aw6_0 where aw6_0.version_id=o1_0.current_version_id and aw6_0.validation_status='VALID' and aw6_0.period_type='ALWAYS') then 2 when not exists(select aw7_0.id from application_windows aw7_0 where aw7_0.version_id=o1_0.current_version_id and aw7_0.validation_status='VALID' and aw7_0.period_type='FIXED' and (aw7_0.start_date is not null or aw7_0.end_date is not null) and (aw7_0.start_date is null or aw7_0.start_date<=CURRENT_DATE) and (aw7_0.end_date is null or aw7_0.end_date>=CURRENT_DATE)) and not exists(select aw8_0.id from application_windows aw8_0 where aw8_0.version_id=o1_0.current_version_id and aw8_0.validation_status='VALID' and aw8_0.period_type='ALWAYS') and not exists(select aw9_0.id from application_windows aw9_0 where aw9_0.version_id=o1_0.current_version_id and aw9_0.validation_status='VALID' and aw9_0.period_type='FIXED' and aw9_0.start_date is not null and aw9_0.start_date>CURRENT_DATE) and exists(select aw10_0.id from application_windows aw10_0 where aw10_0.version_id=o1_0.current_version_id and aw10_0.validation_status='VALID' and (aw10_0.period_type='CLOSED' or aw10_0.period_type='FIXED' and aw10_0.end_date is not null and aw10_0.end_date<CURRENT_DATE and (aw10_0.start_date is null or aw10_0.start_date<=CURRENT_DATE))) then 3 else 4 end,case when exists(select aw11_0.id from application_windows aw11_0 where aw11_0.version_id=o1_0.current_version_id and aw11_0.validation_status='VALID' and aw11_0.period_type='FIXED' and (aw11_0.start_date is not null or aw11_0.end_date is not null) and (aw11_0.start_date is null or aw11_0.start_date<=CURRENT_DATE) and (aw11_0.end_date is null or aw11_0.end_date>=CURRENT_DATE)) then (select min(aw12_0.end_date) from application_windows aw12_0 where aw12_0.version_id=o1_0.current_version_id and aw12_0.validation_status='VALID' and aw12_0.period_type='FIXED' and (aw12_0.start_date is null or aw12_0.start_date<=CURRENT_DATE) and aw12_0.end_date>=CURRENT_DATE) when not exists(select aw13_0.id from application_windows aw13_0 where aw13_0.version_id=o1_0.current_version_id and aw13_0.validation_status='VALID' and aw13_0.period_type='FIXED' and (aw13_0.start_date is not null or aw13_0.end_date is not null) and (aw13_0.start_date is null or aw13_0.start_date<=CURRENT_DATE) and (aw13_0.end_date is null or aw13_0.end_date>=CURRENT_DATE)) and not exists(select aw14_0.id from application_windows aw14_0 where aw14_0.version_id=o1_0.current_version_id and aw14_0.validation_status='VALID' and aw14_0.period_type='ALWAYS') and exists(select aw15_0.id from application_windows aw15_0 where aw15_0.version_id=o1_0.current_version_id and aw15_0.validation_status='VALID' and aw15_0.period_type='FIXED' and aw15_0.start_date is not null and aw15_0.start_date>CURRENT_DATE) then (select min(aw16_0.end_date) from application_windows aw16_0 where aw16_0.version_id=o1_0.current_version_id and aw16_0.validation_status='VALID' and aw16_0.period_type='FIXED' and aw16_0.start_date>CURRENT_DATE and aw16_0.end_date>=CURRENT_DATE) else null end,case when not exists(select aw17_0.id from application_windows aw17_0 where aw17_0.version_id=o1_0.current_version_id and aw17_0.validation_status='VALID' and aw17_0.period_type='FIXED' and (aw17_0.start_date is not null or aw17_0.end_date is not null) and (aw17_0.start_date is null or aw17_0.start_date<=CURRENT_DATE) and (aw17_0.end_date is null or aw17_0.end_date>=CURRENT_DATE)) and not exists(select aw18_0.id from application_windows aw18_0 where aw18_0.version_id=o1_0.current_version_id and aw18_0.validation_status='VALID' and aw18_0.period_type='ALWAYS') and not exists(select aw19_0.id from application_windows aw19_0 where aw19_0.version_id=o1_0.current_version_id and aw19_0.validation_status='VALID' and aw19_0.period_type='FIXED' and aw19_0.start_date is not null and aw19_0.start_date>CURRENT_DATE) and exists(select aw20_0.id from application_windows aw20_0 where aw20_0.version_id=o1_0.current_version_id and aw20_0.validation_status='VALID' and (aw20_0.period_type='CLOSED' or aw20_0.period_type='FIXED' and aw20_0.end_date is not null and aw20_0.end_date<CURRENT_DATE and (aw20_0.start_date is null or aw20_0.start_date<=CURRENT_DATE))) then (select max(aw21_0.end_date) from application_windows aw21_0 where aw21_0.version_id=o1_0.current_version_id and aw21_0.validation_status='VALID' and aw21_0.period_type='FIXED' and (aw21_0.start_date is null or aw21_0.start_date<=CURRENT_DATE) and aw21_0.end_date<CURRENT_DATE) else null end desc nulls last,o1_0.id desc offset 0 rows fetch first 20 rows only;
