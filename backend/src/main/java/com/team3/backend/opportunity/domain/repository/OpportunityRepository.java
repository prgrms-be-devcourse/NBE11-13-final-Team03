package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.dto.OpportunitySummaryRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OpportunityRepository extends JpaRepository<Opportunity, Long> {

    /** 정규화된(현재 버전이 있는) 기회 요약. 정규화 스냅샷은 읽지 않는다 */
    @Query(value = "select new com.team3.backend.opportunity.dto.OpportunitySummaryRow("
            + "o.id, o.currentVersion.id, o.opportunityType, o.title, o.category) "
            + "from Opportunity o where o.currentVersion is not null",
            countQuery = "select count(o) from Opportunity o where o.currentVersion is not null")
    Page<OpportunitySummaryRow> findNormalizedSummaries(Pageable pageable);
}
