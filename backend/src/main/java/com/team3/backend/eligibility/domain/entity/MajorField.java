package com.team3.backend.eligibility.domain.entity;

/**
 * 전공 계열. NULL은 미입력. 온통청년 정책전공요건코드(plcyMajorCd) 기준이다.
 * 교육·의약 등 해당 계열이 없으면 OTHER.
 */
public enum MajorField {
    HUMANITIES,
    SOCIAL_SCIENCE,
    BUSINESS_ECONOMICS,
    NATURAL_SCIENCE,
    ENGINEERING,
    ARTS_AND_PHYSICAL,
    AGRICULTURE,
    OTHER,
}
