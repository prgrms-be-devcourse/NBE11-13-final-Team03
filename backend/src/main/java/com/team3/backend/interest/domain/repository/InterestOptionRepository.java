package com.team3.backend.interest.domain.repository;

import com.team3.backend.interest.dto.InterestOptionRow;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * 정규화된 공고에서 관심 분야로 선택할 수 있는 분류값을 조회한다.
 */
public interface InterestOptionRepository
        extends Repository<Opportunity, Long> {

    @Query("""
        select distinct new com.team3.backend.interest.dto.InterestOptionRow(
            opportunity.category,
            opportunity.subCategory,
            opportunity.detailCategory
        )
        from Opportunity opportunity
        where opportunity.currentVersion is not null
          and opportunity.category is not null
        """)
    List<InterestOptionRow> findDistinctNormalizedCategories();
}