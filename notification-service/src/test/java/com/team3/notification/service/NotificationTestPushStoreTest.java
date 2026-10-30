package com.team3.notification.service;

import com.team3.notification.domain.entity.Notification;
import com.team3.notification.domain.repository.NotificationRepository;
import com.team3.notification.fixture.NotificationMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.sql.Timestamp;
import java.time.*;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises target/ownership/expiry SQL on H2; only the PostgreSQL advisory-lock guard is mocked. */
@DataJpaTest(showSql = false, properties =
        "spring.datasource.url=jdbc:h2:mem:test-push-store;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NotificationTestPushStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationTestPushStoreTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean @Primary Clock testPushClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
    @Autowired NotificationTestPushStore store;
    @Autowired NotificationRepository notifications;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NotificationUserGuard guard;
    private long notificationId;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM notification_endpoints");
        jdbc.update("DELETE FROM notification_settings");
        notifications.deleteAll();
        notificationId = notification(Map.of("title", "[시연] 테스트 알림"));
        jdbc.update("INSERT INTO notification_endpoints(id,user_id,fcm_token,active,created_at,updated_at) VALUES(32,7,'private-token',true,?,?)",
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("INSERT INTO notification_settings(user_id,in_app_enabled,push_enabled,updated_at) VALUES(7,true,true,?)",
                Timestamp.from(NOW));
    }

    @Test
    void readsOwnedNotificationAndEndpointWithoutWritingDeliveryOrSlotState() {
        var requestId = UUID.randomUUID();
        var message = store.prepare(7, notificationId, 32, requestId);
        assertThat(message.notificationId()).isEqualTo(notificationId);
        assertThat(message.endpointId()).isEqualTo(32);
        assertThat(message.fcmToken()).isEqualTo("private-token");
        assertThat(message.leaseToken()).isEqualTo(requestId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_deliveries", Long.class)).isZero();
        verify(guard).lockActive(7);
    }

    @Test
    void neverCombinesDifferentOwnersOrAcceptsMissingIds() {
        assertCode(() -> store.prepare(8, notificationId, 32, UUID.randomUUID()), "TEST_PUSH_TARGET_NOT_FOUND");
        assertCode(() -> store.prepare(7, notificationId, 999, UUID.randomUUID()), "TEST_PUSH_TARGET_NOT_FOUND");
        assertCode(() -> store.prepare(7, 999, 32, UUID.randomUUID()), "TEST_PUSH_TARGET_NOT_FOUND");
        jdbc.update("UPDATE notification_endpoints SET user_id=8 WHERE id=32");
        assertCode(() -> store.prepare(7, notificationId, 32, UUID.randomUUID()), "TEST_PUSH_TARGET_NOT_FOUND");
    }

    @Test
    void rejectsMissingOrRevokedConsentAndInactiveEndpoint() {
        jdbc.update("UPDATE notification_settings SET push_enabled=false WHERE user_id=7");
        assertCode(() -> store.prepare(7, notificationId, 32, UUID.randomUUID()), "PUSH_CONSENT_REVOKED");
        jdbc.update("DELETE FROM notification_settings WHERE user_id=7");
        assertCode(() -> store.prepare(7, notificationId, 32, UUID.randomUUID()), "PUSH_CONSENT_REVOKED");
        jdbc.update("INSERT INTO notification_settings(user_id,in_app_enabled,push_enabled,updated_at) VALUES(7,true,true,?)",
                Timestamp.from(NOW));
        jdbc.update("UPDATE notification_endpoints SET active=false WHERE id=32");
        assertCode(() -> store.prepare(7, notificationId, 32, UUID.randomUUID()), "ENDPOINT_INACTIVE");
    }

    @Test
    void honorsEarlierPushExpiryIncludingExactExpiryBoundary() {
        jdbc.update("UPDATE notifications SET push_expires_at=? WHERE id=?", Timestamp.from(NOW), notificationId);
        assertCode(() -> store.prepare(7, notificationId, 32, UUID.randomUUID()), "PUSH_EXPIRED");
        jdbc.update("UPDATE notifications SET push_expires_at=NULL,expires_at=? WHERE id=?", Timestamp.from(NOW), notificationId);
        assertCode(() -> store.prepare(7, notificationId, 32, UUID.randomUUID()), "PUSH_EXPIRED");
    }

    @Test
    void requiresExplicitTestTitleAndRespectsWithdrawalGuard() {
        jdbc.update("UPDATE notifications SET title='일반 사용자 알림' WHERE id=?", notificationId);
        assertCode(() -> store.prepare(7, notificationId, 32, UUID.randomUUID()), "TEST_PUSH_REQUIRES_TEST_NOTIFICATION");
        doThrow(new NotificationUserApiException(410, "USER_WITHDRAWN")).when(guard).lockActive(7);
        assertCode(() -> store.prepare(7, notificationId, 32, UUID.randomUUID()), "USER_WITHDRAWN");
    }

    @Test
    void invalidTokenDeactivationUsesCompareAndSetSoReplacementSurvives() {
        var message = store.prepare(7, notificationId, 32, UUID.randomUUID());
        jdbc.update("UPDATE notification_endpoints SET fcm_token='replacement-token' WHERE id=32");
        store.deactivateInvalidToken(message);
        assertThat(jdbc.queryForObject("SELECT active FROM notification_endpoints WHERE id=32", Boolean.class)).isTrue();
        var replacement = store.prepare(7, notificationId, 32, UUID.randomUUID());
        store.deactivateInvalidToken(replacement);
        assertThat(jdbc.queryForObject("SELECT active FROM notification_endpoints WHERE id=32", Boolean.class)).isFalse();
        verify(guard, times(2)).lock(7);
    }

    private long notification(Map<String, ?> overrides) {
        return notifications.saveAndFlush(Notification.inApp(NotificationMessages.message(overrides), NOW)).getId();
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(NotificationUserApiException.class).hasMessage(code);
    }
}
