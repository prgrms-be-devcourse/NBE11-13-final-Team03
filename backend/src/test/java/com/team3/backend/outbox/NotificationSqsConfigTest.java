package com.team3.backend.outbox;

import com.team3.backend.outbox.config.NotificationPublisherConfig;
import com.team3.backend.outbox.publisher.*;
import com.team3.backend.outbox.service.OutboxPublicationWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NotificationSqsConfigTest {
    // No Feign infrastructure: SQS publishing does not require HTTP base URL or internal key.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NotificationPublisherConfig.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

    @Test
    void disabledNeverCreatesAwsInfrastructureEvenWhenSqsIsSelected() {
        runner.withPropertyValues("NOTIFICATION_PUBLISHER_TRANSPORT=sqs").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(SqsClient.class)
                    .doesNotHaveBean(AwsCredentialsProvider.class).doesNotHaveBean(NotificationMessagePublisher.class);
        });
    }

    @Test
    void explicitSqsCreatesExactlyOnePublisherWithoutHttpKeyOrReadingCredentials() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(NotificationMessagePublisher.class)
                    .hasSingleBean(SqsClient.class).hasSingleBean(OutboxPublicationWorker.class)
                    .doesNotHaveBean(HttpNotificationPublisher.class);
            assertThat(context.getBean(NotificationMessagePublisher.class)).isInstanceOf(SqsNotificationPublisher.class);
        });
    }

    @Test
    void requiresRegionAndQueueAndRejectsFifoOrWrongRegionAndUnknownTransport() {
        runner.withPropertyValues("NOTIFICATION_PUBLISHER_ENABLED=true", "NOTIFICATION_PUBLISHER_TRANSPORT=sqs")
                .run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("NOTIFICATION_SQS_QUEUE_URL=").run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("NOTIFICATION_SQS_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/queue.fifo")
                .run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("NOTIFICATION_SQS_REGION=us-east-1")
                .run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("NOTIFICATION_PUBLISHER_TRANSPORT=other")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void localOverrideIsExplicitAndRestrictedToSameLoopbackOrigin() {
        enabled().withPropertyValues("NOTIFICATION_SQS_ENDPOINT=http://127.0.0.1:4566",
                        "NOTIFICATION_SQS_QUEUE_URL=http://127.0.0.1:4566/000000000000/yon-notifications")
                .run(context -> assertThat(context).hasNotFailed());
        enabled().withPropertyValues("NOTIFICATION_SQS_ENDPOINT=http://untrusted.example:4566")
                .run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("yon.notification.publisher.lease-timeout=PT10S")
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("NOTIFICATION_PUBLISHER_ENABLED=true", "NOTIFICATION_PUBLISHER_TRANSPORT=sqs",
                "NOTIFICATION_SQS_REGION=ap-northeast-2",
                "NOTIFICATION_SQS_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/yon-notifications");
    }
}
