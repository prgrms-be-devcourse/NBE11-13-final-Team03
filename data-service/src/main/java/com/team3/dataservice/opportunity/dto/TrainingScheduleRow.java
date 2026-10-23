package com.team3.dataservice.opportunity.dto;

import java.time.LocalDate;

/** 훈련과정 회차의 NCS 코드·훈련 시작일 조회 결과 */
public record TrainingScheduleRow(
        Long versionId,
        String ncsCode,
        LocalDate trainingStartDate
) {
}
