package com.team3.backend.recommendation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 확인된 추천 근거의 배지와 설명을 담는다. */
public record RecommendationReasonsDto(
        @Schema(description = "확인된 근거 배지, 최대 3개") List<String> badges,
        @Schema(description = "배지와 일치하는 1~2문장 설명") String description
) {
}
