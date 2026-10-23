package com.team3.backend.preference.domain.repository;

import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 사용자별 공고 상태와 저장 목록·공고별 저장 사용자를 조회한다. */
@Repository
public interface UserOpportunityPreferenceRepository
        extends JpaRepository<UserOpportunityPreference, Long> {

    /** 사용자와 공고의 현재 저장·숨김 상태를 조회한다. */
    Optional<UserOpportunityPreference> findByUserIdAndOpportunityId(
            Long userId,
            Long opportunityId
    );

    /** 사용자와 공고 조합의 행 수를 확인한다. */
    long countByUserIdAndOpportunityId(Long userId, Long opportunityId);

    /** 숨김 여부와 관계없이 저장된 행을 조회한다. */
    @Query("""
        select preference
        from UserOpportunityPreference preference
        where preference.user.id = :userId
          and preference.isSaved = true
        """)
    List<UserOpportunityPreference> findSavedByUserId(
            @Param("userId") Long userId
    );

    /** 지정한 공고 중 숨김 여부와 관계없이 저장된 공고 ID만 조회한다. */
    @Query("""
        select preference.opportunity.id
        from UserOpportunityPreference preference
        where preference.user.id = :userId
          and preference.opportunity.id in :opportunityIds
          and preference.isSaved = true
        """)
    List<Long> findSavedOpportunityIds(
            @Param("userId") Long userId,
            @Param("opportunityIds") Collection<Long> opportunityIds
    );

    /** 지정한 공고 중 저장 여부와 관계없이 숨긴 공고 ID만 조회한다. */
    @Query("""
        select preference.opportunity.id
        from UserOpportunityPreference preference
        where preference.user.id = :userId
          and preference.opportunity.id in :opportunityIds
          and preference.isHidden = true
        """)
    List<Long> findHiddenOpportunityIds(
            @Param("userId") Long userId,
            @Param("opportunityIds") Collection<Long> opportunityIds
    );

    /** 공고를 저장한 사용자 ID를 마지막 ID 이후부터 오름차순으로 조회한다. */
    @Query("""
        select preference.user.id
        from UserOpportunityPreference preference
        where preference.opportunity.id = :opportunityId
          and preference.isSaved = true
          and preference.user.id > :afterUserId
        order by preference.user.id asc
        """)
    List<Long> findSavedUserIdsAfter(
            @Param("opportunityId") Long opportunityId,
            @Param("afterUserId") Long afterUserId,
            Pageable pageable
    );

    /** 지정한 사용자 중 해당 공고를 현재 저장한 사용자 ID만 조회한다. */
    @Query("""
        select preference.user.id
        from UserOpportunityPreference preference
        where preference.opportunity.id = :opportunityId
          and preference.user.id in :userIds
          and preference.isSaved = true
        """)
    List<Long> findSavedUserIds(
            @Param("opportunityId") Long opportunityId,
            @Param("userIds") Collection<Long> userIds
    );

    /** 공고를 함께 읽어 저장 목록과 정확한 전체 건수를 페이지로 반환한다. */
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
