package com.team3.backend.tracking.domain.entity;

/**
 * 이벤트 Worker(EventFanoutBatch)는 DateNotificationMessageFactory.supports에 있는 유형만 사용자별 Outbox로 만든다.
 * 같은 허용 목록을 후보 조회·claim·소진 복구에 적용하며 다른 유형은 해당 Worker가 점유하지 않는다.
 * 별도 유형의 생산자를 추가하기 전에 전용 소비 경로와 Notification 계약 표본을 준비한다.
 */
public enum OpportunityEventType {

    /**
     * 새로운 정책이 추적 대상에 추가됨.
     * 최초 전체 적재 데이터에는 생성하지 않는다.
     */
    OPPORTUNITY_CREATED,

    /**
     * 모집 예정 상태에서 모집 중 상태로 변경됨.
     */
    APPLICATION_OPENED,

    /**
     * 모집 중 상태에서 마감 상태로 변경됨.
     */
    APPLICATION_CLOSED,

    /**
     * 신청 마감일까지 D-7, D-3, D-1이 됨.
     */
    DEADLINE_SOON,

    /**
     * 이전 정책 버전보다 마감일이 연장됨.
     */
    DEADLINE_EXTENDED,

    /**
     * 정책 조건 변경으로 사용자의 신청 가능성이 달라질 수 있음.
     */
    ELIGIBILITY_CHANGED
}
