package com.team3.dataservice.opportunity.ingest;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 벤치마크용 JDBC 계측. DataSource를 감싸 Statement 실행(execute*)과 커밋을 종류별로 세고 걸린 시간을 더한다.
 * 측정 범위: 드라이버가 SQL을 보내고 응답을 받을 때까지 (네트워크·DB 실행 포함, ResultSet을 읽는 시간은 제외).
 * 의존성 추가 없이 JDK 동적 프록시로 만든다. 테스트 전용.
 */
final class JdbcTimer {

    enum Kind { SELECT, INSERT, UPDATE, DELETE, OTHER, COMMIT }

    private static final Map<Kind, LongAdder> COUNT = new EnumMap<>(Kind.class);
    private static final Map<Kind, LongAdder> NANOS = new EnumMap<>(Kind.class);
    /** "INSERT opportunity_conditions"처럼 종류 + 대상 테이블별 [횟수, 나노초] */
    private static final Map<String, LongAdder[]> BY_TABLE = new ConcurrentHashMap<>();
    private static final Pattern TABLE = Pattern.compile(
            "^(?:insert\\s+into|update|delete\\s+from)\\s+([\\w.]+)|\\bfrom\\s+([\\w.]+)");

    static {
        for (Kind k : Kind.values()) {
            COUNT.put(k, new LongAdder());
            NANOS.put(k, new LongAdder());
        }
    }

    private JdbcTimer() {
    }

    static void reset() {
        COUNT.values().forEach(LongAdder::reset);
        NANOS.values().forEach(LongAdder::reset);
        BY_TABLE.clear();
    }

    /** 종류 + 테이블별 [횟수, 밀리초]. 시간 큰 순 */
    static Map<String, long[]> byTable() {
        Map<String, long[]> result = new LinkedHashMap<>();
        BY_TABLE.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1].sum(), a.getValue()[1].sum()))
                .forEach(e -> result.put(e.getKey(), new long[]{e.getValue()[0].sum(), e.getValue()[1].sum() / 1_000_000}));
        return result;
    }

    static long count(Kind kind) {
        return COUNT.get(kind).sum();
    }

    static long millis(Kind kind) {
        return NANOS.get(kind).sum() / 1_000_000;
    }

    static long totalMillis() {
        long sum = 0;
        for (Kind k : Kind.values()) {
            sum += NANOS.get(k).sum();
        }
        return sum / 1_000_000;
    }

    static DataSource wrap(DataSource target) {
        return proxy(DataSource.class, target, (method, args, result) ->
                result instanceof Connection c && method.getName().equals("getConnection") ? connection(c) : result, null);
    }

    private static Connection connection(Connection target) {
        return proxy(Connection.class, target, (method, args, result) -> {
            if (result instanceof CallableStatement s) {
                return statement(CallableStatement.class, s, (String) args[0]);
            }
            if (result instanceof PreparedStatement s) {
                return statement(PreparedStatement.class, s, (String) args[0]);
            }
            if (result instanceof Statement s) {
                return statement(Statement.class, s, null);
            }
            return result;
        }, (method) -> method.getName().equals("commit") ? Kind.COMMIT : null);
    }

    private static <T extends Statement> T statement(Class<T> type, T target, String preparedSql) {
        return proxy(type, target, (method, args, result) -> result, (method) -> null, preparedSql);
    }

    private interface ResultWrapper {
        Object wrap(Method method, Object[] args, Object result) throws Exception;
    }

    private interface Timed {
        Kind kindOf(Method method);
    }

    private static <T> T proxy(Class<T> type, T target, ResultWrapper wrapper, Timed timed) {
        return proxy(type, target, wrapper, timed, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T target, ResultWrapper wrapper, Timed timed, String preparedSql) {
        InvocationHandler handler = (p, method, args) -> {
            Kind kind = timed == null ? null : timed.kindOf(method);
            String sql = null;
            if (kind == null && Statement.class.isAssignableFrom(type) && method.getName().startsWith("execute")) {
                sql = args != null && args.length > 0 && args[0] instanceof String s ? s : preparedSql;
                kind = kindOf(sql);
            }
            String tableKey = kind == null || kind == Kind.COMMIT ? null : kind + " " + tableOf(sql);
            long start = kind == null ? 0 : System.nanoTime();
            try {
                Object result = method.invoke(target, args);
                return wrapper.wrap(method, args, result);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            } finally {
                if (kind != null) {
                    long elapsed = System.nanoTime() - start;
                    NANOS.get(kind).add(elapsed);
                    COUNT.get(kind).increment();
                    if (tableKey != null) {
                        LongAdder[] t = BY_TABLE.computeIfAbsent(tableKey, k -> new LongAdder[]{new LongAdder(), new LongAdder()});
                        t[0].increment();
                        t[1].add(elapsed);
                    }
                }
            }
        };
        return (T) Proxy.newProxyInstance(JdbcTimer.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static String tableOf(String sql) {
        if (sql == null) {
            return "?";
        }
        Matcher m = TABLE.matcher(sql.strip().toLowerCase(Locale.ROOT));
        if (!m.find()) {
            return "?";
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private static Kind kindOf(String sql) {
        if (sql == null) {
            return Kind.OTHER;
        }
        String s = sql.stripLeading().toLowerCase(Locale.ROOT);
        if (s.startsWith("select") || s.startsWith("with")) {
            return Kind.SELECT;
        }
        if (s.startsWith("insert")) {
            return Kind.INSERT;
        }
        if (s.startsWith("update")) {
            return Kind.UPDATE;
        }
        if (s.startsWith("delete")) {
            return Kind.DELETE;
        }
        return Kind.OTHER;
    }
}
