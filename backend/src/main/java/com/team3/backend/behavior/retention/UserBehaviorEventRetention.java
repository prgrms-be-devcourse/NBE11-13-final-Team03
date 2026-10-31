package com.team3.backend.behavior.retention;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 월 RANGE 파티션으로 전환된 user_behavior_events에서 현재 월을 포함한 보관 개월 수(기본 4: 현재 월 + 직전 3개월)만 남기고
 * 범위 끝이 보관 시작일 이하인 월 파티션을 삭제한다.
 * 이번 달부터 12개월 뒤까지 없는 월 파티션은 만든다 (user-behavior-events-add-partitions.sql과 같은 범위).
 * <ul>
 *   <li>부모가 received_at RANGE 파티션 테이블이 아니면(일반 테이블·롤백 상태) 아무것도 바꾸지 않는다.</li>
 *   <li>파티션 이름이 아니라 카탈로그의 실제 범위로 판정한다. DEFAULT·범위를 해석할 수 없는 파티션·하위 파티션 테이블은 건드리지 않는다.</li>
 *   <li>부모에 붙어 있지 않은 테이블(롤백 후 _p 등)은 대상이 아니다. 전환 백업 _old가 남아 있는 롤백 기간에는 아무것도 바꾸지 않는다.</li>
 *   <li>세션 advisory lock으로 여러 서버·중복 실행을 한 번만 진행하고, 파티션마다 별도 트랜잭션과 잠금 제한 시간을 쓴다.
 *       실패한 파티션은 그대로 두고 다음 실행에서 다시 판정한다.</li>
 *   <li>dryRun이면 판정 결과만 기록하고 DDL을 실행하지 않는다.</li>
 * </ul>
 * 판정은 {@link #plan}(읽기 전용, advisory lock 없음, 잠금·문장 제한 시간 적용)으로 하고, 실행은 잠금을 잡은 뒤 같은 판정을 다시 해서 수행한다.
 * 관리자 미리보기는 계획만 쓰며, 실행 요청의 계획 토큰이 잠금 아래 다시 만든 계획과 다르면 아무것도 바꾸지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBehaviorEventRetention {

    static final int FUTURE_MONTHS = 12;
    /** 여러 서버의 동시 실행을 막는 advisory lock 키 (임의 고정값) */
    static final long LOCK_KEY = 7_204_311_902_118_640_001L;
    /** 계획 조회 한 문장의 최대 실행 시간. 잠금 대기는 lockTimeout으로 따로 제한한다 */
    static final Duration PLAN_STATEMENT_TIMEOUT = Duration.ofSeconds(10);

    private static final Pattern RANGE = Pattern.compile(
            "^FOR VALUES FROM \\('(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})'\\) TO \\('(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})'\\)$");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy_MM");

    private final JdbcTemplate jdbcTemplate;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public enum Status {
        COMPLETED, COMPLETED_WITH_FAILURES, SKIPPED_LOCKED, SKIPPED_NOT_PARTITIONED, SKIPPED_ROLLBACK_WINDOW, SKIPPED_CLOCK_MISMATCH,
        /** 실행 요청의 계획 토큰이 잠금 아래 다시 만든 계획과 다르다 (미리보기 이후 기준 월·대상이 바뀜) */
        PLAN_CHANGED
    }

    public record Result(Status status, LocalDate keepFrom, boolean dryRun, List<String> created, List<String> dropped,
                         List<String> failed, List<String> ignored) {
    }

    /** 만들 월. conflict면 기존 범위와 겹쳐 만들지 않고 실패로 기록한다 */
    public record CreateTarget(String name, LocalDateTime from, LocalDateTime to, boolean conflict) {
    }

    /** 지울 월 파티션. estimatedRows는 pg_class.reltuples 추정치(ANALYZE 전이면 -1 또는 0)다 */
    public record DropTarget(long oid, String qualifiedName, String name, String bound, LocalDateTime from, LocalDateTime to,
                             long estimatedRows, long totalBytes) {
    }

    public record IgnoredPartition(String name, char kind, String bound) {
    }

    /**
     * 한 시점의 판정 결과. skipped가 null이 아니면 아무것도 바꾸지 않는 상태다.
     * defaultCheckError는 DEFAULT 행 확인 쿼리 실패 메시지다.
     */
    public record Plan(Status skipped, LocalDate today, int retainedMonths, LocalDate keepFrom, List<CreateTarget> toCreate,
                       List<DropTarget> toDrop, List<IgnoredPartition> ignored, boolean defaultHasRows, String defaultCheckError) {

        /** 미리보기에서 확인한 계획과 실행 직전 계획이 같은지 비교하는 값. 추정 건수·크기는 넣지 않는다 */
        public String token() {
            StringBuilder canonical = new StringBuilder().append(skipped).append('|').append(today == null ? null : today.withDayOfMonth(1))
                    .append('|').append(retainedMonths).append('|').append(keepFrom);
            toCreate.forEach(t -> canonical.append("|c:").append(t.name()).append(',').append(t.from()).append(',').append(t.to())
                    .append(',').append(t.conflict()));
            toDrop.forEach(t -> canonical.append("|d:").append(t.oid()).append(',').append(t.qualifiedName()).append(',').append(t.bound()));
            ignored.forEach(t -> canonical.append("|i:").append(t.name()).append(',').append(t.bound()));
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private record Partition(long oid, String qualifiedName, String name, char kind, String bound, LocalDateTime from,
                             LocalDateTime to, long estimatedRows, long totalBytes) {
        boolean isDefault() {
            return "DEFAULT".equals(bound);
        }

        boolean isMonthRange() {
            return from != null && kind == 'r';
        }
    }

    /**
     * 자동(스케줄)·수동 실행의 공통 진입점. DB의 KST 날짜를 기준일로 쓰며, 앱 시계와 하루 넘게 다르면
     * 잘못된 기준 월로 지우지 않도록 아무것도 바꾸지 않는다. 이후 판정·보호 장치는 {@link #run}과 같다.
     */
    public Result runNow(Clock clock, String trigger, int retainedMonths, boolean dryRun, Duration lockTimeout) {
        return runNow(clock, trigger, retainedMonths, dryRun, lockTimeout, null);
    }

    /** expectedPlanToken이 있으면 잠금 아래 다시 만든 계획의 토큰과 같을 때만 실행한다 (관리자 API) */
    public Result runNow(Clock clock, String trigger, int retainedMonths, boolean dryRun, Duration lockTimeout,
                         String expectedPlanToken) {
        long started = System.nanoTime();
        LocalDate today = verifiedToday(clock, trigger);
        log.info("Behavior event retention started: trigger={}, dbDate={}, retainedMonths={}, dryRun={}",
                trigger, today, retainedMonths, dryRun);
        if (today == null) {
            return new Result(Status.SKIPPED_CLOCK_MISMATCH, null, dryRun, List.of(), List.of(), List.of(), List.of());
        }
        Result result = run(today, retainedMonths, dryRun, lockTimeout, expectedPlanToken);
        log.info("Behavior event retention finished: trigger={}, status={}, elapsedMs={}",
                trigger, result.status(), (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    /**
     * 미리보기: 같은 기준일·판정으로 계획만 만든다. advisory lock을 잡거나 DDL을 실행하지 않는다.
     * 다른 연결이 파티션을 잠그고 있으면 lockTimeout 뒤 예외로 끝난다.
     */
    public Plan preview(Clock clock, int retainedMonths, Duration lockTimeout) {
        requirePositive(retainedMonths);
        LocalDate today = verifiedToday(clock, "preview");
        if (today == null) {
            return new Plan(Status.SKIPPED_CLOCK_MISMATCH, null, retainedMonths, null, List.of(), List.of(), List.of(), false, null);
        }
        return plan(today, retainedMonths, lockTimeout);
    }

    /** 오늘(KST 날짜) 기준 계획. 읽기 전용이며 advisory lock을 잡지 않는다 */
    public Plan plan(LocalDate today, int retainedMonths, Duration lockTimeout) {
        requirePositive(retainedMonths);
        return jdbcTemplate.execute((ConnectionCallback<Plan>) connection -> planWithTimeout(connection, today, retainedMonths, lockTimeout));
    }

    /**
     * 오늘(KST 날짜) 기준으로 한 번 실행한다. retainedMonths는 현재 월을 포함해 보관하는 월 수이며
     * (예: 4면 2026-11에 8~11월 보관, 7월 이전 삭제) 1 이상이어야 한다.
     * lockTimeout은 계획 조회와 파티션 DDL 하나가 테이블 잠금을 기다리는 최대 시간이다.
     */
    public Result run(LocalDate today, int retainedMonths, boolean dryRun, Duration lockTimeout) {
        return run(today, retainedMonths, dryRun, lockTimeout, null);
    }

    Result run(LocalDate today, int retainedMonths, boolean dryRun, Duration lockTimeout, String expectedPlanToken) {
        requirePositive(retainedMonths);
        return jdbcTemplate.execute((ConnectionCallback<Result>) connection ->
                runWithConnection(connection, today, retainedMonths, dryRun, lockTimeout, expectedPlanToken));
    }

    private LocalDate verifiedToday(Clock clock, String trigger) {
        LocalDate appToday = LocalDate.now(clock.withZone(KST));
        LocalDate dbToday = jdbcTemplate.queryForObject("select (now() at time zone 'Asia/Seoul')::date", LocalDate.class);
        if (dbToday == null || Math.abs(ChronoUnit.DAYS.between(appToday, dbToday)) > 1) {
            log.error("Behavior event retention skipped: trigger={}, app date {} and DB date {} disagree", trigger, appToday, dbToday);
            return null;
        }
        return dbToday;
    }

    private static void requirePositive(int retainedMonths) {
        if (retainedMonths < 1) {
            throw new IllegalArgumentException("retainedMonths must be at least 1: " + retainedMonths);
        }
    }

    private Result runWithConnection(Connection connection, LocalDate today, int retainedMonths, boolean dryRun,
                                     Duration lockTimeout, String expectedPlanToken) throws SQLException {
        LocalDate keepFrom = today.withDayOfMonth(1).minusMonths(retainedMonths - 1L);
        List<String> created = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(true);
        try {
            if (!queryBoolean(connection, "select pg_try_advisory_lock(?)", LOCK_KEY)) {
                log.warn("Behavior event retention skipped: another run holds the lock");
                return new Result(Status.SKIPPED_LOCKED, keepFrom, dryRun, created, dropped, failed, ignored);
            }
            try {
                // 잠금을 잡은 뒤 다시 판정한다. 미리보기 이후 상태가 바뀌었어도 이 계획만 실행한다.
                Plan plan = planWithTimeout(connection, today, retainedMonths, lockTimeout);
                if (plan.skipped() != null) {
                    log.warn("Behavior event retention skipped: {}; nothing changed", plan.skipped());
                    return new Result(plan.skipped(), keepFrom, dryRun, created, dropped, failed, ignored);
                }
                if (expectedPlanToken != null && !expectedPlanToken.equals(plan.token())) {
                    log.warn("Behavior event retention skipped: plan changed since preview; nothing changed");
                    return new Result(Status.PLAN_CHANGED, keepFrom, dryRun, created, dropped, failed, ignored);
                }
                // 생성 → 삭제 순서. 각 파티션은 독립 트랜잭션이라 한쪽 실패가 다른 작업을 막지 않는다.
                createMonths(connection, plan, dryRun, lockTimeout, created, failed);
                dropMonths(connection, plan, dryRun, lockTimeout, dropped, failed, ignored);
                if (plan.defaultCheckError() != null) {
                    failed.add("default check: " + plan.defaultCheckError());
                } else if (plan.defaultHasRows()) {
                    log.warn("Behavior event DEFAULT partition has rows; retention does not delete them (keepFrom={}). "
                            + "Follow the partition maintenance document.", keepFrom);
                }
            } finally {
                queryBoolean(connection, "select pg_advisory_unlock(?)", LOCK_KEY);
            }
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        Status status = failed.isEmpty() ? Status.COMPLETED : Status.COMPLETED_WITH_FAILURES;
        String summary = "Behavior event retention {}: dryRun={}, keepFrom={}, created={}, dropped={}, failed={}, ignored={}";
        if (failed.isEmpty()) {
            log.info(summary, status, dryRun, keepFrom, created, dropped, failed, ignored);
        } else {
            log.warn(summary, status, dryRun, keepFrom, created, dropped, failed, ignored);
        }
        return new Result(status, keepFrom, dryRun, created, dropped, failed, ignored);
    }

    // ── 판정 (읽기 전용) ──

    /**
     * 계획 조회를 잠금·문장 제한 시간이 있는 트랜잭션에서 한다. pg_total_relation_size와 DEFAULT 행 확인은 파티션 잠금을
     * 기다리므로, 다른 연결이 ACCESS EXCLUSIVE를 잡고 있으면 lockTimeout 뒤 예외로 끝나고 아무것도 실행하지 않는다.
     * 읽기 전용이라 항상 롤백해서 SET LOCAL과 실패한 트랜잭션 상태가 연결에 남지 않게 한다.
     */
    private static Plan planWithTimeout(Connection connection, LocalDate today, int retainedMonths, Duration lockTimeout)
            throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set local lock_timeout = '" + lockTimeout.toMillis() + "ms'");
                statement.execute("set local statement_timeout = '" + PLAN_STATEMENT_TIMEOUT.toMillis() + "ms'");
            }
            return plan(connection, today, retainedMonths);
        } finally {
            try {
                connection.rollback();
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    private static Plan plan(Connection connection, LocalDate today, int retainedMonths) throws SQLException {
        LocalDate currentMonth = today.withDayOfMonth(1);
        LocalDate keepFrom = currentMonth.minusMonths(retainedMonths - 1L);
        String parent = partitionedParent(connection);
        if (parent == null) {
            return new Plan(Status.SKIPPED_NOT_PARTITIONED, today, retainedMonths, keepFrom, List.of(), List.of(), List.of(), false, null);
        }
        if (rollbackCopyExists(connection, parent)) {
            // 롤백 SQL은 _old와 현재 건수가 같아야 한다. 이 기간에 파티션을 지우면 롤백할 수 없게 된다.
            return new Plan(Status.SKIPPED_ROLLBACK_WINDOW, today, retainedMonths, keepFrom, List.of(), List.of(), List.of(), false, null);
        }
        List<Partition> partitions = partitions(connection);

        List<CreateTarget> toCreate = new ArrayList<>();
        for (int offset = 0; offset <= FUTURE_MONTHS; offset++) {
            LocalDateTime from = currentMonth.plusMonths(offset).atStartOfDay();
            LocalDateTime to = from.plusMonths(1);
            if (partitions.stream().anyMatch(p -> p.from() != null && !p.from().isAfter(from) && !p.to().isBefore(to))) {
                continue;
            }
            boolean conflict = partitions.stream().anyMatch(p -> p.from() != null && p.from().isBefore(to) && p.to().isAfter(from));
            toCreate.add(new CreateTarget("user_behavior_events_" + from.format(MONTH), from, to, conflict));
        }

        LocalDateTime cutoff = keepFrom.atStartOfDay();
        List<DropTarget> toDrop = new ArrayList<>();
        List<IgnoredPartition> ignored = new ArrayList<>();
        for (Partition partition : partitions) {
            if (partition.isDefault()) {
                continue;
            }
            if (!partition.isMonthRange()) {
                ignored.add(new IgnoredPartition(partition.name(), partition.kind(), partition.bound()));
            } else if (!partition.to().isAfter(cutoff)) {
                toDrop.add(new DropTarget(partition.oid(), partition.qualifiedName(), partition.name(), partition.bound(),
                        partition.from(), partition.to(), partition.estimatedRows(), partition.totalBytes()));
            }
        }

        boolean defaultHasRows = false;
        String defaultCheckError = null;
        for (Partition partition : partitions.stream().filter(Partition::isDefault).toList()) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("select exists(select 1 from " + partition.qualifiedName() + ")")) {
                defaultHasRows |= result.next() && result.getBoolean(1);
            } catch (SQLException exception) {
                // 확인 실패가 판정·실행 결과의 기록을 막지 않게 한다.
                defaultCheckError = exception.getMessage();
                log.error("Behavior event DEFAULT partition check failed", exception);
            }
        }
        return new Plan(null, today, retainedMonths, keepFrom, toCreate, toDrop, ignored, defaultHasRows, defaultCheckError);
    }

    /** search_path 기준 user_behavior_events가 received_at RANGE 파티션 부모이면 스키마를 포함한 이름을, 아니면 null을 돌려준다 */
    private static String partitionedParent(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select quote_ident(n.nspname) || '.' || quote_ident(c.relname), c.relkind, pg_get_partkeydef(c.oid)
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where c.oid = to_regclass('user_behavior_events')""");
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            boolean partitioned = "p".equals(result.getString(2)) && "RANGE (received_at)".equals(result.getString(3));
            return partitioned ? result.getString(1) : null;
        }
    }

    private static boolean rollbackCopyExists(Connection connection, String parent) throws SQLException {
        String schema = parent.substring(0, parent.lastIndexOf('.'));
        try (PreparedStatement statement = connection.prepareStatement("select to_regclass(?) is not null")) {
            statement.setString(1, schema + ".user_behavior_events_old");
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static List<Partition> partitions(Connection connection) throws SQLException {
        List<Partition> partitions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select c.oid, quote_ident(n.nspname) || '.' || quote_ident(c.relname), c.relname, c.relkind,
                       pg_get_expr(c.relpartbound, c.oid), c.reltuples::bigint, pg_total_relation_size(c.oid)
                from pg_inherits i
                join pg_class c on c.oid = i.inhrelid
                join pg_namespace n on n.oid = c.relnamespace
                where i.inhparent = to_regclass('user_behavior_events')
                order by c.relname""");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                partitions.add(partition(result.getLong(1), result.getString(2), result.getString(3), result.getString(4).charAt(0),
                        result.getString(5), result.getLong(6), result.getLong(7)));
            }
        }
        return partitions;
    }

    private static Partition partition(long oid, String qualifiedName, String name, char kind, String bound,
                                       long estimatedRows, long totalBytes) {
        Matcher matcher = RANGE.matcher(bound);
        if (!matcher.matches()) {
            return new Partition(oid, qualifiedName, name, kind, bound, null, null, estimatedRows, totalBytes);
        }
        LocalDateTime from = LocalDateTime.parse(matcher.group(1), TIMESTAMP);
        LocalDateTime to = LocalDateTime.parse(matcher.group(2), TIMESTAMP);
        return from.isBefore(to) ? new Partition(oid, qualifiedName, name, kind, bound, from, to, estimatedRows, totalBytes)
                : new Partition(oid, qualifiedName, name, kind, bound, null, null, estimatedRows, totalBytes);
    }

    // ── 실행 ──

    private void createMonths(Connection connection, Plan plan, boolean dryRun, Duration lockTimeout,
                              List<String> created, List<String> failed) throws SQLException {
        String parent = partitionedParent(connection);
        String schema = parent.substring(0, parent.lastIndexOf('.'));
        for (CreateTarget target : plan.toCreate()) {
            String name = target.name();
            if (target.conflict()) {
                failed.add(name + ": overlaps an existing partition range");
                log.error("Behavior event partition {} not created: overlaps an existing range", name);
                continue;
            }
            if (dryRun) {
                created.add(name + " (dry-run)");
                continue;
            }
            String ddl = "create table " + schema + "." + quote(name) + " partition of " + parent
                    + " for values from ('" + target.from().format(TIMESTAMP) + "') to ('" + target.to().format(TIMESTAMP) + "')";
            if (inTransaction(connection, lockTimeout, statement -> {
                // 같은 이름의 일반 테이블이 있으면 실패한다. DEFAULT에 그 달 행이 있으면 실패하며 운영 문서의 복구 절차를 따른다.
                statement.execute(ddl);
                return true;
            }, name, failed)) {
                created.add(name);
                log.info("Behavior event partition created: {} [{}, {})", name, target.from(), target.to());
            }
        }
    }

    private void dropMonths(Connection connection, Plan plan, boolean dryRun, Duration lockTimeout,
                            List<String> dropped, List<String> failed, List<String> ignored) {
        for (IgnoredPartition partition : plan.ignored()) {
            ignored.add(partition.name() + ": " + partition.bound());
            log.warn("Behavior event partition {} ignored: unsupported bound or kind ({}, {})",
                    partition.name(), partition.kind(), partition.bound());
        }
        for (DropTarget target : plan.toDrop()) {
            if (dryRun) {
                dropped.add(target.name() + " (dry-run)");
                log.info("Behavior event partition would be dropped (dry-run): {} [{}, {})", target.name(), target.from(), target.to());
                continue;
            }
            if (inTransaction(connection, lockTimeout, statement -> {
                // 판정 이후 분리·변경되었으면 지우지 않는다.
                try (ResultSet result = statement.executeQuery("select pg_get_expr(c.relpartbound, c.oid), c.reltuples::bigint, "
                        + "pg_total_relation_size(c.oid) from pg_inherits i join pg_class c on c.oid = i.inhrelid "
                        + "where i.inhparent = to_regclass('user_behavior_events') and c.oid = " + target.oid())) {
                    if (!result.next() || !target.bound().equals(result.getString(1))) {
                        log.warn("Behavior event partition {} changed before drop; skipped", target.name());
                        return false;
                    }
                    log.info("Behavior event partition dropping: {} [{}, {}), estimatedRows={}, bytes={}",
                            target.name(), target.from(), target.to(), result.getLong(2), result.getLong(3));
                }
                statement.execute("drop table " + target.qualifiedName());
                return true;
            }, target.name(), failed)) {
                dropped.add(target.name());
            }
        }
    }

    @FunctionalInterface
    private interface Work {
        boolean apply(Statement statement) throws SQLException;
    }

    /** 한 파티션 작업을 잠금·문장 제한 시간이 있는 트랜잭션으로 실행한다. 실패는 기록하고 다음 작업을 계속한다. */
    private static boolean inTransaction(Connection connection, Duration lockTimeout, Work work, String name, List<String> failed) {
        try {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("set local lock_timeout = '" + lockTimeout.toMillis() + "ms'");
                statement.execute("set local statement_timeout = '60s'");
                boolean done = work.apply(statement);
                connection.commit();
                return done;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                failed.add(name + ": " + exception.getMessage());
                log.error("Behavior event retention failed for {}", name, exception);
                return false;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            failed.add(name + ": " + exception.getMessage());
            log.error("Behavior event retention transaction error for {}", name, exception);
            return false;
        }
    }

    private static boolean queryBoolean(Connection connection, String sql, long key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
