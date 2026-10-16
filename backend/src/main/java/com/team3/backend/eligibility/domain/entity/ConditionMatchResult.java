package com.team3.backend.eligibility.domain.entity;

/**
 * 조건 하나의 판정 결과.
 * MATCHED / NOT_MATCHED: 충족 / 불충족
 * USER_INFO_MISSING: 사용자 프로필 미입력(NULL) 또는 모름(UNKNOWN)
 * CHECK_REQUIRED: 사용자가 원문을 직접 확인해야 함 (추가 자격·참여 제한 원문, "기타" 코드, 기준이 불명확한 소득 등)
 * DATA_UNCLEAR: 정책 조건 자체가 불확실 (모순·오류·검토 필요. 예: 연령 제한 Y인데 API·공식 원문 모두 연령 없음)
 * NOT_SPECIFIED: 정책 데이터에 해당 조건이 비어 있음 — 응답에는 보여 주되 집계에서 제외
 * 집계 규칙은 {@link EligibilityStatus} 참고.
 */
public enum ConditionMatchResult {
    MATCHED,
    NOT_MATCHED,
    USER_INFO_MISSING,
    CHECK_REQUIRED,
    DATA_UNCLEAR,
    NOT_SPECIFIED,
}
