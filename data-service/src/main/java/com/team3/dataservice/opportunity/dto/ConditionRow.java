package com.team3.dataservice.opportunity.dto;

import com.team3.dataservice.opportunity.domain.entity.ConditionType;
import com.team3.dataservice.opportunity.domain.entity.RequirementType;
import com.team3.dataservice.opportunity.domain.entity.ValidationStatus;

import java.util.Map;

/** 판정용 조건 조회 결과 (evidence·created_at은 읽지 않는다) */
public record ConditionRow(
        Long versionId,
        ConditionType conditionType,
        RequirementType requirementType,
        String conditionField,
        Map<String, Object> expression,
        ValidationStatus validationStatus
) {
}
