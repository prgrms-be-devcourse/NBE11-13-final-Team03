package com.team3.backend.search.benchmark;

import com.team3.backend.eligibility.api.EligibilityProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** 상세 API 벤치마크의 현재 스레드에서 JDBC 실행 횟수와 execute 구간 시간을 기록한다. */
final class DetailSqlProbe {

    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();

    private DetailSqlProbe() {
    }

    static void start() {
        CURRENT.set(new Capture());
    }

    static void enterProvider() {
        Capture capture = CURRENT.get();
        if (capture != null) {
            capture.provider = true;
        }
    }

    static void leaveProvider() {
        Capture capture = CURRENT.get();
        if (capture != null) {
            capture.provider = false;
        }
    }

    static List<SqlExecution> stop() {
        Capture capture = CURRENT.get();
        CURRENT.remove();
        return capture == null ? List.of() : List.copyOf(capture.executions);
    }

    record SqlExecution(String sql, long nanos, boolean provider) {
    }

    private static final class Capture {
        private final List<SqlExecution> executions = new ArrayList<>();
        private boolean provider;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        static BeanPostProcessor detailSqlDataSourceProbe() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource) {
                        return proxy(DataSource.class, dataSource, null);
                    }
                    if (bean instanceof EligibilityProvider provider) {
                        return providerSection(provider);
                    }
                    return bean;
                }
            };
        }
    }

    /** 메서드 이름과 무관하게 Provider 호출 전체를 Provider 구간으로 표시해 개선 전후 같은 기준으로 센다. */
    private static EligibilityProvider providerSection(EligibilityProvider delegate) {
        return (EligibilityProvider) Proxy.newProxyInstance(EligibilityProvider.class.getClassLoader(),
                new Class<?>[]{EligibilityProvider.class}, (ignored, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(delegate, args);
                    }
                    enterProvider();
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    } finally {
                        leaveProvider();
                    }
                });
    }

    private static <T> T proxy(Class<T> type, Object delegate, String sql) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, args) -> invoke(delegate, method, args, sql)));
    }

    private static Object invoke(Object delegate, Method method, Object[] args, String sql) throws Throwable {
        String name = method.getName();
        boolean execute = sql != null && (name.equals("execute") || name.equals("executeQuery")
                || name.equals("executeUpdate") || name.equals("executeLargeUpdate") || name.equals("executeBatch"));
        long started = execute && CURRENT.get() != null ? System.nanoTime() : 0;
        try {
            Object result = method.invoke(delegate, args);
            if (result instanceof Connection connection && name.equals("getConnection")) {
                return proxy(Connection.class, connection, null);
            }
            if (result instanceof PreparedStatement statement && name.startsWith("prepareStatement")) {
                return proxy(PreparedStatement.class, statement, (String) args[0]);
            }
            if (result instanceof Statement statement && name.equals("createStatement")) {
                return proxy(Statement.class, statement, "<statement>");
            }
            return result;
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        } finally {
            Capture capture = CURRENT.get();
            if (started != 0 && capture != null) {
                capture.executions.add(new SqlExecution(sql, System.nanoTime() - started, capture.provider));
            }
        }
    }
}
