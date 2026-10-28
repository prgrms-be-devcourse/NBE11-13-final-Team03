-- search-title-columns.sql이 만든 생성 컬럼 2개(search_title_norm, search_title_strip)를 제거한다.
-- application_user로 실행한다. 다른 컬럼과 데이터는 그대로 남는다. 반복 실행해도 된다 (IF EXISTS).
--
-- 반드시 backend를 이 두 컬럼 매핑이 없는 버전으로 먼저 되돌린 뒤 실행한다.
--   매핑이 남은 backend는 공고를 읽을 때마다 두 컬럼을 SELECT하므로, 컬럼이 사라지면 공고 조회가 모두 실패한다.
--   또 ddl-auto:update로 재기동하면 같은 이름의 일반 컬럼을 다시 만든다.
-- data-service는 두 컬럼을 매핑하지 않으므로 순서와 무관하다.
--
-- DROP COLUMN은 테이블을 다시 쓰지 않아 금방 끝나지만 ACCESS EXCLUSIVE 잠금을 잠깐 잡는다.
-- 차지하던 공간은 이후 행이 갱신되거나 VACUUM FULL을 해야 돌아온다.
--
-- 실행 대상: primary(쓰기) DB 한 곳뿐이다. 복제본은 복제로 자동 반영된다.
-- 실행 (노트북 저장소 루트에서, 테이블 소유자 application_user로):
--   { echo 'SET ROLE application_user;'; cat database/scripts/rollback-search-title-columns.sql; } \
--     | ssh yon-db 'cd ~/yon-db && docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1'
--
-- 제거 후 search-title-columns-check.sql의 1번이 0행인지 확인한다.

SET client_encoding = 'UTF8';
SET search_path TO application, pg_catalog;
SET lock_timeout = '5s';

DO $$
BEGIN
    IF current_user <> 'application_user' THEN
        RAISE EXCEPTION 'Run rollback-search-title-columns.sql as application_user';
    END IF;
END $$;

BEGIN;

-- 같은 이름이라도 이 스크립트가 만든 정의가 아니면 삭제를 중단한다.
-- 특히 Hibernate가 만든 일반 컬럼은 이 파일이 지우지 않는다. 원인을 확인한 뒤 담당자가 판단한다.
DO $$
DECLARE
    want record;
    cur  record;   -- 이름을 found 로 두면 PL/pgSQL 내장 변수 FOUND 를 가린다
BEGIN
    FOR want IN
        SELECT * FROM (VALUES
            ('search_title_norm',
             'lower(NORMALIZE(title, NFKC))'),
            ('search_title_strip',
             'regexp_replace(lower(NORMALIZE(title, NFKC)), ''[\s·・‧ᆞ\-]''::text, ''''::text, ''g''::text)')
        ) AS t(column_name, expected_expr)
    LOOP
        SELECT a.attgenerated,
               format_type(a.atttypid, a.atttypmod) AS data_type,
               pg_get_expr(d.adbin, d.adrelid)      AS expr
          INTO cur
          FROM pg_attribute a
          LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
         WHERE a.attrelid = 'application.opportunities'::regclass
           AND a.attname = want.column_name
           AND NOT a.attisdropped;

        IF FOUND AND (cur.attgenerated <> 's' OR cur.data_type <> 'text'
                      OR cur.expr IS DISTINCT FROM want.expected_expr) THEN
            RAISE EXCEPTION
                'Column opportunities.% was not created by search-title-columns.sql. Refusing to drop it. expected=[generated text %] actual=[generated=% % %]',
                want.column_name, want.expected_expr, cur.attgenerated <> '', cur.data_type, cur.expr;
        END IF;
    END LOOP;
END $$;

ALTER TABLE opportunities
    DROP COLUMN IF EXISTS search_title_strip,
    DROP COLUMN IF EXISTS search_title_norm;

COMMIT;
