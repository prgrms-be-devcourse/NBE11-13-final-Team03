package com.team3.backend.eligibility.domain.entity;

/**
 * 값 입력형 항목의 응답 상태. NULL은 미입력, PROVIDED일 때만 값이 있다. NOT_APPLICABLE은 해당 없음
 */
public enum AnswerStatus {
    PROVIDED,
    NOT_APPLICABLE,
    UNKNOWN,
}
