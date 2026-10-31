package com.team3.backend.behavior.benchmark;

import com.team3.backend.behavior.service.PreferenceBehaviorRecorder;
import com.team3.backend.behavior.service.UserBehaviorEventService;
import com.team3.backend.preference.service.PreferenceService;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 행동 이벤트 벤치마크의 현재 스레드에서 JDBC 실행(문장·시간), 커넥션 획득 대기·점유·commit 시간,
 * 저장·숨김·이벤트 빈의 메서드별 경과 시간을 기록한다. 운영 빈을 대체하지 않고 감싸기만 한다.
 * PreferenceBehaviorRecorder.record 안에서 실행된 문장·커넥션은 이벤트 기록 구간으로 표시한다.
 */
final class BehaviorBenchProbe {

    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();
    private static final Set<Class<?>> TIMED = Set.of(PreferenceService.class, PreferenceBehaviorRecorder.class,
            UserBehaviorEventService.class);

    private BehaviorBenchProbe() {
    }

    static void start(boolean captureParameters) {
        CURRENT.set(new Capture(captureParameters));
    }

    static Capture stop() {
        Capture capture = CURRENT.get();
        CURRENT.remove();
        return capture;
    }

    static final class Execution {
        final String sql;
        long nanos;
        boolean recorder;
        List<String> parameters;

        Execution(String sql) {
            this.sql = sql;
        }
    }

    static final class Lease {
        long waitNanos;
        long holdNanos;
        long commitNanos;
        boolean recorder;
    }

    static final class Capture {
        final boolean captureParameters;
        final List<Execution> executions = new ArrayList<>();
        final List<Lease> leases = new ArrayList<>();
        /** 메서드 이름 → {호출 수, 누적 나노초} */
        final Map<String, long[]> methods = new LinkedHashMap<>();
        int recorderDepth;

        Capture(boolean captureParameters) {
            this.captureParameters = captureParameters;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        static BeanPostProcessor behaviorBenchProbe() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource) {
                        return dataSource(dataSource);
                    }
                    if (TIMED.stream().anyMatch(type -> type.isInstance(bean))) {
                        return timed(bean);
                    }
                    return bean;
                }
            };
        }
    }

    private static Object timed(Object bean) {
        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) invocation -> {
            Capture capture = CURRENT.get();
            if (capture == null) {
                return invocation.proceed();
            }
            String name = invocation.getMethod().getDeclaringClass().getSimpleName() + "." + invocation.getMethod().getName();
            boolean recorder = invocation.getThis() instanceof PreferenceBehaviorRecorder;
            if (recorder) {
                capture.recorderDepth++;
            }
            long started = System.nanoTime();
            try {
                return invocation.proceed();
            } finally {
                long[] total = capture.methods.computeIfAbsent(name, ignored -> new long[2]);
                total[0]++;
                total[1] += System.nanoTime() - started;
                if (recorder) {
                    capture.recorderDepth--;
                }
            }
        });
        return factory.getProxy();
    }

    private static DataSource dataSource(DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (ignored, method, args) -> {
                    long started = System.nanoTime();
                    Object result = call(delegate, method, args);
                    Capture capture = CURRENT.get();
                    if (result instanceof Connection connection && method.getName().equals("getConnection") && capture != null) {
                        Lease lease = new Lease();
                        lease.waitNanos = System.nanoTime() - started;
                        lease.recorder = capture.recorderDepth > 0;
                        capture.leases.add(lease);
                        return connection(connection, capture, lease);
                    }
                    return result;
                });
    }

    /** 커넥션을 얻은 순간부터 close까지를 점유 시간으로 센다 */
    private static Connection connection(Connection delegate, Capture capture, Lease lease) {
        long acquired = System.nanoTime();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (ignored, method, args) -> {
                    String name = method.getName();
                    long started = System.nanoTime();
                    Object result;
                    try {
                        result = call(delegate, method, args);
                    } finally {
                        if (name.equals("commit")) {
                            lease.commitNanos += System.nanoTime() - started;
                        }
                        if (name.equals("close")) {
                            lease.holdNanos = System.nanoTime() - acquired;
                        }
                    }
                    if (result instanceof PreparedStatement statement && name.startsWith("prepareStatement")) {
                        return statement(statement, (String) args[0], capture);
                    }
                    return result;
                });
    }

    private static PreparedStatement statement(PreparedStatement delegate, String sql, Capture capture) {
        List<String> parameters = new ArrayList<>();
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (ignored, method, args) -> {
                    String name = method.getName();
                    if (capture.captureParameters && name.startsWith("set") && args != null && args.length >= 2
                            && args[0] instanceof Integer index) {
                        while (parameters.size() < index) {
                            parameters.add(null);
                        }
                        parameters.set(index - 1, String.valueOf(args[1]));
                    }
                    boolean execute = name.equals("execute") || name.equals("executeQuery") || name.equals("executeUpdate")
                            || name.equals("executeLargeUpdate") || name.equals("executeBatch");
                    if (!execute) {
                        return call(delegate, method, args);
                    }
                    Execution execution = new Execution(sql);
                    execution.recorder = capture.recorderDepth > 0;
                    execution.parameters = capture.captureParameters ? new ArrayList<>(parameters) : null;
                    capture.executions.add(execution);
                    long started = System.nanoTime();
                    try {
                        return call(delegate, method, args);
                    } finally {
                        execution.nanos = System.nanoTime() - started;
                    }
                });
    }

    private static Object call(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
