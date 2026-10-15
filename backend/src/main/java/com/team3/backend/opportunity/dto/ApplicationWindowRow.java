package com.team3.backend.opportunity.dto;

import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;

import java.time.LocalDate;

/** 신청 상태 계산용 신청기간 조회 결과 (scope는 읽지 않는다) */
public record ApplicationWindowRow(
        Long versionId,
        PeriodType periodType,
        LocalDate startDate,
        LocalDate endDate,
        ValidationStatus validationStatus,
        String periodText
) {
}
