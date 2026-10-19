package com.team3.backend.search.dto;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;

import java.time.LocalDateTime;

/** 검색 목록에 필요한 공통 공고 정보다. */
public record OpportunitySearchItemDto(
        Long opportunityId,
        OpportunityType type,
        String title,
        String category,
        String subCategory,
        LocalDateTime createdAt
) {
    /** 공고 엔티티를 검색 목록 항목으로 변환한다. */
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
