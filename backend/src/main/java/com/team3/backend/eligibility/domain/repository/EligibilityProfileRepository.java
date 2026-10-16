package com.team3.backend.eligibility.domain.repository;

import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EligibilityProfileRepository extends JpaRepository<EligibilityProfile, Long> {

    Optional<EligibilityProfile> findByUserId(Long userId);
}
