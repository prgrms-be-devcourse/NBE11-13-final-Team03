package com.team3.backend.opportunity.normalization;

/** 지역 조건 상태. NATIONAL은 전국, SPECIFIC은 특정 지역, UNKNOWN은 지역을 확인할 수 없음 (전국으로 해석하지 않는다) */
public enum RegionStatus {
    NATIONAL,
    SPECIFIC,
    UNKNOWN,
}
