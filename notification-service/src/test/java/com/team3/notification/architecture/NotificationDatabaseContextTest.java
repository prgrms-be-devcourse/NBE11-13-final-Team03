package com.team3.notification.architecture;

import com.team3.notification.NotificationApplication;
import com.team3.notification.fixture.BoundaryProbe;
import com.team3.notification.fixture.BoundaryProbeRepository;
import jakarta.persistence.EntityManagerFactory;
import com.team3.notification.config.NotificationDatabaseBoundaryConfig;
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
@SpringBootTest(classes = NotificationApplication.class,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = {
                "app.scheduling.enabled=false"
        })
class NotificationDatabaseContextTest {

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
            statement.execute("CREATE TABLE application.private_probe(id bigint PRIMARY KEY)");
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "notification_user");
        registry.add("spring.datasource.password", () -> "boundary-test-password");
        // Only the disposable container may initialize/drop the Entity schema.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void startupGuardRejectsWrongAccountSearchPathAndExcessPrivileges() throws Exception {
        var runner = new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("database-boundary"))
                .withUserConfiguration(NotificationDatabaseBoundaryConfig.class);
        runner.withBean(DataSource.class, () -> new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), "notification_user", "boundary-test-password"))
                .run(context -> assertThat(context).hasNotFailed());
        for (String user : new String[]{"application_user", POSTGRES.getUsername()}) {
            String password = user.equals(POSTGRES.getUsername()) ? POSTGRES.getPassword() : "boundary-test-password";
            runner.withBean(DataSource.class, () -> new DriverManagerDataSource(
                            POSTGRES.getJdbcUrl(), user, password))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasStackTraceContaining("Database boundary violation");
                    });
        }
        String jdbcUrl = POSTGRES.getJdbcUrl();
        String wrongSchemaUrl = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=public";
        runner.withBean(DataSource.class, () -> new DriverManagerDataSource(
                        wrongSchemaUrl, "notification_user", "boundary-test-password"))
                .run(context -> assertThat(context).hasFailed());
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = admin.createStatement()) {
            statement.execute("GRANT USAGE ON SCHEMA application TO notification_user");
            try {
                runner.withBean(DataSource.class, () -> new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(), "notification_user", "boundary-test-password"))
                        .run(context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure()).hasStackTraceContaining("other schema access");
                        });
            } finally {
                statement.execute("REVOKE USAGE ON SCHEMA application FROM notification_user");
            }
            statement.execute("CREATE TABLE public.unmigrated(id bigint)");
            try {
                runner.withBean(DataSource.class, () -> new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(), "notification_user", "boundary-test-password"))
                        .run(context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure()).hasStackTraceContaining("unmigrated public tables");
                        });
            } finally {
                statement.execute("DROP TABLE public.unmigrated");
            }
        }
    }

    @Autowired
    private BoundaryProbeRepository repository;

    @Test
    void actualEntrypointUsesItsOwnSchemaForJpaAndJdbc() throws Exception {
        assertThat(entityManagerFactory.getProperties().get("hibernate.default_schema")).isEqualTo("notification");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT current_user, current_schema()")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("notification_user");
                assertThat(result.getString(2)).isEqualTo("notification");
            }
            try (var result = statement.executeQuery("SELECT count(*) FROM boundary_probe")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
            var saved = repository.saveAndFlush(new BoundaryProbe());
            assertThat(repository.count()).isEqualTo(1);
            repository.delete(saved);
            repository.flush();
            assertThatThrownBy(() -> statement.execute("SELECT * FROM application.private_probe"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));
        }
        // The role default must also protect connections opened outside Hikari.
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                "notification_user", "boundary-test-password");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_schema()")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("notification");
        }
    }
}
