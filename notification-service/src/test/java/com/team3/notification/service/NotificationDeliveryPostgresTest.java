package com.team3.notification.service;

import com.team3.notification.NotificationApplication;
import com.team3.notification.api.NotificationUserDtos.*;
import com.team3.notification.config.NotificationPushProperties;
import com.team3.notification.domain.repository.NotificationRepository;
import com.team3.notification.fixture.NotificationMessages;
import com.team3.notification.push.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.core.api.dataset.ExpectedDataSet;
import com.github.database.rider.junit5.api.DBRider;
import org.dbunit.ext.postgresql.PostgresqlDataTypeFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.team3.notification.fixture.NotificationPostgres;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Restricted PostgreSQL, real operator DDL+validate and a mocked sender: never contacts Firebase. */
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {NotificationApplication.class, NotificationDeliveryPostgresTest.Beans.class},
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = {"app.scheduling.enabled=false", "yon.notification.push.enabled=false"})
class NotificationDeliveryPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    @Container static final PostgreSQLContainer POSTGRES = NotificationPostgres.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        NotificationPostgres.userSchema(POSTGRES, registry);
    }

    private static String sql(String name) throws Exception {
        return new ClassPathResource("db-boundary/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean @Primary MutableClock deliveryClock() { return new MutableClock(); }
        @Bean PushSender mockSender() { return mock(PushSender.class); }
        @Bean NotificationDeliveryWorker testDeliveryWorker(NotificationDeliveryStore store, PushSender sender, NotificationPushProperties props) {
            return new NotificationDeliveryWorker(store, sender, props);
        }
    }
    static class MutableClock extends Clock {
        volatile Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired NotificationMessageHandler handler;
    @Autowired NotificationUserService users;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationDeliveryQueue queue;
    @Autowired NotificationDeliveryStore store;
    @Autowired NotificationDeliveryWorker worker;
    @Autowired PushSender sender;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void resetDisposableContainerOnly() {
        clock.now = NOW;
        reset(sender);
        jdbc.execute("TRUNCATE notification_deliveries, consumed_messages, notifications, notification_endpoints, notification_settings, withdrawn_users RESTART IDENTITY CASCADE");
    }

    @Test
    @DBRider(dataSourceBeanName = "dataSource")
    @DBUnit(schema = "notification", qualifiedTableNames = true, caseSensitiveTableNames = true,
            cacheConnection = false, cacheTableNames = false, dataTypeFactoryClass = PostgresqlDataTypeFactory.class)
    @DataSet(value = "datasets/user/push-ready.yml", executorId = "notification-user", transactional = false)
    @ExpectedDataSet("datasets/user/delivered.yml")
    void receiveCreatesPerEndpointDeliveriesOnceWithNotificationAndReceipt() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("notification_user");
        assertThat(jdbc.queryForObject("SELECT has_schema_privilege(current_user, 'application', 'USAGE')", Boolean.class)).isFalse();
        var result = handler.handle(NotificationMessages.json(Map.of()));
        assertThat(count()).isEqualTo(2);
        assertThat(handler.handle(NotificationMessages.json(Map.of())).duplicateMessage()).isTrue();
        assertThat(count()).isEqualTo(2);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(queue.enqueue(notifications.findById(result.notificationId()).orElseThrow(), NOW)).isZero();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries WHERE next_retry_at IS NOT NULL AND status = 'PENDING'", Long.class)).isEqualTo(2);
    }

    @Test
    void expiredPushStillStoresInAppAndDoesNotCreateDelivery() {
        consentAndEndpoint("expired-push");
        var result = handler.handle(NotificationMessages.json(Map.of("pushExpiresAt", NOW.toString())));
        assertThat(result.status()).isEqualTo(com.team3.notification.domain.entity.ReceptionStatus.CREATED);
        var notification = notifications.findById(result.notificationId()).orElseThrow();
        assertThat(notification.isInAppVisible()).isTrue();
        assertThat(notification.getPushExpiresAt()).isEqualTo(NOW);
        assertThat(notification.getExpiresAt()).isAfter(NOW);
        assertThat(count()).isZero(); assertThat(users.unreadCount(7).unreadCount()).isEqualTo(1);
    }

    @Test
    void pushOnlyExpiredRequestRecordsConsumptionWithoutNotificationOrDelivery() {
        consentAndEndpoint("expired-push-only");
        var json = NotificationMessages.json(Map.of("channels", List.of("PUSH"), "pushExpiresAt", NOW.toString()));
        var result = handler.handle(json);
        assertThat(result.status()).isEqualTo(com.team3.notification.domain.entity.ReceptionStatus.CHANNEL_DISABLED);
        assertThat(result.notificationId()).isNull(); assertThat(notifications.count()).isZero(); assertThat(count()).isZero();
        assertThat(handler.handle(json).duplicateMessage()).isTrue();
    }

    @Test
    void deliveryPreflightUsesPushExpiryWithoutHidingStillValidInApp() {
        consentAndEndpoint("separate-expiry");
        var expiry = NOW.plusSeconds(60);
        var json = NotificationMessages.json(Map.of("pushExpiresAt", expiry.toString()));
        var result = handler.handle(json);
        var claim = store.claimNext().orElseThrow();
        assertThat(store.prepare(claim).orElseThrow().expiresAt()).isEqualTo(expiry);
        clock.now = expiry;
        assertThat(store.prepare(claim)).isEmpty();
        assertThat(state()).isEqualTo("SKIPPED"); verifyNoInteractions(sender);
        assertThat(users.unreadCount(7).unreadCount()).isEqualTo(1);
        assertThat(notifications.findById(result.notificationId()).orElseThrow().getExpiresAt()).isAfter(expiry);
    }

    @Test
    void knownPushExpiryMutationConflictsButSameMessageReplaysIdempotently() {
        consentAndEndpoint("identity");
        var json = NotificationMessages.json(Map.of("pushExpiresAt", NOW.plusSeconds(60).toString()));
        handler.handle(json); assertThat(handler.handle(json).duplicateMessage()).isTrue();
        assertThatThrownBy(() -> handler.handle(NotificationMessages.json(Map.of("pushExpiresAt", NOW.plusSeconds(120).toString()))))
                .isInstanceOf(MessageIdentityConflictException.class);
        assertThat(count()).isEqualTo(1); assertThat(notifications.count()).isEqualTo(1);
    }

    @Test
    void existingDatabaseUpgradePreservesRowsAndIsRepeatable() throws Exception {
        consentAndEndpoint("upgrade");
        var result = handler.handle(NotificationMessages.json(Map.of()));
        // Simulate the previous deployed schema only inside this disposable test database.
        jdbc.execute("ALTER TABLE notifications DROP COLUMN push_expires_at");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "notification_user", NotificationPostgres.PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute(sql("notification-push-expiry.sql"));
            statement.execute(sql("notification-push-expiry.sql"));
        }
        assertThat(notifications.findById(result.notificationId()).orElseThrow().getPushExpiresAt()).isNull();
        assertThat(handler.handle(NotificationMessages.json(Map.of())).duplicateMessage()).isTrue();
        assertThat(store.prepare(store.claimNext().orElseThrow()).orElseThrow().expiresAt())
                .isEqualTo(NotificationMessages.message(Map.of()).expiresAt());
    }

    @Test
    void pushOnlyParentIsNeverExposedThroughInAppListCountReadOrClick() {
        users.updateSettings(7, new SettingsRequest(false, true));
        users.registerEndpoint(7, new EndpointRequest("push-only"));
        var result = handler.handle(NotificationMessages.json(Map.of("channels", List.of("PUSH"))));
        assertThat(count()).isEqualTo(1);
        assertThat(notifications.findById(result.notificationId()).orElseThrow().isInAppVisible()).isFalse();
        assertThat(users.list(7, null, 20).items()).isEmpty();
        assertThat(users.unreadCount(7).unreadCount()).isZero();
        assertThatThrownBy(() -> users.read(7, result.notificationId())).isInstanceOf(NotificationUserApiException.class);
        assertThatThrownBy(() -> users.click(7, result.notificationId())).isInstanceOf(NotificationUserApiException.class);
        users.readAll(7);
        assertThat(notifications.findById(result.notificationId()).orElseThrow().getReadAt()).isNull();
    }

    @Test
    void noConsentNoRequestedPushOrNoEndpointDoesNotQueueDelivery() {
        users.registerEndpoint(7, new EndpointRequest("no-consent"));
        handler.handle(NotificationMessages.json(Map.of()));
        assertThat(count()).isZero();
        users.updateSettings(7, new SettingsRequest(true, true));
        handler.handle(NotificationMessages.json(Map.of("messageId", UUID.randomUUID().toString(),
                "sourceEventId", UUID.randomUUID().toString(), "channels", List.of("IN_APP"))));
        assertThat(count()).isZero();
    }

    @Test
    @DBRider(dataSourceBeanName = "dataSource")
    @DBUnit(schema = "notification", qualifiedTableNames = true, caseSensitiveTableNames = true,
            cacheConnection = false, cacheTableNames = false, dataTypeFactoryClass = PostgresqlDataTypeFactory.class)
    @DataSet(value = "datasets/user/push-ready.yml", executorId = "notification-user", transactional = false)
    @ExpectedDataSet("datasets/user/rolled-back.yml")
    void deliveryInsertFailureRollsBackNotificationAndConsumption() {
        jdbc.execute("ALTER TABLE notification_deliveries ADD CONSTRAINT reject_delivery CHECK(user_id <> 7)");
        try { assertThatThrownBy(() -> handler.handle(NotificationMessages.json(Map.of()))).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE notification_deliveries DROP CONSTRAINT reject_delivery"); }
        assertThat(notifications.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_messages", Long.class)).isZero();
    }

    @Test
    void sendsOutsideTransactionOnlyAfterCommitAndRecordsFcmAcceptance() {
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            consentAndEndpoint("commit-test");
            // Handler's REQUIRES_NEW cannot see uncommitted settings, so create notification+queue here directly.
            var notification = notifications.saveAndFlush(com.team3.notification.domain.entity.Notification.inApp(NotificationMessages.message(Map.of()), NOW));
            queue.enqueue(notification, NOW);
            worker.runOnce();
            verifyNoInteractions(sender);
        });
        when(sender.send(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(state()).isEqualTo("SENDING");
            return "projects/test/messages/accepted";
        });
        worker.runOnce();
        assertThat(state()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT sent_at IS NOT NULL AND lease_token IS NULL FROM notification_deliveries", Boolean.class)).isTrue();
    }

    @Test
    void preflightRejectsRevokedConsentInactiveEndpointAndExpiry() {
        for (String reason : List.of("consent", "endpoint", "expiry")) {
            resetDisposableContainerOnly();
            long endpoint = consentAndEndpoint("preflight");
            var message = NotificationMessages.message(Map.of());
            handler.handle(message);
            if (reason.equals("consent")) users.updateSettings(7, new SettingsRequest(true, false));
            if (reason.equals("endpoint")) users.deactivateEndpoint(7, endpoint);
            if (reason.equals("expiry")) clock.now = message.expiresAt();
            worker.runOnce();
            assertThat(state()).isEqualTo("SKIPPED");
            verifyNoInteractions(sender);
        }
    }

    @Test
    void invalidTokenDeactivatesEndpointButInvalidPayloadDoesNot() {
        long endpoint = consentAndEndpoint("invalid");
        handler.handle(NotificationMessages.json(Map.of()));
        when(sender.send(any())).thenThrow(new PushFailure(PushFailure.Kind.INVALID_TOKEN, "FCM_UNREGISTERED"));
        worker.runOnce();
        assertThat(state()).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT active FROM notification_endpoints WHERE id = ?", Boolean.class, endpoint)).isFalse();
        resetDisposableContainerOnly();
        endpoint = consentAndEndpoint("valid-token");
        handler.handle(NotificationMessages.json(Map.of()));
        when(sender.send(any())).thenThrow(new PushFailure(PushFailure.Kind.PERMANENT, "FCM_INVALID_ARGUMENT"));
        worker.runOnce();
        assertThat(state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT active FROM notification_endpoints WHERE id = ?", Boolean.class, endpoint)).isTrue();
    }

    @Test
    void transientFailureUsesBackoffAndStopsAtAttemptLimit() {
        consentAndEndpoint("retry"); handler.handle(NotificationMessages.json(Map.of()));
        when(sender.send(any())).thenThrow(new PushFailure(PushFailure.Kind.TRANSIENT, "FCM_TEMPORARY_FAILURE"));
        worker.runOnce(); worker.runOnce();
        verify(sender, times(1)).send(any());
        assertThat(state()).isEqualTo("PENDING");
        for (int attempt = 2; attempt <= 5; attempt++) {
            clock.now = clock.now.plus(Duration.ofHours(1)); worker.runOnce();
        }
        assertThat(state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT retry_count FROM notification_deliveries", Integer.class)).isEqualTo(5);
    }

    @Test
    void configurationErrorPreservesAttemptBudgetAndStopsCurrentBatch() {
        consentAndEndpoint("config-a"); users.registerEndpoint(7, new EndpointRequest("config-b"));
        handler.handle(NotificationMessages.json(Map.of()));
        when(sender.send(any())).thenThrow(new PushFailure(PushFailure.Kind.CONFIGURATION, "FCM_CONFIGURATION_ERROR"));
        worker.runOnce();
        verify(sender, times(1)).send(any());
        assertThat(jdbc.queryForObject("SELECT sum(retry_count) FROM notification_deliveries", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries WHERE status = 'PENDING'", Long.class)).isEqualTo(2);
    }

    @Test
    void staleRecoveryRejectsOldLeaseCompletionAndInvalidTokenSideEffects() {
        long endpoint = consentAndEndpoint("stale"); handler.handle(NotificationMessages.json(Map.of()));
        var old = store.claimNext().orElseThrow(); var snapshot = store.prepare(old).orElseThrow();
        clock.now = NOW.plus(Duration.ofMinutes(3));
        assertThat(store.recoverStale()).isEqualTo(1);
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        var current = store.claimNext().orElseThrow();
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(store.sent(old, "old-receipt")).isFalse();
        assertThat(store.failed(old, snapshot, new PushFailure(PushFailure.Kind.INVALID_TOKEN, "FCM_UNREGISTERED"))).isFalse();
        assertThat(jdbc.queryForObject("SELECT active FROM notification_endpoints WHERE id = ?", Boolean.class, endpoint)).isTrue();
        assertThat(store.sent(current, "new-receipt")).isTrue();
        assertThat(state()).isEqualTo("SENT");
    }

    @Test
    void withdrawalCancelsQueuedOrClaimedDeliveriesAndStaleSenderCannotRecreateState() throws Exception {
        consentAndEndpoint("withdraw"); handler.handle(NotificationMessages.json(Map.of()));
        var claim = store.claimNext().orElseThrow();
        var snapshot = store.prepare(claim).orElseThrow();
        var json = new ClassPathResource("contracts/user-withdrawn-v1.json").getContentAsString(StandardCharsets.UTF_8);
        handler.handle(json);
        assertThat(count()).isZero();
        assertThat(store.prepare(claim)).isEmpty();
        assertThat(store.sent(claim, "too-late")).isFalse();
        assertThat(store.failed(claim, snapshot, new PushFailure(PushFailure.Kind.INVALID_TOKEN, "FCM_UNREGISTERED"))).isFalse();
        worker.runOnce(); verifyNoInteractions(sender);
    }

    @Test
    void concurrentClaimersCannotOwnSameDelivery() throws Exception {
        consentAndEndpoint("claim"); handler.handle(NotificationMessages.json(Map.of()));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> { start.await(); return store.claimNext(); });
            var b = pool.submit(() -> { start.await(); return store.claimNext(); });
            start.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)).stream().filter(Optional::isPresent).count()).isEqualTo(1);
        }
    }

    private long consentAndEndpoint(String token) {
        users.updateSettings(7, new SettingsRequest(true, true));
        return users.registerEndpoint(7, new EndpointRequest(token)).id();
    }
    private long count() { return jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Long.class); }
    private String state() { return jdbc.queryForObject("SELECT status FROM notification_deliveries", String.class); }
}
