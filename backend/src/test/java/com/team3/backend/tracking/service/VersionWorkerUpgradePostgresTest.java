package com.team3.backend.tracking.service;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

/** Production upgrade script, exercised only against a dedicated disposable container. */
@Testcontainers
class VersionWorkerUpgradePostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("worker_upgrade_disposable");
    private Connection connection;

    @BeforeEach
    void prepareDisposableSchema() throws Exception {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='application_user') THEN CREATE ROLE application_user; END IF; END $$");
        execute("DROP SCHEMA IF EXISTS application CASCADE");
        execute("CREATE SCHEMA application AUTHORIZATION application_user");
        execute("SET ROLE application_user"); execute("SET search_path TO application, pg_catalog");
        execute("CREATE TABLE opportunity_events(id bigint PRIMARY KEY, processing_status varchar(30) CHECK (processing_status IN ('PENDING','PROCESSING','COMPLETED','FAILED')))");
        execute("CREATE TABLE opportunity_version_tracking(id bigint PRIMARY KEY, status varchar(40) CHECK (status IN ('READY_FOR_EVENT_ANALYSIS','PROCESSED')))");
        execute("CREATE TABLE opportunity_version_analysis(id bigint PRIMARY KEY, tracking_id bigint NOT NULL UNIQUE REFERENCES opportunity_version_tracking(id), analysis_version varchar(60) NOT NULL)");
        execute("INSERT INTO opportunity_events VALUES (1,'PENDING'); INSERT INTO opportunity_version_tracking VALUES (1,'READY_FOR_EVENT_ANALYSIS'); INSERT INTO opportunity_version_analysis VALUES (1,1,'old-v1')");
    }

    @AfterEach
    void closeConnection() throws Exception { if (connection != null) connection.close(); }

    @Test
    void upgradePreservesEvidenceAllowsNewStatusesAndAppendedRuleButRejectsDuplicateRule() throws Exception {
        execute(script());
        execute("UPDATE opportunity_events SET processing_status='WAITING_POLICY'; UPDATE opportunity_version_tracking SET status='PROCESSED_WITH_DEFERRED_DEADLINE'");
        execute("INSERT INTO opportunity_version_analysis VALUES (2,1,'new-v2')");
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM opportunity_version_analysis")) {
            rows.next(); assertThat(rows.getInt(1)).isEqualTo(2);
        }
        assertThatThrownBy(() -> execute("INSERT INTO opportunity_version_analysis VALUES (3,1,'new-v2')")).isInstanceOf(SQLException.class);
    }

    @Test
    void upgradeCanBeRepeatedWithoutDeletingHistory() throws Exception {
        execute(script()); execute(script());
        execute("INSERT INTO opportunity_version_analysis VALUES (2,1,'new-v2')");
        assertThatThrownBy(() -> execute("UPDATE opportunity_events SET processing_status='UNRECOGNIZED'" )).isInstanceOf(SQLException.class);
    }

    @Test
    void unknownCustomConstraintAbortsWholeUpgradeWithoutRemovingIt() throws Exception {
        execute("ALTER TABLE opportunity_version_tracking ADD CONSTRAINT custom_status_length CHECK (length(status)>2)");
        assertThatThrownBy(() -> execute(script())).isInstanceOf(SQLException.class);
        execute("ROLLBACK");
        assertThatThrownBy(() -> execute("UPDATE opportunity_events SET processing_status='WAITING_POLICY'")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO opportunity_version_analysis VALUES (2,1,'new-v2')")).isInstanceOf(SQLException.class);
    }

    private String script() throws Exception {
        return Files.readString(Path.of("../database/scripts/notification-version-worker-upgrade.sql"));
    }
    private void execute(String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
}
