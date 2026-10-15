package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.OpportunityCondition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OpportunityConditionRepository extends JpaRepository<OpportunityCondition, Long> {

    List<OpportunityCondition> findByVersionId(Long versionId);

    List<OpportunityCondition> findByVersionIdIn(Collection<Long> versionIds);
}
