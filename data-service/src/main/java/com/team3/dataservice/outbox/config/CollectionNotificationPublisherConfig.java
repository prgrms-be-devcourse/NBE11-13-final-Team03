package com.team3.dataservice.outbox.config;

import com.team3.dataservice.outbox.publisher.CollectionEventPublisher;
import com.team3.dataservice.outbox.publisher.SqsCollectionEventPublisher;
import com.team3.dataservice.outbox.service.OutboxPublicationStore;
import com.team3.dataservice.outbox.service.OutboxPublicationWorker;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CollectionNotificationPublisherProperties.class)
@PropertySource("classpath:collection-notification.properties")
public class CollectionNotificationPublisherConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "yon.collection.notification.publisher.enabled", havingValue = "true")
    DefaultCredentialsProvider collectionSqsCredentials() {
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "yon.collection.notification.publisher.enabled", havingValue = "true")
    SqsClient collectionSqsClient(CollectionNotificationPublisherProperties properties,
                                  DefaultCredentialsProvider credentials) {
        var builder = SqsClient.builder().region(Region.of(properties.region())).credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(1))
                        .socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(5))
                        .retryPolicy(RetryPolicy.none()).build());
        if (!properties.endpoint().isBlank()) builder.endpointOverride(java.net.URI.create(properties.endpoint()));
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "yon.collection.notification.publisher.enabled", havingValue = "true")
    CollectionEventPublisher collectionEventPublisher(SqsClient sqs, DefaultCredentialsProvider credentials,
            CollectionNotificationPublisherProperties properties, ObjectMapper mapper) {
        return new SqsCollectionEventPublisher(sqs, credentials, properties.queueUrl(), mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "yon.collection.notification.publisher.enabled", havingValue = "true")
    OutboxPublicationStore collectionOutboxPublicationStore(JdbcTemplate jdbc,
            CollectionNotificationPublisherProperties properties) {
        return new OutboxPublicationStore(jdbc, Clock.systemUTC(), properties);
    }

    @Bean
    @ConditionalOnProperty(name = "yon.collection.notification.publisher.enabled", havingValue = "true")
    OutboxPublicationWorker collectionOutboxPublicationWorker(OutboxPublicationStore store,
            CollectionEventPublisher publisher, CollectionNotificationPublisherProperties properties) {
        return new OutboxPublicationWorker(store, publisher, properties);
    }

    // Preserve Boot's taskScheduler; collectionExecutor is an async fetch executor, not this poller.
    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "yon.collection.notification.publisher.enabled", havingValue = "true")
    ThreadPoolTaskScheduler collectionOutboxScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("collection-outbox-");
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
