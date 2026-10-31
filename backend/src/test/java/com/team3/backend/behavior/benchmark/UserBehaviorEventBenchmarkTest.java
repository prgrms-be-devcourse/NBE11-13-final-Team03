package com.team3.backend.behavior.benchmark;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.user.domain.entity.Role;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 합성 데이터만 있는 전용 PostgreSQL(perf-data/user-behavior-events/env)에서 행동 이벤트 기록 경로의 비용을 측정한다.
 * 운영 코드는 바꾸지 않고, 실제 Controller·보안 필터·트랜잭션 경로를 MockMvc로 호출한다(인증 principal은 테스트 주입).
 * <ul>
 *   <li>schema: YON_BEHAVIOR_BENCH_DDL=update로 Hibernate가 빈 DB에 테이블만 만든다. 데이터는 env/sql의 SQL로 적재한다.</li>
 *   <li>measure: E1~E3·S1·S2·H1·H2를 VUser 1(교차 반복)·10·50(고정 시간)으로 측정하고 build/reports/behavior-bench/&lt;label&gt;에 원자료를 남긴다.</li>
 * </ul>
 * 측정 전에는 env/sql/reset.sql로 기준 시각 이후의 이벤트와 S1·H1 대상 preference를 지워야 한다.
 */
@Tag("manual")
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=${YON_BEHAVIOR_BENCH_DB_URL}",
        "spring.datasource.username=application_user",
        "spring.datasource.password=${YON_BEHAVIOR_BENCH_DB_PASSWORD}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.hikari.connection-init-sql=SET search_path TO application, pg_catalog",
        "spring.jpa.hibernate.ddl-auto=${YON_BEHAVIOR_BENCH_DDL:none}",
        "spring.jpa.properties.hibernate.default_schema=application",
        "logging.level.org.hibernate.SQL=OFF",
        "app.scheduling.enabled=false",
        "collection.schedule.enabled=false",
        "yon.normalization.condition-set-backfill.enabled=false"
})
@AutoConfigureMockMvc
@Import(BehaviorBenchProbe.Configuration.class)
@EnabledIfEnvironmentVariable(named = "YON_BEHAVIOR_BENCH_DB_URL", matches = ".+")
class UserBehaviorEventBenchmarkTest {

    private static final String LABEL = System.getenv().getOrDefault("YON_BEHAVIOR_BENCH_LABEL", "plain");
    private static final Path OUT = Path.of("build/reports/behavior-bench", LABEL);
    /** 이 시각 이후의 이벤트는 측정 요청이 만든 것이다 (배경 이벤트는 모두 이전) */
    private static final LocalDateTime CUTOFF = LocalDateTime.parse(
            System.getenv().getOrDefault("YON_BEHAVIOR_BENCH_CUTOFF", "2026-10-30T00:00:00"));
    private static final int ROUNDS = Integer.parseInt(System.getenv().getOrDefault("YON_BEHAVIOR_BENCH_ROUNDS", "3"));
    private static final int SEQUENTIAL = Integer.parseInt(System.getenv().getOrDefault("YON_BEHAVIOR_BENCH_REQUESTS", "1000"));
    private static final int SEQUENTIAL_WARMUP = 100;
    private static final int INITIAL_WARMUP = 300;
    private static final List<Integer> VUSERS = Arrays.stream(
            System.getenv().getOrDefault("YON_BEHAVIOR_BENCH_VUSERS", "10,50").split(",")).map(Integer::parseInt).toList();
    private static final Duration CONCURRENT_WARMUP = Duration.ofSeconds(3);
    private static final Duration CONCURRENT_MEASURE = Duration.ofSeconds(
            Long.parseLong(System.getenv().getOrDefault("YON_BEHAVIOR_BENCH_SECONDS", "15")));

    // env/sql/data.sql의 합성 데이터 배치와 같아야 한다.
    private static final int BENCH_USERS = 1_000;
    private static final long OPPORTUNITIES = 140_000;
    private static final long SAVED_FIRST = 139_561;
    private static final long HIDDEN_FIRST = 139_581;
    private static final int KEPT = 20;
    private static final long FRESH_SAVE_FIRST = 139_601;
    private static final long FRESH_HIDE_FIRST = 139_801;
    private static final int FRESH = 200;

