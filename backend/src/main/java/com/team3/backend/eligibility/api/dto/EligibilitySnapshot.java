package com.team3.backend.eligibility.api.dto;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 사용자 × 기회 판정 결과 하나 (ELIGIBILITY_RESULT를 외부에 넘기는 형태).
 * 최신 여부 기준: opportunityVersionId·profileVersion·ruleVersion이 현재와 같고 오늘 < validUntil.
 * opportunity: 추천에 필요한 기회 요약 (분류, NCS, 모집 상태, 마감일, 교육 시작일, 등록일)
 */
public record EligibilitySnapshot(
        Long userId,
        Long opportunityId,
        OpportunityInfo opportunity,
        EligibilityStatus status,
        String reason,
        List<ConditionResult> conditions,
        Long opportunityVersionId,
        Long profileVersion,
        String ruleVersion,
        LocalDate validUntil,
        LocalDateTime evaluatedAt
) {
}
