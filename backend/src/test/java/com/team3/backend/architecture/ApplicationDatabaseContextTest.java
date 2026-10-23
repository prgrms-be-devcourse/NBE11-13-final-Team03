package com.team3.backend.architecture;

import com.team3.backend.BackendApplication;
import jakarta.persistence.EntityManagerFactory;
import com.team3.backend.global.config.ApplicationDatabaseBoundaryConfig;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = BackendApplication.class,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = {
                "app.scheduling.enabled=false",
                "yon.normalization.ingest.enabled=false",
                "yon.normalization.condition-set-backfill.enabled=false",
                "region-code-api.sync-on-startup=false"
        })
class ApplicationDatabaseContextTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = admin.createStatement()) {
            statement.execute(new ClassPathResource("db-boundary/database-boundaries.sql")
                    .getContentAsString(StandardCharsets.UTF_8));
            statement.execute("ALTER ROLE application_user PASSWORD 'boundary-test-password'");
            statement.execute("ALTER ROLE notification_user PASSWORD 'boundary-test-password'");
            statement.execute("CREATE TABLE notification.private_probe(id bigint PRIMARY KEY)");
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "application_user");
        registry.add("spring.datasource.password", () -> "boundary-test-password");
        // Keep the runtime ddl-auto: provisioning -> first boot must create every table in application.
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void startupGuardRejectsWrongAccountSearchPathAndExcessPrivileges() throws Exception {
        var runner = new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("database-boundary"))
                .withUserConfiguration(ApplicationDatabaseBoundaryConfig.class);
        runner.withBean(DataSource.class, () -> new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), "application_user", "boundary-test-password"))
                .run(context -> assertThat(context).hasNotFailed());
        assertRejected(runner, POSTGRES.getJdbcUrl(), "notification_user", "boundary-test-password", "login");
        assertRejected(runner, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), "login");
        String jdbcUrl = POSTGRES.getJdbcUrl();
        String wrongSchemaUrl = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=public";
        assertRejected(runner, wrongSchemaUrl, "application_user", "boundary-test-password", "current schema");
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = admin.createStatement()) {
            rejectsWhile(runner, statement, "ALTER SCHEMA notification RENAME TO notification_missing",
                    "ALTER SCHEMA notification_missing RENAME TO notification", "notification schema exists");
            rejectsWhile(runner, statement, "GRANT USAGE ON SCHEMA notification TO application_user",
                    "REVOKE USAGE ON SCHEMA notification FROM application_user", "other schema access");
            rejectsWhile(runner, statement, "GRANT USAGE ON SCHEMA public TO application_user",
                    "REVOKE USAGE ON SCHEMA public FROM application_user", "public schema access");
            rejectsWhile(runner, statement, "ALTER ROLE application_user CREATEDB",
                    "ALTER ROLE application_user NOCREATEDB", "elevated privileges");
            rejectsWhile(runner, statement, "CREATE TABLE public.unmigrated(id bigint)",
                    "DROP TABLE public.unmigrated", "unmigrated public tables");
        }
    }

    private void rejectsWhile(ApplicationContextRunner runner, java.sql.Statement admin,
                              String breakSql, String restoreSql, String check) throws SQLException {
        admin.execute(breakSql);
        try {
            assertRejected(runner, POSTGRES.getJdbcUrl(), "application_user", "boundary-test-password", check);
        } finally {
            admin.execute(restoreSql);
        }
    }

    private void assertRejected(ApplicationContextRunner runner, String url, String user, String password,
                                String check) {
        runner.withBean(DataSource.class, () -> new DriverManagerDataSource(url, user, password))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Database boundary violation (application): " + check + ".");
                });
    }

    @Test
    void actualEntrypointUsesItsOwnSchemaForJpaAndJdbc() throws Exception {
        assertThat(entityManagerFactory.getProperties().get("hibernate.default_schema")).isEqualTo("application");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT current_user, current_schema()")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("application_user");
                assertThat(result.getString(2)).isEqualTo("application");
            }
            try (var result = statement.executeQuery("SELECT count(*) FROM users")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
            assertThatThrownBy(() -> statement.execute("SELECT * FROM notification.private_probe"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));
        }
        // RawIngestListener uses DriverManager, not Hikari: server-side role defaults must also work.
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                "application_user", "boundary-test-password");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_schema()")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("application");
        }
    }
}
