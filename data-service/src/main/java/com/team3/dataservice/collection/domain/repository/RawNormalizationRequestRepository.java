package com.team3.dataservice.collection.domain.repository;

import com.team3.dataservice.collection.domain.entity.RawNormalizationRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawNormalizationRequestRepository extends JpaRepository<RawNormalizationRequest, Long> {
}
