-- 검색 제목 생성 컬럼(search-title-columns.sql)의 적용 결과를 확인한다. 읽기 전용이며 아무것도 바꾸지 않는다.
-- application_user로 실행한다. 반복 실행해도 된다.
--
-- primary에서 실행한다. 복제본에서도 같은 결과가 나오지만(복제로 반영됨) 기준은 primary다.
--   { echo 'SET ROLE application_user;'; cat database/scripts/search-title-columns-check.sql; } \
--     | ssh yon-db 'cd ~/yon-db && docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1'
--
-- 3번은 공고 전체를 한 번 읽는다 (140,657건 기준 1초 안팎). 부하가 적은 시간에 실행한다.

SET client_encoding = 'UTF8';
SET search_path TO application, pg_catalog;
SET statement_timeout = '120s';

\echo ''
\echo '=== 1. 두 컬럼의 정의 (generated=s 가 생성 컬럼이다. 롤백 후에는 0행이어야 정상) ==='
SELECT a.attname                               AS column_name,
       a.attgenerated                          AS generated,
       format_type(a.atttypid, a.atttypmod)    AS data_type,
       pg_get_expr(d.adbin, d.adrelid)         AS expression
FROM pg_attribute a
LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
WHERE a.attrelid = 'application.opportunities'::regclass
  AND a.attname IN ('search_title_norm', 'search_title_strip')
  AND NOT a.attisdropped
ORDER BY a.attname;

\echo ''
\echo '=== 2. 두 컬럼이 기대한 생성 컬럼인지 한 줄로 판정 ==='
\echo '    (일반 컬럼으로 나오면 Hibernate ddl-auto:update가 먼저 만든 것이다. 자동으로 고치지 말고 배포 순서를 확인한다)'
SELECT count(*) FILTER (WHERE a.attgenerated = 's'
                          AND format_type(a.atttypid, a.atttypmod) = 'text'
                          AND pg_get_expr(d.adbin, d.adrelid) = CASE a.attname
                                WHEN 'search_title_norm'
                                    THEN 'lower(NORMALIZE(title, NFKC))'
                                ELSE 'regexp_replace(lower(NORMALIZE(title, NFKC)), ''[\s·・‧ᆞ\-]''::text, ''''::text, ''g''::text)'
                              END)                                  AS ok_count,
       2                                                            AS expected,
       CASE
           WHEN count(*) FILTER (WHERE a.attgenerated <> 's') > 0
               THEN 'CHECK: 일반 컬럼이 있다. search-title-columns.sql 상단의 배포 순서 설명 확인'
           WHEN count(*) FILTER (WHERE a.attgenerated = 's'
                                   AND format_type(a.atttypid, a.atttypmod) = 'text'
                                   AND pg_get_expr(d.adbin, d.adrelid) = CASE a.attname
                                         WHEN 'search_title_norm'
                                             THEN 'lower(NORMALIZE(title, NFKC))'
                                         ELSE 'regexp_replace(lower(NORMALIZE(title, NFKC)), ''[\s·・‧ᆞ\-]''::text, ''''::text, ''g''::text)'
                                       END) = 2
               THEN 'OK: 두 생성 컬럼 모두 기대한 정의'
           ELSE 'CHECK: 누락이거나 정의가 다르다. 1번 확인' END     AS verdict
FROM pg_attribute a
LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
WHERE a.attrelid = 'application.opportunities'::regclass
  AND a.attname IN ('search_title_norm', 'search_title_strip')
  AND NOT a.attisdropped;

\echo ''
\echo '=== 3. 저장된 값이 정규화 식과 같은지 (mismatch·null_count 모두 0이어야 정상) ==='
-- 컬럼이 없으면 아래 SELECT가 구문 오류로 멈추므로, 두 생성 컬럼이 있을 때만 실행한다.
SELECT count(*) = 2 AS has_generated_columns
FROM pg_attribute
WHERE attrelid = 'application.opportunities'::regclass
  AND attname IN ('search_title_norm', 'search_title_strip')
  AND attgenerated = 's' AND NOT attisdropped \gset
\if :has_generated_columns
SELECT count(*)                                                                       AS total,
       count(*) FILTER (WHERE search_title_norm IS DISTINCT FROM lower(normalize(title, NFKC)))
                                                                                      AS norm_mismatch,
       count(*) FILTER (WHERE search_title_strip IS DISTINCT FROM
                              regexp_replace(lower(normalize(title, NFKC)), '[\s·・‧ᆞ\-]', '', 'g'))
                                                                                      AS strip_mismatch,
       count(*) FILTER (WHERE search_title_norm IS NULL OR search_title_strip IS NULL) AS null_count
FROM opportunities;
\else
\echo '    건너뜀: 생성 컬럼 2개가 없다 (2번 판정 확인)'
\endif

\echo ''
\echo '=== 4. opportunities 크기 (컬럼 추가 전 대비 heap 약 +36%가 예상 범위다) ==='
SELECT pg_size_pretty(pg_relation_size('application.opportunities'))       AS heap,
       pg_size_pretty(pg_total_relation_size('application.opportunities')) AS total_with_indexes;