    private static final Pattern LOCK = Pattern.compile("for (no key )?update");
    private static final Pattern USER_TABLE = Pattern.compile("\\bfrom (\\w+\\.)?users\\b");
    private static final Pattern OPPORTUNITY_TABLE = Pattern.compile("\\bfrom (\\w+\\.)?opportunities\\b");
    private static final int BASELINE = 2_000;
    /** 동시성 블록에서 측정할 시나리오 (원인 확인용 재측정에서 일부만 고른다) */
    private static final List<Kind> CONCURRENT_KINDS = Arrays.stream(
            System.getenv().getOrDefault("YON_BEHAVIOR_BENCH_KINDS", "ALL").split(","))
            .flatMap(name -> name.equals("ALL") ? Arrays.stream(Kind.values()) : Arrays.stream(new Kind[]{Kind.valueOf(name)}))
            .toList();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Value("${spring.datasource.url}") private String url;
    @Value("${spring.datasource.hikari.maximum-pool-size:10}") private int poolSize;

    enum Kind { E1_IMPRESSION, E2_DETAIL_VIEW, E3_EXTERNAL_LINK_CLICK, S1_SAVE, S2_DUPLICATE_SAVE, H1_HIDE, H2_DUPLICATE_HIDE }

    /** 문장 분류: 사용자 조회·잠금, 공고 조회, preference 조회·변경, 이벤트 INSERT */
    enum Sql { USER_SELECT, USER_LOCK, OPPORTUNITY_SELECT, PREFERENCE_SELECT, PREFERENCE_INSERT, PREFERENCE_UPDATE, EVENT_INSERT, OTHER }

    private final Map<Kind, AtomicLong> counters = new EnumMap<>(Kind.class);
    private final Map<Kind, AtomicLong> successes = new EnumMap<>(Kind.class);

