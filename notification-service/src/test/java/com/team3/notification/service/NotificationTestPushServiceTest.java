package com.team3.notification.service;

import com.team3.notification.config.NotificationPushProperties;
import com.team3.notification.config.NotificationTestPushProperties;
import com.team3.notification.push.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationTestPushServiceTest {
    private final NotificationTestPushStore store = mock(NotificationTestPushStore.class);
    private final PushSender sender = mock(PushSender.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PushSender> senders = mock(ObjectProvider.class);
    private final PushMessage message = new PushMessage(0, 7, 1, 32, UUID.randomUUID(), "private-token",
            "[시연] 알림", "content", "/notifications", Instant.parse("2099-01-01T00:00:00Z"),
            UUID.randomUUID(), UUID.randomUUID());
    private NotificationTestPushService service;

    @BeforeEach
    void setup() {
        service = service(true, true);
        when(senders.getIfAvailable()).thenReturn(sender);
        when(store.prepare(eq(1L), eq(7L), eq(32L), any(UUID.class))).thenReturn(message);
    }

    @Test
    void sendsOneSelectedEndpointAndReturnsAcceptanceWithoutLeakingToken() {
        when(sender.send(message)).thenReturn("projects/test/messages/receipt");
        var result = service.send(1, 7, 32);
        assertThat(result.status()).isEqualTo("FCM_ACCEPTED");
        assertThat(result.fcmMessageId()).isEqualTo("projects/test/messages/receipt");
        assertThat(result.toString()).doesNotContain("private-token");
        verify(sender).send(message);
        verify(store).prepare(1, 7, 32, result.requestId());
        verifyNoMoreInteractions(store, sender);
    }

    @Test
    void rejectsDisabledPushAndNonAllowlistedUsersBeforeReadingTargets() {
        assertCode(() -> service(false, true).send(1, 7, 32), "TEST_PUSH_DISABLED");
        assertCode(() -> service(true, false).send(1, 7, 32), "TEST_PUSH_DISABLED");
        assertCode(() -> service.send(2, 7, 32), "TEST_PUSH_USER_NOT_ALLOWED");
        verifyNoInteractions(store, sender);
    }

    @Test
    void preflightRejectionNeverCallsFcm() {
        when(store.prepare(eq(1L), eq(7L), eq(32L), any(UUID.class)))
                .thenThrow(new NotificationUserApiException(410, "USER_WITHDRAWN"));
        assertCode(() -> service.send(1, 7, 32), "USER_WITHDRAWN");
        verifyNoInteractions(sender);
    }

    @Test
    void invalidTokenIsDeactivatedAndNeverRetried() {
        when(sender.send(message)).thenThrow(new PushFailure(PushFailure.Kind.INVALID_TOKEN, "FCM_UNREGISTERED"));
        assertCode(() -> service.send(1, 7, 32), "FCM_UNREGISTERED");
        verify(store).deactivateInvalidToken(message);
        verify(sender, times(1)).send(message);
    }

    @Test
    void transientConfigurationAndPermanentFailuresDoNotDeactivateOrRetry() {
        for (var kind : new PushFailure.Kind[]{PushFailure.Kind.TRANSIENT, PushFailure.Kind.CONFIGURATION, PushFailure.Kind.PERMANENT}) {
            doThrow(new PushFailure(kind, "private-provider-details")).when(sender).send(message);
            assertCode(() -> service.send(1, 7, 32), "TEST_PUSH_FCM_" + kind.name());
        }
        verify(store, never()).deactivateInvalidToken(any());
        verify(sender, times(3)).send(message);
    }

    @Test
    void invalidReceiptAndUnexpectedErrorsNeverReturnProviderDetails() {
        when(sender.send(message)).thenReturn(" ");
        assertCode(() -> service.send(1, 7, 32), "TEST_PUSH_FCM_TRANSIENT");
        when(sender.send(message)).thenThrow(new IllegalStateException("private-token"));
        assertCode(() -> service.send(1, 7, 32), "TEST_PUSH_UNAVAILABLE");
    }

    private NotificationTestPushService service(boolean enabled, boolean pushEnabled) {
        var properties = new NotificationTestPushProperties(enabled, "test-key", Set.of(1L));
        var push = new NotificationPushProperties(pushEnabled, "test-project", "https://app.example", 10, 5,
                Duration.ofMinutes(2), Duration.ofMinutes(1), Duration.ofSeconds(10));
        return new NotificationTestPushService(properties, push, store, senders);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(NotificationUserApiException.class).hasMessage(code);
    }
}
