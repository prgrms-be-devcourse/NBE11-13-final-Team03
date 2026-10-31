package com.team3.backend.behavior.retention;

import com.team3.backend.BackendApplication;
import com.team3.backend.behavior.retention.UserBehaviorEventRetention.Result;
import com.team3.backend.behavior.retention.UserBehaviorEventRetention.Status;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.service.UserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 운영 계정·공식 전환 SQL을 쓴 폐기 PostgreSQL에서 4개월 보관 정리를 검증한다. 운영·공유 DB는 사용하지 않는다. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserBehaviorEventRetentionPostgresTest {

    private static final Duration LOCK_TIMEOUT = Duration.ofMillis(300);
    private static final Clock KST = Clock.system(ZoneId.of("Asia/Seoul"));
    private static final int RETAINED = 4;

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private JdbcTemplate db;
    private UserBehaviorEventRetention retention;
    private ConfigurableApplicationContext context;
    private long owner;
    private long survivor;
    private long opportunity;

    @BeforeAll
    void provision() throws Exception {
        try (Connection connection = admin(); var statement = connection.createStatement()) {
            statement.execute(sql("database-boundaries.sql"));
            statement.execute("DROP SCHEMA application CASCADE; CREATE SCHEMA application AUTHORIZATION application_user");
            statement.execute("ALTER ROLE application_user PASSWORD 'retention-test'");
        }
        db = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), "application_user", "retention-test"));
        try (var ignored = start()) { }              // Hibernate update로 운영과 같은 일반 테이블을 만든다
        db.execute(sql("user-behavior-events-partition.sql"));
        context = start();                             // 전환 후 재기동 (보관 스케줄러는 기본 비활성)
        owner = context.getBean(UserRepository.class).saveAndFlush(User.builder().role(Role.USER).build()).getId();
        survivor = context.getBean(UserRepository.class).saveAndFlush(User.builder().role(Role.USER).build()).getId();
        opportunity = context.getBean(OpportunityRepository.class).saveAndFlush(Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY).title("Retention test").build()).getId();
        retention = new UserBehaviorEventRetention(db);
    }

    @AfterAll
    void close() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void cleanExtraObjects() {
        db.execute("DROP VIEW IF EXISTS june_events");
        db.execute("DROP TABLE IF EXISTS user_behavior_events_old, user_behavior_events_2020_01, user_behavior_events_minvalue");
    }

    @Test
    void novemberKeepsAugustToNovemberAndDropsJulyAndEarlier() {
        months(YearMonth.of(2026, 5), YearMonth.of(2026, 11));
        Result result = retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);

        assertEquals(Status.COMPLETED, result.status());
        assertEquals(LocalDate.of(2026, 8, 1), result.keepFrom());
        assertEquals(List.of("user_behavior_events_2026_05", "user_behavior_events_2026_06", "user_behavior_events_2026_07"), result.dropped());
        assertEquals(monthRange(YearMonth.of(2026, 8), YearMonth.of(2027, 11)), monthPartitions());
        // 2026-11 + 12개월(2027-11)까지 없는 달만 만든다
        assertEquals(monthNames(YearMonth.of(2026, 12), YearMonth.of(2027, 11)), result.created());
    }

    @Test
    void februaryRunAcrossYearKeepsNovemberThroughFebruary() {
        // 2027-02-10 실행 기준: 2026-11 ~ 2027-02 보관이므로 2026-10-31은 삭제 대상이다 (2026-11 실행 기준과 다르다)
        months(YearMonth.of(2026, 8), YearMonth.of(2027, 2));
        insert(owner, LocalDateTime.of(2026, 10, 31, 23, 59, 59, 999_999_000));   // 2027-02 실행의 보관 시작 직전 → 삭제
        insert(owner, LocalDateTime.of(2026, 11, 1, 0, 0));                         // 2027-02 실행의 보관 시작 → 유지
        insert(survivor, LocalDateTime.of(2027, 2, 28, 23, 59, 59));                // 현재 월 → 유지

        Result result = retention.run(LocalDate.of(2027, 2, 10), RETAINED, false, LOCK_TIMEOUT);

        assertEquals(LocalDate.of(2026, 11, 1), result.keepFrom());
        assertEquals(List.of("user_behavior_events_2026_08", "user_behavior_events_2026_09", "user_behavior_events_2026_10"), result.dropped());
        assertEquals(List.of(LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2027, 2, 28, 23, 59, 59)),
                db.queryForList("SELECT received_at FROM user_behavior_events ORDER BY received_at", LocalDateTime.class));
        assertTrue(monthPartitions().containsAll(monthRange(YearMonth.of(2026, 11), YearMonth.of(2028, 2))));
    }

    @Test
    void repeatedRunWithNothingToDoCompletesWithoutChanges() {
        months(YearMonth.of(2026, 8), YearMonth.of(2027, 11));
        insert(owner, LocalDateTime.of(2026, 8, 1, 0, 0));
        Result first = retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);
        Result second = retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);

        for (Result result : List.of(first, second)) {
            assertEquals(Status.COMPLETED, result.status());
            assertEquals(List.of(), result.dropped());
            assertEquals(List.of(), result.created());
        }
        assertEquals(1, count("user_behavior_events"));
    }

    @Test
    void dryRunReportsTargetsWithoutChangingAnything() {
        months(YearMonth.of(2026, 6), YearMonth.of(2026, 11));
        insert(owner, LocalDateTime.of(2026, 6, 15, 0, 0));
        List<String> before = monthPartitions();

        Result result = retention.run(LocalDate.of(2026, 11, 3), RETAINED, true, LOCK_TIMEOUT);

        assertEquals(List.of("user_behavior_events_2026_06 (dry-run)", "user_behavior_events_2026_07 (dry-run)"), result.dropped());
        assertEquals(12, result.created().size());
        assertEquals(before, monthPartitions());
        assertEquals(1, count("user_behavior_events"));
    }

    @Test
    void lockTimeoutKeepsPartitionAndRerunDropsIt() throws Exception {
        months(YearMonth.of(2026, 7), YearMonth.of(2027, 11));
        insert(owner, LocalDateTime.of(2026, 7, 10, 0, 0));
        Result failed;
        try (Connection reader = user(); var statement = reader.createStatement()) {
            reader.setAutoCommit(false);
            statement.execute("SELECT count(*) FROM user_behavior_events_2026_07");   // ACCESS SHARE 잠금 유지
            failed = retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);
            reader.rollback();
        }
        assertEquals(Status.COMPLETED_WITH_FAILURES, failed.status());
        assertTrue(failed.failed().getFirst().startsWith("user_behavior_events_2026_07"));
        assertTrue(monthPartitions().contains("user_behavior_events_2026_07"));
        assertEquals(1, count("user_behavior_events"));

        Result rerun = retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);
        assertEquals(Status.COMPLETED, rerun.status());
        assertEquals(List.of("user_behavior_events_2026_07"), rerun.dropped());
        assertEquals(0, count("user_behavior_events"));
    }

    @Test
    void planningTimesOutOnLockedPartitionAndLeavesLockAndConnectionClean() throws Exception {
        months(YearMonth.of(2026, 6), YearMonth.of(2026, 11));
        insert(owner, LocalDateTime.of(2026, 6, 10, 0, 0));
        List<String> before = monthPartitions();
        try (Connection pinned = user()) {
            // 풀에 반환된 연결을 다음 실행이 다시 쓰는 상황: 같은 연결 하나로 실패 후 상태와 재실행을 본다
            UserBehaviorEventRetention onPinned = new UserBehaviorEventRetention(new JdbcTemplate(new SingleConnectionDataSource(pinned, true)));
            LocalDate november = LocalDate.of(2026, 11, 1);
            // 삭제 대상 파티션과 DEFAULT 파티션 (DEFAULT 행 확인도 같은 제한을 받는다)
            for (String locked : List.of("user_behavior_events_2026_06", "user_behavior_events_default")) {
                try (Connection locker = user(); var statement = locker.createStatement()) {
                    locker.setAutoCommit(false);
                    statement.execute("LOCK TABLE " + locked + " IN ACCESS EXCLUSIVE MODE");
                    assertLockTimeoutWithinLimit(() -> onPinned.run(november, RETAINED, false, LOCK_TIMEOUT));
                    assertLockTimeoutWithinLimit(() -> onPinned.run(november, RETAINED, true, LOCK_TIMEOUT));
                    assertLockTimeoutWithinLimit(() -> onPinned.plan(november, RETAINED, LOCK_TIMEOUT));
                    assertCleanConnection(pinned);
                    assertAdvisoryLockFree();
                    locker.rollback();
                }
            }
            assertEquals(before, monthPartitions());                  // 생성·삭제 모두 실행하지 않았다
            assertEquals(1, count("user_behavior_events"));

            Result rerun = onPinned.run(november, RETAINED, false, LOCK_TIMEOUT);   // 외부 잠금 해제 후 같은 연결로 재실행
            assertEquals(Status.COMPLETED, rerun.status());
            assertEquals(List.of("user_behavior_events_2026_06", "user_behavior_events_2026_07"), rerun.dropped());
            assertEquals(12, rerun.created().size());
            assertCleanConnection(pinned);
        }
        assertEquals(0, count("user_behavior_events"));
    }

    @Test
    void concurrentRunIsSkippedWhileAnotherHoldsTheLock() throws Exception {
        months(YearMonth.of(2026, 6), YearMonth.of(2027, 11));
        try (Connection other = user(); var statement = other.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + UserBehaviorEventRetention.LOCK_KEY + ")");
            Result skipped = retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);
            assertEquals(Status.SKIPPED_LOCKED, skipped.status());
            assertTrue(monthPartitions().contains("user_behavior_events_2026_06"));
            statement.execute("SELECT pg_advisory_unlock(" + UserBehaviorEventRetention.LOCK_KEY + ")");
        }
        assertEquals(List.of("user_behavior_events_2026_06", "user_behavior_events_2026_07"),
                retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT).dropped());
    }

    @Test
    void defaultDetachedAndUnsupportedRangesAreProtected() {
        months(YearMonth.of(2026, 6), YearMonth.of(2027, 11));
        insert(owner, LocalDateTime.of(2019, 5, 1, 0, 0));                        // 해당 월 파티션이 없어 DEFAULT로 간다
        db.execute("CREATE TABLE user_behavior_events_2020_01 (LIKE user_behavior_events)");   // 이름만 월 파티션인 분리된 테이블
        db.execute("INSERT INTO user_behavior_events_2020_01 SELECT * FROM user_behavior_events");
        db.execute("CREATE TABLE user_behavior_events_minvalue PARTITION OF user_behavior_events FOR VALUES FROM (MINVALUE) TO ('2019-01-01')");

        Result result = retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);

        assertEquals(List.of("user_behavior_events_2026_06", "user_behavior_events_2026_07"), result.dropped());
        assertEquals(1, result.ignored().size());
        assertEquals(1, count("user_behavior_events_default"));
        assertEquals(1, count("user_behavior_events_2020_01"));
        assertNotNull(db.queryForObject("SELECT to_regclass('user_behavior_events_minvalue')::text", String.class));
    }

    @Test
    void rollbackWindowWithOldCopyChangesNothing() {
        months(YearMonth.of(2026, 6), YearMonth.of(2026, 11));
        insert(owner, LocalDateTime.of(2026, 6, 15, 0, 0));
        db.execute("CREATE TABLE user_behavior_events_old (LIKE user_behavior_events)");
        db.execute("INSERT INTO user_behavior_events_old SELECT * FROM user_behavior_events");
        List<String> before = monthPartitions();

        Result result = retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);

        assertEquals(Status.SKIPPED_ROLLBACK_WINDOW, result.status());
        assertEquals(before, monthPartitions());
        assertEquals(1, count("user_behavior_events"));
        assertEquals(1, count("user_behavior_events_old"));
    }

    @Test
    void plainTableAndRollbackPartitionCopyAreNeverTouched() throws Exception {
        try (Connection connection = admin(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS plain_case CASCADE; CREATE SCHEMA plain_case AUTHORIZATION application_user");
        }
        String url = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=plain_case";
        JdbcTemplate plainDb = new JdbcTemplate(new DriverManagerDataSource(url, "application_user", "retention-test"));
        plainDb.execute("CREATE TABLE user_behavior_events (LIKE application.user_behavior_events)");
        plainDb.execute("INSERT INTO user_behavior_events (id, user_id, opportunity_id, event_type, source, received_at) "
                + "VALUES (1, 1, 1, 'DETAIL_VIEW', 'OTHER', '2020-01-01')");
        plainDb.execute("CREATE TABLE user_behavior_events_p (LIKE application.user_behavior_events) PARTITION BY RANGE (received_at)");
        plainDb.execute("CREATE TABLE user_behavior_events_2020_01 PARTITION OF user_behavior_events_p FOR VALUES FROM ('2020-01-01') TO ('2020-02-01')");

        Result result = new UserBehaviorEventRetention(plainDb).run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);

        assertEquals(Status.SKIPPED_NOT_PARTITIONED, result.status());
        assertEquals(1, plainDb.queryForObject("SELECT count(*) FROM user_behavior_events", Integer.class));
        assertNotNull(plainDb.queryForObject("SELECT to_regclass('user_behavior_events_2020_01')::text", String.class));
    }

    @Test
    void createdMonthReceivesInsertsAndWithdrawalStillDeletesEvents() {
        months(YearMonth.of(2026, 9), YearMonth.of(2026, 11));
        retention.run(LocalDate.of(2026, 11, 20), RETAINED, false, LOCK_TIMEOUT);
        insert(owner, LocalDateTime.of(2026, 12, 1, 0, 0));
        insert(owner, LocalDateTime.of(2027, 11, 30, 23, 0));
        insert(survivor, LocalDateTime.of(2026, 9, 2, 0, 0));
        assertEquals(List.of("user_behavior_events_2026_09", "user_behavior_events_2026_12", "user_behavior_events_2027_11"),
                db.queryForList("SELECT tableoid::regclass::text FROM user_behavior_events ORDER BY received_at", String.class)
                        .stream().map(name -> name.replace("application.", "")).sorted().toList());
        assertEquals(0, count("user_behavior_events_default"));

        context.getBean(UserService.class).withdraw(owner);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM user_behavior_events WHERE user_id=?", Integer.class, owner));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM user_behavior_events WHERE user_id=?", Integer.class, survivor));
        owner = context.getBean(UserRepository.class).saveAndFlush(User.builder().role(Role.USER).build()).getId();
    }

    @Test
    void schedulerUsesConfiguredMonthsAndRefusesInvalidValueOrClockMismatch() {
        LocalDate today = dbToday();
        months(YearMonth.from(today).minusMonths(6), YearMonth.from(today));

        Result result = scheduler(KST, true).runOnce();
        assertEquals(today.withDayOfMonth(1).minusMonths(3), result.keepFrom());
        assertEquals(3, result.dropped().size());                    // dry-run 기본: 대상만 보고
        assertTrue(monthPartitions().contains(monthName(YearMonth.from(today).minusMonths(6))));

        Clock skewed = Clock.offset(KST, Duration.ofDays(40));
        assertEquals(Status.SKIPPED_CLOCK_MISMATCH, scheduler(skewed, false).runOnce().status());
        assertEquals(Status.SKIPPED_CLOCK_MISMATCH, manual(skewed, true).runOnce().status());
        assertTrue(monthPartitions().contains(monthName(YearMonth.from(today).minusMonths(6))));
        assertThrows(IllegalArgumentException.class, () -> new UserBehaviorEventRetentionScheduler(retention, KST, 0, true, LOCK_TIMEOUT));
        assertThrows(IllegalArgumentException.class, () -> retention.run(today, 0, true, LOCK_TIMEOUT));
    }

    // ── 정확한 4개월 경계 ──

    @Test
    void novemberFirstRunKeepsAugustFirstThroughNovember() {
        months(YearMonth.of(2026, 6), YearMonth.of(2026, 11));
        insert(owner, LocalDateTime.of(2026, 7, 31, 23, 59, 59, 999_999_000));     // 삭제 대상
        insert(owner, LocalDateTime.of(2026, 8, 1, 0, 0));                           // 보관 시작
        insert(owner, LocalDateTime.of(2026, 10, 31, 23, 59, 59, 999_999_000));    // 보관
        insert(owner, LocalDateTime.of(2026, 11, 1, 0, 0));                          // 보관 (현재 월)

        Result result = retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);

        assertEquals(LocalDate.of(2026, 8, 1), result.keepFrom());
        assertEquals(List.of("user_behavior_events_2026_06", "user_behavior_events_2026_07"), result.dropped());
        assertEquals(List.of(LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 10, 31, 23, 59, 59, 999_999_000),
                LocalDateTime.of(2026, 11, 1, 0, 0)), receivedAt());
    }

    @Test
    void yearChangeAndLeapDayBoundaries() {
        // 2027-01-01 실행: 2026-10 ~ 2027-01 보관
        months(YearMonth.of(2026, 8), YearMonth.of(2027, 1));
        insert(owner, LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_999_000));
        insert(owner, LocalDateTime.of(2026, 10, 1, 0, 0));
        insert(owner, LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_999_000));
        insert(owner, LocalDateTime.of(2027, 1, 1, 0, 0));
        assertEquals(LocalDate.of(2026, 10, 1), retention.run(LocalDate.of(2027, 1, 1), RETAINED, false, LOCK_TIMEOUT).keepFrom());
        assertEquals(List.of(LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_999_000),
                LocalDateTime.of(2027, 1, 1, 0, 0)), receivedAt());

        // 2028-05-31(윤년) 실행: 2028-02 ~ 2028-05 보관, 2월 29일 마지막 순간도 보관, 1월 31일은 삭제
        months(YearMonth.of(2027, 12), YearMonth.of(2028, 5));
        insert(owner, LocalDateTime.of(2028, 1, 31, 23, 59, 59, 999_999_000));
        insert(owner, LocalDateTime.of(2028, 2, 29, 23, 59, 59, 999_999_000));
        insert(owner, LocalDateTime.of(2028, 5, 31, 23, 59, 59, 999_999_000));
        Result leap = retention.run(LocalDate.of(2028, 5, 31), RETAINED, false, LOCK_TIMEOUT);
        assertEquals(LocalDate.of(2028, 2, 1), leap.keepFrom());
        assertEquals(List.of("user_behavior_events_2027_12", "user_behavior_events_2028_01"), leap.dropped());
        assertEquals(List.of(LocalDateTime.of(2028, 2, 29, 23, 59, 59, 999_999_000), LocalDateTime.of(2028, 5, 31, 23, 59, 59, 999_999_000)),
                receivedAt());
    }

    // ── 수동 실행 ──

    @Test
    void manualDryRunMatchesScheduledPolicyAndExecuteDropsOnlyWhenExplicit() {
        LocalDate today = dbToday();
        months(YearMonth.from(today).minusMonths(6), YearMonth.from(today));
        List<String> before = monthPartitions();

        Result manualDryRun = manual(KST, false).runOnce();
        Result scheduledDryRun = scheduler(KST, true).runOnce();
        assertEquals(scheduledDryRun.keepFrom(), manualDryRun.keepFrom());
        assertEquals(scheduledDryRun.dropped(), manualDryRun.dropped());
        assertEquals(scheduledDryRun.created(), manualDryRun.created());
        assertTrue(manualDryRun.dryRun());
        assertEquals(before, monthPartitions());
        assertEquals(0, UserBehaviorEventRetentionManualRun.exitCode(manualDryRun));

        Result executed = manual(KST, true).runOnce();
        assertEquals(Status.COMPLETED, executed.status());
        assertEquals(manualDryRun.dropped().stream().map(name -> name.replace(" (dry-run)", "")).toList(), executed.dropped());
        assertEquals(monthRange(YearMonth.from(today).minusMonths(3), YearMonth.from(today).plusMonths(12)), monthPartitions());
        assertEquals(0, UserBehaviorEventRetentionManualRun.exitCode(executed));
    }

    @Test
    void manualRunIsBlockedByOldCopyAndByARunningScheduledJob() throws Exception {
        LocalDate today = dbToday();
        months(YearMonth.from(today).minusMonths(6), YearMonth.from(today));
        List<String> before = monthPartitions();

        db.execute("CREATE TABLE user_behavior_events_old (LIKE user_behavior_events)");
        Result rollbackWindow = manual(KST, true).runOnce();
        assertEquals(Status.SKIPPED_ROLLBACK_WINDOW, rollbackWindow.status());
        assertEquals(2, UserBehaviorEventRetentionManualRun.exitCode(rollbackWindow));
        db.execute("DROP TABLE user_behavior_events_old");

        try (Connection scheduled = user(); var statement = scheduled.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + UserBehaviorEventRetention.LOCK_KEY + ")");   // 자동 실행 진행 중
            Result locked = manual(KST, true).runOnce();
            assertEquals(Status.SKIPPED_LOCKED, locked.status());
            assertEquals(2, UserBehaviorEventRetentionManualRun.exitCode(locked));
            statement.execute("SELECT pg_advisory_unlock(" + UserBehaviorEventRetention.LOCK_KEY + ")");
        }
        assertEquals(before, monthPartitions());
    }

    @Test
    void simultaneousScheduledAndManualRunsDropEachPartitionOnce() throws Exception {
        LocalDate today = dbToday();
        months(YearMonth.from(today).minusMonths(6), YearMonth.from(today));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Result> scheduled = executor.submit(() -> { start.await(); return scheduler(KST, false).runOnce(); });
            Future<Result> manual = executor.submit(() -> { start.await(); return manual(KST, true).runOnce(); });
            start.countDown();
            List<Result> results = List.of(scheduled.get(60, TimeUnit.SECONDS), manual.get(60, TimeUnit.SECONDS));

            assertTrue(results.stream().allMatch(r -> r.failed().isEmpty()));
            List<String> dropped = results.stream().flatMap(r -> r.dropped().stream()).sorted().toList();
            assertEquals(monthRange(YearMonth.from(today).minusMonths(6), YearMonth.from(today).minusMonths(4)), dropped);
            assertEquals(12, results.stream().mapToInt(r -> r.created().size()).sum());
        }
    }

    // ── 생성·삭제 독립성 ──

    @Test
    void futureCreationFailureDoesNotBlockDropsAndIsReportedThenRecovered() {
        months(YearMonth.of(2026, 6), YearMonth.of(2026, 11));
        insert(survivor, LocalDateTime.of(2027, 3, 10, 0, 0));      // 파티션 없는 미래 월 → DEFAULT, 2027-03 생성이 실패한다

        Result result = retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);

        assertEquals(Status.COMPLETED_WITH_FAILURES, result.status());
        assertEquals(1, result.failed().size());
        assertTrue(result.failed().getFirst().startsWith("user_behavior_events_2027_03"));
        assertEquals(11, result.created().size());
        assertEquals(List.of("user_behavior_events_2026_06", "user_behavior_events_2026_07"), result.dropped());
        insert(owner, LocalDateTime.of(2027, 3, 20, 0, 0));          // 미생성 월도 DEFAULT로 들어가 INSERT는 실패하지 않는다
        assertEquals(2, count("user_behavior_events_default"));

        db.execute("DELETE FROM user_behavior_events_default");       // 운영 문서의 DEFAULT 이동 절차를 대신한 정리
        Result rerun = retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);
        assertEquals(Status.COMPLETED, rerun.status());
        assertEquals(List.of("user_behavior_events_2027_03"), rerun.created());
        assertEquals(List.of(), rerun.dropped());
        insert(owner, LocalDateTime.of(2027, 3, 20, 0, 0));
        assertEquals(0, count("user_behavior_events_default"));
        assertEquals(1, count("user_behavior_events_2027_03"));
    }

    @Test
    void dropDdlFailureKeepsOtherDropsAndCreationsAndRerunRetriesOnlyTheFailedPartition() {
        months(YearMonth.of(2026, 5), YearMonth.of(2026, 11));
        insert(owner, LocalDateTime.of(2026, 6, 10, 0, 0));
        db.execute("CREATE VIEW june_events AS SELECT * FROM user_behavior_events_2026_06");   // DROP TABLE이 의존 객체 때문에 실패한다

        Result result = retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);

        assertEquals(Status.COMPLETED_WITH_FAILURES, result.status());
        assertTrue(result.failed().getFirst().startsWith("user_behavior_events_2026_06"));
        assertEquals(List.of("user_behavior_events_2026_05", "user_behavior_events_2026_07"), result.dropped());
        assertEquals(12, result.created().size());
        assertEquals(1, count("user_behavior_events_2026_06"));
        insert(owner, LocalDateTime.of(2027, 11, 1, 0, 0));
        assertEquals(1, count("user_behavior_events_2027_11"));

        db.execute("DROP VIEW june_events");
        Result rerun = retention.run(LocalDate.of(2026, 11, 1), RETAINED, false, LOCK_TIMEOUT);
        assertEquals(Status.COMPLETED, rerun.status());
        assertEquals(List.of("user_behavior_events_2026_06"), rerun.dropped());
        assertEquals(List.of(), rerun.created());
    }

    @Test
    void backendBootsWithoutWebServerForOneShotRuns() {
        try (var oneShot = new SpringApplicationBuilder(BackendApplication.class).profiles("test", "database-boundary").run(
                "--DB_URL=" + postgres.getJdbcUrl(), "--APPLICATION_DB_PASSWORD=retention-test",
                "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=application_user",
                "--spring.datasource.password=retention-test", "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.jpa.hibernate.ddl-auto=none", "--app.scheduling.enabled=false",
                "--spring.main.web-application-type=none", "--spring.main.banner-mode=off")) {
            assertFalse(oneShot instanceof org.springframework.boot.web.server.context.WebServerApplicationContext);
            assertNotNull(oneShot.getBean(UserBehaviorEventRetention.class));
            assertTrue(oneShot.getBeanProvider(UserBehaviorEventRetentionManualRun.class).getIfAvailable() == null);
        }
    }

    // ── 도우미 ──

    /** 별도 스레드에서 실행해 잠금을 무제한 기다리면 테스트가 멈추지 않고 실패하게 한다 */
    private static void assertLockTimeoutWithinLimit(Callable<?> work) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            long started = System.nanoTime();
            Future<?> future = executor.submit(work);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(DataAccessException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("lock timeout"), failure.getCause().getMessage());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 자동 커밋으로 돌아왔고, 트랜잭션·세션 제한 시간·advisory lock이 남지 않았다 */
    private static void assertCleanConnection(Connection connection) throws Exception {
        assertTrue(connection.getAutoCommit());
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT current_setting('lock_timeout'), current_setting('statement_timeout'),
                       (SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid())""")) {
            assertTrue(result.next());
            assertEquals("0", result.getString(1));
            assertEquals("0", result.getString(2));
            assertEquals(0, result.getInt(3));
        }
    }

    private void assertAdvisoryLockFree() throws Exception {
        try (Connection other = user(); var statement = other.createStatement();
             var result = statement.executeQuery("SELECT pg_try_advisory_lock(" + UserBehaviorEventRetention.LOCK_KEY + ")")) {
            assertTrue(result.next() && result.getBoolean(1));
        }
    }

    private LocalDate dbToday() {
        return db.queryForObject("SELECT (now() AT TIME ZONE 'Asia/Seoul')::date", LocalDate.class);
    }

    private UserBehaviorEventRetentionScheduler scheduler(Clock clock, boolean dryRun) {
        return new UserBehaviorEventRetentionScheduler(retention, clock, RETAINED, dryRun, LOCK_TIMEOUT);
    }

    /** ApplicationRunner.run은 프로세스를 끝내므로 같은 설정으로 만든 runOnce만 호출한다 */
    private UserBehaviorEventRetentionManualRun manual(Clock clock, boolean execute) {
        return new UserBehaviorEventRetentionManualRun(retention, context, clock, RETAINED, execute, LOCK_TIMEOUT);
    }

    private List<LocalDateTime> receivedAt() {
        return db.queryForList("SELECT received_at FROM user_behavior_events ORDER BY received_at", LocalDateTime.class);
    }

    /** DEFAULT를 남기고 월 파티션을 first~last로 다시 만들고 이벤트를 비운다 */
    private void months(YearMonth first, YearMonth last) {
        db.execute("DELETE FROM user_behavior_events");
        for (String name : db.queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'user_behavior_events'::regclass AND c.relname <> 'user_behavior_events_default'""", String.class)) {
            db.execute("DROP TABLE " + name);
        }
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            db.execute("CREATE TABLE " + monthName(month) + " PARTITION OF user_behavior_events FOR VALUES FROM ('"
                    + month.atDay(1) + "') TO ('" + month.plusMonths(1).atDay(1) + "')");
        }
    }

    private List<String> monthPartitions() {
        return db.queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'user_behavior_events'::regclass AND c.relname ~ '^user_behavior_events_\\d{4}_\\d{2}$'
                ORDER BY 1""", String.class);
    }

    private static List<String> monthRange(YearMonth first, YearMonth last) {
        return monthNames(first, last);
    }

    private static List<String> monthNames(YearMonth first, YearMonth last) {
        List<String> names = new java.util.ArrayList<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            names.add(monthName(month));
        }
        return names;
    }

    private static String monthName(YearMonth month) {
        return String.format("user_behavior_events_%04d_%02d", month.getYear(), month.getMonthValue());
    }

    private void insert(long userId, LocalDateTime receivedAt) {
        db.update("INSERT INTO user_behavior_events(user_id, opportunity_id, event_type, source, received_at) VALUES (?,?,'DETAIL_VIEW','OTHER',?)",
                userId, opportunity, receivedAt);
    }

    private int count(String table) {
        Integer value = db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private Connection admin() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Connection user() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "application_user", "retention-test");
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(BackendApplication.class).profiles("test", "database-boundary").run(
                "--DB_URL=" + postgres.getJdbcUrl(), "--APPLICATION_DB_PASSWORD=retention-test",
                "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=application_user",
                "--spring.datasource.password=retention-test", "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
                "--app.scheduling.enabled=false", "--server.port=0", "--spring.main.banner-mode=off");
    }

    private String sql(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/db-boundary/" + name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
