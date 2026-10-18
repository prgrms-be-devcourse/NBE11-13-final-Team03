package com.team3.backend.opportunity.normalization;

import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;

import java.time.LocalDate;

/**
 * 신청기간 하나 (ApplicationWindow로 저장될 값).
 * periodText: aplyYmd에서 이 기간에 해당하는 원문
 */
public record ApplicationPeriod(
        PeriodType periodType,
        LocalDate startDate,
        LocalDate endDate,
        ValidationStatus validationStatus,
        String periodText
) {
}
