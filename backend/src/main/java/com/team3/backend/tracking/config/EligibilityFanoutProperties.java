package com.team3.backend.tracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

/** Independent workload limits: eligibility pages perform evaluation and revalidation per user. */
@ConfigurationProperties("yon.tracking.eligibility-fanout")
public record EligibilityFanoutProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("25") int batchSize,
        @DefaultValue("10") int maxEventsPerRun,
        @DefaultValue("PT30M") Duration leaseTimeout,
        @DefaultValue("PT1M") Duration retryDelay,
        @DefaultValue("5") int maxAttempts
) {
    public EligibilityFanoutProperties {
        if (batchSize < 1 || batchSize > 500 || maxEventsPerRun < 1 || maxAttempts < 1
                || leaseTimeout == null || leaseTimeout.isNegative() || leaseTimeout.isZero()
                || retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("Invalid eligibility fanout configuration");
        }
    }
}
