package com.team3.backend.tracking.domain.repository;

import com.team3.backend.tracking.domain.entity.OpportunityVersionTracking;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface OpportunityVersionTrackingRepository extends JpaRepository<OpportunityVersionTracking, Long> {
    Optional<OpportunityVersionTracking> findByOpportunityVersionId(Long opportunityVersionId);

    /** Unlocked keyset discovery only. The transaction must lock and recheck each policy. */
    @Query("""
            select o.id from Opportunity o
            where o.id > :afterId and o.opportunityType = :type and o.currentVersion is not null
            order by o.id
            """)
    List<Long> findSequentialPolicyIds(@Param("afterId") long afterId,
            @Param("type") OpportunityType type, Pageable pageable);
}
