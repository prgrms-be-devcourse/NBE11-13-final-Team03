package com.team3.backend.client.config;

import com.team3.backend.outbox.config.NotificationPublisherProperties;
import feign.Logger;
import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;
import java.util.concurrent.TimeUnit;

/**
 * Loaded only by NotificationClient's child context.
 * Do not add @Configuration/@Component: that would apply these settings to unrelated clients.
 */
public class NotificationFeignConfig {
    @Bean
    Request.Options notificationRequestOptions(NotificationPublisherProperties properties) {
        return new Request.Options(properties.connectTimeout().toMillis(), TimeUnit.MILLISECONDS,
                properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS, false);
    }

    @Bean
    Retryer notificationRetryer() { return Retryer.NEVER_RETRY; }

    @Bean
    Logger.Level notificationLoggerLevel() { return Logger.Level.NONE; }
}
