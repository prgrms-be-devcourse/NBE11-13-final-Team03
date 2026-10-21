package com.team3.backend.search.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.search.dto.OpportunityDetailResponseDto;
import com.team3.backend.search.service.OpportunityDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 비로그인·로그인 사용자의 공고 상세 조회 요청을 받는다. */
@Tag(name = "Opportunity Search", description = "청년정책·직업훈련 통합 검색 API")
@RestController
@RequiredArgsConstructor
public class OpportunityDetailController {

    private final OpportunityDetailService detailService;

    /** 정규화된 현재 공고 상세와 로그인 사용자의 저장된 개인화 정보를 반환한다. */
    @Operation(summary = "공고 상세 조회", description = "비로그인도 조회할 수 있다. 현재 정규화 버전의 공고 정보와 실제 신청 상태를 반환한다. 로그인 사용자는 저장된 최신 판정과 저장 여부를 함께 받으며, 조회 중 재판정하지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상세 조회 성공"),
            @ApiResponse(responseCode = "404", description = "공고가 없거나 현재 정규화 버전이 없음 (OPPORTUNITY_001)")
    })
    @GetMapping("/api/opportunities/{opportunityId}")
    public ResponseEntity<OpportunityDetailResponseDto> get(
            @PathVariable Long opportunityId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(detailService.get(opportunityId,
                userDetails == null ? null : userDetails.getUserId()));
    }
}
