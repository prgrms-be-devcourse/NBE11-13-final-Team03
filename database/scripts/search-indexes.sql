-- 통합 검색(GET /api/opportunities) 성능 인덱스를 추가한다. application_user로 한 번 실행한다.
-- 기존 테이블·데이터·인덱스를 바꾸지 않고 인덱스 3개만 더한다. 반복 실행해도 된다 (IF NOT EXISTS).
--
-- CREATE INDEX CONCURRENTLY는 트랜잭션 안에서 실행할 수 없다. 그래서 BEGIN/COMMIT 없이,
-- 문장마다 따로 커밋되는 자동 커밋 모드로 실행한다. 서비스를 멈출 필요는 없다.
--
-- 실행 대상: primary(쓰기) DB 한 곳뿐이다. 복제본은 primary의 인덱스를 스트리밍 복제로 자동 반영한다.
-- 실행 (노트북 저장소 루트에서, 테이블 소유자 application_user로):
--   { echo 'SET ROLE application_user;'; cat database/scripts/search-indexes.sql; } \
--     | ssh ubuntu@<DB 서버> 'cd ~/yon-db && docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1'
--
-- 실행 후 반드시 search-indexes-check.sql로 세 인덱스가 valid·ready인지 확인한다.
-- CONCURRENTLY는 실패해도 INVALID 인덱스를 남기며, 그 상태에서 이 파일을 다시 실행하면
-- IF NOT EXISTS가 이름만 보고 건너뛰어 INVALID가 그대로 남는다. 아래 사전 확인이 그 경우를
-- 조용히 넘어가지 않고 멈춘다. 멈추면 rollback-search-indexes.sql로 지운 뒤 다시 실행한다.
--
-- 엔티티를 바꾸지 않는 인덱스 추가라서 ddl-auto:update와 겹치지 않는다.
-- 서버 배포 순서와 무관하게 적용할 수 있고 서버를 멈출 필요도 없다.
--
-- lock_timeout은 마지막 ANALYZE에도 적용된다. 인덱스는 만들어졌는데 ANALYZE만 lock timeout으로
-- 실패하면, 점검으로 valid를 확인한 뒤 ANALYZE 세 줄만 다시 실행하면 된다.

SET search_path TO application, pg_catalog;
SET lock_timeout = '5s';

DO $$
BEGIN
    IF current_user <> 'application_user' THEN
        RAISE EXCEPTION 'Run search-indexes.sql as application_user';
    END IF;
END $$;

-- CREATE INDEX IF NOT EXISTS는 이름만 본다. 그래서 두 가지를 조용히 넘어간다.
--   (가) 같은 이름의 다른 정의 인덱스가 이미 있는 경우 -> 의도한 인덱스가 끝내 안 생긴다
--   (나) 이전 실행이 실패해 남긴 INVALID 인덱스 -> 플래너는 안 쓰는데 쓰기 비용만 든다
-- 아래에서 둘 다 멈춘다. 비교 기준은 PostgreSQL 16이 출력하는 pg_get_indexdef 정규화 형태다.
-- 메이저 버전이 바뀌어 표기만 달라져도 멈추지만, 기대값과 실제값을 함께 출력하므로 바로 판단할 수 있다.
DO $$
DECLARE
    want record;
    cur  record;   -- 이름을 found 로 두면 PL/pgSQL 내장 변수 FOUND 를 가린다
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
        SELECT pg_get_indexdef(c.oid) AS def, i.indisvalid, i.indisready
          INTO cur
          FROM pg_class c
          JOIN pg_index i ON i.indexrelid = c.oid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'application' AND c.relname = want.index_name;

        IF NOT FOUND THEN
            CONTINUE;   -- 아직 없다. 아래에서 새로 만든다
        END IF;

        IF cur.def IS DISTINCT FROM want.expected_def THEN
            RAISE EXCEPTION
                'Index % already exists with a different definition. Check it before applying. expected=[%] actual=[%]',
                want.index_name, want.expected_def, cur.def;
        END IF;

        IF NOT (cur.indisvalid AND cur.indisready) THEN
            RAISE EXCEPTION
                'INVALID index left by a previous run: %. Run rollback-search-indexes.sql first.', want.index_name;
        END IF;
    END LOOP;
END $$;

-- 1. 청년정책 지역 검색의 전국 대상 판정.
--    opportunity_conditions 183,598행 중 조건에 맞는 911행만 색인한다 (실험 DB 기준 40 kB).
--    jsonb_extract_path_text는 IMMUTABLE이라 부분 인덱스 조건에 쓸 수 있다.
--    OpportunitySearchService.residenceOrNational / policyVersionInRegion 의 NATIONAL EXISTS가 사용한다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_opportunity_conditions_nationwide
    ON opportunity_conditions (version_id)
    WHERE condition_type = 'REGION'
      AND condition_field = 'residenceRegion'
      AND jsonb_extract_path_text(expression, 'status') = 'NATIONAL';

-- 2. 모집 상태(OPEN/UPCOMING/CLOSED) 판정과 마감일 우선 정렬.
--    application_windows 142,652행 중 VALID+FIXED 2,555행만 색인한다 (실험 DB 기준 96 kB).
--    ApplicationStatusSpecifications 와 OpportunitySearchService.orderByDeadlinePriority 가 사용한다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_application_windows_active
    ON application_windows (version_id, start_date, end_date)
    WHERE validation_status = 'VALID'
      AND period_type = 'FIXED';

-- 3. 유형 필터 + 기본 정렬(LATEST).
--    opportunity_type 조건과 created_at DESC, id DESC 정렬을 함께 제공해 공고 전수 스캔과 Sort를 없앤다.
--    실험 DB 기준 8 MB로 셋 중 가장 크고, INSERT 비용을 약 50% 늘린다 (20,000행 복제 테이블 기준).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_opportunities_type_latest
    ON opportunities (opportunity_type, created_at DESC, id DESC);

-- 플래너가 새 인덱스를 반영하도록 통계를 갱신한다.
ANALYZE opportunity_conditions;
ANALYZE application_windows;
ANALYZE opportunities;
