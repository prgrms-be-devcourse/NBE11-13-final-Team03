package com.team3.backend.interest.dto;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record InterestCategoryOptionResponse(
        String category,
        List<InterestSubCategoryOptionResponse> subCategories,
        @Schema(description = "이 대분류가 나오는 공고 유형 (YOUTH_POLICY, VOCATIONAL_TRAINING 순). "
                + "청년정책과 직업훈련이 같은 대분류명을 쓰면 둘 다 담기며, 중분류의 opportunityTypes로 유형별 하위 분류를 구분한다")
        List<OpportunityType> opportunityTypes
) {
}
