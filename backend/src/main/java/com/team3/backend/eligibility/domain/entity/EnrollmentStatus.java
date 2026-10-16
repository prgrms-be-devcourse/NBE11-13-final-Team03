package com.team3.backend.eligibility.domain.entity;

/**
 * 학적 상태 (해당 시 필수). NULL은 미입력
 */
public enum EnrollmentStatus {
    ENROLLED,
    LEAVE_OF_ABSENCE,
    EXPECTED_GRADUATION,
    GRADUATED,
    DROPPED_OUT,
}
