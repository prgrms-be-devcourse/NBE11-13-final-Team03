package com.team3.backend.preference.dto;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;

import java.time.LocalDateTime;

/** 저장 목록에 표시할 공고 정보와 저장 시각이다. */
public record SavedOpportunityResponseDto(
        Long opportunityId,
        OpportunityType type,
        String title,
        String category,
        String subCategory,
        LocalDateTime savedAt
) {
    /** 저장 상태와 연결된 공고를 목록 항목으로 변환한다. */
    public static SavedOpportunityResponseDto from(
            UserOpportunityPreference preference
    ) {
        Opportunity opportunity = preference.getOpportunity();
        return new SavedOpportunityResponseDto(
                opportunity.getId(),
                opportunity.getOpportunityType(),
                opportunity.getTitle(),
                opportunity.getCategory(),
                opportunity.getSubCategory(),
                preference.getSavedAt()
        );
    }
}
