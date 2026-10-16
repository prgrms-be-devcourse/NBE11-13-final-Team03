package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.OpportunitySource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OpportunitySourceRepository extends JpaRepository<OpportunitySource, Long> {

    Optional<OpportunitySource> findBySourceIdAndExternalId(Long sourceId, String externalId);

    List<OpportunitySource> findByOpportunityIdOrderByIdAsc(Long opportunityId);
}
