package com.team3.backend.eligibility.domain.repository;

import com.team3.backend.eligibility.domain.entity.EligibilityResult;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.dto.EligibilityResultFreshness;
import com.team3.backend.eligibility.dto.EligibilityStatusRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EligibilityResultRepository extends JpaRepository<EligibilityResult, Long>,
        EligibilityResultUpsertRepository {

    List<EligibilityResult> findByUserIdAndOpportunityIdIn(Long userId, Collection<Long> opportunityIds);

    /** 신청 가능성 필터용 (예: ELIGIBLE, POSSIBLY_ELIGIBLE). 최신화는 EligibilityResultService.refreshAll 이후에 조회한다 */
    Page<EligibilityResult> findByUserIdAndEligibilityStatusIn(Long userId, Collection<EligibilityStatus> statuses,
                                                               Pageable pageable);

    @Query("select new com.team3.backend.eligibility.dto.EligibilityResultFreshness("
            + "r.opportunityId, r.opportunityVersionId, r.profileVersion, r.ruleVersion, r.validUntil) "
            + "from EligibilityResult r where r.userId = :userId and r.opportunityId in :opportunityIds")
    List<EligibilityResultFreshness> findFreshness(@Param("userId") Long userId,
                                                   @Param("opportunityIds") Collection<Long> opportunityIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        delete from EligibilityResult r
        where r.userId = :userId
        """)
    int deleteAllByUserId(@Param("userId") Long userId);

    /**
     * 저장된 최신 판정 결과의 상태만 조회한다 (재판정하지 않음). 최신 기준은 EligibilityResultFreshness.isFresh·EligibilitySpecifications와 같다:
     * 기회의 현재 버전, 판정 프로필의 현재 버전, 규칙 버전이 모두 같고 validUntil이 없거나 today보다 뒤.
     * 결과가 없거나 오래된 기회, 현재 버전이 없는 기회는 빠진다. 판정 프로필이 없으면 빈 목록.
     */
    @Query("select new com.team3.backend.eligibility.dto.EligibilityStatusRow(r.opportunityId, r.eligibilityStatus) "
            + "from EligibilityResult r, Opportunity o "
            + "where o.id = r.opportunityId and r.userId = :userId and r.opportunityId in :opportunityIds "
            + "and r.opportunityVersionId = o.currentVersion.id "
            + "and r.profileVersion = (select p.profileVersion from EligibilityProfile p where p.userId = :userId) "
            + "and r.ruleVersion = :ruleVersion "
            + "and (r.validUntil is null or r.validUntil > :today)")
    List<EligibilityStatusRow> findFreshStatuses(@Param("userId") Long userId,
                                                 @Param("opportunityIds") Collection<Long> opportunityIds,
                                                 @Param("ruleVersion") String ruleVersion,
                                                 @Param("today") LocalDate today);

    /**
     * 저장된 최신 판정 결과 한 건 (상세 조회용, 재판정하지 않음). 최신 기준은 findFreshStatuses와 같다.
     * 결과가 없거나 오래됐거나, 기회에 현재 버전이 없거나, 판정 프로필이 없으면 빈 값.
     */
    @Query("select r from EligibilityResult r, Opportunity o "
            + "where o.id = r.opportunityId and r.userId = :userId and r.opportunityId = :opportunityId "
            + "and r.opportunityVersionId = o.currentVersion.id "
            + "and r.profileVersion = (select p.profileVersion from EligibilityProfile p where p.userId = :userId) "
            + "and r.ruleVersion = :ruleVersion "
            + "and (r.validUntil is null or r.validUntil > :today)")
    Optional<EligibilityResult> findFresh(@Param("userId") Long userId,
                                          @Param("opportunityId") Long opportunityId,
                                          @Param("ruleVersion") String ruleVersion,
                                          @Param("today") LocalDate today);
}
