package com.team3.backend.tracking.domain.model;

import java.util.List;
import java.util.Objects;

/** Structural audit result, not a decision to notify users. */
public record SnapshotComparison(Outcome outcome, List<SnapshotDifference> differences) {
    public enum Outcome { NO_CHANGE, CHANGES_DETECTED, NORMALIZATION_VERSION_CHANGED }

    public SnapshotComparison {
        Objects.requireNonNull(outcome, "outcome");
        differences = List.copyOf(differences);
        if ((outcome == Outcome.NO_CHANGE && !differences.isEmpty())
                || (outcome == Outcome.CHANGES_DETECTED && differences.isEmpty())) {
            throw new IllegalArgumentException("Comparison outcome does not match differences");
        }
    }
}
