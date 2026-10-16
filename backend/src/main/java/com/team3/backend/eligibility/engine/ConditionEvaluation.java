package com.team3.backend.eligibility.engine;

import com.team3.backend.eligibility.domain.entity.ConditionMatchResult;
import com.team3.backend.opportunity.domain.entity.ConditionType;
import com.team3.backend.opportunity.domain.entity.RequirementType;

/**
 * 조건 하나의 판정 결과 (판정 응답 DTO의 conditions 항목 재료).
 * operator: BETWEEN, GTE, LTE, BORN_BETWEEN, IN, ANY_OF, LTE_AMOUNT 등 / expected: 정책 기준 / actual: 사용자 값
 */
public record ConditionEvaluation(
        ConditionType type,
        String field,
        RequirementType requirement,
        String operator,
        Object expected,
        Object actual,
        ConditionMatchResult result,
        String reason
) {
}
