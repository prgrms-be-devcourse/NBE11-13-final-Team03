package com.team3.backend.outbox;

import com.team3.backend.outbox.config.*;
import com.team3.backend.outbox.publisher.*;
import com.team3.backend.outbox.service.*;
import com.team3.backend.client.NotificationClient;
import feign.Request;
import feign.Retryer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NotificationPublisherConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class))
            .withUserConfiguration(NotificationPublisherConfig.class, NotificationFeignTestConfiguration.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

    @Test
    void disabledByDefaultWithoutKeysOrPublisherInfrastructure() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(NotificationMessagePublisher.class);
            assertThat(context).doesNotHaveBean(OutboxPublicationWorker.class);
        });
    }

    @Test
    void explicitEnvironmentAliasesEnableExactlyOnePublisherAndRequireSeparateKey() {
        runner.withPropertyValues("NOTIFICATION_PUBLISHER_ENABLED=true",
                        "NOTIFICATION_INTERNAL_API_KEY=test-notification-key",
                        "NOTIFICATION_BASE_URL=http://notification-service:8081")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NotificationMessagePublisher.class);
                    assertThat(context).hasSingleBean(NotificationClient.class);
                    // These options belong to the Feign child context, not to all clients globally.
                    assertThat(context).doesNotHaveBean(Request.Options.class);
                    assertThat(context).doesNotHaveBean(Retryer.class);
                    assertThat(context).hasSingleBean(OutboxPublicationWorker.class);
                    var scheduler = (AbstractBeanDefinition) context.getBeanFactory()
                            .getBeanDefinition("notificationOutboxScheduler");
                    assertThat(scheduler.isDefaultCandidate()).isFalse();
                    assertThat(context.getBean(NotificationPublisherProperties.class).toString())
                            .doesNotContain("test-notification-key");
                });
        runner.withPropertyValues("NOTIFICATION_PUBLISHER_ENABLED=true", "internal.api-key=old-backend-key",
                        "NOTIFICATION_BASE_URL=http://notification-service:8081")
                .run(context -> assertThat(context).hasFailed());
        // A forgotten URL must fail at startup instead of silently calling localhost.
        runner.withPropertyValues("NOTIFICATION_PUBLISHER_ENABLED=true",
                        "NOTIFICATION_INTERNAL_API_KEY=test-notification-key")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("NOTIFICATION_BASE_URL is required"));
        runner.withPropertyValues("NOTIFICATION_PUBLISHER_ENABLED=true",
                        "NOTIFICATION_INTERNAL_API_KEY=test-notification-key", "NOTIFICATION_PUBLISHER_TRANSPORT=sqs")
                .run(context -> assertThat(context).hasFailed());
    }
}