    record Sample(Kind kind, int vusers, int round, long index, int status, long latencyNanos, int sqlCount,
                  long jdbcNanos, long[] sqlNanos, int[] sqlCounts, int connections, long waitNanos, long maxWaitNanos,
                  long holdNanos, long commitNanos, long recorderNanos, int recorderSql, long recorderJdbcNanos,
                  long recorderHoldNanos, long recorderWaitNanos, long serviceNanos) {
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "YON_BEHAVIOR_BENCH_PHASE", matches = "schema")
    void schema() {
        assertThat(jdbcTemplate.queryForObject("select to_regclass('application.user_behavior_events')::text", String.class))
                .isNotNull();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "YON_BEHAVIOR_BENCH_PHASE", matches = "measure")
    void measure() throws Exception {
        Files.createDirectories(OUT);
        for (Kind kind : Kind.values()) {
            counters.put(kind, new AtomicLong());
            successes.put(kind, new AtomicLong());
        }
        assertThat(LocalDateTime.now()).as("측정 이벤트는 기준 시각 이후여야 한다").isAfter(CUTOFF);
        assertThat(countAfterCutoff()).as("reset.sql로 이전 측정 이벤트를 지워야 한다").isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from user_opportunity_preferences
                where user_id <= ? and opportunity_id >= ?""", Long.class, BENCH_USERS, FRESH_SAVE_FIRST))
                .as("S1·H1 대상 preference가 비어 있어야 한다").isZero();
        Map<String, Object> before = snapshot();
        writeEnvironment(before);

        // SQL 형태 기록 (파라미터 포함 1건씩)
        try (Writer trace = Files.newBufferedWriter(OUT.resolve("sql-trace.md"))) {
            for (Kind kind : Kind.values()) {
                Sample sample = execute(kind, 0, 0, true, trace);
                assertThat(sample.status()).isEqualTo(204);
            }
        }
        for (int i = 0; i < INITIAL_WARMUP; i++) {
            for (Kind kind : Kind.values()) {
                execute(kind, 0, 0, false, null);
            }
        }

        writeBaseline();

        List<Sample> samples = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        // VUser 1: 라운드마다 시나리오 순서를 한 칸씩 회전한다
        for (int round = 1; round <= ROUNDS; round++) {
            List<Kind> order = rotate(Arrays.asList(Kind.values()), round - 1);
            for (Kind kind : order) {
                for (int i = 0; i < SEQUENTIAL_WARMUP; i++) {
                    execute(kind, 1, round, false, null);
                }
                Map<String, Object> db = dbBefore();
                long started = System.nanoTime();
                List<Sample> block = new ArrayList<>();
                for (int i = 0; i < SEQUENTIAL; i++) {
                    block.add(execute(kind, 1, round, false, null));
                }
                blocks.add(dbAfter(kind, 1, round, db, block.size(), System.nanoTime() - started));
                samples.addAll(block);
            }
        }
        // VUser 10·50: 시나리오별 고정 시간, 각 VUser는 서로 다른 사용자 구간을 쓴다
        for (int vusers : VUSERS) {
            for (Kind kind : CONCURRENT_KINDS) {
                runConcurrent(kind, vusers, CONCURRENT_WARMUP, false);
                Map<String, Object> db = dbBefore();
                long started = System.nanoTime();
                List<Sample> block = runConcurrent(kind, vusers, CONCURRENT_MEASURE, true);
                blocks.add(dbAfter(kind, vusers, 0, db, block.size(), System.nanoTime() - started));
                samples.addAll(block);
            }
        }

        assertThat(samples).allSatisfy(sample -> assertThat(sample.status()).isEqualTo(204));
        Map<String, Long> events = verifyEvents();
        writeSamples(samples);
        writeSummary(samples, blocks, before, snapshot(), events);
    }

    // ── 요청 ──

    private MockHttpServletRequestBuilder request(Kind kind, long index) {
        long userId = index % BENCH_USERS + 1;
        long slot = index / BENCH_USERS;
        long opportunityId = switch (kind) {
            case E1_IMPRESSION, E2_DETAIL_VIEW, E3_EXTERNAL_LINK_CLICK -> Math.floorMod(index * 104_729L, OPPORTUNITIES) + 1;
            case S1_SAVE -> FRESH_SAVE_FIRST + checkFresh(slot);
            case H1_HIDE -> FRESH_HIDE_FIRST + checkFresh(slot);
            case S2_DUPLICATE_SAVE -> SAVED_FIRST + slot % KEPT;
            case H2_DUPLICATE_HIDE -> HIDDEN_FIRST + slot % KEPT;
        };
        MockHttpServletRequestBuilder builder = switch (kind) {
            case E1_IMPRESSION -> event(opportunityId, "{\"eventType\":\"IMPRESSION\",\"source\":\"RECOMMENDATION\"}");
            case E2_DETAIL_VIEW -> event(opportunityId, "{\"eventType\":\"DETAIL_VIEW\",\"source\":\"SEARCH\"}");
            case E3_EXTERNAL_LINK_CLICK -> event(opportunityId,
                    "{\"eventType\":\"EXTERNAL_LINK_CLICK\",\"source\":\"SEARCH\",\"linkType\":\"APPLICATION\"}");
            case S1_SAVE, S2_DUPLICATE_SAVE -> post("/api/opportunities/{id}/save", opportunityId).param("source", "RECOMMENDATION");
            case H1_HIDE, H2_DUPLICATE_HIDE -> post("/api/opportunities/{id}/hide", opportunityId).param("source", "RECOMMENDATION");
        };
        return builder.with(user(new CustomUserDetails(userId, Role.USER, "behavior-bench"))).with(csrf());
    }

    private static long checkFresh(long slot) {
        if (slot >= FRESH) {
            throw new IllegalStateException("S1·H1 대상 공고가 부족하다. 요청 수나 FRESH 범위를 조정한다");
        }
        return slot;
    }

    private static MockHttpServletRequestBuilder event(long opportunityId, String body) {
        return post("/api/opportunities/{id}/events", opportunityId).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private Sample execute(Kind kind, int vusers, int round, boolean parameters, Writer trace) throws Exception {
        long index = counters.get(kind).getAndIncrement();
        MockHttpServletRequestBuilder builder = request(kind, index);
        BehaviorBenchProbe.start(parameters);
        long started = System.nanoTime();
        int status;
        try {
            status = mockMvc.perform(builder).andReturn().getResponse().getStatus();
        } catch (Exception exception) {
            BehaviorBenchProbe.stop();
            throw exception;
        }
        long latency = System.nanoTime() - started;
        BehaviorBenchProbe.Capture capture = BehaviorBenchProbe.stop();
        if (status == 204) {
            successes.get(kind).incrementAndGet();
        }
        if (trace != null) {
            trace.write("## " + kind + " (index " + index + ", status " + status + ")\n\n");
            for (BehaviorBenchProbe.Execution execution : capture.executions) {
                trace.write("- [" + classify(execution.sql) + (execution.recorder ? ", recorder" : "") + "] `"
                        + execution.sql + "` " + execution.parameters + "\n");
            }
            trace.write("- connections: " + capture.leases.size() + ", methods: " + methods(capture) + "\n\n");
        }
        return sample(kind, vusers, round, index, status, latency, capture);
    }

    private List<Sample> runConcurrent(Kind kind, int vusers, Duration duration, boolean keep) throws Exception {
        Queue<Sample> collected = new ConcurrentLinkedQueue<>();
        long deadline = System.nanoTime() + duration.toNanos();
        try (ExecutorService executor = Executors.newFixedThreadPool(vusers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int v = 0; v < vusers; v++) {
                futures.add(executor.submit(() -> {
                    while (System.nanoTime() < deadline) {
                        Sample sample = execute(kind, vusers, 0, false, null);
                        if (keep) {
                            collected.add(sample);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }
        return new ArrayList<>(collected);
    }

    private Sample sample(Kind kind, int vusers, int round, long index, int status, long latency,
                          BehaviorBenchProbe.Capture capture) {
        long[] sqlNanos = new long[Sql.values().length];
        int[] sqlCounts = new int[Sql.values().length];
        long jdbc = 0;
        int recorderSql = 0;
        long recorderJdbc = 0;
        for (BehaviorBenchProbe.Execution execution : capture.executions) {
            Sql type = classify(execution.sql);
            sqlNanos[type.ordinal()] += execution.nanos;
            sqlCounts[type.ordinal()]++;
            jdbc += execution.nanos;
            if (execution.recorder) {
                recorderSql++;
                recorderJdbc += execution.nanos;
            }
        }
        long wait = 0, maxWait = 0, hold = 0, commit = 0, recorderHold = 0, recorderWait = 0;
        for (BehaviorBenchProbe.Lease lease : capture.leases) {
            wait += lease.waitNanos;
            maxWait = Math.max(maxWait, lease.waitNanos);
            hold += lease.holdNanos;
            commit += lease.commitNanos;
            if (lease.recorder) {
                recorderHold += lease.holdNanos;
                recorderWait += lease.waitNanos;
            }
        }
        long recorder = method(capture, "PreferenceBehaviorRecorder.record");
        long service = switch (kind) {
            case E1_IMPRESSION, E2_DETAIL_VIEW, E3_EXTERNAL_LINK_CLICK -> method(capture, "UserBehaviorEventService.record");
            case S1_SAVE, S2_DUPLICATE_SAVE -> method(capture, "PreferenceService.save");
            case H1_HIDE, H2_DUPLICATE_HIDE -> method(capture, "PreferenceService.hide");
        };
        return new Sample(kind, vusers, round, index, status, latency, capture.executions.size(), jdbc, sqlNanos, sqlCounts,
                capture.leases.size(), wait, maxWait, hold, commit, recorder, recorderSql, recorderJdbc, recorderHold,
                recorderWait, service);
    }

    private static long method(BehaviorBenchProbe.Capture capture, String name) {
        long[] value = capture.methods.get(name);
        return value == null ? 0 : value[1];
    }

    private static String methods(BehaviorBenchProbe.Capture capture) {
        StringBuilder text = new StringBuilder();
        capture.methods.forEach((name, value) -> text.append(name).append(" x").append(value[0]).append(' '));
        return text.toString().trim();
    }

    static Sql classify(String sql) {
        String text = sql.toLowerCase(Locale.ROOT);
        if (text.startsWith("insert") && text.contains("user_behavior_events")) return Sql.EVENT_INSERT;
        if (text.startsWith("insert") && text.contains("user_opportunity_preferences")) return Sql.PREFERENCE_INSERT;
        if (text.startsWith("update") && text.contains("user_opportunity_preferences")) return Sql.PREFERENCE_UPDATE;
        if (text.startsWith("select") && text.contains("user_opportunity_preferences")) return Sql.PREFERENCE_SELECT;
        if (text.startsWith("select") && USER_TABLE.matcher(text).find()) return LOCK.matcher(text).find() ? Sql.USER_LOCK : Sql.USER_SELECT;
        if (text.startsWith("select") && OPPORTUNITY_TABLE.matcher(text).find()) return Sql.OPPORTUNITY_SELECT;
        return Sql.OTHER;
    }

    private static <T> List<T> rotate(List<T> values, int by) {
        List<T> rotated = new ArrayList<>(values.subList(by % values.size(), values.size()));
        rotated.addAll(values.subList(0, by % values.size()));
        return rotated;
    }

    // ── DB 상태 (관리자 연결: pg_stat_statements·WAL 위치·크기) ──

    private Connection admin() throws SQLException {
        return DriverManager.getConnection(url, "postgres", System.getenv("YON_BEHAVIOR_BENCH_ADMIN_PASSWORD"));
    }

    private Map<String, Object> dbBefore() throws SQLException {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute("select pg_stat_statements_reset()");
            return wal(statement);
        }
    }

    /** WAL 위치로 블록의 WAL 생성량을 잰다 (통계 뷰는 백엔드별 반영 지연이 있어 짧은 블록에 쓰지 않는다) */
    private static Map<String, Object> wal(Statement statement) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        try (ResultSet result = statement.executeQuery("select pg_current_wal_lsn()::text")) {
            result.next();
            values.put("lsn", result.getString(1));
        }
        return values;
    }

    private String dbAfter(Kind kind, int vusers, int round, Map<String, Object> before, int requests, long elapsed)
            throws SQLException {
        StringBuilder text = new StringBuilder();
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            long walBytes;
            try (ResultSet result = statement.executeQuery(
                    "select pg_wal_lsn_diff(pg_current_wal_lsn(), '" + before.get("lsn") + "'::pg_lsn)::bigint")) {
                result.next();
                walBytes = result.getLong(1);
            }
            text.append("### ").append(kind).append(" VU").append(vusers).append(round > 0 ? " round " + round : "")
                    .append(" (").append(requests).append(" requests, ").append(String.format("%.1f", elapsed / 1e9)).append(" s)\n\n");
            text.append("- WAL 생성량(클러스터 전체, 측정 구간): 요청당 ")
                    .append(String.format("%.0f", walBytes / (double) requests)).append(" bytes\n");
            text.append("\n| calls | mean ms | total ms | rows | shared hit | shared read | dirtied | wal bytes | query |\n")
                    .append("|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
            try (ResultSet result = statement.executeQuery("""
                    select calls, mean_exec_time, total_exec_time, rows, shared_blks_hit, shared_blks_read,
                           shared_blks_dirtied, wal_bytes, regexp_replace(query, '\\s+', ' ', 'g')
                    from pg_stat_statements
                    where dbid = (select oid from pg_database where datname = current_database())
                      and query not ilike '%pg_stat%' and calls >= 10
                    order by total_exec_time desc limit 12""")) {
                while (result.next()) {
                    String query = result.getString(9);
                    text.append(String.format("| %d | %.4f | %.1f | %d | %d | %d | %d | %d | `%s` |%n", result.getLong(1),
                            result.getDouble(2), result.getDouble(3), result.getLong(4), result.getLong(5), result.getLong(6),
                            result.getLong(7), result.getLong(8), query.length() > 140 ? query.substring(0, 140) + "…" : query));
                }
            }
        }
        return text.append('\n').toString();
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("events", jdbcTemplate.queryForObject("select count(*) from user_behavior_events", Long.class));
        values.put("preferences", jdbcTemplate.queryForObject("select count(*) from user_opportunity_preferences", Long.class));
        values.put("relkind", jdbcTemplate.queryForObject(
                "select relkind::text from pg_class where oid = 'application.user_behavior_events'::regclass", String.class));
        values.put("partitions", jdbcTemplate.queryForObject(
                "select count(*) from pg_inherits where inhparent = 'application.user_behavior_events'::regclass", Long.class));
        values.put("events_total_size", jdbcTemplate.queryForObject("""
                select pg_size_pretty(coalesce(sum(pg_total_relation_size(c.oid)), 0)) from pg_class c
                where c.oid = 'application.user_behavior_events'::regclass
                   or c.oid in (select inhrelid from pg_inherits where inhparent = 'application.user_behavior_events'::regclass)""",
                String.class));
        values.put("events_indexes", jdbcTemplate.queryForList("""
                select indexname || ' ' || indexdef from pg_indexes
                where schemaname = 'application' and tablename = 'user_behavior_events' order by 1""", String.class));
        values.put("event_constraints", jdbcTemplate.queryForList("""
                select conname || ' ' || pg_get_constraintdef(oid) from pg_constraint
                where conrelid = 'application.user_behavior_events'::regclass order by 1""", String.class));
        return values;
    }

    private long countAfterCutoff() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from user_behavior_events where received_at >= ?", Long.class, CUTOFF);
        return count == null ? 0 : count;
    }

    /** 측정 전후 실제 저장 건수: E·S1·H1 성공 요청 수와 같아야 하고 S2·H2는 0건이어야 한다 */
    private Map<String, Long> verifyEvents() {
        Map<String, Long> stored = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select event_type, count(*) from user_behavior_events where received_at >= ? group by event_type order by 1""",
                (ResultSet row) -> {
                    stored.put(row.getString(1), row.getLong(2));
                }, CUTOFF);
        assertThat(stored.getOrDefault("IMPRESSION", 0L)).isEqualTo(successes.get(Kind.E1_IMPRESSION).get());
        assertThat(stored.getOrDefault("DETAIL_VIEW", 0L)).isEqualTo(successes.get(Kind.E2_DETAIL_VIEW).get());
        assertThat(stored.getOrDefault("EXTERNAL_LINK_CLICK", 0L)).isEqualTo(successes.get(Kind.E3_EXTERNAL_LINK_CLICK).get());
        assertThat(stored.getOrDefault("SAVE", 0L)).isEqualTo(successes.get(Kind.S1_SAVE).get());
        assertThat(stored.getOrDefault("HIDE", 0L)).isEqualTo(successes.get(Kind.H1_HIDE).get());
        return stored;
    }

    /**
     * 같은 풀·드라이버로 DB 작업이 거의 없는 왕복 비용을 잰다.
     * select 1: 자동 커밋 단일 왕복. 빈 쓰기 트랜잭션: xid만 할당하고 커밋해 commit 기록·fsync 비용을 본다.
     */
    private void writeBaseline() throws IOException {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        long[] select = new long[BASELINE];
        long[] commit = new long[BASELINE];
        long[] commitOnly = new long[BASELINE];
        for (int i = 0; i < BASELINE; i++) {
            long started = System.nanoTime();
            jdbcTemplate.queryForObject("select 1", Integer.class);
            select[i] = System.nanoTime() - started;
            BehaviorBenchProbe.start(false);
            started = System.nanoTime();
            transaction.executeWithoutResult(status -> jdbcTemplate.queryForObject("select pg_current_xact_id()::text", String.class));
            commit[i] = System.nanoTime() - started;
            BehaviorBenchProbe.Capture capture = BehaviorBenchProbe.stop();
            commitOnly[i] = capture.leases.stream().mapToLong(lease -> lease.commitNanos).sum();
        }
        Arrays.sort(select);
        Arrays.sort(commit);
        Arrays.sort(commitOnly);
        String text = "# 왕복 기준선 (" + LABEL + ", 각 " + BASELINE + "회, ms)\n\n| 항목 | 평균 | p50 | p95 | p99 |\n|---|---:|---:|---:|---:|\n"
                + row("select 1 (자동 커밋, 풀 대여 포함)", select) + row("빈 쓰기 트랜잭션 전체 (xid 할당 + commit)", commit)
                + row("그중 commit 호출", commitOnly);
        Files.writeString(OUT.resolve("baseline.md"), text);
    }

    private static String row(String name, long[] sorted) {
        return String.format("| %s | %.3f | %.3f | %.3f | %.3f |%n", name, mean(sorted), pct(sorted, 50), pct(sorted, 95), pct(sorted, 99));
    }

    // ── 출력 ──

    private void writeEnvironment(Map<String, Object> before) throws IOException {
        Runtime runtime = Runtime.getRuntime();
        String text = "# 환경 (" + LABEL + ")\n\n"
                + "- 측정 시작: " + LocalDateTime.now() + " (JVM 시간대 " + ZoneId.systemDefault() + ")\n"
                + "- JVM: " + Runtime.version() + ", 논리 CPU " + runtime.availableProcessors() + ", 최대 힙 "
                + runtime.maxMemory() / 1024 / 1024 + " MB\n"
                + "- Spring Boot " + SpringBootVersion.getVersion() + ", Hikari maximum-pool-size " + poolSize + "\n"
                + "- PostgreSQL: " + jdbcTemplate.queryForObject("select version()", String.class) + "\n"
                + "- DB 설정: " + jdbcTemplate.queryForList("""
                select name || '=' || setting from pg_settings
                where name in ('shared_buffers','synchronous_commit','wal_level','fsync','max_connections','work_mem','TimeZone')
                order by 1""", String.class) + "\n"
                + "- 측정 전 상태: " + before + "\n"
                + "- VUser 1: 초기 워밍업 시나리오별 " + INITIAL_WARMUP + "건, 라운드 " + ROUNDS + "회 × 시나리오별 워밍업 "
                + SEQUENTIAL_WARMUP + "건 + 측정 " + SEQUENTIAL + "건 (라운드마다 순서 회전)\n"
                + "- VUser " + VUSERS + ": 시나리오별 워밍업 " + CONCURRENT_WARMUP.toSeconds() + "초 + 측정 "
                + CONCURRENT_MEASURE.toSeconds() + "초 (closed loop, think time 없음)\n";
        Files.writeString(OUT.resolve("environment.md"), text);
    }

    private void writeSamples(List<Sample> samples) throws IOException {
        try (Writer writer = Files.newBufferedWriter(OUT.resolve("samples.csv"))) {
            StringBuilder header = new StringBuilder("kind,vusers,round,index,status,latency_us,sql_count,jdbc_us");
            for (Sql type : Sql.values()) {
                header.append(',').append(type.name().toLowerCase(Locale.ROOT)).append("_us");
            }
            header.append(",connections,wait_us,max_wait_us,hold_us,commit_us,recorder_us,recorder_sql,recorder_jdbc_us,"
                    + "recorder_hold_us,recorder_wait_us,service_us\n");
            writer.write(header.toString());
            for (Sample s : samples) {
                StringBuilder row = new StringBuilder();
                row.append(s.kind()).append(',').append(s.vusers()).append(',').append(s.round()).append(',').append(s.index())
                        .append(',').append(s.status()).append(',').append(us(s.latencyNanos())).append(',').append(s.sqlCount())
                        .append(',').append(us(s.jdbcNanos()));
                for (long value : s.sqlNanos()) {
                    row.append(',').append(us(value));
                }
                row.append(',').append(s.connections()).append(',').append(us(s.waitNanos())).append(',').append(us(s.maxWaitNanos()))
                        .append(',').append(us(s.holdNanos())).append(',').append(us(s.commitNanos())).append(',')
                        .append(us(s.recorderNanos())).append(',').append(s.recorderSql()).append(',').append(us(s.recorderJdbcNanos()))
                        .append(',').append(us(s.recorderHoldNanos())).append(',').append(us(s.recorderWaitNanos())).append(',')
                        .append(us(s.serviceNanos())).append('\n');
                writer.write(row.toString());
            }
        }
    }

    private void writeSummary(List<Sample> samples, List<String> blocks, Map<String, Object> before,
                              Map<String, Object> after, Map<String, Long> events) throws IOException {
        StringBuilder text = new StringBuilder("# 요약 (" + LABEL + ")\n\n단위: ms (SQL 수·건수 제외). 지연은 MockMvc 요청 전체, "
                + "JDBC는 문장 실행 합, 점유는 커넥션 획득~반환 합, 대기는 풀 획득 대기 합이다.\n\n");
        text.append("| 시나리오 | VU | n | 오류 | 처리량/s | 평균 | p50 | p95 | p99 | 최대 | SQL 수 | JDBC | 커넥션 수 | 대기 평균 | 대기 p95 | 점유 | commit |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        List<Integer> levels = new ArrayList<>(List.of(1));
        levels.addAll(VUSERS);
        for (int vusers : levels) {
            for (Kind kind : Kind.values()) {
                List<Sample> group = samples.stream().filter(s -> s.kind() == kind && s.vusers() == vusers).toList();
                if (group.isEmpty()) {
                    continue;
                }
                double seconds = vusers == 1 ? group.stream().mapToLong(Sample::latencyNanos).sum() / 1e9
                        : CONCURRENT_MEASURE.toNanos() / 1e9;
                long[] latency = group.stream().mapToLong(Sample::latencyNanos).sorted().toArray();
                long[] wait = group.stream().mapToLong(Sample::waitNanos).sorted().toArray();
                text.append(String.format("| %s | %d | %d | %d | %.0f | %.3f | %.3f | %.3f | %.3f | %.3f | %.2f | %.3f | %.2f | %.3f | %.3f | %.3f | %.3f |%n",
                        kind, vusers, group.size(), group.stream().filter(s -> s.status() != 204).count(), group.size() / seconds,
                        mean(latency), pct(latency, 50), pct(latency, 95), pct(latency, 99), latency[latency.length - 1] / 1e6,
                        group.stream().mapToInt(Sample::sqlCount).average().orElse(0), avg(group, Sample::jdbcNanos),
                        group.stream().mapToInt(Sample::connections).average().orElse(0), mean(wait), pct(wait, 95),
                        avg(group, Sample::holdNanos), avg(group, Sample::commitNanos)));
            }
        }
        text.append("\n## 문장 분류별 평균 (ms, 괄호는 요청당 건수)\n\n| 시나리오 | VU |");
        for (Sql type : Sql.values()) {
            text.append(' ').append(type).append(" |");
        }
        text.append("\n|---|---:|").append("---:|".repeat(Sql.values().length)).append('\n');
        for (int vusers : levels) {
            for (Kind kind : Kind.values()) {
                List<Sample> group = samples.stream().filter(s -> s.kind() == kind && s.vusers() == vusers).toList();
                if (group.isEmpty()) {
                    continue;
                }
                text.append("| ").append(kind).append(" | ").append(vusers).append(" |");
                for (Sql type : Sql.values()) {
                    int ordinal = type.ordinal();
                    text.append(String.format(" %.3f (%.2f) |", group.stream().mapToLong(s -> s.sqlNanos()[ordinal]).average().orElse(0) / 1e6,
                            group.stream().mapToInt(s -> s.sqlCounts()[ordinal]).average().orElse(0)));
                }
                text.append('\n');
            }
        }
        text.append("\n## 같은 요청 안의 구간 (ms 평균)\n\n서비스는 E: UserBehaviorEventService.record, S: PreferenceService.save, "
                + "H: PreferenceService.hide. 기록기는 PreferenceBehaviorRecorder.record(커밋 후 별도 트랜잭션)이다.\n\n"
                + "| 시나리오 | VU | 지연 | 서비스 | 기록기 | 기록기 SQL 수 | 기록기 JDBC | 기록기 점유 | 기록기 대기 | 기록기 제외 지연 |\n"
                + "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (int vusers : levels) {
            for (Kind kind : Kind.values()) {
                List<Sample> group = samples.stream().filter(s -> s.kind() == kind && s.vusers() == vusers).toList();
                if (group.isEmpty()) {
                    continue;
                }
                text.append(String.format("| %s | %d | %.3f | %.3f | %.3f | %.2f | %.3f | %.3f | %.3f | %.3f |%n", kind, vusers,
                        avg(group, Sample::latencyNanos), avg(group, Sample::serviceNanos), avg(group, Sample::recorderNanos),
                        group.stream().mapToInt(Sample::recorderSql).average().orElse(0), avg(group, Sample::recorderJdbcNanos),
                        avg(group, Sample::recorderHoldNanos), avg(group, Sample::recorderWaitNanos),
                        avg(group, s -> s.latencyNanos() - s.recorderNanos())));
            }
        }
        text.append("\n## 저장 건수\n\n- 측정 요청이 만든 이벤트(기준 시각 이후): ").append(events)
                .append("\n- 성공 요청 수(워밍업 포함): ").append(successes).append("\n- 측정 전: ").append(before)
                .append("\n- 측정 후: ").append(after).append("\n\n## DB 지표 (블록별)\n\n");
        blocks.forEach(text::append);
        Files.writeString(OUT.resolve("summary.md"), text.toString());
    }

    private static double avg(List<Sample> group, java.util.function.ToLongFunction<Sample> value) {
        return group.stream().mapToLong(value).average().orElse(0) / 1e6;
    }

    private static double mean(long[] sorted) {
        return Arrays.stream(sorted).average().orElse(0) / 1e6;
    }

    private static double pct(long[] sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))] / 1e6;
    }

    private static long us(long nanos) {
        return nanos / 1_000;
    }
}
