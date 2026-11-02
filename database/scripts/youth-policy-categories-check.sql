-- 청년정책 분류 행(opportunity_categories) 백필 완료 여부를 확인한다. 읽기 전용이며 아무것도 바꾸지 않는다.
-- application_user로 실행한다. 반복 실행해도 된다. (docs/youth-policy-category-matching.md)
--
-- backend의 검색 분류 필터·관심 분야 선택지·추천 관심 적합도는 청년정책 분류를 이 테이블로만 판단한다.
-- 분류 행이 없는 청년정책은 공고 컬럼에 분류가 있어도 분류 필터·관심 매칭에서 빠지므로,
-- 다중 분류 매칭 backend를 배포하기 전에 1번의 pending_backfill이 0이어야 한다.
--
--   { echo 'SET ROLE application_user;'; cat database/scripts/youth-policy-categories-check.sql; } \
--     | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1

SET search_path TO application, pg_catalog;

\echo ''
\echo '=== 1. 현재 버전 기준 청년정책 분류 행 상태 (pending_backfill = 0 이어야 배포 가능) ==='
\echo '    no_category: 원본 대·중분류가 모두 비어 행이 없는 정책 (실제로 분류가 없음, 백필 대상 아님)'
SELECT count(*)                                                       AS youth_policies,
       count(*) FILTER (WHERE has_rows)                               AS with_rows,
       count(*) FILTER (WHERE NOT has_rows AND has_source)            AS pending_backfill,
       count(*) FILTER (WHERE NOT has_rows AND NOT has_source)        AS no_category
FROM (
    SELECT EXISTS (SELECT 1 FROM opportunity_categories c WHERE c.version_id = o.current_version_id) AS has_rows,
           -- 백필(YouthPolicyCategoryBackfill)과 같이 버전 스냅샷의 원본 문자열을 본다. 공백·쉼표만이면 행이 없다
           (coalesce(btrim(replace(v.normalized_snapshot #>> '{category,large}', ',', '')), '') <> ''
            OR coalesce(btrim(replace(v.normalized_snapshot #>> '{category,medium}', ',', '')), '') <> '') AS has_source
    FROM opportunities o
    JOIN opportunity_versions v ON v.id = o.current_version_id
    WHERE o.opportunity_type = 'YOUTH_POLICY'
) s;

\echo ''
\echo '=== 2. 백필이 남은 정책 예시 (최대 20건) ==='
SELECT o.id AS opportunity_id, o.current_version_id, o.category, o.sub_category
FROM opportunities o
WHERE o.opportunity_type = 'YOUTH_POLICY'
  AND o.current_version_id IS NOT NULL
  AND (o.category IS NOT NULL OR o.sub_category IS NOT NULL)
  AND NOT EXISTS (SELECT 1 FROM opportunity_categories c WHERE c.version_id = o.current_version_id)
ORDER BY o.id
LIMIT 20;
