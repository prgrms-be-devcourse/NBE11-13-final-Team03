package com.team3.backend.tracking.domain.entity;

public enum OpportunityEventProcessingStatus {

    /**
     * Worker가 아직 처리하지 않은 이벤트.
     */
    PENDING,

    /**
     * Worker가 점유하여 처리 중인 이벤트.
     */
    PROCESSING,

    /**
     * 대상 사용자 알림 생성이 완료된 이벤트.
     */
    COMPLETED,

    /**
     * 최대 재시도 횟수를 초과하거나 복구할 수 없는 오류가 발생한 이벤트.
     */
    FAILED
}
