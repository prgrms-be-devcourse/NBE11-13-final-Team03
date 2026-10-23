package com.team3.backend.eligibility.api.dto;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 추천에 필요한 기회 요약. 판정 결과와 같은 기준일(KST 오늘)로 계산한다.
 *
 * - category / subCategory: 온통청년 분류 체계의 대·중분류 (고용24 훈련은 교육･직업훈련 / 직업훈련)
 * - ncsCode: 고용24 훈련만 (청년정책은 null)
 * - applicationStatus: 신청기간으로 계산한 현재 모집 상태 (UPCOMING, OPEN, ALWAYS_OPEN, CLOSED, UNKNOWN)
 * - applicationEndDate: 오늘 이후로 끝나는 신청기간 중 가장 이른 종료일. 상시·마감·미확인이면 null
 * - trainingStartDate: 고용24 훈련 시작일 (청년정책은 null)
 * - registeredAt: 정책 엔진에 처음 들어온 시각 (원 출처 등록일 아님)
 */
public record OpportunityInfo(
        Long opportunityId,
        OpportunityType opportunityType,
        String title,
        String category,
        String subCategory,
        String ncsCode,
        ApplicationStatus applicationStatus,
        LocalDate applicationEndDate,
        LocalDate trainingStartDate,
        LocalDateTime registeredAt,
        String detailUrl
) {
}
