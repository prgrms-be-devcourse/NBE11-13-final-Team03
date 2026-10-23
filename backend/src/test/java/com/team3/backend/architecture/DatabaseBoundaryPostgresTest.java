package com.team3.backend.architecture;

import com.team3.backend.global.config.ApplicationDatabaseBoundaryConfig;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class DatabaseBoundaryPostgresTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void preservesLegacyDataSequencesConstraintsAndCommittedNotifications() throws Exception {
        String url = newDatabase();
        try (var admin = admin(url)) {
            execute(admin, "CREATE TABLE public.users (id bigserial PRIMARY KEY, name text UNIQUE NOT NULL)");
            execute(admin, "INSERT INTO public.users(name) VALUES ('existing')");
            execute(admin, "CREATE TABLE public.raw_normalization_request (id bigserial PRIMARY KEY, "
                    + "user_id bigint REFERENCES public.users(id))");
            script(admin, "normalization-notifications.sql");
            script(admin, "database-boundaries.sql");
            script(admin, "database-boundaries.sql");
            script(admin, "migrate-public-to-application.sql");
            script(admin, "migrate-public-to-application.sql");
            setTestPasswords(admin);

            assertThat(scalar(admin, "SELECT to_regclass('public.users') IS NULL")).isEqualTo("t");
            assertThat(scalar(admin, "SELECT count(*) FROM application.users")).isEqualTo("1");
            assertThat(scalar(admin, "SELECT pg_get_userbyid(relowner) FROM pg_class "
                    + "WHERE oid = 'application.users_id_seq'::regclass")).isEqualTo("application_user");
            assertThat(scalar(admin, "SELECT count(*) FROM pg_indexes WHERE schemaname='application' "
                    + "AND tablename='users'")).isEqualTo("2");
            assertThat(scalar(admin, "SELECT count(*) FROM pg_trigger WHERE "
                    + "tgrelid='application.raw_normalization_request'::regclass AND NOT tgisinternal"))
                    .isEqualTo("1");
        }
        assertStartupGuard(url);
        try (var api = login(url, "application_user"); var listener = login(url, "application_user")) {
            assertThat(scalar(api, "SELECT current_schema()")).isEqualTo("application");
            assertThat(scalar(api, "INSERT INTO users(name) VALUES ('new') RETURNING id")).isEqualTo("2");
            assertDenied(api, "INSERT INTO users(name) VALUES ('existing')", "23505");
            assertDenied(api, "INSERT INTO raw_normalization_request(user_id) VALUES (999)", "23503");
            execute(listener, "LISTEN raw_normalization_requested");
            api.setAutoCommit(false);
            execute(api, "INSERT INTO raw_normalization_request(user_id) VALUES (1)");
            var pg = listener.unwrap(PGConnection.class);
            assertThat(pg.getNotifications(100)).isEmpty();
            api.commit();
            assertThat(pg.getNotifications(2_000)).hasSize(1);
            execute(api, "INSERT INTO raw_normalization_request(user_id) VALUES (1)");
            api.rollback();
            assertThat(pg.getNotifications(100)).isEmpty();
        }
    }

    @Test
    void eachLoginCanOnlyUseItsOwnSchemaAndSequences() throws Exception {
        String url = newDatabase();
        try (var admin = admin(url)) {
            script(admin, "database-boundaries.sql");
            setTestPasswords(admin);
            execute(admin, "CREATE TABLE public.private_probe(id bigint)");
        }
        try (var api = login(url, "application_user"); var notification = login(url, "notification_user")) {
            execute(api, "CREATE TABLE boundary_records(id bigserial PRIMARY KEY, value text)");
            execute(notification, "CREATE TABLE boundary_records(id bigserial PRIMARY KEY, value text)");
            execute(api, "CREATE FUNCTION boundary_ping() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
            execute(notification, "CREATE FUNCTION boundary_ping() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
            assertThat(scalar(api, "SELECT boundary_ping()")).isEqualTo("1");
            assertThat(scalar(notification, "SELECT boundary_ping()")).isEqualTo("1");
            assertThat(scalar(api, "INSERT INTO boundary_records(value) VALUES ('api') RETURNING id")).isEqualTo("1");
            assertThat(scalar(notification, "INSERT INTO boundary_records(value) VALUES ('notification') RETURNING id"))
                    .isEqualTo("1");
            assertThat(scalar(api, "SELECT value FROM boundary_records")).isEqualTo("api");
            assertThat(scalar(notification, "SELECT value FROM boundary_records")).isEqualTo("notification");
            assertIsolation(api, "notification");
            assertIsolation(notification, "application");
            assertThat(scalar(notification, "SELECT current_schema()")).isEqualTo("notification");
        }
        try (var admin = admin(url)) {
            assertThat(scalar(admin, "SELECT has_function_privilege('notification_user', "
                    + "'application.boundary_ping()', 'EXECUTE')")).isEqualTo("f");
            assertThat(scalar(admin, "SELECT has_function_privilege('application_user', "
                    + "'notification.boundary_ping()', 'EXECUTE')")).isEqualTo("f");
            assertThat(scalar(admin, "SELECT count(*) FROM pg_constraint c "
                    + "JOIN pg_class s ON s.oid=c.conrelid JOIN pg_class d ON d.oid=c.confrelid "
                    + "JOIN pg_namespace sn ON sn.oid=s.relnamespace JOIN pg_namespace dn ON dn.oid=d.relnamespace "
                    + "WHERE c.contype='f' AND sn.oid<>dn.oid "
                    + "AND sn.nspname IN ('application','notification')")).isEqualTo("0");
        }
    }

    @Test
    void unknownLegacyTablesAbortWithoutMovingData() throws Exception {
        String url = newDatabase();
        try (var admin = admin(url)) {
            execute(admin, "CREATE TABLE public.users(id bigint PRIMARY KEY)");
            execute(admin, "INSERT INTO public.users VALUES (42)");
            execute(admin, "CREATE TABLE public.unreviewed_table(id bigint)");
            script(admin, "database-boundaries.sql");
            assertThatThrownBy(() -> script(admin, "migrate-public-to-application.sql"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Unknown public table");
            execute(admin, "ROLLBACK");
            assertThat(scalar(admin, "SELECT id FROM public.users")).isEqualTo("42");
            assertThat(scalar(admin, "SELECT to_regclass('application.users') IS NULL")).isEqualTo("t");
        }
    }

    @Test
    void duplicateTablesAbortWithoutOverwritingEitherSchema() throws Exception {
        String url = newDatabase();
        try (var admin = admin(url)) {
            script(admin, "database-boundaries.sql");
            execute(admin, "CREATE TABLE public.users(id bigint PRIMARY KEY)");
            execute(admin, "INSERT INTO public.users VALUES (42)");
            execute(admin, "CREATE TABLE application.users(id bigint PRIMARY KEY)");
            execute(admin, "INSERT INTO application.users VALUES (84)");
            assertThatThrownBy(() -> script(admin, "migrate-public-to-application.sql"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Both schemas contain");
            execute(admin, "ROLLBACK");
            assertThat(scalar(admin, "SELECT id FROM public.users")).isEqualTo("42");
            assertThat(scalar(admin, "SELECT id FROM application.users")).isEqualTo("84");
        }
    }

    @Test
    void crossSchemaForeignKeysAbortAndRestoreTheOriginalTable() throws Exception {
        String url = newDatabase();
        try (var admin = admin(url)) {
            script(admin, "database-boundaries.sql");
            execute(admin, "CREATE TABLE notification.parent(id bigint PRIMARY KEY)");
            execute(admin, "CREATE TABLE public.users(id bigint PRIMARY KEY, "
                    + "parent_id bigint REFERENCES notification.parent(id))");
            assertThatThrownBy(() -> script(admin, "migrate-public-to-application.sql"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Cross-schema foreign keys");
            execute(admin, "ROLLBACK");
            assertThat(scalar(admin, "SELECT to_regclass('public.users') IS NOT NULL")).isEqualTo("t");
            assertThat(scalar(admin, "SELECT to_regclass('application.users') IS NULL")).isEqualTo("t");
        }
    }

    private void assertIsolation(Connection connection, String otherSchema) throws Exception {
        assertDenied(connection, "SELECT * FROM " + otherSchema + ".boundary_records", "42501");
        assertDenied(connection, "INSERT INTO " + otherSchema + ".boundary_records(value) VALUES ('bad')", "42501");
        assertDenied(connection, "SELECT nextval('" + otherSchema + ".boundary_records_id_seq')", "42501");
        assertDenied(connection, "SELECT " + otherSchema + ".boundary_ping()", "42501");
        assertDenied(connection, "CREATE TABLE " + otherSchema + ".bad(id bigint)", "42501");
        assertDenied(connection, "CREATE TABLE bad_fk(id bigint REFERENCES " + otherSchema + ".boundary_records(id))", "42501");
        assertDenied(connection, "SELECT * FROM public.private_probe", "42501");
        assertDenied(connection, "CREATE TABLE public.bad(id bigint)", "42501");
        assertDenied(connection, "CREATE SCHEMA unauthorized", "42501");
    }

    @Test
    void trackingDdlIsOwnedByApplicationAndDoesNotResetExistingData() throws Exception {
        String url = newDatabase();
        try (var admin = admin(url)) {
            script(admin, "database-boundaries.sql");
            setTestPasswords(admin);
        }
        try (var application = login(url, "application_user")) {
            execute(application, "CREATE TABLE opportunities(id bigint PRIMARY KEY)");
            execute(application, "CREATE TABLE opportunity_versions(id bigint PRIMARY KEY)");
            script(application, "date-tracking.sql");
            execute(application, "INSERT INTO event_scan_history "
                    + "(scan_date,status,execution_id,started_at,retry_count,created_at,updated_at) "
                    + "VALUES ('2026-10-20','RUNNING','execution',now(),0,now(),now())");
            assertThatThrownBy(() -> script(application, "date-tracking.sql")).isInstanceOf(SQLException.class);
            execute(application, "ROLLBACK");
            assertThat(scalar(application, "SELECT count(*) FROM event_scan_history")).isEqualTo("1");
            assertThat(scalar(application, "SELECT pg_get_userbyid(relowner) FROM pg_class "
                    + "WHERE oid='application.opportunity_events'::regclass")).isEqualTo("application_user");
            assertThat(scalar(application, "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema='application' AND table_name='opportunity_events' "
                    + "AND column_name IN ('event_id','correlation_id','next_retry_at') AND is_nullable='NO'"))
                    .isEqualTo("3");
        }
        try (var notification = login(url, "notification_user")) {
            assertDenied(notification, "SELECT * FROM application.opportunity_events", "42501");
        }
    }

    private void assertStartupGuard(String url) {
        var runner = new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("database-boundary"))
                .withUserConfiguration(ApplicationDatabaseBoundaryConfig.class);
        runner.withBean(DataSource.class, () ->
                new DriverManagerDataSource(url, "application_user", "boundary-test-password"))
                .run(context -> assertThat(context).hasNotFailed());
        for (String user : new String[]{"notification_user", POSTGRES.getUsername()}) {
            String password = user.equals(POSTGRES.getUsername()) ? POSTGRES.getPassword() : "boundary-test-password";
            runner.withBean(DataSource.class, () -> new DriverManagerDataSource(url, user, password))
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    private void assertDenied(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> execute(connection, sql)).isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(sqlState));
    }

    private String newDatabase() throws Exception {
        String database = "boundary_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = admin(POSTGRES.getJdbcUrl())) {
            execute(connection, "CREATE DATABASE " + database);
        }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    private Connection admin(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Connection login(String url, String user) throws SQLException {
        return DriverManager.getConnection(url, user, "boundary-test-password");
    }

    private void setTestPasswords(Connection connection) throws SQLException {
        execute(connection, "ALTER ROLE application_user PASSWORD 'boundary-test-password'");
        execute(connection, "ALTER ROLE notification_user PASSWORD 'boundary-test-password'");
    }

    private void script(Connection connection, String name) throws Exception {
        // 관리자 SQL은 모두 저장소 루트 database/scripts에 있고 processTestResources가 db-boundary로 복사한다
        execute(connection, new ClassPathResource("db-boundary/" + name).getContentAsString(StandardCharsets.UTF_8));
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
