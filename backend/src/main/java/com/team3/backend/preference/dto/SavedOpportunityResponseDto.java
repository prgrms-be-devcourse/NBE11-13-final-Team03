package com.team3.backend.preference.dto;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** 저장 목록에 표시할 공고 정보와 저장 시각이다. */
public record SavedOpportunityResponseDto(
        @Schema(description = "공고 ID")
        Long opportunityId,
        @Schema(description = "공고 유형: YOUTH_POLICY 또는 VOCATIONAL_TRAINING")
        OpportunityType type,
        @Schema(description = "공고 제목")
        String title,
        @Schema(description = "대분류")
        String category,
        @Schema(description = "중분류")
        String subCategory,
        @Schema(description = "공고를 저장한 시각")
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
