package com.team3.notification.config;

import com.team3.notification.service.NotificationMessageHandler;
import com.team3.notification.sqs.NotificationSqsConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NotificationSqsConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NotificationSqsConfig.class)
            .withBean(NotificationMessageHandler.class, () -> mock(NotificationMessageHandler.class))
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void disabledByDefaultWithoutCredentialsOrQueuesOrScheduler() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(SqsClient.class)
                    .doesNotHaveBean(DefaultCredentialsProvider.class).doesNotHaveBean(NotificationSqsConsumer.class);
            assertThat(context.containsBean("notificationSqsScheduler")).isFalse();
        });
    }

    @Test
    void explicitAliasesCreateConsumerWithoutReadingCredentialsOrCallingAws() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SqsClient.class).hasSingleBean(NotificationSqsConsumer.class);
            assertThat(context.containsBean("notificationSqsScheduler")).isTrue();
        });
    }

    @Test
    void enabledRequiresRegionAndBothStandardQueueUrls() {
        runner.withPropertyValues("NOTIFICATION_SQS_CONSUMER_ENABLED=true").run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("NOTIFICATION_SQS_DLQ_URL=").run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("NOTIFICATION_SQS_DLQ_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/queue.fifo")
                .run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("NOTIFICATION_SQS_DLQ_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/yon-notifications")
                .run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("NOTIFICATION_SQS_REGION=us-east-1").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void localOverrideAndTimingsAreValidated() {
        enabled().withPropertyValues("NOTIFICATION_SQS_ENDPOINT=http://127.0.0.1:4566",
                        "NOTIFICATION_SQS_QUEUE_URL=http://127.0.0.1:4566/000000000000/yon-notifications",
                        "NOTIFICATION_SQS_DLQ_URL=http://127.0.0.1:4566/000000000000/yon-notifications-dlq")
                .run(context -> assertThat(context).hasNotFailed());
        enabled().withPropertyValues("NOTIFICATION_SQS_ENDPOINT=http://untrusted.example")
                .run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("yon.notification.sqs.wait-time-seconds=21")
                .run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("yon.notification.sqs.visibility-timeout-seconds=29")
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("NOTIFICATION_SQS_CONSUMER_ENABLED=true", "NOTIFICATION_SQS_REGION=ap-northeast-2",
                "NOTIFICATION_SQS_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/yon-notifications",
                "NOTIFICATION_SQS_DLQ_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/yon-notifications-dlq");
    }
}
