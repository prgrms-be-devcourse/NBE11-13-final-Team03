package com.team3.backend.eligibility.dto;

import com.team3.backend.eligibility.domain.entity.ConditionMatchResult;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.engine.ConditionEvaluation;
import com.team3.backend.eligibility.engine.EligibilityEvaluation;
import com.team3.backend.opportunity.domain.entity.ConditionType;
import com.team3.backend.opportunity.domain.entity.RequirementType;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/opportunities/{id}/eligibility 응답.
 * eligibility(자격 판정)와 applicationStatus(현재 신청 상태)는 별개다. 마감된 정책도 자격 판정은 내려준다.
 */
public record EligibilityResponse(
        Long opportunityId,
        Long opportunityVersionId,
        EligibilityStatus eligibility,
        ApplicationStatus applicationStatus,
        OffsetDateTime evaluatedAt,
        List<Condition> conditions,
        String reason
) {

    public record Condition(
            ConditionType type,
            String field,
            RequirementType requirement,
            String operator,
            Object expected,
            Object actual,
            ConditionMatchResult result,
            String reason
    ) {

        static Condition from(ConditionEvaluation e) {
            return new Condition(e.type(), e.field(), e.requirement(), e.operator(), e.expected(), e.actual(),
                    e.result(), e.reason());
        }
    }

    public static EligibilityResponse of(Long opportunityId, Long versionId, EligibilityEvaluation evaluation,
                                         ApplicationStatus applicationStatus, OffsetDateTime evaluatedAt) {
        return new EligibilityResponse(opportunityId, versionId, evaluation.status(), applicationStatus, evaluatedAt,
                evaluation.conditions().stream().map(Condition::from).toList(), evaluation.reason());
    }
}
