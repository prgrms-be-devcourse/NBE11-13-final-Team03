package com.team3.backend.eligibility.domain.entity;

/**
 * 취업 상태 (필수, 모름 없음). 온통청년 정책취업요건코드(jobCd)와 대응한다.
 * STARTUP_PREPARING: (예비)창업자, PART_TIME: 단기근로자·아르바이트
 */
public enum EmploymentStatus {
    UNEMPLOYED,
    EMPLOYED,
    SELF_EMPLOYED,
    FREELANCER,
    PART_TIME,
    STARTUP_PREPARING,
    DAY_LABORER,
    FARMER,
}
