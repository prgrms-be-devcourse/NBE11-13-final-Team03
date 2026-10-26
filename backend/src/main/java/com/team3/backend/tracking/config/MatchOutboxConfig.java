package com.team3.backend.tracking.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({MatchOutboxProperties.class,MatchAutomaticProperties.class})
public class MatchOutboxConfig {
    /** The legacy writer reads the same flag and yields new positive transitions, not saved loss/review. */
    public record Ready() { }
    @Bean @ConditionalOnProperty(name="yon.tracking.match.outbox.enabled",havingValue="true")
    Ready matchOutboxReady(MatchTrackingProperties match) {
        if (!match.enabled()) throw new IllegalStateException("Match observation must be enabled before its Outbox route");
        return new Ready();
    }
}
