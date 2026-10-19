package com.team3.backend.eligibility.domain.repository;

import com.team3.backend.eligibility.domain.entity.EligibilityResult;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.dto.EligibilityResultFreshness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

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
}
