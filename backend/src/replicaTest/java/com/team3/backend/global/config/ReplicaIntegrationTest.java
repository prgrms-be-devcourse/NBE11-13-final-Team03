package com.team3.backend.global.config;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.core.api.dataset.ExpectedDataSet;
import com.github.database.rider.core.api.dataset.SeedStrategy;
import com.github.database.rider.junit5.api.DBRider;
import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.eligibility.support.EligibilityResultFixtures;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.recommendation.service.RecommendationService;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.search.dto.OpportunitySearchSort;
import com.team3.backend.search.service.OpportunityDetailService;
import com.team3.backend.search.service.OpportunitySearchService;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import org.dbunit.ext.postgresql.PostgresqlDataTypeFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/** One lifecycle: committed primary fixture -> WAL replay -> real services -> lag -> outage/recovery. */
@Testcontainers
@ActiveProfiles({"test", "database-boundary"})
@SpringBootTest(properties = {
        "app.scheduling.enabled=false", "collection.schedule.enabled=false",
        "yon.notification.publisher.enabled=false", "yon.normalization.ingest.enabled=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.team3.backend.global.config.ReplicaIntegrationTest$SqlLabels"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReplicaIntegrationTest {
    static final String PASSWORD = "replica-test-application-only";
    static final String PGDATA = "/var/lib/postgresql/data/standby";
    static final Network NETWORK = Network.newNetwork();
    static final Path EVIDENCE = Path.of("build/replica-evidence");
    static final Map<String, Object> results = new LinkedHashMap<>();

    @Container
    static final PostgreSQLContainer primary = new PostgreSQLContainer("postgres:16-alpine")
            .withNetwork(NETWORK).withNetworkAliases("primary")
            .withCopyFileToContainer(MountableFile.forClasspathResource("replica/database-boundaries.sql"),
                    "/docker-entrypoint-initdb.d/01-boundaries.sql")
            .withCopyFileToContainer(MountableFile.forClasspathResource("replica/primary.sql"),
                    "/docker-entrypoint-initdb.d/02-replication.sql")
            .withCopyFileToContainer(MountableFile.forClasspathResource("replica/pg_hba.conf"), "/etc/replica-pg_hba.conf")
            .withCommand("postgres", "-c", "hba_file=/etc/replica-pg_hba.conf", "-c", "wal_level=replica",
                    "-c", "max_replication_slots=4", "-c", "log_statement=all");

    @Container
    static final GenericContainer<?> standby = new GenericContainer<>("postgres:16-alpine")
            .withNetwork(NETWORK).withExposedPorts(5432).dependsOn(primary)
            .withEnv("PGDATA", PGDATA).withEnv("PGPASSWORD", "replica-test-stream-only")
            .withCreateContainerCmdModifier(command -> command.withUser("postgres"))
            .withCommand("sh", "-ec", "pg_basebackup -h primary -U replica_test -D \"$PGDATA\" -R -X stream -C -S replica_test "
                    + "--checkpoint=fast; exec postgres -D \"$PGDATA\" -c hot_standby=on -c hot_standby_feedback=on "
                    + "-c max_standby_streaming_delay=30s -c log_statement=all")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept read-only connections.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(90)));

    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> url(primary.getJdbcUrl()));
        registry.add("DB_REPLICA_URL", () -> url(standbyUrl()));
        registry.add("spring.datasource.username", () -> "application_user");
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("APPLICATION_DB_PASSWORD", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", primary::getDriverClassName);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO application, pg_catalog");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "250");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "application");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.import_files", () -> "search-title-columns-hibernate-create.sql");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.halt_on_error", () -> "true");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.charset_name", () -> "UTF-8");
        registry.add("yon.datasource.replica.connection-timeout-ms", () -> "500");
        registry.add("yon.datasource.replica.failure-cooldown-ms", () -> "30000");
    }

    static String url(String value) {
        return value + (value.contains("?") ? "&" : "?") + "connectTimeout=1&socketTimeout=2";
    }

    static String standbyUrl() {
        return "jdbc:postgresql://" + standby.getHost() + ":" + standby.getMappedPort(5432) + "/" + primary.getDatabaseName();
    }

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OpportunitySearchService search;
    @Autowired OpportunityDetailService detail;
    @Autowired RecommendationService recommendation;
    @Autowired PreferenceService preferences;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired @Qualifier("replicaDataSource") HikariDataSource replicaPool;

    @BeforeAll
    static void clearPreviousEvidence() throws Exception {
        Files.createDirectories(EVIDENCE);
        Files.deleteIfExists(EVIDENCE.resolve("checkpoints.json"));
    }

    @Test
    @DBRider(dataSourceBeanName = "primaryDataSource")
    @DBUnit(schema = "application", qualifiedTableNames = true, caseSensitiveTableNames = true,
            cacheConnection = false, cacheTableNames = false, dataTypeFactoryClass = PostgresqlDataTypeFactory.class)
    @DataSet(value = "datasets/replica-user.yml", strategy = SeedStrategy.INSERT, transactional = false,
            useSequenceFiltering = false, executorId = "replica-primary")
    @ExpectedDataSet("datasets/replica-user.yml")
    void streamingRoutingLagAndConnectionRecovery() throws Exception {
        assertThat(query(primary.getJdbcUrl(), "SELECT pg_is_in_recovery()::text", false)).isEqualTo("false");
        assertThat(query(standbyUrl(), "SELECT pg_is_in_recovery()::text", false)).isEqualTo("true");
        assertThat(query(primary.getJdbcUrl(), "SELECT count(*) FROM application.users WHERE id=900001", false)).isEqualTo("1");
        assertThat(query(primary.getJdbcUrl(), "SELECT current_user", false)).isEqualTo("application_user");
        assertThat(query(standbyUrl(), "SELECT current_user", false)).isEqualTo("application_user");
        Long opportunity = fixture();
        String initialLsn = replayCommittedWal();
        assertThat(query(primary.getJdbcUrl(), "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'", true)).isEqualTo("1");
        assertThat(query(standbyUrl(), "SELECT count(*) FROM application.users WHERE id=900001", false)).isEqualTo("1");
        checkpoint("streaming-and-committed-rider", Map.of("flushLsn", initialLsn, "standbyRecovery", true));

        assertThatThrownBy(() -> query(standbyUrl(), "UPDATE application.users SET nickname='illegal' WHERE id=900001", false))
                .isInstanceOf(SQLException.class).satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("25006"));
        assertThat(query(primary.getJdbcUrl(), "SELECT nickname FROM application.users WHERE id=900001", false)).isEqualTo("replica fixture");
        checkpoint("standby-write-rejected", Map.of("sqlState", "25006"));

        onServer("search", true, () -> {
            assertThat(search.search("replica", null, null, null, null, null, null, null, null,
                    null, null, null, null, OpportunitySearchSort.LATEST, 0, 10).content())
                    .extracting(item -> item.opportunityId()).containsExactly(opportunity);
            return null;
        });
        onServer("detail", true, () -> { assertThat(detail.get(opportunity, null).title()).isEqualTo("replica policy"); return null; });
        onServer("recommendation", true, () -> {
            assertThat(recommendation.recommend(900001L)).extracting(item -> item.opportunityId()).containsExactly(opportunity);
            return null;
        });
        checkpoint("public-reads-on-standby", Map.of("services", List.of("search", "detail", "recommendation")));

        query(standbyUrl(), "SELECT pg_wal_replay_pause()", true);
        try {
            await("standby replay paused", () -> read(standbyUrl(), "SELECT pg_get_wal_replay_pause_state()='paused'", true));
            onServer("write", false, () -> { assertThat(preferences.save(900001L, opportunity)).isTrue(); return null; });
            onServer("non-routed-read", false, () -> {
                assertThat(preferences.getSavedOpportunityIds(900001L, List.of(opportunity))).containsExactly(opportunity); return null;
            });
            String written = query(primary.getJdbcUrl(), "SELECT pg_current_wal_flush_lsn()::text", true);
            assertThat(read(standbyUrl(), "SELECT pg_last_wal_replay_lsn() < '" + written + "'::pg_lsn", true)).isTrue();
            onServer("lag-is-not-failure", true, () -> { assertThat(detail.get(opportunity, 900001L).saved()).isFalse(); return null; });
            checkpoint("lag-is-distinct-from-connection-failure", Map.of("primaryFlushLsn", written, "staleSaved", false));
        } finally {
            query(standbyUrl(), "SELECT pg_wal_replay_resume()", true);
        }
        replayCommittedWal();
        onServer("caught-up", true, () -> { assertThat(detail.get(opportunity, 900001L).saved()).isTrue(); return null; });
        assertThat(jdbc.queryForObject("/* replica-case:outside */ SELECT pg_is_in_recovery()", Boolean.class)).isFalse();
        assertThat(primary.getLogs()).contains("replica-case:outside");
        assertThat(standby.getLogs()).doesNotContain("replica-case:outside");
        checkpoint("primary-write-nontarget-and-outside", Map.of("saved", true));

        // Freeze the real standby; keep its mapped port stable and force acquisition of a fresh connection.
        var docker = DockerClientFactory.instance().client();
        long fallbackAttempt;
        docker.pauseContainerCmd(standby.getContainerId()).exec();
        try {
            replicaPool.getHikariPoolMXBean().softEvictConnections();
            fallbackAttempt = System.nanoTime();
            onServer("connection-fallback", false, () -> { assertThat(detail.get(opportunity, 900001L).saved()).isTrue(); return null; });
        } finally {
            docker.unpauseContainerCmd(standby.getContainerId()).exec();
        }
        replayCommittedWal();
        onServer("cooldown", false, () -> { assertThat(detail.get(opportunity, 900001L).saved()).isTrue(); return null; });
        checkpoint("fallback-and-cooldown", Map.of("cooldownMillis", 30000, "standbyBackOnline", true));
        await("routing retries standby after cooldown", () -> {
            String label = "recovered-" + System.nanoTime();
            labelled(label, () -> detail.get(opportunity, 900001L));
            return standby.getLogs().contains("replica-case:" + label);
        });
        long retryElapsed = Duration.ofNanos(System.nanoTime() - fallbackAttempt).toMillis();
        assertThat(retryElapsed).as("standby must not be retried before the configured cooldown").isGreaterThanOrEqualTo(30000);
        assertThat(detail.get(opportunity, 900001L).saved()).isTrue();
        checkpoint("recovered-standby-retried", Map.of("saved", true, "retryElapsedMillis", retryElapsed));
        assertThat(results).hasSize(7);
    }

    Long fixture() {
        return new TransactionTemplate(transactions).execute(status -> {
            var region = Region.builder().regionCode("1100000000").regionName("서울").build(); em.persist(region);
            em.persist(EligibilityProfile.builder().userId(900001L).birthDate(LocalDate.of(1998, 1, 1))
                    .residenceRegion(region).employmentStatus(EmploymentStatus.UNEMPLOYED).educationLevel(EducationLevel.UNIVERSITY).build());
            var opportunity = Opportunity.builder().opportunityType(OpportunityType.YOUTH_POLICY).title("replica policy").build();
            em.persist(opportunity);
            var version = OpportunityVersion.builder().opportunity(opportunity).versionNo(1).normalizedSnapshot(Map.of())
                    .personalizationStatus(PersonalizationStatus.PERSONALIZABLE).normalizationVersion("replica-test").build();
            em.persist(version); opportunity.changeCurrentVersion(version);
            em.persist(ApplicationWindow.builder().version(version).periodType(PeriodType.ALWAYS)
                    .validationStatus(ValidationStatus.VALID).build());
            EligibilityResultFixtures.result(em, 900001L, opportunity, EligibilityStatus.ELIGIBLE, 1L,
                    EligibilityResultService.RULE_VERSION, LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(3));
            return opportunity.getId();
        });
    }

    String replayCommittedWal() throws Exception {
        String lsn = query(primary.getJdbcUrl(), "SELECT pg_current_wal_flush_lsn()::text", true);
        await("standby WAL replay reaches committed primary LSN", () -> read(standbyUrl(),
                "SELECT pg_last_wal_replay_lsn() >= '" + lsn + "'::pg_lsn", true));
        return lsn;
    }

    void onServer(String label, boolean replica, Supplier<?> call) throws Exception {
        labelled(label, call);
        String marker = "replica-case:" + label;
        await("SQL connection evidence: " + label,
                () -> standby.getLogs().contains(marker) || primary.getLogs().contains(marker));
        assertThat((replica ? standby : primary).getLogs()).contains(marker);
        assertThat((replica ? primary : standby).getLogs()).doesNotContain(marker);
    }

    static Object labelled(String label, Supplier<?> call) {
        SqlLabels.label.set(label);
        try { return call.get(); } finally { SqlLabels.label.remove(); }
    }

    static String query(String address, String sql, boolean admin) throws SQLException {
        try (var connection = DriverManager.getConnection(url(address), admin ? primary.getUsername() : "application_user",
                admin ? primary.getPassword() : PASSWORD); var statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            try (var rows = statement.executeQuery(sql)) { rows.next(); return rows.getString(1); }
        }
    }

    static boolean read(String address, String sql, boolean admin) {
        try { String value = query(address, sql, admin); return "t".equals(value) || "true".equals(value); }
        catch (SQLException failure) { return false; }
    }

    static void await(String description, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out: " + description);
    }

    void checkpoint(String name, Object evidence) throws Exception {
        results.put(name, evidence);
        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve("checkpoints.json"), mapper.writeValueAsString(results));
        System.out.println("REPLICA PASS: " + name);
    }

    @AfterAll
    static void logs() throws Exception {
        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve("primary.log"), primary.getLogs());
        Files.writeString(EVIDENCE.resolve("standby.log"), standby.getLogs());
        Files.writeString(EVIDENCE.resolve("topology.json"), new ObjectMapper().writeValueAsString(Map.of(
                "primary", primary.getContainerId(), "standby", standby.getContainerId(),
                "network", NETWORK.getId(), "image", "postgres:16-alpine")));
    }

    public static class SqlLabels implements StatementInspector {
        static final ThreadLocal<String> label = new ThreadLocal<>();
        @Override public String inspect(String sql) {
            return label.get() == null ? sql : "/* replica-case:" + label.get() + " */ " + sql;
        }
    }
}
