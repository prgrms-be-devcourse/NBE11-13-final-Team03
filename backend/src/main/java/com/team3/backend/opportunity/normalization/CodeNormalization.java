package com.team3.backend.opportunity.normalization;

import com.team3.backend.opportunity.domain.entity.ValidationStatus;

import java.util.List;

/**
 * 코드 필드 정규화 결과. values는 "이 중 하나에 해당" (ANY_OF) 의미의 내부 값 목록.
 */
public record CodeNormalization(
        String field,
        CodeConditionStatus status,
        List<String> values,
        List<String> unmappedCodes,
        ValidationStatus validationStatus,
        String reason,
        String raw
) {
}
