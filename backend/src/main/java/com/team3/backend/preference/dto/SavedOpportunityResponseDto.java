package com.team3.backend.preference.dto;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.dto.OpportunityCategoryItem;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

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
        @Schema(description = "청년정책 현재 버전의 정규화된 분류 행 (대·중분류 짝, 정렬 순서대로). 직업훈련과 분류 행이 없는 정책은 빈 배열")
        List<OpportunityCategoryItem> categories,
        @Schema(description = "공고를 저장한 시각")
        LocalDateTime savedAt
) {
    /** 저장 상태와 연결된 공고를 목록 항목으로 변환한다. */
    public static SavedOpportunityResponseDto from(
            UserOpportunityPreference preference, List<OpportunityCategoryItem> categories
    ) {
        Opportunity opportunity = preference.getOpportunity();
        return new SavedOpportunityResponseDto(
                opportunity.getId(),
                opportunity.getOpportunityType(),
                opportunity.getTitle(),
                opportunity.getCategory(),
                opportunity.getSubCategory(),
                categories,
                preference.getSavedAt()
        );
    }
}
