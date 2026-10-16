package com.team3.backend.eligibility.domain.entity;

/**
 * 정책 단위 신청 가능성 판정 결과. 필수조건(REQUIRED)과 제외조건(EXCLUSION)만 대상으로 하며 우대조건은 제외한다.
 * 아래 순서로 먼저 해당하는 상태가 결과가 된다.
 *
 * 1. NOT_ELIGIBLE      : 필수조건 NOT_MATCHED가 하나라도 있거나 제외조건에 해당(MATCHED)
 * 2. UNKNOWN           : DATA_UNCLEAR가 하나라도 있음 (정책 데이터 자체가 불확실 — 사용자가 다 입력해도 판정 불가)
 * 3. POSSIBLY_ELIGIBLE : USER_INFO_MISSING 또는 CHECK_REQUIRED가 하나라도 있음 (나머지는 모두 충족)
 * 4. ELIGIBLE          : 위에 해당하지 않음 (NOT_SPECIFIED는 집계에서 제외)
 */
public enum EligibilityStatus {
    ELIGIBLE,
    NOT_ELIGIBLE,
    POSSIBLY_ELIGIBLE,
    UNKNOWN,
}
