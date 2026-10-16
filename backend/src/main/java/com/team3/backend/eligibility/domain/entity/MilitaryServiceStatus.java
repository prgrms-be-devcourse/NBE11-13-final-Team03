package com.team3.backend.eligibility.domain.entity;

/**
 * 병역 상태. NULL은 미입력
 */
public enum MilitaryServiceStatus {
    COMPLETED,
    SERVING,
    NOT_COMPLETED,
    EXEMPTED,
    NOT_APPLICABLE,
    UNKNOWN,
}
