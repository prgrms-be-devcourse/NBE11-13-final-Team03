package com.team3.backend.outbox.config;

import com.team3.backend.client.NotificationClient;
import com.team3.backend.outbox.publisher.HttpNotificationPublisher;
import com.team3.backend.outbox.publisher.NotificationMessagePublisher;
import com.team3.backend.outbox.service.OutboxPublicationStore;
import com.team3.backend.outbox.service.OutboxPublicationWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationPublisherProperties.class)
@PropertySource("classpath:notification-http.properties")
public class NotificationPublisherConfig {
    @Bean
    @ConditionalOnProperty(name = "yon.notification.publisher.enabled", havingValue = "true")
    NotificationMessagePublisher notificationMessagePublisher(NotificationClient client,
            NotificationPublisherProperties properties, ObjectMapper mapper) {
        return new HttpNotificationPublisher(client, properties, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "yon.notification.publisher.enabled", havingValue = "true")
    OutboxPublicationStore outboxPublicationStore(JdbcTemplate jdbc, Clock clock, NotificationPublisherProperties properties) {
        return new OutboxPublicationStore(jdbc, clock, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "yon.notification.publisher.enabled", havingValue = "true")
    OutboxPublicationWorker outboxPublicationWorker(OutboxPublicationStore store, NotificationMessagePublisher publisher,
            NotificationPublisherProperties properties) {
        return new OutboxPublicationWorker(store, publisher, properties);
    }

    // Do not suppress Boot's normal taskScheduler or make other schedulers use this worker thread.
    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "yon.notification.publisher.enabled", havingValue = "true")
    ThreadPoolTaskScheduler notificationOutboxScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-outbox-");
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
