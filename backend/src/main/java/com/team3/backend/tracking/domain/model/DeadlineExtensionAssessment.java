package com.team3.backend.tracking.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/** Structural application-period assessment, not approval to send an alert. */
public record DeadlineExtensionAssessment(
        Outcome outcome,
        LocalDate previousEndDate,
        LocalDate currentEndDate
) {
    public enum Outcome {
        EXTENDED,
        NOT_EXTENDED,
        INCONCLUSIVE
    }

    public DeadlineExtensionAssessment {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.INCONCLUSIVE) {
            if (previousEndDate != null || currentEndDate != null) {
                throw new IllegalArgumentException("An inconclusive assessment has no matched deadline pair");
            }
        } else {
            Objects.requireNonNull(previousEndDate, "previousEndDate");
            Objects.requireNonNull(currentEndDate, "currentEndDate");
            if ((outcome == Outcome.EXTENDED) != currentEndDate.isAfter(previousEndDate)) {
                throw new IllegalArgumentException("Assessment and deadline order disagree");
            }
        }
    }
}
