package com.team3.backend.tracking.domain.entity;

public enum OpportunityEventSuppressionReason {

    /**
     * 최초 적재 또는 Cutover 기준선에 포함된 데이터.
     */
    BASELINE_DATA,

    /**
     * 누락 스캔 복구 시 D-7과 같은 알림 시점이 이미 지남.
     */
    THRESHOLD_ALREADY_PASSED,

    /**
     * 정규화 규칙 버전 변경으로 발생한 정책 버전이므로
     * 실제 정책 변경 알림으로 확정하지 않음.
     */
    NORMALIZATION_VERSION_CHANGED,

    /**
     * 현재 날짜 알림 범위에서 지원하지 않는 정책 유형.
     */
    UNSUPPORTED_OPPORTUNITY_TYPE,

    /**
     * 신청기간이 유효하지 않거나 공식 기간으로 확인되지 않음.
     */
    INVALID_APPLICATION_PERIOD,

    /**
     * 이벤트 생성 후 정책 상태가 다시 바뀌어
     * 이벤트가 기대한 상태가 더 이상 유효하지 않음.
     */
    STALE_POLICY_STATE
}
