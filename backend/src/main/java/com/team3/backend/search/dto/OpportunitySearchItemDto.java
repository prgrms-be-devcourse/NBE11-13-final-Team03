package com.team3.backend.search.dto;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;

import java.time.LocalDateTime;

public record OpportunitySearchItemDto(
        Long opportunityId,
        OpportunityType type,
        String title,
        String category,
        String subCategory,
        LocalDateTime createdAt
) {
    public static OpportunitySearchItemDto from(Opportunity opportunity) {
        return new OpportunitySearchItemDto(
                opportunity.getId(),
                opportunity.getOpportunityType(),
                opportunity.getTitle(),
                opportunity.getCategory(),
                opportunity.getSubCategory(),
                opportunity.getCreatedAt()
        );
    }
}
