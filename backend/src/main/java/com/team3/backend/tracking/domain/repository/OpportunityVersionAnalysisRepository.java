package com.team3.backend.tracking.domain.repository;

import com.team3.backend.tracking.domain.entity.OpportunityVersionAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface OpportunityVersionAnalysisRepository extends JpaRepository<OpportunityVersionAnalysis, Long> {
    Optional<OpportunityVersionAnalysis> findByTrackingId(Long trackingId);
}
