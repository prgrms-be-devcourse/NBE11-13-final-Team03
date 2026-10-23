package com.team3.backend.tracking.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DateTrackingProperties.class)
public class DateTrackingConfig {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock trackingClock() {
        return Clock.system(KST);
    }
}
