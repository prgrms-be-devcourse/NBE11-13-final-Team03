package com.team3.backend.collection.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class NormalizationNotificationListenerTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    void notifiesOnlyAfterCommitAndNeverOnRollback() throws Exception {
        try (var listener = connect(); var writer = connect()) {
            try (var sql = writer.createStatement()) {
                sql.execute("create table if not exists raw_normalization_request (id bigint)");
            }
            installTrigger(writer);
            installTrigger(writer);
            try (var sql = listener.createStatement()) {
                sql.execute("LISTEN " + NormalizationNotificationListener.CHANNEL);
            }
            var postgres = listener.unwrap(PGConnection.class);
            writer.setAutoCommit(false);
            try (var sql = writer.createStatement()) {
                sql.execute("insert into raw_normalization_request values (123), (124)");
                assertEquals(0, postgres.getNotifications(100).length);
                writer.commit();
                var notifications = postgres.getNotifications(2_000);
                assertNotNull(notifications);
                assertEquals(2, notifications.length);
                assertEquals(NormalizationNotificationListener.CHANNEL, notifications[0].getName());
                assertEquals("123", notifications[0].getParameter());
                assertEquals(NormalizationNotificationListener.CHANNEL, notifications[1].getName());
                assertEquals("124", notifications[1].getParameter());
                sql.execute("insert into raw_normalization_request values (125)");
                writer.rollback();
                assertEquals(0, postgres.getNotifications(100).length);
            }
        }
    }

    @Test
    void reconnectsAndSignalsPersistedRequestsWithoutNewNotification() throws Exception {
        var properties = new DataSourceProperties();
        properties.setUrl(POSTGRES.getJdbcUrl() + "?ApplicationName=normalization-reconnect-test");
        properties.setUsername(POSTGRES.getUsername());
        properties.setPassword(POSTGRES.getPassword());
        var signals = new LinkedBlockingQueue<Object>();
        var listener = new NormalizationNotificationListener(properties, signals::add);
        try (var admin = connect()) {
            listener.start();
            assertInstanceOf(NormalizationRequestsAvailable.class, signals.poll(5, TimeUnit.SECONDS));
            int originalPid;
            try (var statement = admin.createStatement();
                 var rows = statement.executeQuery("select pid from pg_stat_activity "
                         + "where application_name = 'normalization-reconnect-test'")) {
                assertTrue(rows.next());
                originalPid = rows.getInt(1);
            }
            try (var statement = admin.createStatement()) {
                statement.execute("select pg_terminate_backend(" + originalPid + ")");
            }
            // 새 NOTIFY가 없어도 재구독 직후 영속 PENDING 조회 신호를 보낸다.
            assertInstanceOf(NormalizationRequestsAvailable.class, signals.poll(12, TimeUnit.SECONDS));
            try (var statement = admin.createStatement();
                 var rows = statement.executeQuery("select pid from pg_stat_activity "
                         + "where application_name = 'normalization-reconnect-test'")) {
                assertTrue(rows.next());
                assertNotEquals(originalPid, rows.getInt(1));
                assertFalse(rows.next());
            }
        } finally {
            listener.stop();
        }
        try (var admin = connect(); var statement = admin.createStatement();
             var rows = statement.executeQuery("select count(*) from pg_stat_activity "
                     + "where application_name = 'normalization-reconnect-test'")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1), "종료 후 전용 물리 연결이 남으면 안 됩니다.");
        }
    }

    private void installTrigger(Connection connection) throws Exception {
        var script = new ClassPathResource("db/scripts/normalization-notifications.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try (var statement = connection.createStatement()) {
            statement.execute(script);
        }
    }
}
