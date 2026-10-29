package com.team3.notification.config;

import com.team3.notification.admin.*;
import com.team3.notification.message.NotificationMessageFingerprint;
import com.team3.notification.sqs.NotificationSqsTopology;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
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
@EnableConfigurationProperties(AdminNotificationProperties.class)
@PropertySource("classpath:notification-admin.properties")
public class AdminNotificationConfig {
    @Bean(defaultCandidate = false, destroyMethod = "close")
    @ConditionalOnProperty(name = "yon.notification.admin.consumer-enabled", havingValue = "true")
    DefaultCredentialsProvider adminNotificationSqsCredentials() { return DefaultCredentialsProvider.builder().build(); }

    @Bean(defaultCandidate = false, destroyMethod = "close")
    @ConditionalOnProperty(name = "yon.notification.admin.consumer-enabled", havingValue = "true")
    SqsClient adminNotificationSqsClient(AdminNotificationProperties properties,
            @Qualifier("adminNotificationSqsCredentials") DefaultCredentialsProvider credentials) {
        var builder = SqsClient.builder().region(Region.of(properties.region())).credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(1))
                        .socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(28)).retryPolicy(RetryPolicy.none()).build());
        if (!properties.endpoint().isBlank()) builder.endpointOverride(URI.create(properties.endpoint()));
        return builder.build();
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "yon.notification.admin.consumer-enabled", havingValue = "true")
    NotificationSqsTopology adminNotificationSqsTopology(AdminNotificationProperties properties,
            @Qualifier("adminNotificationSqsClient") SqsClient sqs, ObjectMapper mapper, Clock clock, Environment environment) {
        var adminQueues = Set.of(properties.queueUrl(), properties.dlqUrl());
        for (var suffix : new String[]{"queue-url", "dlq-url"}) {
            var alias = suffix.equals("queue-url") ? "NOTIFICATION_SQS_QUEUE_URL" : "NOTIFICATION_SQS_DLQ_URL";
            if (adminQueues.contains(environment.getProperty("yon.notification.sqs." + suffix, environment.getProperty(alias, "")))) {
                throw new IllegalArgumentException("ADMIN_AND_USER_QUEUES_MUST_DIFFER");
            }
        }
        return new NotificationSqsTopology(sqs, properties.sqsSettings(), mapper, clock);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "yon.notification.admin.consumer-enabled", havingValue = "true")
    SlackWebhookSender adminSlackWebhookSender(AdminNotificationProperties properties, ObjectMapper mapper) {
        return new SlackWebhookSender(URI.create(properties.slackWebhookUrl()), mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "yon.notification.admin.consumer-enabled", havingValue = "true")
    AdminNotificationHandler adminNotificationHandler(JdbcTemplate jdbc, ObjectMapper mapper,
            NotificationMessageFingerprint fingerprints, SlackWebhookSender slack, Clock clock) {
        return new AdminNotificationHandler(jdbc, mapper, fingerprints, slack, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "yon.notification.admin.consumer-enabled", havingValue = "true")
    AdminNotificationSqsConsumer adminNotificationSqsConsumer(AdminNotificationProperties properties,
            @Qualifier("adminNotificationSqsClient") SqsClient sqs,
            @Qualifier("adminNotificationSqsTopology") NotificationSqsTopology topology, AdminNotificationHandler handler) {
        return new AdminNotificationSqsConsumer(sqs, properties, topology, handler);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "yon.notification.admin.consumer-enabled", havingValue = "true")
    ThreadPoolTaskScheduler adminNotificationSqsScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-admin-sqs-");
        scheduler.setAwaitTerminationSeconds(35);
        return scheduler;
    }
}
