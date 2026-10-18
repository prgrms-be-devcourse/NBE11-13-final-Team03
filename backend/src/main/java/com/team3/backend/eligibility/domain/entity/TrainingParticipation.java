package com.team3.backend.eligibility.domain.entity;

/**
 * 직업훈련(KDT 등) 참여 이력. NULL은 미입력. 이력이 있어도 확인된 제도 규칙 없이 불충족 처리하지 않는다
 */
public enum TrainingParticipation {
    NONE,
    IN_PROGRESS,
    COMPLETED,
    DROPPED_OUT,
    UNKNOWN,
}
