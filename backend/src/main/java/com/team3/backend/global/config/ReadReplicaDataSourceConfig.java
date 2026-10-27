package com.team3.backend.global.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

/**
 * DB 읽기 복제본 연결. DB_REPLICA_URL이 있을 때만 켜지고, 없으면 Spring Boot 기본 DataSource(primary 하나)를 그대로 쓴다.
 * 두 연결 풀 모두 spring.datasource.* 계정과 spring.datasource.hikari.* 설정(search_path 등)을 같이 쓴다.
 * 복제본 풀은 연결 자체를 읽기 전용으로 열어, 라우팅이 잘못돼도 복제본에 쓰기가 가지 않게 한다.
 * 어떤 조회를 복제본으로 보낼지는 yon.datasource.replica.transaction-prefixes (트랜잭션 이름 = 클래스.메서드 전체 이름의 접두사).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${DB_REPLICA_URL:}' != ''")
public class ReadReplicaDataSourceConfig {

    /** 기동 검증(ApplicationDatabaseBoundaryConfig)이 복제본 풀을 구분하는 빈 이름 */
    static final String REPLICA_BEAN = "replicaDataSource";

    static final List<String> DEFAULT_PREFIXES = List.of(
            "com.team3.backend.search.service.OpportunitySearchService.",
            "com.team3.backend.search.service.OpportunityDetailService.",
            "com.team3.backend.recommendation.service.RecommendationService.");

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource primaryDataSource(Environment environment) {
        return pool(environment, environment.getRequiredProperty("spring.datasource.url"), "primary");
    }

    /**
     * 공통 hikari 설정을 먼저 바인딩한 뒤 복제본 전용 값을 덮어쓴다.
     * (@ConfigurationProperties는 빈 메서드 뒤에 바인딩되어, yaml에 connection-timeout·read-only가 생기면 아래 값을 덮어쓰게 된다)
     */
    @Bean(REPLICA_BEAN)
    HikariDataSource replicaDataSource(Environment environment,
                                       @Value("${DB_REPLICA_URL}") String replicaUrl,
                                       @Value("${yon.datasource.replica.connection-timeout-ms:2000}") long connectionTimeoutMs) {
        HikariDataSource replica = pool(environment, replicaUrl, "replica");
        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(replica));
        replica.setPoolName("replica");
        replica.setReadOnly(true);
        // 복제본이 죽었을 때 primary로 넘어가기까지 사용자 요청이 오래 기다리지 않게 짧게 둔다
        replica.setConnectionTimeout(connectionTimeoutMs);
        // 풀을 만들 때 연결을 시도하지 않는다. 복제본이 꺼진 채 첫 조회가 와도 PoolInitializationException(RuntimeException) 대신
        // 연결 실패(SQLException)로 나와 primary로 넘어간다
        replica.setInitializationFailTimeout(-1);
        return replica;
    }

    @Bean
    @Primary
    DataSource dataSource(@Qualifier("primaryDataSource") DataSource primary,
                          @Qualifier(REPLICA_BEAN) DataSource replica,
                          @Value("${yon.datasource.replica.transaction-prefixes:}") List<String> prefixes,
                          @Value("${yon.datasource.replica.failure-cooldown-ms:30000}") long failureCooldownMs) {
        List<String> routed = prefixes.stream().filter(prefix -> !prefix.isBlank()).toList();
        return new LazyConnectionDataSourceProxy(new ReadReplicaRoutingDataSource(primary, replica,
                routed.isEmpty() ? DEFAULT_PREFIXES : routed, Duration.ofMillis(failureCooldownMs),
                () -> System.nanoTime() / 1_000_000));
    }

    private static HikariDataSource pool(Environment environment, String url, String poolName) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(environment.getProperty("spring.datasource.username"));
        pool.setPassword(environment.getProperty("spring.datasource.password"));
        String driver = environment.getProperty("spring.datasource.driver-class-name");
        if (driver != null) {
            pool.setDriverClassName(driver);
        }
        pool.setPoolName(poolName);
        return pool;
    }
}
