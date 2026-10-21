package com.team3.backend.eligibility.domain.repository;

import com.team3.backend.eligibility.domain.entity.EligibilityResult;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.dto.ConditionSetRef;
import com.team3.backend.eligibility.dto.EligibilityResultFreshness;
import com.team3.backend.eligibility.dto.EligibilityStatusRow;
import com.team3.backend.eligibility.dto.OpportunityEligibility;
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

/**
 * 판정 결과는 (사용자, 조건 묶음) 단위로 저장한다. 기회의 결과는 기회 현재 버전의 조건 묶음으로 찾는다
 * (o.currentVersion.conditionSetId = r.conditionSetId).
 *
 * 최신 기준 (EligibilityResultFreshness.isFresh·EligibilitySpecifications와 같음):
 * 기회 현재 버전의 조건 묶음 결과이고, 프로필 버전·규칙 버전이 현재와 같고, validUntil이 없거나 today보다 뒤.
 */
public interface EligibilityResultRepository extends JpaRepository<EligibilityResult, Long>,
        EligibilityResultUpsertRepository {

    /** 기회별 결과 (최신 여부와 무관). 정규화되지 않았거나 결과가 없는 기회는 빠진다. 최신화 직후 읽는 용도 */
    @Query("select new com.team3.backend.eligibility.dto.OpportunityEligibility(o.id, v.id, r) "
            + "from Opportunity o join o.currentVersion v, EligibilityResult r "
            + "where o.id in :opportunityIds and r.userId = :userId and r.conditionSetId = v.conditionSetId")
    List<OpportunityEligibility> findByOpportunities(@Param("userId") Long userId,
                                                     @Param("opportunityIds") Collection<Long> opportunityIds);

    /** 판정 상태로 거른 기회별 결과 (기회 ID 오름차순). 최신화(refreshAll) 직후 읽는 용도 */
    @Query(value = "select new com.team3.backend.eligibility.dto.OpportunityEligibility(o.id, v.id, r) "
            + "from Opportunity o join o.currentVersion v, EligibilityResult r "
            + "where r.userId = :userId and r.conditionSetId = v.conditionSetId and r.eligibilityStatus in :statuses "
            + "order by o.id",
            countQuery = "select count(o) from Opportunity o join o.currentVersion v, EligibilityResult r "
                    + "where r.userId = :userId and r.conditionSetId = v.conditionSetId "
                    + "and r.eligibilityStatus in :statuses")
    Page<OpportunityEligibility> findByStatuses(@Param("userId") Long userId,
                                                @Param("statuses") Collection<EligibilityStatus> statuses,
                                                Pageable pageable);

    @Query("select new com.team3.backend.eligibility.dto.EligibilityResultFreshness("
            + "r.conditionSetId, r.profileVersion, r.ruleVersion, r.validUntil) "
            + "from EligibilityResult r where r.userId = :userId and r.conditionSetId in :conditionSetIds")
    List<EligibilityResultFreshness> findFreshness(@Param("userId") Long userId,
                                                   @Param("conditionSetIds") Collection<Long> conditionSetIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        delete from EligibilityResult r
        where r.userId = :userId
        """)
    int deleteAllByUserId(@Param("userId") Long userId);

    /**
     * 저장된 최신 판정 결과의 상태만 조회한다 (재판정하지 않음).
     * 결과가 없거나 오래된 기회, 현재 버전(또는 조건 묶음)이 없는 기회는 빠진다. 판정 프로필이 없으면 빈 목록.
     */
    @Query("select new com.team3.backend.eligibility.dto.EligibilityStatusRow(o.id, r.eligibilityStatus) "
            + "from Opportunity o join o.currentVersion v, EligibilityResult r "
            + "where o.id in :opportunityIds and r.userId = :userId and r.conditionSetId = v.conditionSetId "
            + "and r.profileVersion = (select p.profileVersion from EligibilityProfile p where p.userId = :userId) "
            + "and r.ruleVersion = :ruleVersion "
            + "and (r.validUntil is null or r.validUntil > :today)")
    List<EligibilityStatusRow> findFreshStatuses(@Param("userId") Long userId,
                                                 @Param("opportunityIds") Collection<Long> opportunityIds,
                                                 @Param("ruleVersion") String ruleVersion,
                                                 @Param("today") LocalDate today);

    /** 저장된 최신 판정 결과 한 건 (상세 조회용, 재판정하지 않음). 최신 기준은 findFreshStatuses와 같다 */
    @Query("select new com.team3.backend.eligibility.dto.OpportunityEligibility(o.id, v.id, r) "
            + "from Opportunity o join o.currentVersion v, EligibilityResult r "
            + "where o.id = :opportunityId and r.userId = :userId and r.conditionSetId = v.conditionSetId "
            + "and r.profileVersion = (select p.profileVersion from EligibilityProfile p where p.userId = :userId) "
            + "and r.ruleVersion = :ruleVersion "
            + "and (r.validUntil is null or r.validUntil > :today)")
    Optional<OpportunityEligibility> findFresh(@Param("userId") Long userId,
                                               @Param("opportunityId") Long opportunityId,
                                               @Param("ruleVersion") String ruleVersion,
                                               @Param("today") LocalDate today);

    /**
     * 사용자의 판정 결과가 없거나 오래된 조건 묶음과 그 대표 버전 (배치 최신화용).
     * 기회의 현재 버전이 쓰는 조건 묶음만 대상이며, afterConditionSetId보다 큰 묶음 ID 순서로 pageable 크기만큼.
     * 오래됨: 결과 없음(새 묶음), 프로필·규칙 버전이 다름, validUntil(다음 생일) 경과. 조건 묶음이 없는 버전은 빠진다.
     */
    @Query("select new com.team3.backend.eligibility.dto.ConditionSetRef(v.conditionSetId, min(v.id)) "
            + "from Opportunity o join o.currentVersion v "
            + "where v.conditionSetId is not null and v.conditionSetId > :afterConditionSetId "
            + "and not exists (select 1 from EligibilityResult r "
            + "where r.userId = :userId and r.conditionSetId = v.conditionSetId "
            + "and r.profileVersion = :profileVersion "
            + "and r.ruleVersion = :ruleVersion "
            + "and (r.validUntil is null or r.validUntil > :today)) "
            + "group by v.conditionSetId order by v.conditionSetId")
    List<ConditionSetRef> findStaleConditionSets(@Param("userId") Long userId,
                                                 @Param("profileVersion") Long profileVersion,
                                                 @Param("ruleVersion") String ruleVersion,
                                                 @Param("today") LocalDate today,
                                                 @Param("afterConditionSetId") Long afterConditionSetId,
                                                 Pageable pageable);
}
