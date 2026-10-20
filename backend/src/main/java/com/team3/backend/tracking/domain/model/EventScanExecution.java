package com.team3.backend.tracking.domain.model;

import java.time.LocalDate;
import java.util.Objects;

public record EventScanExecution(
        LocalDate scanDate,
        String executionId
) {

    public EventScanExecution {
        Objects.requireNonNull(scanDate, "scanDate");

        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException(
                    "스캔 실행 식별자는 필수입니다."
            );
        }
    }
}