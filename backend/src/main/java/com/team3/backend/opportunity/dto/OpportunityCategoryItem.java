package com.team3.backend.opportunity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 청년정책 현재 버전의 분류 행 하나 (대·중분류 짝). data-service 정규화가 쉼표 원문을 나누고 옛 이름을 현재 이름으로 바꾼 값이다.
 * 원본 개수가 맞지 않으면 대분류만 있거나 중분류만 있는 행이 있다 (짝을 추정하지 않은 결과).
 */
public record OpportunityCategoryItem(
        @Schema(description = "대분류. 중분류만 있는 행은 null", nullable = true)
        String largeCategory,
        @Schema(description = "중분류. 대분류만 있는 행은 null", nullable = true)
        String mediumCategory
) {
}
