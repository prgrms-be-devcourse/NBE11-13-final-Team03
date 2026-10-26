package com.team3.backend.tracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("yon.tracking.version")
public record VersionTrackingProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("25") int maxPoliciesPerRun,
        @DefaultValue("60000") long pollIntervalMs
) {
    public VersionTrackingProperties {
        if (maxPoliciesPerRun < 1 || maxPoliciesPerRun > 500 || pollIntervalMs < 1000) {
            throw new IllegalArgumentException("Version tracking requires policy budget 1..500 and poll interval >= 1000ms");
        }
    }
}
