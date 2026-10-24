package com.team3.backend.tracking.domain.repository;

import com.team3.backend.tracking.domain.entity.OpportunityChange;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface OpportunityChangeRepository extends JpaRepository<OpportunityChange, Long> {
    Optional<OpportunityChange> findByFromVersionIdAndToVersionId(Long fromVersionId, Long toVersionId);
}
