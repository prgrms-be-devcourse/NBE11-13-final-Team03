package com.team3.backend.opportunity.domain.entity;

/**
 * VALID: 검증 완료
 * INVALID: 데이터 오류 (날짜 파싱 실패, 시작일 > 종료일 등)
 * DATA_CONFLICT: 값 사이 모순 또는 출처 간 충돌 (연령 0/0/Y, API와 정책 원문 불일치, 규칙과 AI 결과 불일치 등)
 * UNKNOWN: 근거 부족·미확인
 * NEEDS_REVIEW: 복합조건·예외 등 추가 검토 필요 (AI 구조화 후보)
 * UNVERIFIED: 값은 있지만 공식 출처로 확인하지 못함 (API 값이 없거나 모순이어서 API 자유텍스트 등 공식 출처가 아닌 근거로 채운 값).
 *   판정에서 불리한 결과도 유리한 결과도 확정하지 않는다 (EligibilityEngine.applyValidation).
 */
public enum ValidationStatus {
    VALID,
    INVALID,
    DATA_CONFLICT,
    UNKNOWN,
    NEEDS_REVIEW,
    UNVERIFIED,
}
