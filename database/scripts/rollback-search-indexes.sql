-- search-indexes.sql이 만든 검색 인덱스 3개를 제거한다. application_user로 실행한다.
-- 이 파일이 만들지 않은 인덱스는 건드리지 않는다. 기존 인덱스와 데이터는 그대로 남는다.
-- 반복 실행해도 된다 (IF EXISTS).
--
-- DROP INDEX CONCURRENTLY도 트랜잭션 안에서 실행할 수 없다. BEGIN/COMMIT 없이 실행한다.
-- 서비스를 멈출 필요는 없다.
--
-- 실행 (DB 서버 ~/yon-db, 테이블 소유자 application_user로):
--   { echo 'SET ROLE application_user;'; cat sql/rollback-search-indexes.sql; } | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
--
-- CONCURRENTLY 생성이 실패해 INVALID 인덱스가 남은 경우에도 이 파일로 지운다.
-- 제거 후 search-indexes-check.sql의 1·3번을 다시 실행해 0행인지 확인한다.

SET search_path TO application, pg_catalog;
SET lock_timeout = '5s';

DO $$
BEGIN
    IF current_user <> 'application_user' THEN
        RAISE EXCEPTION 'Run rollback-search-indexes.sql as application_user';
    END IF;
END $$;

-- 같은 이름의 인덱스라도 정의가 다르면 삭제를 중단한다.
-- 정의가 동일한 인덱스는 INVALID 상태여도 삭제할 수 있으므로, 롤백 전 이번 작업의 적용 이력을 확인한다.
-- (CONCURRENTLY는 DO 블록 안에서 쓸 수 없어 확인만 여기서 하고, 삭제는 아래 문장이 한다.)
DO $$
DECLARE
    want    record;
    cur_def text;   -- 이름을 found 로 두면 PL/pgSQL 내장 변수 FOUND 를 가린다
BEGIN
    FOR want IN
        SELECT * FROM (VALUES
            ('idx_opportunity_conditions_nationwide',
             'CREATE INDEX idx_opportunity_conditions_nationwide ON application.opportunity_conditions USING btree (version_id) WHERE (((condition_type)::text = ''REGION''::text) AND ((condition_field)::text = ''residenceRegion''::text) AND (jsonb_extract_path_text(expression, VARIADIC ARRAY[''status''::text]) = ''NATIONAL''::text))'),
            ('idx_application_windows_active',
             'CREATE INDEX idx_application_windows_active ON application.application_windows USING btree (version_id, start_date, end_date) WHERE (((validation_status)::text = ''VALID''::text) AND ((period_type)::text = ''FIXED''::text))'),
            ('idx_opportunities_type_latest',
             'CREATE INDEX idx_opportunities_type_latest ON application.opportunities USING btree (opportunity_type, created_at DESC, id DESC)')
        ) AS t(index_name, expected_def)
    LOOP
        SELECT pg_get_indexdef(c.oid)
          INTO cur_def
          FROM pg_class c
          JOIN pg_index i ON i.indexrelid = c.oid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'application' AND c.relname = want.index_name;

        IF FOUND AND cur_def IS DISTINCT FROM want.expected_def THEN
            RAISE EXCEPTION
                'Index % was not created by search-indexes.sql. Refusing to drop it. expected=[%] actual=[%]',
                want.index_name, want.expected_def, cur_def;
        END IF;
    END LOOP;
END $$;

DROP INDEX CONCURRENTLY IF EXISTS idx_opportunities_type_latest;
DROP INDEX CONCURRENTLY IF EXISTS idx_application_windows_active;
DROP INDEX CONCURRENTLY IF EXISTS idx_opportunity_conditions_nationwide;

-- 인덱스가 사라진 상태로 플래너 통계를 되돌린다.
ANALYZE opportunities;
ANALYZE application_windows;
ANALYZE opportunity_conditions;
