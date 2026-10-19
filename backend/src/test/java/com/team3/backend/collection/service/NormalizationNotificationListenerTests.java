package com.team3.backend.collection.service;

import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
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
                sql.execute("create table raw_normalization_request (id bigint)");
            }
            NormalizationNotificationListener.installTrigger(writer);
            NormalizationNotificationListener.installTrigger(writer);
            try (var sql = listener.createStatement()) {
                sql.execute("LISTEN " + NormalizationNotificationListener.CHANNEL);
            }
            var postgres = listener.unwrap(PGConnection.class);
            writer.setAutoCommit(false);
            try (var sql = writer.createStatement()) {
                sql.execute("insert into raw_normalization_request values (1)");
                assertNull(postgres.getNotifications(100));
                writer.commit();
                var notifications = postgres.getNotifications(2_000);
                assertNotNull(notifications);
                assertEquals(NormalizationNotificationListener.CHANNEL, notifications[0].getName());
                sql.execute("insert into raw_normalization_request values (2)");
                writer.rollback();
                assertNull(postgres.getNotifications(100));
            }
        }
    }
}
