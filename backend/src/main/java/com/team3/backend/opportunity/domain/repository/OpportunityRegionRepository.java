package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.OpportunityRegion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OpportunityRegionRepository extends JpaRepository<OpportunityRegion, Long> {

    List<OpportunityRegion> findByVersionId(Long versionId);

    List<OpportunityRegion> findByVersionIdIn(Collection<Long> versionIds);
}
