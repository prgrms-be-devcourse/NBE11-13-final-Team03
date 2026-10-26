package com.team3.notification.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.team3.notification.push.PushSender;
import com.team3.notification.service.NotificationDeliveryStore;
import com.team3.notification.service.NotificationDeliveryWorker;
import com.team3.notification.service.NotificationSlotStore;
import com.team3.notification.service.NotificationSlotWorker;
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

    @Test
    void slotModeRegistersOnlySlotWorkerWhenPushEnabled() {
        runner.withBean(NotificationSlotStore.class,()->mock(NotificationSlotStore.class))
                .withBean(PushSender.class,()->mock(PushSender.class))
                .withPropertyValues("NOTIFICATION_PUSH_ENABLED=true","FCM_PROJECT_ID=test-project",
                        "NOTIFICATION_PUBLIC_BASE_URL=https://app.example","yon.notification.push.slots.enabled=true",
                        "yon.notification.push.slots.inbox-path=/test-inbox")
                .run(context->assertThat(context).hasNotFailed().hasSingleBean(NotificationSlotWorker.class)
                        .doesNotHaveBean(NotificationDeliveryWorker.class).doesNotHaveBean(FirebaseApp.class));
    }

    @Test
    void slotModeAloneDoesNotEnablePushAndRequiresVerifiedInboxPath() {
        runner.withPropertyValues("yon.notification.push.slots.enabled=true","yon.notification.push.slots.inbox-path=/test-inbox")
                .run(context->assertThat(context).hasNotFailed().doesNotHaveBean(NotificationSlotWorker.class)
                        .doesNotHaveBean(NotificationDeliveryWorker.class).doesNotHaveBean(FirebaseApp.class));
        for(String path:new String[]{"","https://elsewhere.example/inbox","//elsewhere.example/inbox","/%2felsewhere","/x%5cy","/x%0ay"}) {
            runner.withPropertyValues("yon.notification.push.slots.enabled=true","yon.notification.push.slots.inbox-path="+path)
                    .run(context->assertThat(context).hasFailed());
        }
    }

    @Test
    void slotWorkBudgetsAreValidated() {
        for(String property:new String[]{"users-per-run=0","users-per-run=101","items-per-bundle=0","items-per-bundle=501"}) {
            runner.withPropertyValues("yon.notification.push.slots."+property).run(context->assertThat(context).hasFailed());
        }
    }
}
