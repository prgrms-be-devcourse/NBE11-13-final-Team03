package com.team3.backend.region.controller;

import com.team3.backend.region.dto.RegionsResponse;
import com.team3.backend.region.service.RegionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로필 거주지·학교 소재지 선택용 지역 목록.
 */
@Tag(
        name = "Region",
        description = "지역 사전 조회 API"
)
@RestController
@RequiredArgsConstructor
public class RegionController {

    private final RegionQueryService regionQueryService;

    @Operation(
            summary = "지역 목록 조회",
            description = """
                    로그인하지 않은 사용자도 조회할 수 있는 공개 API입니다.
                    판정 프로필의 거주지(residenceRegionCode)·학교 소재지(schoolRegionCode) 선택에 사용합니다.

                    지역 사전(행정표준코드 법정동코드의 시·도, 시·군·구) 전체를 코드 오름차순 평면 목록으로 반환합니다.
                    parentCode로 시·도 → 시·군·구 → 일반구 계층을 구성합니다.
                    - 시·도와 세종특별자치시(3611000000)는 parentCode가 null입니다. 세종은 하위가 없어 leaf=true입니다.
                    - 일반구가 있는 시(예: 수원시 4111000000)는 leaf=false이고, 그 아래 구(예: 장안구 4111100000)가 leaf=true입니다.

                    프로필 저장 규칙
                    - residenceRegionCode: leaf=true인 코드만 저장할 수 있습니다 (아니면 INVALID_REGION_CODE).
                    - schoolRegionCode: 목록에 있는 코드면 계층과 관계없이 저장할 수 있습니다.
                    - 전국·미확인 같은 특수 코드는 없습니다. 목록에 없는 값은 저장할 수 없습니다.

                    지역 사전이 비어 있으면 빈 배열을 반환합니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "지역 목록 조회 성공"
            )
    })
    @GetMapping("/api/regions")
    public ResponseEntity<RegionsResponse> getRegions() {
        return ResponseEntity.ok(regionQueryService.getRegions());
    }
}
