package com.team3.notification.push;

import com.google.api.core.ApiFutures;
import com.google.firebase.messaging.*;
import com.team3.notification.config.NotificationPushProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FirebasePushSenderTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    @Test
    void returnsFcmAcceptanceIdAndBuildsWebpushWithoutExposingTokenInLogs() {
        var messaging = mock(FirebaseMessaging.class);
        when(messaging.sendAsync(any(Message.class))).thenReturn(ApiFutures.immediateFuture("projects/test/messages/accepted"));
        var sender = new FirebasePushSender(messaging, properties(), Clock.fixed(NOW, ZoneOffset.UTC));
        var task = message(NOW.plusSeconds(120));
        assertThat(sender.send(task)).isEqualTo("projects/test/messages/accepted");
        var captured = ArgumentCaptor.forClass(Message.class);
        verify(messaging).sendAsync(captured.capture());
        assertThat(captured.getValue()).extracting("token").isEqualTo("private-fcm-token");
        assertThat(task.toString()).doesNotContain("private-fcm-token", "private-title", "private-content");
    }

    @Test
    void expiredTaskNeverCallsSdk() {
        var messaging = mock(FirebaseMessaging.class);
        var sender = new FirebasePushSender(messaging, properties(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> sender.send(message(NOW))).isInstanceOf(PushFailure.class).hasMessage("PUSH_EXPIRED");
        verifyNoInteractions(messaging);
    }

    @Test
    void timeoutCancelsOutstandingSdkFutureAndReturnsSafeRetryReason() throws Exception {
        var messaging = mock(FirebaseMessaging.class);
        @SuppressWarnings("unchecked")
        com.google.api.core.ApiFuture<String> pending = mock(com.google.api.core.ApiFuture.class);
        when(messaging.sendAsync(any(Message.class))).thenReturn(pending);
        when(pending.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("private SDK diagnostic"));
        var sender = new FirebasePushSender(messaging, properties(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> sender.send(message(NOW.plusSeconds(120))))
                .isInstanceOf(PushFailure.class).hasMessage("FCM_TIMEOUT");
        verify(pending).cancel(true);
    }

    @Test
    void distinguishesInvalidTokenInvalidPayloadConfigurationAndTemporaryFailures() {
        for (var entry : java.util.Map.of(MessagingErrorCode.UNREGISTERED, PushFailure.Kind.INVALID_TOKEN,
                MessagingErrorCode.INVALID_ARGUMENT, PushFailure.Kind.PERMANENT,
                MessagingErrorCode.SENDER_ID_MISMATCH, PushFailure.Kind.CONFIGURATION,
                MessagingErrorCode.QUOTA_EXCEEDED, PushFailure.Kind.TRANSIENT,
                MessagingErrorCode.UNAVAILABLE, PushFailure.Kind.TRANSIENT).entrySet()) {
            var error = mock(FirebaseMessagingException.class);
            when(error.getMessagingErrorCode()).thenReturn(entry.getKey());
            var messaging = mock(FirebaseMessaging.class);
            when(messaging.sendAsync(any(Message.class))).thenReturn(ApiFutures.immediateFailedFuture(error));
            var sender = new FirebasePushSender(messaging, properties(), Clock.fixed(NOW, ZoneOffset.UTC));
            assertThatThrownBy(() -> sender.send(message(NOW.plusSeconds(120))))
                    .isInstanceOfSatisfying(PushFailure.class, failure -> assertThat(failure.kind()).isEqualTo(entry.getValue()));
        }
    }

    private NotificationPushProperties properties() {
        return new NotificationPushProperties(true, "test-project", "https://app.example", 10, 5,
                Duration.ofMinutes(2), Duration.ofMinutes(1), Duration.ofSeconds(10));
    }
    private PushMessage message(Instant expires) {
        return new PushMessage(1, 2, 7, 3, UUID.randomUUID(), "private-fcm-token", "private-title", "private-content",
                "/opportunities/10", expires, UUID.randomUUID(), UUID.randomUUID());
    }
}
