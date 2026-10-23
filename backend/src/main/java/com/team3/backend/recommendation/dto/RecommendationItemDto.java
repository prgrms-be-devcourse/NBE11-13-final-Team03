package com.team3.backend.recommendation.dto;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.search.dto.OpportunityDetailResponseDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 선정된 공고의 기존 분류·날짜와 개인화 추천 근거를 제공한다. */
public record RecommendationItemDto(
        @Schema(description = "공고 ID") Long opportunityId,
        @Schema(description = "공고 유형") OpportunityType type,
        @Schema(description = "공고 제목") String title,
        @Schema(description = "대분류") String category,
        @Schema(description = "중분류") String subCategory,
        @Schema(description = "세 번째 분류 (직업훈련: NCS 중분류)", nullable = true) String detailCategory,
        @Schema(description = "현재 버전의 지역 목록. 정책은 거주 대상 지역, 훈련은 교육 장소이며 전국·미확인은 상태로 구분한다")
        List<OpportunityDetailResponseDto.RegionItem> regions,
        @Schema(description = "현재 모집 상태. 직업훈련은 실제 모집기간 미확인으로 UNKNOWN이다") ApplicationStatus applicationStatus,
        @Schema(description = "저장된 최신 신청 가능성 판정") EligibilityStatus eligibilityStatus,
        @Schema(description = "청년정책 신청 마감일이며, 상시 모집·직업훈련은 null이다", nullable = true) LocalDate applicationEndDate,
        @Schema(description = "직업훈련 교육 시작일이며 신청 마감일이 아니다", nullable = true) LocalDate trainingStartDate,
        @Schema(description = "Y:ON 시스템 최초 등록 시각") LocalDateTime createdAt,
        @Schema(description = "로그인 사용자의 저장 여부") boolean saved,
        @Schema(description = "실제 선정 근거로 만든 추천 이유") RecommendationReasonsDto recommendationReasons
) {
}
