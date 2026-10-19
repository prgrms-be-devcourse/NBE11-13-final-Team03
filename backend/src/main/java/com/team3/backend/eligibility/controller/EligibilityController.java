package com.team3.backend.eligibility.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.dto.EligibilityListResponse;
import com.team3.backend.eligibility.dto.EligibilityResponse;
import com.team3.backend.eligibility.service.EligibilityQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(
        name = "Eligibility",
        description = "기회별 신청 가능성 판정 API"
)
@RestController
@RequiredArgsConstructor
public class EligibilityController {

    private final EligibilityQueryService queryService;

    @Operation(
            summary = "기회 하나의 신청 가능성 판정",
            description = """
                    내 판정 조건으로 기회의 현재 버전을 판정합니다. 조건별 결과(conditions)를 포함합니다.
                    eligibility: ELIGIBLE / POSSIBLY_ELIGIBLE / NOT_ELIGIBLE / UNKNOWN
                    applicationStatus: UPCOMING / OPEN / ALWAYS_OPEN / CLOSED / UNKNOWN (오늘 기준 계산)
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "판정 성공"),
            @ApiResponse(responseCode = "401", description = "Access Token이 없거나 유효하지 않은 경우"),
            @ApiResponse(responseCode = "404", description = "판정 조건 미등록(ELIGIBILITY_001) 또는 없는 기회(ELIGIBILITY_002)"),
            @ApiResponse(responseCode = "409", description = "아직 정규화되지 않은 기회 (ELIGIBILITY_003)")
    })
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping("/api/opportunities/{opportunityId}/eligibility")
    public ResponseEntity<EligibilityResponse> evaluate(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long opportunityId
    ) {
        return ResponseEntity.ok(queryService.evaluate(userDetails.getUserId(), opportunityId));
    }

    @Operation(
            summary = "기회 목록의 신청 가능성 판정",
            description = "정규화된 기회를 최신순으로 페이지 단위 판정합니다. 조건별 상세는 단건 API에서 봅니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "판정 성공"),
            @ApiResponse(responseCode = "400", description = "페이지 요청값 오류 (ELIGIBILITY_006, size 1~100)"),
            @ApiResponse(responseCode = "401", description = "Access Token이 없거나 유효하지 않은 경우"),
            @ApiResponse(responseCode = "404", description = "판정 조건 미등록 (ELIGIBILITY_001)")
    })
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping("/api/users/me/opportunities/eligibility")
    public ResponseEntity<EligibilityListResponse> evaluateAll(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "0부터 시작") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "1~100") @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(queryService.evaluateAll(userDetails.getUserId(), page, size));
    }
}
