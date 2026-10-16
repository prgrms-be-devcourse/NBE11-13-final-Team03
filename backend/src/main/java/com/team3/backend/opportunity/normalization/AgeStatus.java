package com.team3.backend.opportunity.normalization;

/**
 * 연령 조건 상태 (개선된 정제 기준).
 * DATE_BOUND, NOT_APPLICABLE은 텍스트·공식 공고 근거가 필요해 규칙 기반 1차 정규화에서는 만들지 않는다.
 */
public enum AgeStatus {
    RANGE,
    MIN_ONLY,
    MAX_ONLY,
    DATE_BOUND,
    NO_LIMIT,
    NOT_APPLICABLE,
    UNKNOWN,
}
