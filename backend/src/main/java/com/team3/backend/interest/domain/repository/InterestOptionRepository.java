package com.team3.backend.interest.domain.repository;

import com.team3.backend.interest.dto.InterestOptionRow;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 정규화된 공고에서 관심 분야로 선택할 수 있는 분류값을 조회한다.
 * 청년정책은 원문이 여러 분류를 쉼표로 이어 붙이므로 현재 버전의 분류 행(대·중분류 짝)을 쓰고,
 * 직업훈련은 분류가 하나라 공고의 대·중·세분류 컬럼을 그대로 쓴다.
 */
public interface InterestOptionRepository
        extends Repository<Opportunity, Long> {

    /** 직업훈련 분류와 청년정책 분류 짝. 대분류가 없는 행은 계층에 놓을 수 없어 빼고, 같은 값은 서비스가 합친다 */
    default List<InterestOptionRow> findDistinctNormalizedCategories() {
        List<InterestOptionRow> rows = new ArrayList<>(findDistinctTrainingCategories());
        rows.addAll(findDistinctYouthPolicyCategories());
        return rows;
    }

    @Query("""
        select distinct new com.team3.backend.interest.dto.InterestOptionRow(
            opportunity.opportunityType,
            opportunity.category,
            opportunity.subCategory,
            opportunity.detailCategory
        )
        from Opportunity opportunity
        where opportunity.currentVersion is not null
          and opportunity.opportunityType = com.team3.backend.opportunity.domain.entity.OpportunityType.VOCATIONAL_TRAINING
          and opportunity.category is not null
        """)
    List<InterestOptionRow> findDistinctTrainingCategories();

    @Query("""
        select distinct new com.team3.backend.interest.dto.InterestOptionRow(
            opportunity.opportunityType,
            category.largeCategory,
            category.mediumCategory,
            cast(null as String)
        )
        from Opportunity opportunity
        join OpportunityCategory category on category.version = opportunity.currentVersion
        where opportunity.opportunityType = com.team3.backend.opportunity.domain.entity.OpportunityType.YOUTH_POLICY
          and category.largeCategory is not null
        """)
    List<InterestOptionRow> findDistinctYouthPolicyCategories();
}
