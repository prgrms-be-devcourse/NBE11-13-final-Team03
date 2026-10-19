package com.team3.backend.eligibility.engine;

import com.team3.backend.opportunity.domain.entity.ConditionType;
import com.team3.backend.opportunity.domain.entity.OpportunityCondition;
import com.team3.backend.opportunity.domain.entity.RequirementType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;

import java.util.Map;

/**
 * 판정 엔진의 조건 입력. 엔진이 조건에서 읽는 값만 담고 JPA 엔티티에 의존하지 않는다. 프로필 입력은 EligibilityProfileInput.
 */
public record EligibilityConditionInput(
        ConditionType type,
        RequirementType requirement,
        String field,
        Map<String, Object> expression,
        ValidationStatus validationStatus
) {

    public static EligibilityConditionInput from(OpportunityCondition c) {
        return new EligibilityConditionInput(c.getConditionType(), c.getRequirementType(), c.getConditionField(),
                c.getExpression(), c.getValidationStatus());
    }
}
