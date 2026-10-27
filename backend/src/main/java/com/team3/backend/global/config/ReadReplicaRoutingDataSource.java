package com.team3.backend.global.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 읽기 전용 트랜잭션 중 지정한 조회(트랜잭션 이름 접두사)만 DB 읽기 복제본으로 보낸다. 그 밖의 모든 연결은 primary다.
 * <ul>
 *   <li>쓰기 트랜잭션, 트랜잭션 밖(Hibernate DDL·기동 검증 포함), 대상이 아닌 읽기 전용 트랜잭션은 primary</li>
 *   <li>복제본 연결을 못 얻으면 primary로 대신 연결한다 (복제본 장애가 사용자 조회 장애로 번지지 않게).
 *       SQLException뿐 아니라 연결 풀이 던지는 RuntimeException(풀 초기화 실패 등)도 같이 대체한다</li>
 *   <li>실패한 뒤 failureCooldown 동안은 복제본을 시도하지 않고 바로 primary로 간다
 *       (복제본이 죽어 있는 동안 요청마다 연결 대기 시간이 붙지 않게, 경고 로그도 이 간격으로 한 번)</li>
 * </ul>
 * 트랜잭션의 읽기 전용 여부·이름은 연결을 실제로 얻는 시점에 정해져 있어야 하므로 LazyConnectionDataSourceProxy로 감싸서 쓴다
 * (JPA 트랜잭션 시작 시점에는 아직 동기화 정보가 없다).
 * 대상은 사용자 응답용 조회(검색·상세·추천)로 한정한다. 배치·추적 처리처럼 읽은 값으로 곧바로 쓰기를 결정하는 경로는
 * 복제 지연 때문에 어긋날 수 있어 대상에서 뺀다.
 */
public class ReadReplicaRoutingDataSource extends AbstractDataSource {

    static final Duration DEFAULT_FAILURE_COOLDOWN = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(ReadReplicaRoutingDataSource.class);

    private final DataSource primary;
    private final DataSource replica;
    private final List<String> transactionPrefixes;
    private final long failureCooldownMillis;
    private final LongSupplier clockMillis;
    private volatile long skipReplicaUntilMillis = Long.MIN_VALUE;

    public ReadReplicaRoutingDataSource(DataSource primary, DataSource replica, List<String> transactionPrefixes) {
        this(primary, replica, transactionPrefixes, DEFAULT_FAILURE_COOLDOWN, () -> System.nanoTime() / 1_000_000);
    }

    ReadReplicaRoutingDataSource(DataSource primary, DataSource replica, List<String> transactionPrefixes,
                                 Duration failureCooldown, LongSupplier clockMillis) {
        this.primary = primary;
        this.replica = replica;
        this.transactionPrefixes = List.copyOf(transactionPrefixes);
        this.failureCooldownMillis = failureCooldown.toMillis();
        this.clockMillis = clockMillis;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (!routesToReplica() || clockMillis.getAsLong() < skipReplicaUntilMillis) {
            return primary.getConnection();
        }
        try {
            return replica.getConnection();
        } catch (SQLException | RuntimeException exception) {
            skipReplicaUntilMillis = clockMillis.getAsLong() + failureCooldownMillis;
            log.warn("action=READ_REPLICA_CONNECT result=FALLBACK_PRIMARY reason={} cooldownMs={}",
                    exception.getClass().getSimpleName(), failureCooldownMillis);
            return primary.getConnection();
        }
    }

    @Override
    public Connection getConnection(String username, String password) {
        throw new UnsupportedOperationException("계정은 각 연결 풀 설정을 따른다");
    }

    boolean routesToReplica() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return false;
        }
        String name = TransactionSynchronizationManager.getCurrentTransactionName();
        return name != null && transactionPrefixes.stream().anyMatch(name::startsWith);
    }
}
