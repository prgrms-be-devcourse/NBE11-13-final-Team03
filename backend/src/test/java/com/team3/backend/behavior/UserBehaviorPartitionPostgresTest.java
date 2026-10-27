package com.team3.backend.behavior;

import com.team3.backend.BackendApplication;
import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.behavior.domain.repository.UserBehaviorEventRepository;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityVersionRepository;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.service.UserService;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Production-account migration and backend restart; no shared or operational database is used. */
@Testcontainers
class UserBehaviorPartitionPostgresTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
    private JdbcTemplate db;

    @BeforeEach
    void provision() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql("database-boundaries.sql"));
            statement.execute("DROP SCHEMA application CASCADE; CREATE SCHEMA application AUTHORIZATION application_user");
            statement.execute("ALTER ROLE application_user PASSWORD 'partition-test'");
        }
        db = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), "application_user", "partition-test"));
    }

    @Test
    void preservesEventsApiPreferenceWithdrawalAndRollbackAcrossMonths() throws Exception {
        long owner;
        long survivor;
        long opportunity;
        long first;
        LocalDate month = db.queryForObject("SELECT date_trunc('month', now() AT TIME ZONE 'Asia/Seoul')::date", LocalDate.class);
        try (var context = start()) {
            owner = createUser(context);
            survivor = createUser(context);
            opportunity = createOpportunity(context);
            record(mvc(context), owner, opportunity);
            record(mvc(context), survivor, opportunity);
            first = db.queryForObject("SELECT min(id) FROM user_behavior_events", Long.class);
        }
        // Synthetic historic month-boundary fixtures, preserving timestamp-without-zone values exactly.
        db.update("UPDATE user_behavior_events SET received_at=? WHERE user_id=?", month.minusMonths(2).atStartOfDay().minusNanos(1000), owner);
        db.update("UPDATE user_behavior_events SET received_at=? WHERE user_id=?", month.minusMonths(2).atStartOfDay(), survivor);
        var oldRows = db.queryForList("SELECT * FROM user_behavior_events ORDER BY id");
        db.execute(sql("user-behavior-events-partition.sql"));
        assertEquals(oldRows, db.queryForList("SELECT * FROM user_behavior_events ORDER BY id"));
        assertEquals("p", kind());
        assertEquals(2, db.queryForObject("SELECT count(DISTINCT tableoid) FROM user_behavior_events", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM user_behavior_events_default", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='application' AND tablename LIKE 'user_behavior_events%' AND tableowner<>'application_user'", Integer.class));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='application' AND tablename='user_behavior_events' AND indexname IN ('idx_user_behavior_events_user_opportunity','idx_user_behavior_events_opportunity')", Integer.class));
        assertEquals("timestamp without time zone", db.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema='application' AND table_name='user_behavior_events' AND column_name='received_at'", String.class));

        try (var context = start()) {
            assertEquals(first, context.getBean(UserBehaviorEventRepository.class).findById(first).orElseThrow().getId());
            var mvc = mvc(context);
            record(mvc, survivor, opportunity);
            record(mvc, survivor, opportunity); // Repeated events remain separate rows.
            for (String action : new String[]{"save", "save", "hide", "hide"}) {
                mvc.perform(post("/api/opportunities/{id}/{action}", opportunity, action)
                                .with(user(new CustomUserDetails(survivor, Role.USER, "partition-test"))).with(csrf()))
                        .andExpect(status().isNoContent());
            }
            assertEquals(6, db.queryForObject("SELECT count(*) FROM user_behavior_events", Integer.class));
            assertEquals(1, db.queryForObject("SELECT count(*) FROM user_behavior_events WHERE event_type='SAVE'", Integer.class));
            assertEquals(1, db.queryForObject("SELECT count(*) FROM user_behavior_events WHERE event_type='HIDE'", Integer.class));
            assertThrows(DataIntegrityViolationException.class, () -> db.update("DELETE FROM opportunities WHERE id=?", opportunity));
            context.getBean(UserService.class).withdraw(owner);
            assertEquals(0, db.queryForObject("SELECT count(*) FROM user_behavior_events WHERE user_id=?", Integer.class, owner));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM user_behavior_events_old WHERE user_id=?", Integer.class, owner));
            assertEquals(5, db.queryForObject("SELECT count(*) FROM user_behavior_events WHERE user_id=?", Integer.class, survivor));
            assertTrue(context.getBean(OpportunityRepository.class).existsById(opportunity));
        }

        // Remove one empty future partition; demonstrate DEFAULT routing and all-or-nothing monthly maintenance.
        String futureName = "user_behavior_events_" + month.plusMonths(12).toString().substring(0,7).replace('-', '_');
        db.execute("DROP TABLE " + futureName);
        insertAt(survivor, opportunity, month.plusMonths(12).atStartOfDay());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM user_behavior_events_default", Integer.class));
        assertThrows(DataAccessException.class, () -> db.execute(sqlUnchecked("user-behavior-events-add-partitions.sql")));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM user_behavior_events_default", Integer.class));
        String add = sql("user-behavior-events-add-partitions.sql").replace("BEGIN;", "").replace("COMMIT;", "");
        db.execute("BEGIN; SET LOCAL search_path TO application,pg_catalog; LOCK TABLE user_behavior_events IN ACCESS EXCLUSIVE MODE; "
                + "CREATE TEMP TABLE behavior_move ON COMMIT DROP AS SELECT * FROM user_behavior_events_default; "
                + "DELETE FROM user_behavior_events_default; " + add
                + "INSERT INTO user_behavior_events SELECT * FROM behavior_move; COMMIT;");
        db.execute(sql("user-behavior-events-add-partitions.sql")); // Repeat is safe.
        assertEquals(0, db.queryForObject("SELECT count(*) FROM user_behavior_events_default", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM " + futureName, Integer.class));
        db.execute(sql("user-behavior-events-partition-check.sql"));
        var beforeRollback = db.queryForList("SELECT * FROM user_behavior_events ORDER BY id");
        db.execute(sql("rollback-user-behavior-events-partition.sql"));
        assertEquals("r", kind());
        assertEquals(beforeRollback, db.queryForList("SELECT * FROM user_behavior_events ORDER BY id"));
        // Existing application and IDs keep working after rollback; append before re-application.
        try (var context = start()) {
            record(mvc(context), survivor, opportunity);
        }
        db.execute("DROP TABLE user_behavior_events_p");
        db.execute(sql("user-behavior-events-partition.sql"));
        try (var context = start()) {
            record(mvc(context), survivor, opportunity);
        }
        assertEquals(8, db.queryForObject("SELECT count(*) FROM user_behavior_events", Integer.class));
        assertEquals(8, db.queryForObject("SELECT count(DISTINCT id) FROM user_behavior_events", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM user_behavior_events WHERE user_id=?", Integer.class, owner));
    }

    @Test
    void supportsEmptyTableAndRejectsUnexpectedIncomingForeignKeyAtomically() throws Exception {
        try (var ignored = start()) { }
        db.execute("CREATE TABLE unexpected_event_reference(event_id bigint REFERENCES user_behavior_events(id))");
        assertThrows(DataAccessException.class, () -> db.execute(sqlUnchecked("user-behavior-events-partition.sql")));
        assertEquals("r", kind());
        assertNull(db.queryForObject("SELECT to_regclass('application.user_behavior_events_p')::text", String.class));
        db.execute("DROP TABLE unexpected_event_reference");
        db.execute(sql("user-behavior-events-partition.sql"));
        try (var ignored = start()) { }
        assertEquals("p", kind());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM user_behavior_events", Integer.class));
        db.execute(sql("rollback-user-behavior-events-partition.sql"));
        assertEquals("r", kind());
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(BackendApplication.class).profiles("test", "database-boundary").run(
                "--DB_URL=" + postgres.getJdbcUrl(), "--APPLICATION_DB_PASSWORD=partition-test",
                "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=application_user",
                "--spring.datasource.password=partition-test", "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
                "--app.scheduling.enabled=false", "--server.port=0", "--spring.main.banner-mode=off");
    }

    private MockMvc mvc(ConfigurableApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).apply(springSecurity()).build();
    }

    private void record(MockMvc mvc, long userId, long opportunityId) throws Exception {
        mvc.perform(post("/api/opportunities/{id}/events", opportunityId)
                        .with(user(new CustomUserDetails(userId, Role.USER, "partition-test"))).with(csrf())
                        .contentType("application/json").content("{\"eventType\":\"DETAIL_VIEW\",\"source\":\"SEARCH\"}"))
                .andExpect(status().isNoContent());
    }

    private long createUser(ConfigurableApplicationContext context) {
        return context.getBean(UserRepository.class).saveAndFlush(User.builder().role(Role.USER).build()).getId();
    }

    private long createOpportunity(ConfigurableApplicationContext context) {
        var opportunities = context.getBean(OpportunityRepository.class);
        var opportunity = opportunities.saveAndFlush(Opportunity.builder().opportunityType(OpportunityType.YOUTH_POLICY).title("Partition test").build());
        var version = context.getBean(OpportunityVersionRepository.class).saveAndFlush(OpportunityVersion.builder()
                .opportunity(opportunity).versionNo(1).normalizedSnapshot(Map.of("title", "Partition test"))
                .personalizationStatus(PersonalizationStatus.UNKNOWN).normalizationVersion("test").build());
        opportunity.changeCurrentVersion(version);
        return opportunities.saveAndFlush(opportunity).getId();
    }

    private void insertAt(long userId, long opportunityId, LocalDateTime receivedAt) {
        db.update("INSERT INTO user_behavior_events(user_id,opportunity_id,event_type,source,received_at) VALUES (?,?,'DETAIL_VIEW','OTHER',?)",
                userId, opportunityId, receivedAt);
    }

    private String kind() {
        return db.queryForObject("SELECT relkind::text FROM pg_class WHERE oid='user_behavior_events'::regclass", String.class);
    }

    private String sql(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/db-boundary/" + name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String sqlUnchecked(String name) {
        try { return sql(name); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
