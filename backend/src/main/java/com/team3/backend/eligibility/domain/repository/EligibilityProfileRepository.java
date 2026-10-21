package com.team3.backend.eligibility.domain.repository;

import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EligibilityProfileRepository
        extends JpaRepository<EligibilityProfile, Long> {

    Optional<EligibilityProfile> findByUserId(Long userId);

    /** 판정 프로필이 있는 사용자 ID (배치 최신화용, afterUserId보다 큰 ID 순서로 pageable 크기만큼) */
    @Query("select p.userId from EligibilityProfile p where p.userId > :afterUserId order by p.userId")
    List<Long> findUserIdsAfter(@Param("afterUserId") Long afterUserId, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        delete from EligibilityProfile p
        where p.userId = :userId
        """)
    int deleteAllByUserId(@Param("userId") Long userId);
}