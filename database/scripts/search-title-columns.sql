-- 통합 검색 제목 정규화용 생성 컬럼 2개를 opportunities에 추가한다. application_user로 한 번 실행한다.
-- 기존 컬럼·데이터·인덱스는 바꾸지 않는다. 반복 실행해도 된다 (이미 같은 정의로 있으면 건너뛴다).
--
--   search_title_norm  = lower(normalize(title, NFKC))
--   search_title_strip = search_title_norm에서 공백·가운뎃점(·・‧ᆞ)·하이픈을 지운 값
--
-- 검색이 행마다 반복 계산하던 제목 정규화를 저장 시점으로 옮긴다. 값은 DB가 계산하며
-- backend Opportunity 엔티티는 읽기 전용(insertable=false, updatable=false)으로 매핑한다.
--
-- 반드시 backend 배포 전에 실행한다.
--   backend는 ddl-auto:update라서, 이 스크립트 없이 새 엔티티가 먼저 뜨면 Hibernate가 같은 이름의
--   일반 컬럼(NULL로 채워진 text)을 만든다. 그 상태에서 이 파일을 실행하면 아래 사전 확인이 멈춘다.
--   일반 컬럼을 자동으로 지우고 바꾸지 않는다. 멈추면 원인(배포 순서)을 확인한 뒤 담당자가 판단한다.
-- data-service 엔티티는 이 두 컬럼을 매핑하지 않는다. 생성 컬럼은 INSERT/UPDATE 컬럼 목록에 없으면
-- DB가 계산하므로 data-service 쓰기는 바뀌지 않는다.
--
-- 잠금과 시간: STORED 생성 컬럼 추가는 테이블을 다시 쓴다 (ACCESS EXCLUSIVE).
--   공고 140,657건 복원본 기준 ALTER 1.7초, heap 약 +18 MB (+36%). 그동안 공고 읽기·쓰기가 대기한다.
--   수집·정규화 배치가 돌지 않는 시간에 실행한다. lock_timeout 5초 안에 잠금을 못 얻으면 아무것도
--   바뀌지 않은 채 실패하므로, 한가한 시간에 다시 실행한다.
--
-- 실행 대상: primary(쓰기) DB 한 곳뿐이다. 복제본은 스트리밍 복제로 자동 반영된다.
-- 실행 (노트북 저장소 루트에서, 테이블 소유자 application_user로):
--   { echo 'SET ROLE application_user;'; cat database/scripts/search-title-columns.sql; } \
--     | ssh yon-db 'cd ~/yon-db && docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1'
--
-- 실행 후 search-title-columns-check.sql로 정의와 값을 확인한다.
-- 되돌릴 때는 backend를 이 컬럼 매핑이 없는 버전으로 먼저 되돌린 뒤 rollback-search-title-columns.sql을 실행한다.

SET client_encoding = 'UTF8';
SET search_path TO application, pg_catalog;
SET lock_timeout = '5s';

DO $$
BEGIN
    IF current_user <> 'application_user' THEN
        RAISE EXCEPTION 'Run search-title-columns.sql as application_user';
    END IF;
    -- normalize()는 UTF8 서버 인코딩에서만 동작한다.
    IF current_setting('server_encoding') <> 'UTF8' THEN
        RAISE EXCEPTION 'search-title-columns.sql requires UTF8 server_encoding. actual=%',
            current_setting('server_encoding');
    END IF;
END $$;

BEGIN;

-- ADD COLUMN IF NOT EXISTS는 이름만 본다. 그래서 두 가지를 조용히 넘어간다.
--   (가) Hibernate ddl-auto:update가 먼저 만든 같은 이름의 일반 컬럼 -> 값이 영영 NULL로 남는다
--   (나) 다른 식으로 만든 같은 이름의 생성 컬럼 -> 검색이 기대와 다른 값으로 비교한다
-- 아래에서 둘 다 멈춘다. 비교 기준은 PostgreSQL 16이 출력하는 pg_get_expr 정규화 형태다.
-- 메이저 버전이 바뀌어 표기만 달라져도 멈추지만, 기대값과 실제값을 함께 출력하므로 바로 판단할 수 있다.
-- 두 컬럼이 모두 없으면 한 ALTER로 같이 추가해 테이블 재작성을 한 번만 한다.
DO $$
DECLARE
    want    record;
    cur     record;   -- 이름을 found 로 두면 PL/pgSQL 내장 변수 FOUND 를 가린다
    missing text[] := '{}';
BEGIN
    FOR want IN
        SELECT * FROM (VALUES
            ('search_title_norm',
             'lower(normalize(title, NFKC))',
             'lower(NORMALIZE(title, NFKC))'),
            ('search_title_strip',
             'regexp_replace(lower(normalize(title, NFKC)), ''[\s·・‧ᆞ\-]'', '''', ''g'')',
             'regexp_replace(lower(NORMALIZE(title, NFKC)), ''[\s·・‧ᆞ\-]''::text, ''''::text, ''g''::text)')
        ) AS t(column_name, ddl_expr, expected_expr)
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

        IF NOT FOUND THEN
            missing := missing || format('ADD COLUMN %I text GENERATED ALWAYS AS (%s) STORED',
                                         want.column_name, want.ddl_expr);
            CONTINUE;
        END IF;

        IF cur.attgenerated <> 's' THEN
            RAISE EXCEPTION
                'Column opportunities.% already exists as a regular column (type %), not a generated column. It was probably created by Hibernate ddl-auto:update before this script ran. Not dropping it automatically. Check the deploy order before applying.',
                want.column_name, cur.data_type;
        END IF;

        IF cur.data_type <> 'text' OR cur.expr IS DISTINCT FROM want.expected_expr THEN
            RAISE EXCEPTION
                'Generated column opportunities.% already exists with a different definition. Check it before applying. expected=[text %] actual=[% %]',
                want.column_name, want.expected_expr, cur.data_type, cur.expr;
        END IF;
    END LOOP;

    IF cardinality(missing) > 0 THEN
        EXECUTE 'ALTER TABLE application.opportunities ' || array_to_string(missing, ', ');
        RAISE NOTICE 'Added % generated column(s) to opportunities', cardinality(missing);
    ELSE
        RAISE NOTICE 'Generated columns already exist with the expected definition. Nothing to do.';
    END IF;
END $$;

COMMIT;

-- 새 컬럼 통계를 플래너에 반영한다. lock_timeout으로 ANALYZE만 실패하면 이 줄만 다시 실행한다.
ANALYZE opportunities;
