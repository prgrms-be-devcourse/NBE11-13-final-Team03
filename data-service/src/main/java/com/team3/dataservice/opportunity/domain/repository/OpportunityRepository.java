package com.team3.dataservice.opportunity.domain.repository;

import com.team3.dataservice.opportunity.domain.entity.Opportunity;
import com.team3.dataservice.opportunity.dto.OpportunityInfoRow;
import com.team3.dataservice.opportunity.dto.OpportunitySummaryRow;
import com.team3.dataservice.opportunity.dto.OpportunityVersionRef;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OpportunityRepository extends JpaRepository<Opportunity, Long> {

    /** 정규화된(현재 버전이 있는) 기회 요약. 정규화 스냅샷은 읽지 않는다 */
    @Query(value = "select new com.team3.dataservice.opportunity.dto.OpportunitySummaryRow("
            + "o.id, o.currentVersion.id, o.opportunityType, o.title, o.category) "
            + "from Opportunity o where o.currentVersion is not null",
            countQuery = "select count(o) from Opportunity o where o.currentVersion is not null")
    Page<OpportunitySummaryRow> findNormalizedSummaries(Pageable pageable);

    /** 주어진 기회 중 정규화된 것의 현재 버전 */
    @Query("select new com.team3.dataservice.opportunity.dto.OpportunityVersionRef(o.id, o.currentVersion.id) "
            + "from Opportunity o where o.id in :ids and o.currentVersion is not null")
    List<OpportunityVersionRef> findCurrentVersionRefs(@Param("ids") Collection<Long> ids);

    /** 주어진 기회 중 정규화된 것의 현재 버전과 조건 묶음 (판정 결과 최신화용) */
    @Query("select new com.team3.dataservice.opportunity.dto.OpportunitySetRef(o.id, v.id, v.conditionSetId) "
            + "from Opportunity o join o.currentVersion v where o.id in :ids")
    List<com.team3.dataservice.opportunity.dto.OpportunitySetRef> findCurrentSetRefs(@Param("ids") Collection<Long> ids);

    /** 정규화된 기회 전체의 현재 버전 (페이지 단위) */
    @Query(value = "select new com.team3.dataservice.opportunity.dto.OpportunityVersionRef(o.id, o.currentVersion.id) "
            + "from Opportunity o where o.currentVersion is not null",
            countQuery = "select count(o) from Opportunity o where o.currentVersion is not null")
    Page<OpportunityVersionRef> findAllCurrentVersionRefs(Pageable pageable);

    /** 주어진 기회 중 정규화된 것의 요약 (다른 영역 제공용) */
    @Query("select new com.team3.dataservice.opportunity.dto.OpportunityInfoRow("
            + "o.id, o.currentVersion.id, o.opportunityType, o.title, o.category, o.subCategory, o.detailUrl, o.createdAt) "
            + "from Opportunity o where o.id in :ids and o.currentVersion is not null")
    List<OpportunityInfoRow> findInfoRows(@Param("ids") Collection<Long> ids);
}
