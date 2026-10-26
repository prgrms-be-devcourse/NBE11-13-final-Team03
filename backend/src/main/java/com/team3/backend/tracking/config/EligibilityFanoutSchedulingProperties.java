package com.team3.backend.tracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Execution limits apply to manual and scheduled runs alike; these are not operational tuning guarantees. */
@ConfigurationProperties("yon.tracking.eligibility-fanout.scheduler")
public record EligibilityFanoutSchedulingProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("10") int maxPagesPerRun,
        @DefaultValue("2") int maxPagesPerEvent,
        @DefaultValue("60000") long pollIntervalMs
) {
    public EligibilityFanoutSchedulingProperties {
        if (maxPagesPerRun < 1 || maxPagesPerRun > 500 || maxPagesPerEvent < 1
                || maxPagesPerEvent > maxPagesPerRun || pollIntervalMs < 1000) {
            throw new IllegalArgumentException("Invalid eligibility fanout scheduling configuration");
        }
    }
}
