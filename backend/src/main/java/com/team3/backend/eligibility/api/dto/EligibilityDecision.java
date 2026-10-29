package com.team3.backend.eligibility.api.dto;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;

import java.util.List;

/**
 * 저장된 판정 결과 하나의 판정 내용만 (기회 요약 OpportunityInfo 없음).
 * 기회 상세처럼 기회 정보를 이미 따로 읽는 화면용이다. 최신 기준은 EligibilitySnapshot과 같다.
 */
public record EligibilityDecision(
        EligibilityStatus status,
        String reason,
        List<ConditionResult> conditions
) {
}
