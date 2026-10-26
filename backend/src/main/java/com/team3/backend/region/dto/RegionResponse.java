package com.team3.backend.region.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 지역 사전 한 행. 프론트는 parentCode로 시·도 → 시·군·구 → (일반구) 계층을 만든다.
 */
public record RegionResponse(
        @Schema(description = "법정동코드 10자리", example = "1168000000")
        String code,
        @Schema(description = "지역 이름", example = "강남구")
        String name,
        @Schema(description = "상위 지역 코드. 시·도와 세종(3611000000)은 null", example = "1100000000", nullable = true)
        String parentCode,
        @Schema(description = "하위 지역이 없으면 true. 거주지(residenceRegionCode)는 leaf=true인 코드만 저장할 수 있다", example = "true")
        boolean leaf
) {
}
