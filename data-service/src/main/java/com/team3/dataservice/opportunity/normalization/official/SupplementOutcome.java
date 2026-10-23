package com.team3.dataservice.opportunity.normalization.official;

/**
 * 원문 보완 결과.
 * CONFIRMED: API 값과 원문이 같음, FILLED: API에 없거나 모순인 값을 원문으로 채움,
 * OVERRIDDEN: API와 공식 원문(HIGH)이 달라 공식 원문으로 바꿈 (dataConflict 기록),
 * NEEDS_REVIEW: 원문 근거가 여러 개이거나 신뢰도가 낮아 자동 반영하지 않음,
 * DOCUMENT_MISMATCH: 원문의 정책명·연도가 이 정책과 맞지 않음 (다른 정책·다른 연도 공고 가능성)
 * NOT_APPLIED: 원문이 없거나 근거를 못 찾음 (API 결과 그대로)
 */
public enum SupplementOutcome {
    CONFIRMED,
    FILLED,
    OVERRIDDEN,
    NEEDS_REVIEW,
    DOCUMENT_MISMATCH,
    NOT_APPLIED,
}
