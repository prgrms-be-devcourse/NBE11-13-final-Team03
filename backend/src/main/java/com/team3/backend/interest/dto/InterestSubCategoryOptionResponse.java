package com.team3.backend.interest.dto;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record InterestSubCategoryOptionResponse(
        String subCategory,
        List<String> detailCategories,
        @Schema(description = "이 대분류 아래에서 이 중분류가 나오는 공고 유형 (YOUTH_POLICY, VOCATIONAL_TRAINING 순). "
                + "세부 분류(detailCategories)는 직업훈련에만 있다")
        List<OpportunityType> opportunityTypes
) {
}
