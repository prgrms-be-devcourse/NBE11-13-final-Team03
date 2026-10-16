package com.team3.backend.preference.dto;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;

import java.time.LocalDateTime;

public record SavedOpportunityResponseDto(
        Long opportunityId,
        OpportunityType type,
        String title,
        String category,
        String subCategory,
        LocalDateTime savedAt
) {
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
