package com.team3.backend.search.domain.repository;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

public interface OpportunitySearchRepository extends Repository<Opportunity, Long>,
        JpaSpecificationExecutor<Opportunity> {
}
