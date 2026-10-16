package com.team3.backend.opportunity.normalization;

/**
 * 기준일 기준 현재 신청 상태 (요구사항 명세 5.6). 저장하지 않고 조회할 때 계산한다.
 */
public enum ApplicationStatus {
    UPCOMING,
    OPEN,
    ALWAYS_OPEN,
    CLOSED,
    UNKNOWN,
}
