package com.team3.backend.eligibility.domain.entity;

/**
 * 특화 대상 (민감정보, 선택). 온통청년 정책특화요건코드(sbizCd) 기준이며, NEAR_POVERTY(차상위)는 서비스 자체 값이다.
 */
public enum SpecialTarget {
    SME_EMPLOYEE,
    WOMAN,
    BASIC_LIVELIHOOD_RECIPIENT,
    SINGLE_PARENT,
    DISABLED,
    FARMER,
    SOLDIER,
    LOCAL_TALENT,
    NEAR_POVERTY,
}
