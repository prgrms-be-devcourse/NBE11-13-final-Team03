package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpportunityRepository extends JpaRepository<Opportunity, Long> {

    /** 정규화된(현재 버전이 있는) 기회만 */
    @EntityGraph(attributePaths = "currentVersion")
    Page<Opportunity> findByCurrentVersionIsNotNull(Pageable pageable);
}
