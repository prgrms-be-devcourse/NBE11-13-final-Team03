package com.team3.backend.fixture;

import jakarta.persistence.EntityManager;

/**
 * 테스트 Fixture의 청년정책에 분류 행(opportunity_categories)을 채운다. data-service 백필이 끝난 상태를 만든다.
 *
 * 검색·추천·관심 분야는 청년정책 분류를 분류 행으로만 판단한다. 공고 컬럼에만 분류를 넣은 Fixture는 백필 전 데이터와 같아
 * 분류 필터·관심 매칭에 맞지 않는다. 이 메서드는 분류 행이 없는 현재 버전에 공고의 대·중분류를 짝 하나로 넣는다
 * (단일 분류 공고를 정규화한 결과와 같다). 쉼표로 이은 다중 분류는 짝을 나누는 규칙이 data-service에 있으므로 여기서 다루지 않고,
 * 그런 Fixture는 분류 행을 직접 넣는다. 행이 있는 버전은 건너뛰므로 여러 번 불러도 된다. 호출 전에 flush한다.
 */
public final class PolicyCategoryRows {

    private PolicyCategoryRows() {
    }

    /** @return 분류 행을 만든 버전 수 */
    public static int backfill(EntityManager entityManager) {
        entityManager.flush();
        if (((Number) entityManager.createNativeQuery("""
                select count(*) from opportunities o
                where o.opportunity_type = 'YOUTH_POLICY' and o.current_version_id is not null
                  and (o.category like '%,%' or o.sub_category like '%,%')
                  and not exists (select 1 from opportunity_categories c where c.version_id = o.current_version_id)
                """).getSingleResult()).longValue() > 0) {
            throw new IllegalStateException("쉼표로 이은 분류는 분류 행을 직접 넣는다");
        }
        return entityManager.createNativeQuery("""
                insert into opportunity_categories (id, version_id, sort_order, large_category, medium_category)
                select nextval('opportunity_categories_seq'), o.current_version_id, 0, o.category, o.sub_category
                from opportunities o
                where o.opportunity_type = 'YOUTH_POLICY' and o.current_version_id is not null
                  and (o.category is not null or o.sub_category is not null)
                  and not exists (select 1 from opportunity_categories c where c.version_id = o.current_version_id)
                """).executeUpdate();
    }
}
