package com.team3.backend.eligibility.domain.repository;

import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EligibilityProfileRepository
        extends JpaRepository<EligibilityProfile, Long> {

    Optional<EligibilityProfile> findByUserId(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        delete from EligibilityProfile p
        where p.userId = :userId
        """)
    int deleteAllByUserId(@Param("userId") Long userId);
}