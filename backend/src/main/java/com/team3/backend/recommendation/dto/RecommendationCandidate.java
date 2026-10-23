package com.team3.backend.recommendation.dto;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 후속 관심 분야·신청 가능성 평가에 사용할 추천 후보 요약이다. */
public record RecommendationCandidate(
        Long opportunityId,
        OpportunityType type,
        String title,
        String category,
        String subCategory,
        String detailCategory,
        ApplicationStatus applicationStatus,
        LocalDate applicationEndDate,
        LocalDate trainingStartDate,
        LocalDateTime createdAt
) {
}
