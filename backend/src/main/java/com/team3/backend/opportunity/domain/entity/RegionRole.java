package com.team3.backend.opportunity.domain.entity;

/**
 * 지역 코드가 어떤 의미의 지역인지 구분한다.
 * 정책 시행 지역(IMPLEMENTATION)을 거주지 조건(RESIDENCE)으로 사용하지 않는다.
 */
public enum RegionRole {
    RESIDENCE,
    SCHOOL,
    BUSINESS,
    IMPLEMENTATION,
    UNKNOWN,
}
