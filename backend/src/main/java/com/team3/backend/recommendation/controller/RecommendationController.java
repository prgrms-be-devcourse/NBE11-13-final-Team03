package com.team3.backend.recommendation.controller;

import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.recommendation.dto.RecommendationItemDto;
import com.team3.backend.recommendation.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 인증 사용자의 개인화 추천 목록을 제공한다. */
@Tag(name = "Recommendation", description = "청년정책·직업훈련 개인화 추천 API")
@SecurityRequirement(name = "cookieAuth")
@RestController
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    /** 저장된 최신 판정과 관심 분야를 반영한 최대 다섯 건을 선정 순서대로 반환한다. */
    @Operation(summary = "내 개인화 추천 조회", description = "로그인과 판정 프로필이 필요하다. 저장된 최신 신청 가능성 판정 결과만 사용하고 새로 판정하지 않는다. 관심 분야는 선택 사항이다. 숨긴 공고는 제외하며 추천 가능 공고가 없으면 빈 배열을 반환한다. 판정이 아직 완료되지 않았거나 최신 판정 결과가 없어도 빈 배열일 수 있으므로, 빈 배열이 반드시 신청 조건에 맞는 공고가 없다는 뜻은 아니다. 추천 이유의 교육 시작일은 신청 마감일이 아니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "추천 목록 조회 성공, 최대 5건 또는 빈 배열"),
            @ApiResponse(responseCode = "401", description = "Access Token이 없거나 유효하지 않은 경우"),
            @ApiResponse(responseCode = "404", description = "판정 프로필이 없는 경우 (ELIGIBILITY_001)")
    })
    @GetMapping("/api/opportunities/recommendations")
    public ResponseEntity<List<RecommendationItemDto>> recommendations(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        if (userDetails == null) {
            throw new BusinessException(AuthErrorCode.ACCESS_TOKEN_NOT_FOUND);
        }
        return ResponseEntity.ok(recommendationService.recommend(userDetails.getUserId()));
    }
}
