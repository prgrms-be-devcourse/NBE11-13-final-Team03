package com.team3.backend.tracking.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({EventFanoutProperties.class, EligibilityFanoutProperties.class, EligibilityFanoutSchedulingProperties.class})
public class EventFanoutConfig {
}
