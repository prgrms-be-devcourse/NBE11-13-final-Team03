package com.team3.backend.tracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

@ConfigurationProperties("yon.tracking.date")
public record DateTrackingProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("13:00") LocalTime cutoff,
        @DefaultValue("PT30M") Duration leaseTimeout,
        @DefaultValue("100") int batchSize,
        @DefaultValue("7") int maxDatesPerRun,
        LocalDate startDate
) {
    public DateTrackingProperties {
        if (cutoff == null || leaseTimeout == null || leaseTimeout.isZero() || leaseTimeout.isNegative()
                || batchSize < 1 || maxDatesPerRun < 1) {
            throw new IllegalArgumentException("Invalid date tracking configuration");
        }
        if (enabled && startDate == null) {
            throw new IllegalArgumentException("yon.tracking.date.start-date is required when enabled");
        }
    }
}
