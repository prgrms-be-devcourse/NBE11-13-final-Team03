package com.team3.backend.recommendation.benchmark;

import com.team3.backend.eligibility.api.EligibilityProvider;
import com.team3.backend.eligibility.service.EligibilityProfileService;
import com.team3.backend.interest.service.UserInterestService;
import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.recommendation.service.RecommendationCandidateService;
import com.team3.backend.recommendation.service.RecommendationEligibilityService;
import com.team3.backend.recommendation.service.RecommendationInterestMatcher;
import com.team3.backend.recommendation.service.RecommendationReasonService;
import com.team3.backend.recommendation.service.RecommendationRegionService;
import com.team3.backend.recommendation.service.RecommendationSelectionService;
import com.team3.backend.recommendation.service.RecommendationService;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 추천 벤치마크의 현재 스레드에서 JDBC 실행(문장·시간·결과 행 수), 커넥션 점유 시간,
 * 추천 협력 빈의 메서드별 경과 시간을 기록한다. 운영 빈을 대체하지 않고 감싸기만 한다.
 */
final class RecoBenchProbe {

    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();

    /** 메서드 시간을 잴 빈 (추천 요청 경로의 협력 객체) */
    private static final Set<Class<?>> TIMED = Set.of(RecommendationService.class, RecommendationSelectionService.class,
            RecommendationCandidateService.class, RecommendationEligibilityService.class,
            RecommendationInterestMatcher.class, RecommendationRegionService.class, RecommendationReasonService.class,
            PreferenceService.class, EligibilityProfileService.class, UserInterestService.class,
            EligibilityProvider.class);

    private RecoBenchProbe() {
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
        int rows;
        List<String> parameters;

        Execution(String sql) {
            this.sql = sql;
        }
    }

    static final class Capture {
        final boolean captureParameters;
        final List<Execution> executions = new ArrayList<>();
        /** 메서드 이름 → {호출 수, 누적 나노초} (중첩 호출은 각자 포함 시간으로 기록) */
        final Map<String, long[]> methods = new LinkedHashMap<>();
        long connectionNanos;
        int connections;

        Capture(boolean captureParameters) {
            this.captureParameters = captureParameters;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        static BeanPostProcessor recoBenchProbe() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource) {
                        return jdbc(DataSource.class, dataSource, null);
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
            long started = System.nanoTime();
            try {
                return invocation.proceed();
            } finally {
                long[] total = capture.methods.computeIfAbsent(name, ignored -> new long[2]);
                total[0]++;
                total[1] += System.nanoTime() - started;
            }
        });
        return factory.getProxy();
    }

    private static <T> T jdbc(Class<T> type, Object delegate, Execution execution) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, args) -> invoke(type, delegate, method, args, execution)));
    }

    private static Object invoke(Class<?> type, Object delegate, Method method, Object[] args, Execution template)
            throws Throwable {
        String name = method.getName();
        Capture capture = CURRENT.get();
        if (type == ResultSet.class && name.equals("next")) {
            Object hasNext = call(delegate, method, args);
            if (Boolean.TRUE.equals(hasNext) && template != null) {
                template.rows++;
            }
            return hasNext;
        }
        if (type == PreparedStatement.class && capture != null && capture.captureParameters
                && name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) {
            List<String> parameters = template.parameters == null ? (template.parameters = new ArrayList<>())
                    : template.parameters;
            while (parameters.size() < index) {
                parameters.add(null);
            }
            parameters.set(index - 1, String.valueOf(args[1]));
        }
        boolean execute = type == PreparedStatement.class && (name.equals("execute") || name.equals("executeQuery")
                || name.equals("executeUpdate") || name.equals("executeLargeUpdate") || name.equals("executeBatch"));
        Execution recorded = null;
        if (execute && capture != null) {
            recorded = new Execution(template.sql);
            recorded.parameters = template.parameters == null ? null : List.copyOf(template.parameters);
            capture.executions.add(recorded);
        }
        long started = System.nanoTime();
        Object result;
        try {
            result = call(delegate, method, args);
        } finally {
            if (recorded != null) {
                recorded.nanos = System.nanoTime() - started;
            }
        }
        if (type == DataSource.class && result instanceof Connection connection && name.equals("getConnection")) {
            return connection(connection, capture);
        }
        if (result instanceof PreparedStatement statement && name.startsWith("prepareStatement")) {
            return jdbc(PreparedStatement.class, statement, new Execution((String) args[0]));
        }
        if (result instanceof Statement statement && name.equals("createStatement")) {
            return jdbc(Statement.class, statement, new Execution("<statement>"));
        }
        if (result instanceof ResultSet resultSet && recorded != null) {
            return jdbc(ResultSet.class, resultSet, recorded);
        }
        if (result instanceof ResultSet resultSet && type == PreparedStatement.class && name.equals("getResultSet")
                && capture != null && !capture.executions.isEmpty()) {
            return jdbc(ResultSet.class, resultSet, capture.executions.getLast());
        }
        return result;
    }

    /** 커넥션을 얻은 순간부터 close까지를 점유 시간으로 센다 */
    private static Connection connection(Connection delegate, Capture capture) {
        long acquired = System.nanoTime();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (ignored, method, args) -> {
                    if (method.getName().equals("close") && capture != null && capture == CURRENT.get()) {
                        capture.connectionNanos += System.nanoTime() - acquired;
                        capture.connections++;
                    }
                    return invoke(Connection.class, delegate, method, args, null);
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
