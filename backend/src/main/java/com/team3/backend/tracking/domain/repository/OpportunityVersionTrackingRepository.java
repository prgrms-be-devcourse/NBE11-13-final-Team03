package com.team3.backend.tracking.domain.repository;

import com.team3.backend.tracking.domain.entity.OpportunityVersionTracking;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface OpportunityVersionTrackingRepository extends JpaRepository<OpportunityVersionTracking, Long> {
    Optional<OpportunityVersionTracking> findByOpportunityVersionId(Long opportunityVersionId);
}
