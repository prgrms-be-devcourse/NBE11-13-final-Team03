package com.team3.backend.eligibility.dto;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.normalization.ApplicationStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/users/me/opportunities/eligibility 응답.
 * 정규화된 기회 전체를 최신순으로 페이지 단위 판정한다. 조건별 상세는 단건 API(/api/opportunities/{id}/eligibility)에서 본다.
 * page는 0부터 시작한다.
 */
public record EligibilityListResponse(
        List<Item> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        OffsetDateTime evaluatedAt
) {

    public record Item(
            Long opportunityId,
            Long opportunityVersionId,
            OpportunityType opportunityType,
            String title,
            String category,
            EligibilityStatus eligibility,
            ApplicationStatus applicationStatus,
            String reason
    ) {
    }
}
