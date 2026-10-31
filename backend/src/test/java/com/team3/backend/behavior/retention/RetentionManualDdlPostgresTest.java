package com.team3.backend.behavior.retention;

import com.team3.backend.BackendApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 문서 7절의 Docker 수동 실행과 같은 OS 환경 변수(SPRING_JPA_HIBERNATE_DDL_AUTO=none 등)로 기동하면
 * dry-run·실제 실행 모두 Hibernate가 스키마를 바꾸지 않고, 일반 기동은 기존처럼 update로 동작함을 확인한다.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionManualDdlPostgresTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private JdbcTemplate db;

    @BeforeAll
    void provision() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql("database-boundaries.sql"));
            statement.execute("DROP SCHEMA application CASCADE; CREATE SCHEMA application AUTHORIZATION application_user");
            statement.execute("ALTER ROLE application_user PASSWORD 'retention-ddl-test'");
        }
        db = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), "application_user", "retention-ddl-test"));
        try (var ignored = start(Map.of())) { }      // 일반 기동(update)으로 스키마 생성
        db.execute(sql("user-behavior-events-partition.sql"));
        db.execute("DROP TABLE user_behavior_events_old");   // 운영 문서 3절: D+1 확인 후 _old 삭제
    }

    @Test
    void oneShotRunWithDdlNoneLeavesSchemaAloneWhileNormalStartupStillUpdates() {
        LocalDate today = db.queryForObject("SELECT (now() AT TIME ZONE 'Asia/Seoul')::date", LocalDate.class);
        for (int offset = 6; offset >= 1; offset--) {
            YearMonth month = YearMonth.from(today).minusMonths(offset);
            db.execute(String.format("CREATE TABLE IF NOT EXISTS user_behavior_events_%04d_%02d PARTITION OF user_behavior_events "
                    + "FOR VALUES FROM ('%s') TO ('%s')", month.getYear(), month.getMonthValue(), month.atDay(1), month.plusMonths(1).atDay(1)));
        }
        // Hibernate update라면 다시 만들 컬럼을 지워 둔다
        db.execute("ALTER TABLE users DROP COLUMN nickname");
        String schemaBefore = schemaFingerprint();

        Map<String, Object> oneShotEnv = Map.of(
                "SPRING_JPA_HIBERNATE_DDL_AUTO", "none",
                "SPRING_MAIN_WEB_APPLICATION_TYPE", "none",
                "APP_SCHEDULING_ENABLED", "false",
                "YON_NORMALIZATION_CONDITION_SET_BACKFILL_ENABLED", "false");
        try (var oneShot = start(oneShotEnv)) {
            assertFalse(oneShot instanceof WebServerApplicationContext);
            UserBehaviorEventRetention retention = oneShot.getBean(UserBehaviorEventRetention.class);
            Clock clock = oneShot.getBean(Clock.class);

            var dryRun = new UserBehaviorEventRetentionManualRun(retention, oneShot, clock, 4, false, Duration.ofMillis(300)).runOnce();
            assertEquals(UserBehaviorEventRetention.Status.COMPLETED, dryRun.status());
            assertEquals(0, UserBehaviorEventRetentionManualRun.exitCode(dryRun));
            assertTrue(dryRun.dryRun());
            assertEquals(3, dryRun.dropped().size());
            assertEquals(schemaBefore, schemaFingerprint());
            assertEquals(List.of(), nicknameColumn());

            var executed = new UserBehaviorEventRetentionManualRun(retention, oneShot, clock, 4, true, Duration.ofMillis(300)).runOnce();
            assertEquals(0, UserBehaviorEventRetentionManualRun.exitCode(executed));
            assertEquals(3, executed.dropped().size());
            assertEquals(schemaBefore, schemaFingerprint());          // 파티션 외 스키마는 그대로
            assertEquals(List.of(), nicknameColumn());
        }

        try (var ignored = start(Map.of())) { }          // 일반 기동은 기존처럼 update로 컬럼을 되살린다
        assertEquals(List.of("nickname"), nicknameColumn());
    }

    /** 실제 OS 환경 변수 위에 주어진 값을 덮어쓴 환경으로 기동한다 (docker compose run -e와 같은 우선순위) */
    private ConfigurableApplicationContext start(Map<String, Object> environmentVariables) {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> variables = new HashMap<>(environment.getSystemEnvironment());
        variables.putAll(environmentVariables);
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        return new SpringApplicationBuilder(BackendApplication.class).environment(environment)
                .profiles("test", "database-boundary").run(
                        "--DB_URL=" + postgres.getJdbcUrl(), "--APPLICATION_DB_PASSWORD=retention-ddl-test",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=application_user",
                        "--spring.datasource.password=retention-ddl-test", "--spring.datasource.driver-class-name=org.postgresql.Driver",
                        "--spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true", "--server.port=0", "--spring.main.banner-mode=off");
    }

    /** 행동 로그 파티션을 뺀 application 스키마의 컬럼·인덱스·제약 */
    private String schemaFingerprint() {
        return String.join("\n", db.queryForList("""
                SELECT 'col ' || table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'application' AND table_name !~ '^user_behavior_events_'
                UNION ALL
                SELECT 'idx ' || tablename || ' ' || indexdef FROM pg_indexes
                WHERE schemaname = 'application' AND tablename !~ '^user_behavior_events_'
                UNION ALL
                SELECT 'con ' || c.relname || ' ' || con.conname FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid
                WHERE c.relnamespace = 'application'::regnamespace AND c.relname !~ '^user_behavior_events_'
                ORDER BY 1""", String.class));
    }

    private List<String> nicknameColumn() {
        return db.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'application' AND table_name = 'users' AND column_name = 'nickname'""", String.class);
    }

    private String sql(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/db-boundary/" + name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
