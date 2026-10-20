package com.team3.backend.eligibility.dto;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;

/** 저장된 판정 결과의 상태만 읽는 조회 결과 (조건별 결과 jsonb는 읽지 않는다) */
public record EligibilityStatusRow(Long opportunityId, EligibilityStatus status) {
}
