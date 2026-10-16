package com.team3.backend.eligibility.api.dto;

import com.team3.backend.eligibility.domain.entity.ConditionMatchResult;

/**
 * 조건 하나의 판정 결과.
 * type: AGE, REGION, INCOME 등 (ConditionType 이름), requirement: REQUIRED / EXCLUSION / PREFERRED
 * expected·actual은 조건 종류마다 모양이 달라(숫자, 코드 목록, 맵) 화면 표시는 reason을 쓴다.
 */
public record ConditionResult(
        String type,
        String field,
        String requirement,
        ConditionMatchResult result,
        String reason,
        Object expected,
        Object actual
) {
}
