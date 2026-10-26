package com.team3.notification.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class NotificationReceptionConfig {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock notificationClock() {
        return Clock.systemUTC();
    }
}
