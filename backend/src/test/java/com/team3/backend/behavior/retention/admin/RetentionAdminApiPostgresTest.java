package com.team3.backend.behavior.retention.admin;

import com.team3.backend.BackendApplication;
import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.behavior.retention.UserBehaviorEventRetention;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 보안 필터·메서드 보안·CSRF와 폐기 PostgreSQL에서 보관 관리자 API를 검증한다. 운영·공유 DB는 사용하지 않는다. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class RetentionAdminApiPostgresTest {

    private static final long ADMIN_KAKAO = 7_100_000_001L;
    private static final long STALE_ADMIN_KAKAO = 7_100_000_002L;
    private static final long USER_KAKAO = 7_100_000_003L;
    private static final String PREVIEW = "/api/admin/behavior-events/retention/preview";
    private static final String EXECUTE = "/api/admin/behavior-events/retention/execute";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate db;
    private ConfigurableApplicationContext context;
    private MockMvc mvc;
    private long adminId;
    private long staleAdminId;
    private long userId;
    private long opportunityId;
    private LocalDate today;

    @BeforeAll
    void provision() throws Exception {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql("database-boundaries.sql"));
            statement.execute("DROP SCHEMA application CASCADE; CREATE SCHEMA application AUTHORIZATION application_user");
            statement.execute("ALTER ROLE application_user PASSWORD 'retention-admin-test'");
        }
        db = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), "application_user", "retention-admin-test"));
        try (var ignored = start(true)) { }
        db.execute(sql("user-behavior-events-partition.sql"));
        context = start(true);
        mvc = mvc(context);
        UserRepository users = context.getBean(UserRepository.class);
        adminId = users.saveAndFlush(User.builder().kakaoId(ADMIN_KAKAO).role(Role.ADMIN).build()).getId();
        // 관리자 목록에서 빠졌지만 DB 역할은 ADMIN으로 남은 계정 (강등 경로가 없음)
        staleAdminId = users.saveAndFlush(User.builder().kakaoId(STALE_ADMIN_KAKAO).role(Role.ADMIN).build()).getId();
        userId = users.saveAndFlush(User.builder().kakaoId(USER_KAKAO).role(Role.USER).build()).getId();
        db.update("INSERT INTO opportunities(opportunity_type, title, created_at, updated_at) VALUES ('YOUTH_POLICY','Retention admin', now(), now())");
        opportunityId = db.queryForObject("SELECT max(id) FROM opportunities", Long.class);
        today = db.queryForObject("SELECT (now() AT TIME ZONE 'Asia/Seoul')::date", LocalDate.class);
    }

    @AfterAll
    void close() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void reset() {
        db.execute("DROP VIEW IF EXISTS old_month_events");
        db.execute("DROP TABLE IF EXISTS user_behavior_events_old, user_behavior_events_minvalue");
        months(YearMonth.from(today).minusMonths(6), YearMonth.from(today));
        insert(LocalDateTime.of(YearMonth.from(today).minusMonths(6).atDay(1), java.time.LocalTime.NOON));
        insert(LocalDateTime.of(YearMonth.from(today).minusMonths(3).atDay(1), java.time.LocalTime.MIDNIGHT));
    }

    // ── 보안 ──

    @Test
    void anonymousAndUsersAreRejectedAndStaleAdminCannotExecute() throws Exception {
        mvc.perform(get(PREVIEW)).andExpect(status().isUnauthorized());
        mvc.perform(post(EXECUTE).with(csrf()).contentType("application/json").content("{\"planToken\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PREVIEW).with(as(userId, Role.USER))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RETENTION_001"));
        mvc.perform(post(EXECUTE).with(as(userId, Role.USER)).with(csrf()).contentType("application/json")
                .content("{\"planToken\":\"x\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RETENTION_001"));

        String token = previewToken(as(staleAdminId, Role.ADMIN));
        mvc.perform(post(EXECUTE).with(as(staleAdminId, Role.ADMIN)).with(csrf()).contentType("application/json").content(body(token)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RETENTION_001"));
        assertEquals(monthRange(-6, 0), monthPartitions());
    }

    @Test
    void missingOrInvalidCsrfIsRejectedWithoutChanges() throws Exception {
        String token = previewToken(admin());
        mvc.perform(post(EXECUTE).with(admin()).contentType("application/json").content(body(token)))
                .andExpect(status().isForbidden());
        mvc.perform(post(EXECUTE).with(admin()).with(csrf().useInvalidToken()).contentType("application/json").content(body(token)))
                .andExpect(status().isForbidden());
        assertEquals(monthRange(-6, 0), monthPartitions());
    }

    @Test
    void executeIsRefusedWhenAdminExecutionIsDisabled() throws Exception {
        try (var disabled = start(false)) {
            MockMvc disabledMvc = mvc(disabled);
            String token = json.readTree(disabledMvc.perform(get(PREVIEW).with(admin())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.policy.adminExecuteEnabled").value(false))
                    .andReturn().getResponse().getContentAsString()).get("planToken").asString();
            disabledMvc.perform(post(EXECUTE).with(admin()).with(csrf()).contentType("application/json").content(body(token)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RETENTION_002"));
        }
        assertEquals(monthRange(-6, 0), monthPartitions());
    }

    // ── 미리보기·실행 ──

    @Test
    void previewIsReadOnlyLockFreeAndShowsFourMonthPlan() throws Exception {
        List<String> before = monthPartitions();
        long events = count("user_behavior_events");
        JsonNode preview;
        try (Connection other = appConnection(); var statement = other.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + lockKey() + ")");   // 다른 정리가 진행 중이어도 미리보기는 된다
            preview = json.readTree(mvc.perform(get(PREVIEW).with(admin())).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            statement.execute("SELECT pg_advisory_unlock(" + lockKey() + ")");
        }
        assertEquals("READY", preview.get("status").asString());
        assertEquals(4, preview.get("policy").get("retainedMonths").asInt());
        assertEquals(today.toString(), preview.get("baseDate").asString());
        assertEquals(today.withDayOfMonth(1).minusMonths(3).toString(), preview.get("keepFrom").asString());
        assertEquals(monthRange(-6, -4), names(preview.get("toDrop")));
        assertEquals(monthRange(1, 12), names(preview.get("toCreate")));
        assertTrue(preview.get("toDrop").get(0).has("estimatedRows"));
        assertTrue(preview.get("toDrop").get(0).get("totalBytes").asLong() > 0);
        assertFalse(preview.get("planToken").asString().isBlank());
        assertEquals(before, monthPartitions());
        assertEquals(events, count("user_behavior_events"));
    }

    @Test
    void executeWithPreviewTokenAppliesExactlyThePreviewedPlan(CapturedOutput output) throws Exception {
        String token = previewToken(admin());
        JsonNode result = json.readTree(mvc.perform(post(EXECUTE).with(admin()).with(csrf()).contentType("application/json").content(body(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertEquals("COMPLETED", result.get("status").asString());
        assertEquals(monthRange(-6, -4), strings(result.get("dropped")));
        assertEquals(monthRange(-3, 12), monthPartitions());
        assertEquals(1, count("user_behavior_events"));                       // 보관 시작 월의 행은 남는다
        insert(LocalDateTime.of(YearMonth.from(today).plusMonths(12).atDay(15), java.time.LocalTime.NOON));
        assertEquals(0, count("user_behavior_events_default"));
        assertTrue(output.getOut().contains("action=BEHAVIOR_RETENTION_EXECUTE adminUserId=" + adminId));
        assertTrue(output.getOut().contains("result=COMPLETED"));
        assertFalse(output.getOut().contains(token));                         // 토큰은 감사 로그에 남기지 않는다
    }

    @Test
    void staleOrForgedTokensAndMonthChangeRequireANewPreview() throws Exception {
        mvc.perform(post(EXECUTE).with(admin()).with(csrf()).contentType("application/json").content(body("forged")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETENTION_003"));
        mvc.perform(post(EXECUTE).with(admin()).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());

        // 다음 달로 넘어간 뒤의 계획 토큰 = 기준 월이 바뀐 상황
        String nextMonth = context.getBean(UserBehaviorEventRetention.class).plan(today.plusMonths(1), 4).token();
        mvc.perform(post(EXECUTE).with(admin()).with(csrf()).contentType("application/json").content(body(nextMonth)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETENTION_003"));

        String token = previewToken(admin());
        db.execute("CREATE TABLE " + monthName(-7) + " PARTITION OF user_behavior_events FOR VALUES FROM ('"
                + YearMonth.from(today).minusMonths(7).atDay(1) + "') TO ('" + YearMonth.from(today).minusMonths(6).atDay(1) + "')");
        mvc.perform(post(EXECUTE).with(admin()).with(csrf()).contentType("application/json").content(body(token)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETENTION_003"));
        assertEquals(monthRange(-7, 0), monthPartitions());

        mvc.perform(post(EXECUTE).with(admin()).with(csrf()).contentType("application/json").content(body(previewToken(admin()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dropped.length()").value(4));
    }

    @Test
    void concurrentRunAndRollbackWindowBlockExecution() throws Exception {
        String token = previewToken(admin());
        try (Connection other = appConnection(); var statement = other.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + lockKey() + ")");   // 자동 또는 Docker 수동 실행 진행 중
            mvc.perform(post(EXECUTE).with(admin()).with(csrf()).contentType("application/json").content(body(token)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETENTION_004"));
            statement.execute("SELECT pg_advisory_unlock(" + lockKey() + ")");
        }
        db.execute("CREATE TABLE user_behavior_events_old (LIKE user_behavior_events)");
        JsonNode preview = json.readTree(mvc.perform(get(PREVIEW).with(admin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals("SKIPPED_ROLLBACK_WINDOW", preview.get("status").asString());
        assertTrue(preview.get("planToken").isNull());
        mvc.perform(post(EXECUTE).with(admin()).with(csrf()).contentType("application/json").content(body(token)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETENTION_005"));
        assertEquals(monthRange(-6, 0), monthPartitions());
    }

    @Test
    void abnormalPartitionsAreProtectedAndPartialFailureCanBeRetried() throws Exception {
        db.execute("CREATE TABLE user_behavior_events_minvalue PARTITION OF user_behavior_events FOR VALUES FROM (MINVALUE) TO ('2019-01-01')");
        db.execute("CREATE VIEW old_month_events AS SELECT * FROM " + monthName(-5));   // 이 파티션 DROP이 실패한다
        JsonNode preview = json.readTree(mvc.perform(get(PREVIEW).with(admin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(List.of("user_behavior_events_minvalue"), names(preview.get("ignored")));
        assertEquals(monthRange(-6, -4), names(preview.get("toDrop")));

        JsonNode partial = json.readTree(mvc.perform(post(EXECUTE).with(admin()).with(csrf()).contentType("application/json")
                .content(body(preview.get("planToken").asString()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("COMPLETED_WITH_FAILURES", partial.get("status").asString());
        assertTrue(partial.get("failed").get(0).asString().startsWith(monthName(-5)));
        assertEquals(List.of(monthName(-6), monthName(-4)), strings(partial.get("dropped")));
        assertNotNull(db.queryForObject("SELECT to_regclass('user_behavior_events_minvalue')::text", String.class));
        assertNotNull(db.queryForObject("SELECT to_regclass('user_behavior_events_default')::text", String.class));

        db.execute("DROP VIEW old_month_events");
        mvc.perform(post(EXECUTE).with(admin()).with(csrf()).contentType("application/json").content(body(previewToken(admin()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.dropped[0]").value(monthName(-5)));
    }

    // ── 도우미 ──

    private ConfigurableApplicationContext start(boolean adminExecuteEnabled) {
        return new SpringApplicationBuilder(BackendApplication.class).profiles("test", "database-boundary").run(
                "--DB_URL=" + postgres.getJdbcUrl(), "--APPLICATION_DB_PASSWORD=retention-admin-test",
                "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=application_user",
                "--spring.datasource.password=retention-admin-test", "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
                "--app.scheduling.enabled=false", "--server.port=0", "--spring.main.banner-mode=off",
                "--app.admin-kakao-ids=" + ADMIN_KAKAO + ", 999",
                "--yon.behavior.retention.admin-execute-enabled=" + adminExecuteEnabled,
                "--yon.behavior.retention.lock-timeout=300ms");
    }

    private static MockMvc mvc(ConfigurableApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).apply(springSecurity()).build();
    }

    private RequestPostProcessor admin() {
        return as(adminId, Role.ADMIN);
    }

    private static RequestPostProcessor as(long id, Role role) {
        return user(new CustomUserDetails(id, role, "retention-admin-test"));
    }

    private String previewToken(RequestPostProcessor who) throws Exception {
        String content = mvc.perform(get(PREVIEW).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(content).get("planToken").asString();
    }

    private static String body(String token) {
        return "{\"planToken\":\"" + token + "\"}";
    }

    private static long lockKey() {
        return 7_204_311_902_118_640_001L;   // UserBehaviorEventRetention.LOCK_KEY
    }

    private void months(YearMonth first, YearMonth last) {
        db.execute("DELETE FROM user_behavior_events");
        for (String name : db.queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'user_behavior_events'::regclass AND c.relname <> 'user_behavior_events_default'""", String.class)) {
            db.execute("DROP TABLE " + name);
        }
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            db.execute("CREATE TABLE " + name(month) + " PARTITION OF user_behavior_events FOR VALUES FROM ('"
                    + month.atDay(1) + "') TO ('" + month.plusMonths(1).atDay(1) + "')");
        }
    }

    private void insert(LocalDateTime receivedAt) {
        db.update("INSERT INTO user_behavior_events(user_id, opportunity_id, event_type, source, received_at) VALUES (?,?,'DETAIL_VIEW','OTHER',?)",
                adminId, opportunityId, receivedAt);
    }

    private List<String> monthPartitions() {
        return db.queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'user_behavior_events'::regclass AND c.relname ~ '^user_behavior_events_\\d{4}_\\d{2}$'
                ORDER BY 1""", String.class);
    }

    private List<String> monthRange(int fromOffset, int toOffset) {
        List<String> names = new ArrayList<>();
        for (int offset = fromOffset; offset <= toOffset; offset++) {
            names.add(monthName(offset));
        }
        return names;
    }

    private String monthName(int offset) {
        return name(YearMonth.from(today).plusMonths(offset));
    }

    private static String name(YearMonth month) {
        return String.format("user_behavior_events_%04d_%02d", month.getYear(), month.getMonthValue());
    }

    private static List<String> names(JsonNode items) {
        List<String> names = new ArrayList<>();
        items.forEach(item -> names.add(item.get("name").asString()));
        return names;
    }

    private static List<String> strings(JsonNode items) {
        List<String> values = new ArrayList<>();
        items.forEach(item -> values.add(item.asString()));
        return values;
    }

    private long count(String table) {
        Long value = db.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private Connection appConnection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "application_user", "retention-admin-test");
    }

    private String sql(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/db-boundary/" + name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
