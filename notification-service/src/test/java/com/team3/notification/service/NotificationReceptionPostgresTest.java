package com.team3.notification.service;

import com.team3.notification.NotificationApplication;
import com.team3.notification.domain.repository.*;
import com.team3.notification.fixture.NotificationMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.team3.notification.fixture.NotificationPostgres;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.*;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static com.team3.notification.domain.entity.ReceptionStatus.*;

/** Real operator DDL + validate, restricted account and independent receive transactions. */
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {NotificationApplication.class, NotificationReceptionPostgresTest.FixedClockConfig.class},
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.scheduling.enabled=false", "NOTIFICATION_RECEIVER_ENABLED=true",
                "NOTIFICATION_USER_API_ENABLED=true",
                "NOTIFICATION_INTERNAL_API_KEY=test-notification-key"})
class NotificationReceptionPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final String PASSWORD = NotificationPostgres.PASSWORD;

    @Container
    static final PostgreSQLContainer POSTGRES = NotificationPostgres.create();

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean @Primary
        TestClock receptionTestClock() { return new TestClock(); }
    }

    static class TestClock extends Clock {
        private volatile Instant now = NOW;
        void set(Instant instant) { now = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        NotificationPostgres.userSchema(POSTGRES, registry);
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            statement.execute("CREATE TABLE application.private_probe(id bigint PRIMARY KEY)");
        }
    }

    private static String sql(String name) throws Exception {
        return new ClassPathResource("db-boundary/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    @Autowired NotificationMessageHandler handler;
    @Autowired NotificationRepository notifications;
    @Autowired ConsumedMessageRepository consumed;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired NotificationUserService userApi;
    @Value("${local.server.port}") int port;

    @Test
    void realHttpAcknowledgesCommittedReceiptAndReplayDoesNotDuplicateNotification() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            String json = NotificationMessages.json(Map.of());
            var first = post(client, json);
            assertThat(first.statusCode()).isEqualTo(200);
            var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
            String expected = new ClassPathResource("contracts/notification-receipt-v1.json")
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(mapper.readTree(first.body())).isEqualTo(mapper.readTree(expected));
            // Separate repository transactions observe the data after HTTP returns.
            assertThat(notifications.count()).isEqualTo(1);
            assertThat(consumed.count()).isEqualTo(1);
            var replay = post(client, json);
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(replay.body()).path("duplicateMessage").asBoolean()).isTrue();
            assertThat(notifications.count()).isEqualTo(1);
            assertThat(consumed.count()).isEqualTo(1);
            assertThat(post(client, NotificationMessages.json(Map.of("title", "다른 본문"))).statusCode()).isEqualTo(409);
            assertThat(post(client, NotificationMessages.json(Map.of("schemaVersion", 2))).statusCode()).isEqualTo(400);
        }
    }

    @Test
    void deadlineExtensionIsPersistedAndHttpReplayIsIdempotent() throws Exception {
        String json = new ClassPathResource("contracts/notification-deadline-extended-v1.json")
                .getContentAsString(StandardCharsets.UTF_8);
        try (var client = HttpClient.newHttpClient()) {
            assertThat(post(client, json).statusCode()).isEqualTo(200);
            assertThat(post(client, json).statusCode()).isEqualTo(200);
        }
        assertThat(notifications.count()).isEqualTo(1);
        assertThat(consumed.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT type FROM notification.notifications", String.class)).isEqualTo("DEADLINE_EXTENDED");
    }

    @Test
    void newMatchSummaryIsOneInAppNotificationAndReplayIsIdempotent() throws Exception {
        String json = new ClassPathResource("contracts/notification-new-match-v1.json")
                .getContentAsString(StandardCharsets.UTF_8);
        try (var client = HttpClient.newHttpClient()) {
            assertThat(post(client, json).statusCode()).isEqualTo(200);
            assertThat(post(client, json).statusCode()).isEqualTo(200);
            var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
            var changed = new LinkedHashMap<>(mapper.readValue(json, Map.class));
            var payload = new LinkedHashMap<>((Map<String, Object>) changed.get("payload"));
            payload.put("matchCount", 4); payload.put("eligibleCount", 3);
            changed.put("payload", payload);
            assertThat(post(client, mapper.writeValueAsString(changed)).statusCode()).isEqualTo(409);
        }
        assertThat(notifications.count()).isEqualTo(1);
        assertThat(consumed.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT type FROM notification.notifications", String.class)).isEqualTo("NEW_MATCH");
        var saved = notifications.findAll().iterator().next();
        assertThat(saved.getLink()).isEqualTo("/test-recommendations");
        assertThat(saved.getPayload()).containsEntry("matchCount", 3).containsEntry("possiblyCount", 1);
    }

    @Test
    void allEligibilityTypesAreStoredAndReplayedWithoutDuplicateNotifications() throws Exception {
        String template = new ClassPathResource("contracts/notification-eligibility-v1.json").getContentAsString(StandardCharsets.UTF_8);
        try (var client = HttpClient.newHttpClient()) {
            for (String type : List.of("BECAME_ELIGIBLE", "ELIGIBILITY_DOWNGRADED", "ELIGIBILITY_REVIEW_REQUIRED")) {
                var json = template.replace("BECAME_ELIGIBLE", type)
                        .replace("11111111-1111-4111-8111-111111111111", UUID.randomUUID().toString())
                        .replace("22222222-2222-4222-8222-222222222222", UUID.randomUUID().toString());
                if (!type.equals("BECAME_ELIGIBLE")) json = json.replace("[\"IN_APP\", \"PUSH\"]", "[\"IN_APP\"]");
                // The shared example occurs two days after this class's default test clock.
                clock.set(Instant.parse("2026-10-25T00:00:00Z"));
                assertThat(post(client, json).statusCode()).isEqualTo(200);
                assertThat(post(client, json).statusCode()).isEqualTo(200);
            }
        }
        assertThat(notifications.count()).isEqualTo(3); assertThat(consumed.count()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT type FROM notification.notifications ORDER BY id", String.class))
                .containsExactly("BECAME_ELIGIBLE", "ELIGIBILITY_DOWNGRADED", "ELIGIBILITY_REVIEW_REQUIRED");
    }

    @Test
    void realHttpStorageFailureReturns503AfterAtomicRollbackAndCanBeRetried() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            jdbc.execute("ALTER TABLE notification.consumed_messages ADD CONSTRAINT reject_http_consume CHECK (user_id <> 7)");
            try {
                assertThat(post(client, NotificationMessages.json(Map.of())).statusCode()).isEqualTo(503);
                assertThat(notifications.count()).isZero();
                assertThat(consumed.count()).isZero();
            } finally {
                jdbc.execute("ALTER TABLE notification.consumed_messages DROP CONSTRAINT reject_http_consume");
            }
            assertThat(post(client, NotificationMessages.json(Map.of())).statusCode()).isEqualTo(200);
            assertThat(notifications.count()).isEqualTo(1);
            assertThat(consumed.count()).isEqualTo(1);
        }
    }

    private HttpResponse<String> post(HttpClient client, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/notification-messages"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-INTERNAL-KEY", "test-notification-key")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @BeforeEach
    void resetDisposableContainerData() {
        clock.set(NOW);
        jdbc.execute("TRUNCATE notification.consumed_messages, notification.notifications, notification.notification_settings, notification.notification_endpoints, notification.withdrawn_users, notification.notification_deliveries RESTART IDENTITY CASCADE");
    }

    @Test
    void savesInAppAndConsumptionAtomicallyWithoutQueryingApplication() {
        var result = handler.handle(NotificationMessages.json(Map.of()));
        assertThat(result.status()).isEqualTo(CREATED);
        assertThat(result.duplicateMessage()).isFalse();
        assertThat(consumed.count()).isEqualTo(1);
        var saved = notifications.findById(result.notificationId()).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getSourceEventId()).isEqualTo(NotificationMessages.message(Map.of()).sourceEventId());
        assertThat(saved.getOpportunityId()).isEqualTo(10L);
        assertThat(saved.getOpportunityVersionId()).isEqualTo(20L);
        assertThat(saved.getPayload()).containsEntry("threshold", "D-3");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getReadAt()).isNull();
        assertThat(saved.getClickedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("notification_user");
        assertThatThrownBy(() -> jdbc.queryForList("SELECT * FROM application.private_probe"))
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void withdrawalPurgesOwnedDataRetainsReceiptsAndBlocksLateMessagesAndAllUserMutations() throws Exception {
        var first = handler.handle(NotificationMessages.json(Map.of()));
        handler.handle(NotificationMessages.json(Map.of("userId", 8, "messageId", UUID.randomUUID().toString())));
        userApi.updateSettings(7, new com.team3.notification.api.NotificationUserDtos.SettingsRequest(true, true));
        userApi.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest("withdraw-token"));
        var result = handler.handle(withdrawal(Map.of()));
        assertThat(result.status()).isEqualTo(WITHDRAWN);
        assertThat(result.notificationId()).isNull();
        assertThat(notifications.findById(first.notificationId())).isEmpty();
        assertThat(notifications.count()).isEqualTo(1); // user 8 is untouched
        assertThat(consumed.findByMessageId(first.messageId()).orElseThrow().getNotificationId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM withdrawn_users", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_settings", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_endpoints", Long.class)).isZero();
        // A replay retains its original receipt, but never recreates the deleted Notification.
        assertThat(handler.handle(NotificationMessages.json(Map.of())).duplicateMessage()).isTrue();
        var late = handler.handle(NotificationMessages.json(Map.of("messageId", UUID.randomUUID().toString(),
                "sourceEventId", UUID.randomUUID().toString())));
        assertThat(late.status()).isEqualTo(WITHDRAWN);
        assertThat(late.notificationId()).isNull();
        for (Runnable attempt : List.<Runnable>of(() -> userApi.list(7, null, 20), () -> userApi.unreadCount(7),
                () -> userApi.settings(7), () -> userApi.readAll(7), () -> userApi.read(7, first.notificationId()),
                () -> userApi.click(7, first.notificationId()),
                () -> userApi.updateSettings(7, new com.team3.notification.api.NotificationUserDtos.SettingsRequest(true, true)),
                () -> userApi.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest("new-token")),
                () -> userApi.deactivateEndpoint(7, 1))) {
            assertThatThrownBy(attempt::run).isInstanceOfSatisfying(NotificationUserApiException.class,
                    error -> assertThat(error.status()).isEqualTo(410));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_endpoints", Long.class)).isZero();
    }

    @Test
    void withdrawalIsIdempotentDoesNotExpireAndRejectsChangedIdentity() throws Exception {
        var result = handler.handle(withdrawal(Map.of("occurredAt", "2000-01-01T00:00:00Z")));
        assertThat(result.status()).isEqualTo(WITHDRAWN);
        assertThat(handler.handle(withdrawal(Map.of("occurredAt", "2000-01-01T00:00:00Z", "futureOptional", true)))
                .duplicateMessage()).isTrue();
        assertThatThrownBy(() -> handler.handle(withdrawal(Map.of("occurredAt", "2000-01-02T00:00:00Z"))))
                .isInstanceOf(MessageIdentityConflictException.class);
        assertThat(handler.handle(withdrawal(Map.of("messageId", UUID.randomUUID().toString()))).status()).isEqualTo(WITHDRAWN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM withdrawn_users", Long.class)).isEqualTo(1);
        assertThat(consumed.count()).isEqualTo(2);
    }

    @Test
    void sessionEndDeactivatesOnlyThatSessionsEndpointsAndIsIdempotent() throws Exception {
        var loggedOut = userApi.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest(
                "laptop-token", "44444444-4444-4444-8444-444444444444"));
        var phone = userApi.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest(
                "phone-token", "55555555-5555-4555-8555-555555555555"));
        var legacy = userApi.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest("legacy-token"));
        var other = userApi.registerEndpoint(8, new com.team3.notification.api.NotificationUserDtos.EndpointRequest(
                "other-user-token", "44444444-4444-4444-8444-444444444444"));
        userApi.updateSettings(7, new com.team3.notification.api.NotificationUserDtos.SettingsRequest(true, true));

        var result = handler.handle(sessionEnd(Map.of()));
        assertThat(result.status()).isEqualTo(SESSION_ENDED);
        assertThat(result.notificationId()).isNull();
        assertThat(result.duplicateMessage()).isFalse();
        assertThat(active(loggedOut.id())).isFalse();
        assertThat(active(phone.id())).isTrue();      // another session of the same user
        assertThat(active(legacy.id())).isFalse();    // registered before session binding: no session to match, so this user's logout ends it
        assertThat(active(other.id())).isTrue();      // same session ID string, other user
        assertThat(userApi.settings(7).pushEnabled()).isTrue(); // logout is not a consent change
        assertThat(jdbc.queryForObject("SELECT count(*) FROM withdrawn_users", Long.class)).isZero();

        // Replay keeps the receipt; a changed identity is a conflict; a second logout message is consumed again.
        assertThat(handler.handle(sessionEnd(Map.of("futureOptional", true))).duplicateMessage()).isTrue();
        assertThatThrownBy(() -> handler.handle(sessionEnd(Map.of("occurredAt", "2026-10-29T00:00:00Z"))))
                .isInstanceOf(MessageIdentityConflictException.class);
        assertThat(handler.handle(sessionEnd(Map.of("messageId", UUID.randomUUID().toString()))).status()).isEqualTo(SESSION_ENDED);
        assertThat(consumed.count()).isEqualTo(2);

        // The next login on that browser re-registers the same token and binds it to the new session.
        var again = userApi.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest(
                "laptop-token", "66666666-6666-4666-8666-666666666666"));
        assertThat(again.id()).isEqualTo(loggedOut.id());
        assertThat(active(loggedOut.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT session_id FROM notification_endpoints WHERE id = ?", String.class, loggedOut.id()))
                .isEqualTo("66666666-6666-4666-8666-666666666666");
        // A registration without a session (older Application) keeps the known session instead of clearing it.
        userApi.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest("laptop-token"));
        assertThat(jdbc.queryForObject("SELECT session_id FROM notification_endpoints WHERE id = ?", String.class, loggedOut.id()))
                .isEqualTo("66666666-6666-4666-8666-666666666666");
        // The legacy row is bound by its next registration and from then on survives other sessions' logouts.
        userApi.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest(
                "legacy-token", "77777777-7777-4777-8777-777777777777"));
        assertThat(active(legacy.id())).isTrue();
        handler.handle(sessionEnd(Map.of("messageId", UUID.randomUUID().toString(), "sessionId", "66666666-6666-4666-8666-666666666666")));
        assertThat(active(loggedOut.id())).isFalse();
        assertThat(active(legacy.id())).isTrue();
        assertThat(active(other.id())).isTrue();
    }

    @Test
    void sessionEndAfterWithdrawalRecordsConsumptionWithoutResurrectingAnything() throws Exception {
        userApi.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest(
                "gone-token", "44444444-4444-4444-8444-444444444444"));
        handler.handle(withdrawal(Map.of()));
        assertThat(handler.handle(sessionEnd(Map.of())).status()).isEqualTo(SESSION_ENDED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_endpoints", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_settings", Long.class)).isZero();
        assertThat(consumed.count()).isEqualTo(2);
    }

    @Test
    void sessionEndConsumptionFailureRollsBackDeactivation() throws Exception {
        var endpoint = userApi.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest(
                "atomic-session-token", "44444444-4444-4444-8444-444444444444"));
        jdbc.execute("ALTER TABLE notification.consumed_messages ADD CONSTRAINT reject_session_receipt "
                + "CHECK (message_id <> '11111111-1111-4111-8111-111111111113'::uuid)");
        try {
            assertThatThrownBy(() -> handler.handle(sessionEnd(Map.of()))).isInstanceOf(DataIntegrityViolationException.class);
        } finally { jdbc.execute("ALTER TABLE notification.consumed_messages DROP CONSTRAINT reject_session_receipt"); }
        assertThat(active(endpoint.id())).isTrue();
        assertThat(handler.handle(sessionEnd(Map.of())).status()).isEqualTo(SESSION_ENDED);
        assertThat(active(endpoint.id())).isFalse();
    }

    private boolean active(long endpointId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT active FROM notification_endpoints WHERE id = ?", Boolean.class, endpointId));
    }

    @Test
    void withdrawalConsumptionFailureRollsBackTombstoneAndEveryDeletion() throws Exception {
        var first = handler.handle(NotificationMessages.json(Map.of()));
        userApi.updateSettings(7, new com.team3.notification.api.NotificationUserDtos.SettingsRequest(true, true));
        userApi.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest("atomic-token"));
        jdbc.execute("ALTER TABLE notification.consumed_messages ADD CONSTRAINT reject_withdrawal_receipt "
                + "CHECK (message_id <> '11111111-1111-4111-8111-111111111112'::uuid)");
        try {
            assertThatThrownBy(() -> handler.handle(withdrawal(Map.of()))).isInstanceOf(DataIntegrityViolationException.class);
        } finally { jdbc.execute("ALTER TABLE notification.consumed_messages DROP CONSTRAINT reject_withdrawal_receipt"); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM withdrawn_users", Long.class)).isZero();
        assertThat(notifications.findById(first.notificationId())).isPresent();
        assertThat(userApi.settings(7).pushEnabled()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_endpoints", Long.class)).isEqualTo(1);
        assertThat(handler.handle(withdrawal(Map.of())).status()).isEqualTo(WITHDRAWN);
    }

    @Test
    void concurrentWithdrawalAndMessageNeverLeaveAnOrphanNotification() throws Exception {
        var results = simultaneously(withdrawal(Map.of()), NotificationMessages.json(Map.of()));
        assertThat(results).anySatisfy(result -> assertThat(result.status()).isEqualTo(WITHDRAWN));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM withdrawn_users", Long.class)).isEqualTo(1);
        assertThat(notifications.count()).isZero();
        assertThat(consumed.count()).isEqualTo(2);
    }

    @Test
    void simultaneousWithdrawalsCreateOneControlReceipt() throws Exception {
        var results = simultaneously(withdrawal(Map.of()), withdrawal(Map.of()));
        assertThat(results.stream().filter(NotificationReceptionResult::duplicateMessage).count()).isEqualTo(1);
        assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(WITHDRAWN));
        assertThat(consumed.count()).isEqualTo(1);
    }

    @Test
    void concurrentEndpointAndSettingsWritesCannotResurrectAfterWithdrawal() throws Exception {
        var ready = new CountDownLatch(3); var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var withdrawal = pool.submit(() -> { ready.countDown(); start.await(); return handler.handle(withdrawal(Map.of())); });
            Callable<Boolean> endpoint = () -> {
                ready.countDown(); start.await();
                try { userApi.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest("race-token")); }
                catch (NotificationUserApiException error) { assertThat(error.status()).isEqualTo(410); }
                return true;
            };
            Callable<Boolean> setting = () -> {
                ready.countDown(); start.await();
                try { userApi.updateSettings(7, new com.team3.notification.api.NotificationUserDtos.SettingsRequest(true, true)); }
                catch (NotificationUserApiException error) { assertThat(error.status()).isEqualTo(410); }
                return true;
            };
            var a = pool.submit(endpoint); var b = pool.submit(setting);
            try { assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            assertThat(withdrawal.get(10, TimeUnit.SECONDS).status()).isEqualTo(WITHDRAWN);
            assertThat(a.get(10, TimeUnit.SECONDS)).isTrue(); assertThat(b.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_settings", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_endpoints", Long.class)).isZero();
    }

    @Test
    void realHttpControlCommitsBeforeReceiptAndUserApiReturns410AfterTombstone() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            assertThat(post(client, withdrawal(Map.of())).statusCode()).isEqualTo(200);
            var replay = post(client, withdrawal(Map.of()));
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readTree(replay.body())
                    .path("duplicateMessage").asBoolean()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM withdrawn_users", Long.class)).isEqualTo(1);
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/users/7/notification-settings"))
                    .header("X-INTERNAL-KEY", "test-notification-key").GET().build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(410);
        }
    }

    private String sessionEnd(Map<String, ?> changes) throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        Map<String, Object> envelope = new LinkedHashMap<>(mapper.readValue(new ClassPathResource("contracts/user-session-ended-v1.json")
                .getContentAsString(StandardCharsets.UTF_8), Map.class));
        envelope.putAll(changes);
        return mapper.writeValueAsString(envelope);
    }

    private String withdrawal(Map<String, ?> changes) throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        Map<String, Object> envelope = new LinkedHashMap<>(mapper.readValue(new ClassPathResource("contracts/user-withdrawn-v1.json")
                .getContentAsString(StandardCharsets.UTF_8), Map.class));
        envelope.putAll(changes);
        return mapper.writeValueAsString(envelope);
    }

    @Test
    void cursorPagesAndUnreadCountsAreIsolatedByUser() {
        var first = handler.handle(NotificationMessages.json(Map.of()));
        var second = handler.handle(NotificationMessages.json(Map.of("messageId", UUID.randomUUID().toString(),
                "sourceEventId", UUID.randomUUID().toString())));
        handler.handle(NotificationMessages.json(Map.of("messageId", UUID.randomUUID().toString(), "userId", 8)));
        var page = userApi.list(7, null, 1);
        assertThat(page.items()).extracting(com.team3.notification.api.NotificationUserDtos.Item::id)
                .containsExactly(second.notificationId());
        assertThat(page.hasNext()).isTrue();
        assertThat(userApi.list(7, page.nextCursor(), 1).items())
                .extracting(com.team3.notification.api.NotificationUserDtos.Item::id).containsExactly(first.notificationId());
        assertThat(userApi.unreadCount(7).unreadCount()).isEqualTo(2);
        assertThat(userApi.unreadCount(8).unreadCount()).isEqualTo(1);
        assertThatThrownBy(() -> userApi.list(7, null, 101)).isInstanceOf(NotificationUserApiException.class);
    }

    @Test
    void readClickAndReadAllAreOwnerScopedAndPreserveFirstTimestamp() {
        var id = handler.handle(NotificationMessages.json(Map.of())).notificationId();
        assertThatThrownBy(() -> userApi.read(8, id)).isInstanceOf(NotificationUserApiException.class);
        assertThatThrownBy(() -> userApi.click(8, id)).isInstanceOf(NotificationUserApiException.class);
        userApi.click(7, id);
        assertThat(userApi.unreadCount(7).unreadCount()).isEqualTo(1);
        userApi.read(7, id);
        clock.set(NOW.plusSeconds(60));
        userApi.read(7, id);
        userApi.click(7, id);
        var row = notifications.findById(id).orElseThrow();
        assertThat(row.getReadAt()).isEqualTo(NOW);
        assertThat(row.getClickedAt()).isEqualTo(NOW);
        handler.handle(NotificationMessages.json(Map.of("userId", 8, "messageId", UUID.randomUUID().toString())));
        userApi.readAll(7);
        assertThat(userApi.unreadCount(7).unreadCount()).isZero();
        assertThat(userApi.unreadCount(8).unreadCount()).isEqualTo(1);
    }

    @Test
    void settingsDefaultsExplicitConsentRevocationAndReceptionIntersection() {
        assertThat(userApi.settings(7).inAppEnabled()).isTrue();
        assertThat(userApi.settings(7).pushEnabled()).isFalse();
        var enabled = userApi.updateSettings(7, new com.team3.notification.api.NotificationUserDtos.SettingsRequest(false, true));
        assertThat(enabled.pushConsentAt()).isEqualTo(NOW);
        clock.set(NOW.plusSeconds(1));
        assertThat(userApi.updateSettings(7, new com.team3.notification.api.NotificationUserDtos.SettingsRequest(false, true))
                .pushConsentAt()).isEqualTo(NOW);
        var disabled = handler.handle(NotificationMessages.json(Map.of()));
        assertThat(disabled.status()).isEqualTo(CHANNEL_DISABLED);
        assertThat(notifications.count()).isZero();
        var revoked = userApi.updateSettings(7, new com.team3.notification.api.NotificationUserDtos.SettingsRequest(true, false));
        assertThat(revoked.pushConsentAt()).isNull();
        assertThat(handler.handle(NotificationMessages.json(Map.of())).status()).isEqualTo(CHANNEL_DISABLED);
        assertThat(handler.handle(NotificationMessages.json(Map.of("messageId", UUID.randomUUID().toString()))).status()).isEqualTo(CREATED);
    }

    @Test
    void tokenRegistrationIsIdempotentDoesNotGrantPushConsentAndNeverTransfersOwnership() {
        var body = new com.team3.notification.api.NotificationUserDtos.EndpointRequest("private-test-token");
        var endpoint = userApi.registerEndpoint(7, body);
        assertThat(userApi.registerEndpoint(7, body).id()).isEqualTo(endpoint.id());
        assertThat(userApi.settings(7).pushEnabled()).isFalse();
        assertThatThrownBy(() -> userApi.registerEndpoint(8, body)).isInstanceOf(NotificationUserApiException.class);
        assertThatThrownBy(() -> userApi.deactivateEndpoint(8, endpoint.id())).isInstanceOf(NotificationUserApiException.class);
        userApi.deactivateEndpoint(7, endpoint.id());
        userApi.deactivateEndpoint(7, endpoint.id());
        assertThat(jdbc.queryForObject("SELECT active FROM notification_endpoints WHERE id = ?", Boolean.class, endpoint.id())).isFalse();
        assertThat(userApi.registerEndpoint(7, body).active()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_endpoints", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentTokenRegistrationUsesOneRowAndRejectsOtherOwner() throws Exception {
        var body = new com.team3.notification.api.NotificationUserDtos.EndpointRequest("concurrent-token");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> { start.await(); return userApi.registerEndpoint(7, body); });
            var b = pool.submit(() -> { start.await(); return userApi.registerEndpoint(7, body); });
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS).id()).isEqualTo(b.get(10, TimeUnit.SECONDS).id());
        }
        assertThatThrownBy(() -> userApi.registerEndpoint(8, body)).isInstanceOf(NotificationUserApiException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_endpoints", Long.class)).isEqualTo(1);
    }

    @Test
    void realUserApiHttpWorksAlongsideMessageReceptionWithDistinctFilterMarkers() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            assertThat(post(client, NotificationMessages.json(Map.of())).statusCode()).isEqualTo(200);
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/users/7/notifications/unread-count"))
                    .header("X-INTERNAL-KEY", "test-notification-key").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body()).path("unreadCount").asLong()).isEqualTo(1);
        }
    }

    @Test
    void messageReplayReturnsOriginalReceiptAndChangedBodyIsRejected() {
        var first = handler.handle(NotificationMessages.json(Map.of()));
        var replay = handler.handle(NotificationMessages.json(Map.of("channels", List.of("PUSH", "IN_APP"),
                "futureOptional", true)));
        assertThat(replay.duplicateMessage()).isTrue();
        assertThat(replay.status()).isEqualTo(CREATED);
        assertThat(replay.notificationId()).isEqualTo(first.notificationId());
        assertThatThrownBy(() -> handler.handle(NotificationMessages.json(Map.of("title", "변경된 본문"))))
                .isInstanceOf(MessageIdentityConflictException.class);
        assertThat(notifications.count()).isEqualTo(1);
        assertThat(consumed.count()).isEqualTo(1);
    }

    @Test
    void alreadyConsumedMessageKeepsOriginalResultEvenAfterExpiry() {
        var first = handler.handle(NotificationMessages.json(Map.of()));
        clock.set(NotificationMessages.message(Map.of()).expiresAt());
        var replay = handler.handle(NotificationMessages.json(Map.of()));
        assertThat(replay.status()).isEqualTo(CREATED);
        assertThat(replay.notificationId()).isEqualTo(first.notificationId());
        assertThat(replay.duplicateMessage()).isTrue();
        assertThat(consumed.count()).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(1);
    }

    @Test
    void differentMessageIdsForSameUserEventCreateOneNotificationButEachReceipt() {
        var first = handler.handle(NotificationMessages.json(Map.of()));
        var second = handler.handle(NotificationMessages.json(Map.of("messageId", UUID.randomUUID().toString())));
        assertThat(second.status()).isEqualTo(DUPLICATE_EVENT);
        assertThat(second.notificationId()).isEqualTo(first.notificationId());
        assertThat(second.duplicateMessage()).isFalse();
        assertThat(notifications.count()).isEqualTo(1);
        assertThat(consumed.count()).isEqualTo(2);
        handler.handle(NotificationMessages.json(Map.of("messageId", UUID.randomUUID().toString(), "userId", 8)));
        assertThat(notifications.count()).isEqualTo(2);
    }

    @Test
    void expirationBoundaryAndDefaultPushDenialConsumeWithoutNotification() {
        String expired = NotificationMessages.json(Map.of("occurredAt", NOW.minusSeconds(60).toString(),
                "expiresAt", NOW.toString()));
        assertThat(handler.handle(expired).status()).isEqualTo(EXPIRED);
        assertThat(handler.handle(expired).duplicateMessage()).isTrue();
        var disabled = handler.handle(NotificationMessages.json(Map.of("messageId", UUID.randomUUID().toString(),
                "channels", List.of("PUSH"))));
        assertThat(disabled.status()).isEqualTo(CHANNEL_DISABLED);
        assertThat(disabled.notificationId()).isNull();
        assertThat(notifications.count()).isZero();
        assertThat(consumed.count()).isEqualTo(2);
    }

    @Test
    void malformedOrUnsupportedMessagesAreNotMarkedConsumed() {
        for (var change : List.of(Map.of("schemaVersion", 2), Map.of("eventType", "UNSUPPORTED_EVENT"),
                Map.of("type", "UNKNOWN"), Map.of("userId", -1), Map.of("link", "https://external.example"))) {
            assertThatThrownBy(() -> handler.handle(NotificationMessages.json(change))).isInstanceOf(RuntimeException.class);
        }
        assertThat(notifications.count()).isZero();
        assertThat(consumed.count()).isZero();
    }

    @Test
    void consumptionFailureRollsBackNotificationAndDoesNotPretendDuplicateSuccess() {
        jdbc.execute("ALTER TABLE notification.consumed_messages ADD CONSTRAINT reject_test_consume CHECK (user_id <> 7)");
        try {
            assertThatThrownBy(() -> handler.handle(NotificationMessages.json(Map.of())))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(notifications.count()).isZero();
            assertThat(consumed.count()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE notification.consumed_messages DROP CONSTRAINT reject_test_consume");
        }
        assertThat(handler.handle(NotificationMessages.json(Map.of())).status()).isEqualTo(CREATED);
    }

    @Test
    void concurrentSameMessageCreatesOneReceiptAndOneNotification() throws Exception {
        var results = simultaneously(NotificationMessages.json(Map.of()), NotificationMessages.json(Map.of()));
        assertThat(results).extracting(NotificationReceptionResult::notificationId).containsOnly(results.getFirst().notificationId());
        assertThat(results.stream().filter(NotificationReceptionResult::duplicateMessage).count()).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(1);
        assertThat(consumed.count()).isEqualTo(1);
    }

    @Test
    void concurrentDifferentMessagesForSameEventCreateOneNotificationAndTwoReceipts() throws Exception {
        var results = simultaneously(NotificationMessages.json(Map.of()),
                NotificationMessages.json(Map.of("messageId", UUID.randomUUID().toString())));
        assertThat(results).extracting(NotificationReceptionResult::status).containsExactlyInAnyOrder(CREATED, DUPLICATE_EVENT);
        assertThat(notifications.count()).isEqualTo(1);
        assertThat(consumed.count()).isEqualTo(2);
    }

    @Test
    void receiptSurvivesNotificationDeletionAndApplicationCannotReadInbox() throws Exception {
        var result = handler.handle(NotificationMessages.json(Map.of()));
        notifications.deleteById(result.notificationId());
        assertThat(consumed.findByMessageId(result.messageId()).orElseThrow().getNotificationId()).isNull();
        assertThat(consumed.count()).isEqualTo(1);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "application_user", PASSWORD);
             var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM notification.notifications"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint c
                JOIN pg_class child ON child.oid = c.conrelid
                JOIN pg_namespace cn ON cn.oid = child.relnamespace
                JOIN pg_class parent ON parent.oid = c.confrelid
                JOIN pg_namespace pn ON pn.oid = parent.relnamespace
                WHERE c.contype = 'f' AND cn.nspname = 'notification' AND pn.nspname <> 'notification'
                """, Long.class)).isZero();
    }

    @Test
    void reapplyingOneTimeDdlFailsWithoutChangingExistingData() throws Exception {
        handler.handle(NotificationMessages.json(Map.of()));
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "notification_user", PASSWORD);
             var statement = connection.createStatement()) {
            String ddl = sql("notification-inbox.sql");
            try {
                assertThatThrownBy(() -> statement.execute(ddl)).isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42P07"));
            } finally {
                statement.execute("ROLLBACK");
            }
        }
        assertThat(notifications.count()).isEqualTo(1);
        assertThat(consumed.count()).isEqualTo(1);
    }

    private List<NotificationReceptionResult> simultaneously(String first, String second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<NotificationReceptionResult>> tasks = new ArrayList<>();
            for (String json : List.of(first, second)) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    return handler.handle(json);
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<NotificationReceptionResult> results = new ArrayList<>();
            for (var task : tasks) results.add(task.get(20, TimeUnit.SECONDS));
            return results;
        }
    }
}
