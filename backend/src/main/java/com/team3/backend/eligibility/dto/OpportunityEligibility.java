package com.team3.backend.eligibility.dto;

import com.team3.backend.eligibility.domain.entity.EligibilityResult;

/** 기회 하나의 판정 결과 = 기회의 현재 버전이 속한 조건 묶음의 판정 결과 */
public record OpportunityEligibility(Long opportunityId, Long opportunityVersionId, EligibilityResult result) {
}
