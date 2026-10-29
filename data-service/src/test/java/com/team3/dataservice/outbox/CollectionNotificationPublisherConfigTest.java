package com.team3.dataservice.outbox;

import com.team3.dataservice.outbox.config.CollectionNotificationPublisherConfig;
import com.team3.dataservice.outbox.config.CollectionNotificationPublisherProperties;
import com.team3.dataservice.outbox.publisher.CollectionEventPublisher;
import com.team3.dataservice.outbox.publisher.SqsCollectionEventPublisher;
import com.team3.dataservice.outbox.service.OutboxPublicationWorker;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CollectionNotificationPublisherConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CollectionNotificationPublisherConfig.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

    @Test
    void disabledByDefaultAndBackendNotificationFlagsCannotEnableCollectionPublishing() {
        runner.withPropertyValues("NOTIFICATION_PUBLISHER_ENABLED=true", "NOTIFICATION_PUBLISHER_TRANSPORT=sqs",
                "NOTIFICATION_SQS_REGION=ap-northeast-2",
                "NOTIFICATION_SQS_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/user-queue")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SqsClient.class)
                        .doesNotHaveBean(AwsCredentialsProvider.class).doesNotHaveBean(CollectionEventPublisher.class)
                        .doesNotHaveBean(OutboxPublicationWorker.class).doesNotHaveBean("collectionOutboxScheduler"));
    }

    @Test
    void explicitAliasesCreateOneSqsPublisherAndDedicatedNonDefaultSchedulerWithoutHttpKeys() {
        enabled().withPropertyValues("COLLECTION_NOTIFICATION_PUBLISHER_POLL_DELAY_MS=1234").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SqsClient.class).hasSingleBean(CollectionEventPublisher.class)
                    .hasSingleBean(OutboxPublicationWorker.class);
            assertThat(context.getBean(CollectionEventPublisher.class)).isInstanceOf(SqsCollectionEventPublisher.class);
            assertThat(context.getBean(CollectionNotificationPublisherProperties.class).pollDelayMs()).isEqualTo(1234);
            var scheduler = (AbstractBeanDefinition) context.getBeanFactory().getBeanDefinition("collectionOutboxScheduler");
            assertThat(scheduler.isDefaultCandidate()).isFalse();
        });
    }

    @Test
    void enabledRequiresItsOwnRegionAndQueue() {
        runner.withPropertyValues("COLLECTION_NOTIFICATION_PUBLISHER_ENABLED=true")
                .run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("COLLECTION_NOTIFICATION_SQS_QUEUE_URL=")
                .run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("COLLECTION_NOTIFICATION_SQS_REGION=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsFifoWrongRegionPlaintextAndUnsafeQueueUrls() {
        for (var url : new String[]{"https://sqs.ap-northeast-2.amazonaws.com/123456789012/admin.fifo",
                "https://sqs.us-east-1.amazonaws.com/123456789012/admin", "http://sqs.ap-northeast-2.amazonaws.com/123456789012/admin",
                "https://user:secret@sqs.ap-northeast-2.amazonaws.com/123456789012/admin",
                "https://sqs.ap-northeast-2.amazonaws.com/123456789012/admin?token=secret"}) {
            enabled().withPropertyValues("COLLECTION_NOTIFICATION_SQS_QUEUE_URL=" + url)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void localOverrideIsExplicitAndRestrictedToTheSameLoopbackOrigin() {
        enabled().withPropertyValues("COLLECTION_NOTIFICATION_SQS_ENDPOINT=http://127.0.0.1:4566",
                        "COLLECTION_NOTIFICATION_SQS_QUEUE_URL=http://127.0.0.1:4566/000000000000/admin")
                .run(context -> assertThat(context).hasNotFailed());
        enabled().withPropertyValues("COLLECTION_NOTIFICATION_SQS_ENDPOINT=http://untrusted.example:4566")
                .run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("COLLECTION_NOTIFICATION_SQS_ENDPOINT=http://127.0.0.1:4566",
                        "COLLECTION_NOTIFICATION_SQS_QUEUE_URL=http://127.0.0.1:4567/000000000000/admin")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsInvalidTimingAndLeaseThatCanExpireDuringAnSdkCall() {
        for (var setting : new String[]{"yon.collection.notification.publisher.lease-timeout=PT15S",
                "yon.collection.notification.publisher.retry-delay=PT0S", "yon.collection.notification.publisher.max-attempts=0",
                "yon.collection.notification.publisher.batch-size=0", "COLLECTION_NOTIFICATION_PUBLISHER_POLL_DELAY_MS=0"}) {
            enabled().withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
        }
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("COLLECTION_NOTIFICATION_PUBLISHER_ENABLED=true",
                "COLLECTION_NOTIFICATION_SQS_REGION=ap-northeast-2",
                "COLLECTION_NOTIFICATION_SQS_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/123456789012/admin");
    }

    static CollectionNotificationPublisherProperties properties() {
        return new CollectionNotificationPublisherProperties(true, "ap-northeast-2",
                "https://sqs.ap-northeast-2.amazonaws.com/123456789012/admin", "", 10, 3,
                Duration.ofMinutes(1), Duration.ofMinutes(1), 5000);
    }
}
