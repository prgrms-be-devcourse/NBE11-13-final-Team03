package com.team3.dataservice.opportunity.domain.repository;

import com.team3.dataservice.opportunity.domain.entity.OpportunityCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OpportunityCategoryRepository extends JpaRepository<OpportunityCategory, Long> {

    List<OpportunityCategory> findByVersionIdOrderBySortOrderAsc(Long versionId);
}
