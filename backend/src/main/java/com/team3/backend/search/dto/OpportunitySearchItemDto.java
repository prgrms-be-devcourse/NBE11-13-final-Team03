package com.team3.backend.search.dto;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;

import java.time.LocalDateTime;

/** 검색 목록의 공고 정보와 사용자별 표시 상태를 담는다. */
public record OpportunitySearchItemDto(
        Long opportunityId,
        OpportunityType type,
        String title,
        String category,
        String subCategory,
        LocalDateTime createdAt,
        EligibilityStatus eligibilityStatus,
        boolean saved
) {
    /** 공고 정보에 저장된 신청 가능성·저장 상태를 결합한다. */
    public static OpportunitySearchItemDto from(
            Opportunity opportunity, EligibilityStatus eligibilityStatus, boolean saved
    ) {
        return new OpportunitySearchItemDto(
                opportunity.getId(),
                opportunity.getOpportunityType(),
                opportunity.getTitle(),
                opportunity.getCategory(),
                opportunity.getSubCategory(),
                opportunity.getCreatedAt(),
                eligibilityStatus,
                saved
        );
    }
}
