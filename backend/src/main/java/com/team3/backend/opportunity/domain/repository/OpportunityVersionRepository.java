package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OpportunityVersionRepository extends JpaRepository<OpportunityVersion, Long> {

    java.util.Optional<OpportunityVersion> findFirstByOpportunityIdAndVersionNoLessThanOrderByVersionNoDesc(
            Long opportunityId, Integer versionNo);

    List<OpportunityVersion> findByOpportunityIdOrderByVersionNoAsc(Long opportunityId);

    /** Includes missing records and non-terminal analyses; never skip an unfinished intermediate version. */
    @Query("""
            select v from OpportunityVersion v
            where v.opportunity.id = :opportunityId and v.versionNo <= :currentVersionNo
              and not exists (select t.id from OpportunityVersionTracking t
                  where t.opportunityVersion.id = v.id and t.processedAt is not null)
            order by v.versionNo, v.id
            """)
    List<OpportunityVersion> findUnfinishedTrackingVersions(@Param("opportunityId") Long opportunityId,
            @Param("currentVersionNo") Integer currentVersionNo, Pageable pageable);

    /** 조건 묶음이 비어 있는 버전 ID (ConditionSetBackfill용, afterId보다 큰 ID 순서로 pageable 크기만큼) */
    @Query("select v.id from OpportunityVersion v where v.conditionSetId is null and v.id > :afterId order by v.id")
    List<Long> findIdsWithoutConditionSet(@Param("afterId") Long afterId, Pageable pageable);

    /**
     * 비어 있는 조건 묶음만 채운다 (ConditionSetBackfill용). 버전 엔티티는 불변(@Immutable)이라 네이티브 SQL로 갱신하며,
     * 이미 값이 있는 버전은 건드리지 않는다.
     */
    @Modifying
    @Query(value = "UPDATE opportunity_versions SET condition_set_id = :conditionSetId "
            + "WHERE id IN (:versionIds) AND condition_set_id IS NULL", nativeQuery = true)
    int assignConditionSet(@Param("conditionSetId") Long conditionSetId,
                           @Param("versionIds") Collection<Long> versionIds);
}
