package com.team3.backend.tracking.domain.repository;

import com.team3.backend.tracking.domain.entity.OpportunityVersionAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface OpportunityVersionAnalysisRepository extends JpaRepository<OpportunityVersionAnalysis, Long> {
    Optional<OpportunityVersionAnalysis> findByTrackingIdAndAnalysisVersion(Long trackingId, String analysisVersion);
    List<OpportunityVersionAnalysis> findByTrackingIdOrderByIdAsc(Long trackingId);
}
