package com.team3.backend.preference.domain.repository;

import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserOpportunityPreferenceRepository
        extends JpaRepository<UserOpportunityPreference, Long> {

    Optional<UserOpportunityPreference> findByUserIdAndOpportunityId(
            Long userId,
            Long opportunityId
    );

    long countByUserIdAndOpportunityId(Long userId, Long opportunityId);

    @Query("""
        select preference
        from UserOpportunityPreference preference
        where preference.user.id = :userId
          and preference.isSaved = true
        """)
    List<UserOpportunityPreference> findSavedByUserId(
            @Param("userId") Long userId
    );

    @Query(value = """
        select preference
        from UserOpportunityPreference preference
        join fetch preference.opportunity
        where preference.user.id = :userId
          and preference.isSaved = true
        """, countQuery = """
        select count(preference)
        from UserOpportunityPreference preference
        where preference.user.id = :userId
          and preference.isSaved = true
        """)
    Page<UserOpportunityPreference> findSavedPageByUserId(
            @Param("userId") Long userId,
            Pageable pageable
    );
}
