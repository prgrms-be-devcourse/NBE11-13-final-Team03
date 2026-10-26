package com.team3.backend.recommendation.dto;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;

/** 관심 분야와 최신 신청 가능성 판정을 모두 통과한 추천 후보이다. */
public record RecommendationEligibleCandidate(
        RecommendationCandidate candidate,
        InterestMatchLevel interestMatchLevel,
        EligibilityStatus eligibilityStatus
) {
}
