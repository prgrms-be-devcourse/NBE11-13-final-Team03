package com.team3.dataservice.opportunity.normalization;

/** 코드 조건 상태. NO_LIMIT은 코드표상 "제한 없음"일 때만, UNKNOWN은 값 없음·해석 불가 */
public enum CodeConditionStatus {
    NO_LIMIT,
    SPECIFIC,
    UNKNOWN,
}
