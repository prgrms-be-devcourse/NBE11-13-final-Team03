package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, String> {
}
