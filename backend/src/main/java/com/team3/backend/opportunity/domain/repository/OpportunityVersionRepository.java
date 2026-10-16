package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OpportunityVersionRepository extends JpaRepository<OpportunityVersion, Long> {

    List<OpportunityVersion> findByOpportunityIdOrderByVersionNoAsc(Long opportunityId);
}
