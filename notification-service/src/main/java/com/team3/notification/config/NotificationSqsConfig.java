package com.team3.notification.config;

import com.team3.notification.service.NotificationMessageHandler;
import com.team3.notification.sqs.NotificationSqsConsumer;
import com.team3.notification.sqs.NotificationSqsTopology;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationSqsProperties.class)
@PropertySource("classpath:notification-sqs.properties")
public class NotificationSqsConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "yon.notification.sqs.consumer-enabled", havingValue = "true")
    DefaultCredentialsProvider notificationSqsCredentials(NotificationSqsProperties properties) {
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "yon.notification.sqs.consumer-enabled", havingValue = "true")
    SqsClient notificationSqsClient(NotificationSqsProperties properties, DefaultCredentialsProvider credentials) {
        var builder = SqsClient.builder().region(Region.of(properties.region())).credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(1))
                        .socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(28)).retryPolicy(RetryPolicy.none()).build());
        if (!properties.endpoint().isBlank()) builder.endpointOverride(java.net.URI.create(properties.endpoint()));
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "yon.notification.sqs.consumer-enabled", havingValue = "true")
    NotificationSqsTopology notificationSqsTopology(NotificationSqsProperties properties, SqsClient sqs,
            ObjectMapper mapper, Clock clock) {
        return new NotificationSqsTopology(sqs, properties, mapper, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "yon.notification.sqs.consumer-enabled", havingValue = "true")
    NotificationSqsConsumer notificationSqsConsumer(NotificationSqsProperties properties, SqsClient sqs,
            NotificationSqsTopology topology, NotificationMessageHandler handler) {
        return new NotificationSqsConsumer(sqs, properties, topology, handler);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "yon.notification.sqs.consumer-enabled", havingValue = "true")
    ThreadPoolTaskScheduler notificationSqsScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-sqs-");
        scheduler.setAwaitTerminationSeconds(35);
        return scheduler;
    }
}
