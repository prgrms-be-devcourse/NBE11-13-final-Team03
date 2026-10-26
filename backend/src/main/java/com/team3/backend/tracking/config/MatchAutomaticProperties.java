package com.team3.backend.tracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounded automatic publication; all switches default to off. */
@ConfigurationProperties("yon.tracking.match.automatic")
public record MatchAutomaticProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean schedulerEnabled,
        @DefaultValue("25") int maxTransitionsPerRun,
        @DefaultValue("25") int maxSummaryUsersPerRun,
        @DefaultValue("60000") long pollIntervalMs) {
    public MatchAutomaticProperties {
        if (maxTransitionsPerRun < 1 || maxTransitionsPerRun > 500
                || maxSummaryUsersPerRun < 1 || maxSummaryUsersPerRun > 500
                || pollIntervalMs < 1000) {
            throw new IllegalArgumentException("Invalid match automatic publication budget");
        }
    }
}
