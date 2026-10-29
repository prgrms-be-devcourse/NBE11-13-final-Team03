package com.team3.notification.admin;

import com.team3.notification.config.*;
import com.team3.notification.message.NotificationMessageFingerprint;
import com.team3.notification.service.NotificationMessageHandler;
import com.team3.notification.sqs.NotificationSqsConsumer;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AdminNotificationConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AdminNotificationConfig.class, NotificationSqsConfig.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(NotificationMessageFingerprint.class, () -> new NotificationMessageFingerprint(JsonMapper.builder().build()))
            .withBean(NotificationMessageHandler.class, () -> mock(NotificationMessageHandler.class))
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void disabledByDefaultWithoutAwsOrSlackInfrastructure() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SqsClient.class)
                .doesNotHaveBean(AdminNotificationSqsConsumer.class).doesNotHaveBean(SlackWebhookSender.class));
    }

    @Test
    void adminCanRunWithUserReceiverAndFcmDisabled() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AdminNotificationSqsConsumer.class)
                    .doesNotHaveBean(NotificationSqsConsumer.class).hasSingleBean(SlackWebhookSender.class);
            assertThat(((AbstractBeanDefinition) context.getBeanFactory().getBeanDefinition("adminNotificationSqsScheduler"))
                    .isDefaultCandidate()).isFalse();
            assertThat(context.getBean(AdminNotificationProperties.class).toString()).doesNotContain("secret-token");
        });
    }

    @Test
    void userAndAdminConsumersCanCoexistWithoutAmbiguousAwsOrTopologyBeans() {
        enabled().withPropertyValues("NOTIFICATION_SQS_CONSUMER_ENABLED=true", "NOTIFICATION_SQS_REGION=ap-northeast-2",
                "NOTIFICATION_SQS_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/user",
                "NOTIFICATION_SQS_DLQ_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/user-dlq")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(AdminNotificationSqsConsumer.class)
                        .hasSingleBean(NotificationSqsConsumer.class));
    }

    @Test
    void rejectsMissingWebhookSharedQueuesUnsafeWebhookAndInvalidTiming() {
        for (var setting : new String[]{"NOTIFICATION_ADMIN_SLACK_WEBHOOK_URL=",
                "NOTIFICATION_ADMIN_SQS_REGION=", "NOTIFICATION_ADMIN_SQS_DLQ_URL=",
                "NOTIFICATION_ADMIN_SQS_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/admin.fifo",
                "NOTIFICATION_SQS_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/admin",
                "NOTIFICATION_SQS_DLQ_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/admin-dlq",
                "NOTIFICATION_ADMIN_SLACK_WEBHOOK_URL=http://hooks.slack.com/services/T/B/secret-token",
                "NOTIFICATION_ADMIN_SLACK_WEBHOOK_URL=https://untrusted.example/services/T/B/secret-token",
                "NOTIFICATION_ADMIN_SLACK_WEBHOOK_URL=https://hooks.slack.com/services/T/B/secret-token?token=secret-token",
                "NOTIFICATION_ADMIN_SQS_VISIBILITY_TIMEOUT_SECONDS=59", "NOTIFICATION_ADMIN_SQS_POLL_DELAY_MS=0"}) {
            enabled().withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void acceptsExplicitLocalStubUrls() {
        enabled().withPropertyValues("NOTIFICATION_ADMIN_SQS_ENDPOINT=http://127.0.0.1:4566",
                "NOTIFICATION_ADMIN_SQS_QUEUE_URL=http://127.0.0.1:4566/000000000000/admin",
                "NOTIFICATION_ADMIN_SQS_DLQ_URL=http://127.0.0.1:4566/000000000000/admin-dlq",
                "NOTIFICATION_ADMIN_SLACK_WEBHOOK_URL=http://127.0.0.1:8090/stub-secret")
                .run(context -> assertThat(context).hasNotFailed());
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("NOTIFICATION_ADMIN_SQS_CONSUMER_ENABLED=true", "NOTIFICATION_ADMIN_SQS_REGION=ap-northeast-2",
                "NOTIFICATION_ADMIN_SQS_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/admin",
                "NOTIFICATION_ADMIN_SQS_DLQ_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/admin-dlq",
                "NOTIFICATION_ADMIN_SLACK_WEBHOOK_URL=https://hooks.slack.com/services/T/B/secret-token");
    }
}
