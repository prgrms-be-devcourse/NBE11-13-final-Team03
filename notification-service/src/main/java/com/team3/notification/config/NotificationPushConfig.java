package com.team3.notification.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.team3.notification.push.*;
import com.team3.notification.service.NotificationDeliveryStore;
import com.team3.notification.service.NotificationDeliveryWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.io.IOException;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationPushProperties.class)
@PropertySource("classpath:notification-push.properties")
public class NotificationPushConfig {
    @Bean
    @ConditionalOnProperty(name = "yon.notification.push.enabled", havingValue = "true")
    NotificationDeliveryWorker notificationDeliveryWorker(NotificationPushProperties properties,
            NotificationDeliveryStore store, PushSender sender) {
        return new NotificationDeliveryWorker(store, sender, properties);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "yon.notification.push.enabled", havingValue = "true")
    ThreadPoolTaskScheduler notificationDeliveryScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("notification-delivery-");
        scheduler.setAwaitTerminationSeconds(15);
        return scheduler;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(PushSender.class)
    static class FirebaseConfig {
        @Bean(destroyMethod = "delete")
        @ConditionalOnProperty(name = "yon.notification.push.enabled", havingValue = "true")
        FirebaseApp notificationFirebaseApp(NotificationPushProperties properties) throws IOException {
            var options = FirebaseOptions.builder().setProjectId(properties.firebaseProjectId())
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .setConnectTimeout(1000).setReadTimeout(5000).setWriteTimeout(5000).build();
            return FirebaseApp.initializeApp(options, "yon-notification");
        }
        @Bean
        @ConditionalOnProperty(name = "yon.notification.push.enabled", havingValue = "true")
        FirebaseMessaging notificationFirebaseMessaging(FirebaseApp notificationFirebaseApp) {
            return FirebaseMessaging.getInstance(notificationFirebaseApp);
        }
        @Bean
        @ConditionalOnProperty(name = "yon.notification.push.enabled", havingValue = "true")
        PushSender firebasePushSender(FirebaseMessaging messaging, NotificationPushProperties properties, Clock clock) {
            return new FirebasePushSender(messaging, properties, clock);
        }
    }
}
