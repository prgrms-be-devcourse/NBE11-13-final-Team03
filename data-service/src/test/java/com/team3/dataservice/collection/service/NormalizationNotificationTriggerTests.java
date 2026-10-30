package com.team3.dataservice.collection.service;

import org.junit.jupiter.api.Tag;

import java.sql.Connection;
import com.team3.dataservice.support.ApplicationPostgres;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@Tag("postgres")
class NormalizationNotificationTriggerTests {
    private static final String CHANNEL = "raw_normalization_requested";
    @Container
    static final PostgreSQLContainer POSTGRES = ApplicationPostgres.create();

    private Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "application_user", "test");
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
                sql.execute("LISTEN " + CHANNEL);
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
                assertEquals(CHANNEL, notifications[0].getName());
                assertEquals("123", notifications[0].getParameter());
                assertEquals(CHANNEL, notifications[1].getName());
                assertEquals("124", notifications[1].getParameter());
                sql.execute("insert into raw_normalization_request values (125)");
                writer.rollback();
                assertEquals(0, postgres.getNotifications(100).length);
            }
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
