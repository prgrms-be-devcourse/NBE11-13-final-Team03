package com.team3.backend.eligibility.engine;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;

import java.util.List;

public record EligibilityEvaluation(
        EligibilityStatus status,
        List<ConditionEvaluation> conditions,
        String reason
) {
}
