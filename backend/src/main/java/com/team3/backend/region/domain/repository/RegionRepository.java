package com.team3.backend.region.domain.repository;

import com.team3.backend.region.domain.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, String> {
}
