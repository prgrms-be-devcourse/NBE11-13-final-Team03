package com.team3.backend.eligibility.domain.entity;

/**
 * 국민내일배움카드 상태. NULL은 미입력. 미발급이어도 자동 불충족 처리하지 않는다
 */
public enum TrainingCardStatus {
    ISSUED,
    APPLYING,
    NOT_ISSUED,
    UNKNOWN,
}
