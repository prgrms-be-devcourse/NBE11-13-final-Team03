package com.team3.backend.tracking.domain.repository;

import com.team3.backend.tracking.domain.entity.EligibilityEventAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface EligibilityEventAssessmentRepository extends JpaRepository<EligibilityEventAssessment, Long> {
    Optional<EligibilityEventAssessment> findByEventIdAndUserId(Long eventId, Long userId);
    boolean existsByEventIdAndStatus(Long eventId, EligibilityEventAssessment.Status status);
}
