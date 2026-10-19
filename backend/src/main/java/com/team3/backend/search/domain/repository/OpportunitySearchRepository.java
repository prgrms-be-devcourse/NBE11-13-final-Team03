package com.team3.backend.search.domain.repository;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/** 검색 전용 Specification과 DB 페이지 조회를 제공한다. */
public interface OpportunitySearchRepository extends Repository<Opportunity, Long>,
        JpaSpecificationExecutor<Opportunity> {
}
