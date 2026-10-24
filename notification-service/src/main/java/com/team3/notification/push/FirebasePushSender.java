package com.team3.notification.push;

import com.google.firebase.messaging.*;
import com.team3.notification.config.NotificationPushProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class FirebasePushSender implements PushSender {
    private final FirebaseMessaging messaging;
    private final NotificationPushProperties properties;
    private final Clock clock;
    public FirebasePushSender(FirebaseMessaging messaging, NotificationPushProperties properties, Clock clock) {
        this.messaging = messaging; this.properties = properties; this.clock = clock;
    }

    @Override public String send(PushMessage message) {
        long ttl = Math.min(2_419_200, Duration.between(clock.instant(), message.expiresAt()).getSeconds());
        if (ttl <= 0) throw new PushFailure(PushFailure.Kind.PERMANENT, "PUSH_EXPIRED");
        var webpush = WebpushConfig.builder().putHeader("TTL", Long.toString(ttl))
                .setNotification(WebpushNotification.builder().setTitle(message.title()).setBody(message.content())
                        .setTag("notification-" + message.notificationId()).build())
                .setFcmOptions(WebpushFcmOptions.withLink(URI.create(properties.publicBaseUrl()).resolve(message.link()).toString()))
                .build();
        // Existing endpoint contract uses FCM registration tokens (not installation IDs).
        var request = Message.builder().setToken(message.fcmToken()).setWebpushConfig(webpush)
                .putData("notificationId", Long.toString(message.notificationId()))
                .putData("messageId", message.messageId().toString()).build();
        var pending = messaging.sendAsync(request);
        try {
            String id = pending.get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (id == null || id.isBlank() || id.length() > 500) throw new PushFailure(PushFailure.Kind.TRANSIENT, "FCM_INVALID_RECEIPT");
            return id;
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new PushFailure(PushFailure.Kind.TRANSIENT, "FCM_TIMEOUT");
        } catch (InterruptedException e) {
            pending.cancel(true); Thread.currentThread().interrupt();
            throw new PushFailure(PushFailure.Kind.TRANSIENT, "FCM_INTERRUPTED");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof FirebaseMessagingException error) throw classify(error);
            throw new PushFailure(PushFailure.Kind.TRANSIENT, "FCM_SEND_FAILURE");
        }
    }

    static PushFailure classify(FirebaseMessagingException error) {
        var code = error.getMessagingErrorCode();
        if (code == MessagingErrorCode.UNREGISTERED) return new PushFailure(PushFailure.Kind.INVALID_TOKEN, "FCM_UNREGISTERED");
        if (code == MessagingErrorCode.SENDER_ID_MISMATCH || code == MessagingErrorCode.THIRD_PARTY_AUTH_ERROR
                || error.getErrorCode() == com.google.firebase.ErrorCode.UNAUTHENTICATED
                || error.getErrorCode() == com.google.firebase.ErrorCode.PERMISSION_DENIED) {
            return new PushFailure(PushFailure.Kind.CONFIGURATION, "FCM_CONFIGURATION_ERROR");
        }
        if (code == MessagingErrorCode.INVALID_ARGUMENT) {
            // Could be a payload problem, not an invalid token: do not deactivate an endpoint on this status.
            return new PushFailure(PushFailure.Kind.PERMANENT, "FCM_INVALID_ARGUMENT");
        }
        return new PushFailure(PushFailure.Kind.TRANSIENT, "FCM_TEMPORARY_FAILURE");
    }
}
