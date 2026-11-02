package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.OpportunityCategory;
import com.team3.backend.opportunity.dto.OpportunityCategoryItem;
import com.team3.backend.opportunity.dto.OpportunityCategoryRow;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 청년정책 분류 행(opportunity_categories)을 응답 표시용으로 읽는다. 행은 data-service 정규화가 만들고 backend는 읽기만 한다.
 * 목록은 공고마다 조회하지 않고 한 번에 읽는다 (uk_opportunity_categories_version_order 인덱스). 직업훈련은 행이 없다.
 * 행이 없는 키는 결과 Map에 없으며, 호출자는 빈 목록으로 다루고 공고 컬럼 원문으로 대체하지 않는다.
 */
public interface OpportunityCategoryRepository extends Repository<OpportunityCategory, Long> {

    /** 버전 ID별 분류 행 (sort_order 순). 빈 입력은 조회하지 않는다 */
    default Map<Long, List<OpportunityCategoryItem>> findItemsByVersionIds(Collection<Long> versionIds) {
        return versionIds.isEmpty() ? Map.of() : group(findRowsByVersionIdIn(versionIds));
    }

    /** 공고 ID별 현재 버전의 분류 행 (sort_order 순). 버전 ID 없이 공고 ID만 있는 목록(추천)용이다 */
    default Map<Long, List<OpportunityCategoryItem>> findCurrentItemsByOpportunityIds(Collection<Long> opportunityIds) {
        return opportunityIds.isEmpty() ? Map.of() : group(findCurrentRowsByOpportunityIdIn(opportunityIds));
    }

    @Query("""
        select new com.team3.backend.opportunity.dto.OpportunityCategoryRow(
            category.version.id, category.largeCategory, category.mediumCategory)
        from OpportunityCategory category
        where category.version.id in :versionIds
        order by category.version.id, category.sortOrder
        """)
    List<OpportunityCategoryRow> findRowsByVersionIdIn(@Param("versionIds") Collection<Long> versionIds);

    @Query("""
        select new com.team3.backend.opportunity.dto.OpportunityCategoryRow(
            opportunity.id, category.largeCategory, category.mediumCategory)
        from Opportunity opportunity
        join OpportunityCategory category on category.version = opportunity.currentVersion
        where opportunity.id in :opportunityIds
        order by opportunity.id, category.sortOrder
        """)
    List<OpportunityCategoryRow> findCurrentRowsByOpportunityIdIn(
            @Param("opportunityIds") Collection<Long> opportunityIds);

    private static Map<Long, List<OpportunityCategoryItem>> group(List<OpportunityCategoryRow> rows) {
        return rows.stream().collect(Collectors.groupingBy(OpportunityCategoryRow::key, LinkedHashMap::new,
                Collectors.mapping(row -> new OpportunityCategoryItem(row.largeCategory(), row.mediumCategory()),
                        Collectors.toList())));
    }
}
