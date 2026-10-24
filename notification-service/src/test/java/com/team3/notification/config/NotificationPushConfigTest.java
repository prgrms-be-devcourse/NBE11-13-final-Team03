package com.team3.notification.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.team3.notification.push.PushSender;
import com.team3.notification.service.NotificationDeliveryStore;
import com.team3.notification.service.NotificationDeliveryWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.io.IOException;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class NotificationPushConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NotificationPushConfig.class)
            .withBean(NotificationDeliveryStore.class, () -> mock(NotificationDeliveryStore.class))
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void disabledByDefaultWithoutCredentialsOrSenderOrScheduler() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(FirebaseApp.class)
                    .doesNotHaveBean(PushSender.class).doesNotHaveBean(NotificationDeliveryWorker.class);
            assertThat(context.containsBean("notificationDeliveryScheduler")).isFalse();
        });
    }

    @Test
    void enabledRequiresExplicitProjectAndHttpsOrigin() {
        runner.withPropertyValues("NOTIFICATION_PUSH_ENABLED=true")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("FCM_PROJECT_ID"));
        runner.withPropertyValues("NOTIFICATION_PUSH_ENABLED=true", "FCM_PROJECT_ID=test-project",
                        "NOTIFICATION_PUBLIC_BASE_URL=http://localhost:3000")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("HTTPS origin"));
    }

    @Test
    void enabledSdkConfigurationReachesCredentialInitializationWithoutReadingRealCredentials() {
        try (var credentials = mockStatic(GoogleCredentials.class)) {
            credentials.when(GoogleCredentials::getApplicationDefault)
                    .thenThrow(new IOException("TEST_ADC_LOOKUP"));

            runner.withPropertyValues("NOTIFICATION_PUSH_ENABLED=true", "FCM_PROJECT_ID=test-project",
                            "NOTIFICATION_PUBLIC_BASE_URL=https://app.example")
                    .run(context -> assertThat(context).hasFailed().getFailure()
                            .hasStackTraceContaining("TEST_ADC_LOOKUP"));

            credentials.verify(GoogleCredentials::getApplicationDefault);
        }
    }

    @Test
    void explicitTestSenderCanRunWorkerWithoutInitializingFirebaseOrReadingCredentials() {
        runner.withBean(PushSender.class, () -> mock(PushSender.class))
                .withPropertyValues("NOTIFICATION_PUSH_ENABLED=true", "FCM_PROJECT_ID=test-project",
                        "NOTIFICATION_PUBLIC_BASE_URL=https://app.example")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(NotificationDeliveryWorker.class)
                            .doesNotHaveBean(FirebaseApp.class);
                    assertThat(context.containsBean("notificationDeliveryScheduler")).isTrue();
                });
    }
}
