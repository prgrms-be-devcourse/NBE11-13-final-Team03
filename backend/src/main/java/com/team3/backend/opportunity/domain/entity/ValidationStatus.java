package com.team3.backend.opportunity.domain.entity;

/**
 * VALID: 검증 완료
 * INVALID: 데이터 오류 (날짜 파싱 실패, 시작일 > 종료일 등)
 * DATA_CONFLICT: 값 사이 모순 또는 출처 간 충돌 (연령 0/0/Y, API와 정책 원문 불일치, 규칙과 AI 결과 불일치 등)
 * UNKNOWN: 근거 부족·미확인
 * NEEDS_REVIEW: 복합조건·예외 등 추가 검토 필요 (AI 구조화 후보)
 */
public enum ValidationStatus {
    VALID,
    INVALID,
    DATA_CONFLICT,
    UNKNOWN,
    NEEDS_REVIEW,
}
