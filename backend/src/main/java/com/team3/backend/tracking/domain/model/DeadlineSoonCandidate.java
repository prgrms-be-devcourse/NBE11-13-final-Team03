package com.team3.backend.tracking.domain.model;

import java.time.LocalDate;
import java.util.Objects;

public record DeadlineSoonCandidate(
        LocalDate eventDate,
        LocalDate endDate,
        DeadlineThreshold threshold
) {

    public DeadlineSoonCandidate {
        Objects.requireNonNull(eventDate, "eventDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(threshold, "threshold");
    }
}
