package com.team3.backend.global.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ReadReplicaRoutingDataSourceTest {

    private static final String SEARCH = "com.team3.backend.search.service.OpportunitySearchService.search";

    private final FakeDataSource primary = new FakeDataSource("primary", Failure.NONE);
    private final FakeDataSource replica = new FakeDataSource("replica", Failure.NONE);
    private final ReadReplicaRoutingDataSource routing =
            new ReadReplicaRoutingDataSource(primary, replica, ReadReplicaDataSourceConfig.DEFAULT_PREFIXES);

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionName(null);
    }

    @Test
    void 지정한_읽기_전용_조회는_복제본으로_간다() throws SQLException {
        transaction(SEARCH, true);

        assertThat(name(routing.getConnection())).isEqualTo("replica");
    }

    @Test
    void 쓰기_트랜잭션은_지정한_조회라도_primary로_간다() throws SQLException {
        transaction(SEARCH, false);

        assertThat(name(routing.getConnection())).isEqualTo("primary");
    }

    @Test
    void 지정하지_않은_읽기_전용_트랜잭션은_primary로_간다() throws SQLException {
        transaction("com.team3.backend.tracking.service.EventFanoutCoordinator.next", true);

        assertThat(name(routing.getConnection())).isEqualTo("primary");
    }

    @Test
    void 트랜잭션_밖_연결은_primary로_간다() throws SQLException {
        assertThat(name(routing.getConnection())).isEqualTo("primary");
    }

    @Test
    void 복제본_연결에_실패하면_primary로_대신_연결한다() throws SQLException {
        var down = new ReadReplicaRoutingDataSource(primary, new FakeDataSource("replica", Failure.SQL),
                ReadReplicaDataSourceConfig.DEFAULT_PREFIXES);
        transaction(SEARCH, true);

        assertThat(name(down.getConnection())).isEqualTo("primary");
    }

    @Test
    void 복제본_풀이_RuntimeException을_던져도_primary로_대신_연결한다() throws SQLException {
        // HikariCP는 풀을 처음 만들 때 연결이 안 되면 PoolInitializationException(RuntimeException)을 던진다
        var down = new ReadReplicaRoutingDataSource(primary, new FakeDataSource("replica", Failure.RUNTIME),
                ReadReplicaDataSourceConfig.DEFAULT_PREFIXES);
        transaction(SEARCH, true);

        assertThat(name(down.getConnection())).isEqualTo("primary");
    }

    @Test
    void 복제본_실패_뒤_대기_시간_동안은_복제본을_시도하지_않는다() throws SQLException {
        AtomicLong now = new AtomicLong(1_000);
        var flaky = new FakeDataSource("replica", Failure.SQL);
        var routing = new ReadReplicaRoutingDataSource(primary, flaky, ReadReplicaDataSourceConfig.DEFAULT_PREFIXES,
                Duration.ofSeconds(30), now::get);
        transaction(SEARCH, true);

        assertThat(name(routing.getConnection())).isEqualTo("primary");
        assertThat(flaky.attempts).isEqualTo(1);

        now.addAndGet(29_999);
        assertThat(name(routing.getConnection())).isEqualTo("primary");
        assertThat(flaky.attempts).isEqualTo(1);

        flaky.failure = Failure.NONE;
        now.addAndGet(1);
        assertThat(name(routing.getConnection())).isEqualTo("replica");
        assertThat(flaky.attempts).isEqualTo(2);
    }

    @Test
    void 지연_연결_프록시는_첫_사용_시점의_트랜잭션_정보로_고른다() throws SQLException {
        LazyConnectionDataSourceProxy proxy = new LazyConnectionDataSourceProxy(routing);
        // JPA는 트랜잭션 동기화 정보가 정해지기 전에 연결 핸들을 먼저 받는다
        // (프록시는 첫 getConnection 때 기본 연결 속성을 읽느라 트랜잭션 밖에서 primary를 한 번 쓴다)
        Connection handle = proxy.getConnection();
        int primaryBefore = primary.opened;
        transaction(SEARCH, true);

        assertThat(handle.getMetaData()).isNull();
        assertThat(replica.opened).isEqualTo(1);
        assertThat(primary.opened).isEqualTo(primaryBefore);
    }

    @Test
    void 추천_상세도_기본_대상이다() {
        List<String> prefixes = ReadReplicaDataSourceConfig.DEFAULT_PREFIXES;

        assertThat(prefixes).anyMatch("com.team3.backend.recommendation.service.RecommendationService.recommend"::startsWith);
        assertThat(prefixes).anyMatch("com.team3.backend.search.service.OpportunityDetailService.get"::startsWith);
    }

    private static void transaction(String name, boolean readOnly) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        TransactionSynchronizationManager.setCurrentTransactionName(name);
    }

    private static String name(Connection connection) {
        return connection.toString();
    }

    private enum Failure { NONE, SQL, RUNTIME }

    private static final class FakeDataSource extends AbstractDataSource {
        private final String name;
        private Failure failure;
        private int opened;
        private int attempts;

        private FakeDataSource(String name, Failure failure) {
            this.name = name;
            this.failure = failure;
        }

        @Override
        public Connection getConnection() throws SQLException {
            attempts++;
            switch (failure) {
                case SQL -> throw new SQLException("connection refused");
                case RUNTIME -> throw new IllegalStateException("pool initialization failed");
                case NONE -> { }
            }
            opened++;
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> name;
                        case "getAutoCommit" -> true;
                        case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                        case "isClosed" -> false;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }
}
