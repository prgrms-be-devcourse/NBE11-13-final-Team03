package com.team3.dataservice.opportunity.normalization;

import com.team3.dataservice.opportunity.domain.entity.ValidationStatus;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 소득 조건 정규화 결과.
 * min/max: type이 AMOUNT면 원, MEDIAN_PERCENT면 %, STUDENT_LOAN_BRACKET이면 구간. 경계 포함 여부는 따로 보존한다.
 * period: MONTHLY|YEARLY|UNKNOWN, subject: INDIVIDUAL|HOUSEHOLD|UNKNOWN (확인되지 않으면 임의로 정하지 않는다)
 * unitConfirmed: 금액 단위를 원문에서 확인했는지 (API 구조화 금액은 단위가 확인되지 않음)
 */
public record IncomeNormalization(
        IncomeStatus status,
        IncomeType type,
        BigDecimal min,
        BigDecimal max,
        Boolean minInclusive,
        Boolean maxInclusive,
        String period,
        String subject,
        boolean unitConfirmed,
        ValidationStatus validationStatus,
        boolean dataConflict,
        String evidenceText,
        String reason,
        Map<String, String> raw
) {
}
