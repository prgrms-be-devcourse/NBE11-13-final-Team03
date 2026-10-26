package com.team3.backend.eligibility.event;

/** Published after a user's batch refresh returns successfully, even when its cache needed no writes. */
public record EligibilityResultsRefreshed(long userId) {
    public EligibilityResultsRefreshed {
        if (userId<1) throw new IllegalArgumentException("Persisted user ID required");
    }
}
