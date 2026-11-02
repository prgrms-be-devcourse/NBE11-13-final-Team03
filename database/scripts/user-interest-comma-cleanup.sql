-- #134 배포 후 기존 쉼표 관심 분야 정리. application 스키마의 검증된 대상 DB에서만 수동 실행한다.
-- 기본: 변경 전후 미리보기 후 ROLLBACK. 적용: psql -v apply=true -f <이 파일>
-- 적용 전 대상 행 백업·분류 백필 확인·관심 분야 변경 요청 중지를 완료한다.
\set ON_ERROR_STOP on
\if :{?apply}
\else
    \set apply false
\endif

BEGIN;
SET LOCAL search_path TO application, pg_catalog;
SET LOCAL lock_timeout = '5s';
LOCK TABLE user_interests IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE comma_interest_source ON COMMIT DROP AS
SELECT id, user_id, interest_type, interest_code, created_at
FROM user_interests WHERE interest_code LIKE '%,%';

CREATE TEMP TABLE comma_interest_parts ON COMMIT DROP AS
SELECT DISTINCT s.id AS source_id, s.user_id, s.interest_type,
       btrim(part) AS interest_code, s.created_at
FROM comma_interest_source s
CROSS JOIN LATERAL unnest(string_to_array(s.interest_code, ',')) AS part;

-- InterestOptionRepository와 같이 현재 버전 청년정책 분류 행 및 직업훈련 컬럼을 확인한다.
CREATE TEMP TABLE comma_interest_options ON COMMIT DROP AS
WITH classifications AS (
    SELECT c.large_category AS category, c.medium_category AS sub_category,
           NULL::varchar AS detail_category
    FROM opportunities o JOIN opportunity_categories c ON c.version_id = o.current_version_id
    WHERE o.opportunity_type = 'YOUTH_POLICY'
    UNION ALL
    SELECT category, sub_category, detail_category FROM opportunities
    WHERE opportunity_type = 'VOCATIONAL_TRAINING' AND current_version_id IS NOT NULL
)
SELECT DISTINCT v.interest_type, v.interest_code
FROM classifications c
CROSS JOIN LATERAL (VALUES
    ('CATEGORY', c.category),
    ('SUB_CATEGORY', c.sub_category),
    ('DETAIL_CATEGORY', CASE WHEN btrim(c.sub_category) <> '' THEN c.detail_category END)
) v(interest_type, interest_code)
WHERE btrim(c.category) <> '' AND btrim(v.interest_code) <> '';

CREATE TEMP TABLE comma_interest_counts ON COMMIT DROP AS
SELECT user_id, count(*) AS resulting_count FROM (
    SELECT user_id, interest_type, interest_code FROM user_interests
    WHERE interest_code NOT LIKE '%,%'
    UNION
    SELECT user_id, interest_type, interest_code FROM comma_interest_parts
) final_interests GROUP BY user_id;

\echo '원본 행과 분리 결과. 원본 created_at은 새 행에도 유지한다.'
SELECT s.id, s.user_id, s.interest_type, s.interest_code AS original_code,
       p.interest_code AS split_code,
       EXISTS (SELECT 1 FROM comma_interest_options o
               WHERE o.interest_type = p.interest_type AND o.interest_code = p.interest_code) AS selectable
FROM comma_interest_source s JOIN comma_interest_parts p ON p.source_id = s.id
ORDER BY s.user_id, s.id, p.interest_code;

SELECT c.* FROM comma_interest_counts c
WHERE c.user_id IN (SELECT user_id FROM comma_interest_source) ORDER BY c.user_id;
SELECT * FROM comma_interest_options WHERE interest_code LIKE '%,%';

\if :apply
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM comma_interest_source
               WHERE interest_type NOT IN ('CATEGORY', 'SUB_CATEGORY')) THEN
        RAISE EXCEPTION '예상하지 않은 유형의 쉼표 관심 분야: 원본을 확인하고 수동 처리하세요.';
    END IF;
    IF EXISTS (SELECT 1 FROM comma_interest_options WHERE interest_code LIKE '%,%') THEN
        RAISE EXCEPTION '현재 선택지에 쉼표 분류명이 있습니다. 정상 명칭인지 먼저 확인하세요.';
    END IF;
    IF EXISTS (SELECT 1 FROM comma_interest_parts p
               WHERE p.interest_code = '' OR length(p.interest_code) > 100
                  OR NOT EXISTS (SELECT 1 FROM comma_interest_options o
                                 WHERE o.interest_type = p.interest_type
                                   AND o.interest_code = p.interest_code)) THEN
        RAISE EXCEPTION '분리값이 비어 있거나 현재 선택지에 없습니다. 자동 변환하지 말고 재선택을 확인하세요.';
    END IF;
    IF EXISTS (SELECT 1 FROM comma_interest_counts c
               WHERE c.resulting_count > 30
                 AND c.user_id IN (SELECT user_id FROM comma_interest_source)) THEN
        RAISE EXCEPTION '분리 후 관심 분야가 30개를 초과합니다. 사용자 선택을 먼저 확인하세요.';
    END IF;
END $$;

INSERT INTO user_interests (user_id, interest_type, interest_code, created_at)
SELECT user_id, interest_type, interest_code, min(created_at)
FROM comma_interest_parts GROUP BY user_id, interest_type, interest_code
ON CONFLICT ON CONSTRAINT uk_user_interests_user_type_code DO NOTHING;
DELETE FROM user_interests WHERE id IN (SELECT id FROM comma_interest_source);
SELECT count(*) AS remaining_comma_rows FROM user_interests WHERE interest_code LIKE '%,%';
COMMIT;
\else
ROLLBACK;
\echo '미리보기 완료: 원본 데이터는 변경하지 않았습니다. 검토 후 -v apply=true로 적용하세요.'
\endif
