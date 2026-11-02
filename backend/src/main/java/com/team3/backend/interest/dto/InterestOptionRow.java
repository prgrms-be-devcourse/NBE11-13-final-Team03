package com.team3.backend.interest.dto;

import com.team3.backend.opportunity.domain.entity.OpportunityType;

/**
 * 정규화된 공고의 분류 계층을 조회하기 위한 내부 조회 결과다.
 * 청년정책과 직업훈련은 같은 대분류명(예: 교육･직업훈련)을 쓸 수 있어 분류가 나온 공고 유형을 함께 둔다.
 */
public record InterestOptionRow(
        OpportunityType type,
        String category,
        String subCategory,
        String detailCategory
) {
}
