package com.team3.dataservice.opportunity.domain.entity;

/**
 * 조건의 성격. 우대조건(PREFERRED)은 신청 자격 판정에 사용하지 않는다.
 * EXCLUSION은 해당하면 신청할 수 없는 조건이다.
 */
public enum RequirementType {
    REQUIRED,
    PREFERRED,
    EXCLUSION,
}
