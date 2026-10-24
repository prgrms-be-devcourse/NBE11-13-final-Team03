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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 벤치마크용 JDBC 계측. DataSource를 감싸 Statement 실행(execute*)과 커밋을 종류별로 세고 걸린 시간을 더한다.
 * 측정 범위: 드라이버가 SQL을 보내고 응답을 받을 때까지 (네트워크·DB 실행 포함, ResultSet을 읽는 시간은 제외).
 * 의존성 추가 없이 JDK 동적 프록시로 만든다. 테스트 전용.
 */
final class JdbcTimer {

    enum Kind { SELECT, INSERT, UPDATE, DELETE, OTHER, COMMIT }

    private static final Map<Kind, LongAdder> COUNT = new EnumMap<>(Kind.class);
    private static final Map<Kind, LongAdder> NANOS = new EnumMap<>(Kind.class);

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
            if (kind == null && Statement.class.isAssignableFrom(type) && method.getName().startsWith("execute")) {
                String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : preparedSql;
                kind = kindOf(sql);
            }
            long start = kind == null ? 0 : System.nanoTime();
            try {
                Object result = method.invoke(target, args);
                return wrapper.wrap(method, args, result);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            } finally {
                if (kind != null) {
                    NANOS.get(kind).add(System.nanoTime() - start);
                    COUNT.get(kind).increment();
                }
            }
        };
        return (T) Proxy.newProxyInstance(JdbcTimer.class.getClassLoader(), new Class<?>[]{type}, handler);
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
