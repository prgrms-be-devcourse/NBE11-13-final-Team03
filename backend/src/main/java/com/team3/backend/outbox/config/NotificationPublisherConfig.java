package com.team3.backend.outbox.config;

import com.team3.backend.client.NotificationClient;
import com.team3.backend.outbox.publisher.HttpNotificationPublisher;
import com.team3.backend.outbox.publisher.NotificationMessagePublisher;
import com.team3.backend.outbox.publisher.SqsNotificationPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import java.time.Duration;
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
@EnableConfigurationProperties({NotificationPublisherProperties.class, NotificationSqsProperties.class})
@PropertySource("classpath:notification-http.properties")
public class NotificationPublisherConfig {
    @Bean
    @ConditionalOnProperty(name = "yon.notification.publisher.enabled", havingValue = "true")
    NotificationMessagePublisher notificationMessagePublisher(NotificationPublisherProperties properties,
            ObjectProvider<NotificationClient> client, ObjectProvider<SqsClient> sqs,
            ObjectProvider<AwsCredentialsProvider> credentials, NotificationSqsProperties queue, ObjectMapper mapper) {
        if ("sqs".equals(properties.transport())) {
            queue.validate(properties);
            return new SqsNotificationPublisher(sqs.getObject(), credentials.getObject(), queue.queueUrl(), mapper);
        }
        return new HttpNotificationPublisher(client.getObject(), properties, mapper);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("${yon.notification.publisher.enabled:false} && '${yon.notification.publisher.transport:http}' == 'sqs'")
    DefaultCredentialsProvider notificationSqsCredentials(NotificationPublisherProperties publisher,
            NotificationSqsProperties queue) {
        queue.validate(publisher);
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("${yon.notification.publisher.enabled:false} && '${yon.notification.publisher.transport:http}' == 'sqs'")
    SqsClient notificationSqsClient(NotificationPublisherProperties publisher, NotificationSqsProperties queue,
            AwsCredentialsProvider credentials) {
        queue.validate(publisher);
        var builder = SqsClient.builder().region(Region.of(queue.region())).credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(1))
                        .socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(5))
                        .retryPolicy(RetryPolicy.none()).build());
        if (!queue.endpoint().isBlank()) builder.endpointOverride(java.net.URI.create(queue.endpoint()));
        return builder.build();
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
