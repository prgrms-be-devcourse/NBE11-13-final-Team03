package com.team3.dataservice.opportunity.domain.repository;

import com.team3.dataservice.opportunity.domain.entity.OpportunityVersion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OpportunityVersionRepository extends JpaRepository<OpportunityVersion, Long> {

    List<OpportunityVersion> findByOpportunityIdOrderByVersionNoAsc(Long opportunityId);

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
