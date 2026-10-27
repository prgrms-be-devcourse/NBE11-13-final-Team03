package com.team3.backend.global.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 읽기 전용 트랜잭션 중 지정한 조회(트랜잭션 이름 접두사)만 DB 읽기 복제본으로 보낸다. 그 밖의 모든 연결은 primary다.
 * <ul>
 *   <li>쓰기 트랜잭션, 트랜잭션 밖(Hibernate DDL·기동 검증 포함), 대상이 아닌 읽기 전용 트랜잭션은 primary</li>
 *   <li>복제본 연결을 못 얻으면 primary로 대신 연결한다 (복제본 장애가 사용자 조회 장애로 번지지 않게)</li>
 * </ul>
 * 트랜잭션의 읽기 전용 여부·이름은 연결을 실제로 얻는 시점에 정해져 있어야 하므로 LazyConnectionDataSourceProxy로 감싸서 쓴다
 * (JPA 트랜잭션 시작 시점에는 아직 동기화 정보가 없다).
 * 대상은 사용자 응답용 조회(검색·상세·추천)로 한정한다. 배치·추적 처리처럼 읽은 값으로 곧바로 쓰기를 결정하는 경로는
 * 복제 지연 때문에 어긋날 수 있어 대상에서 뺀다.
 */
public class ReadReplicaRoutingDataSource extends AbstractDataSource {

    private static final Logger log = LoggerFactory.getLogger(ReadReplicaRoutingDataSource.class);

    private final DataSource primary;
    private final DataSource replica;
    private final List<String> transactionPrefixes;

    public ReadReplicaRoutingDataSource(DataSource primary, DataSource replica, List<String> transactionPrefixes) {
        this.primary = primary;
        this.replica = replica;
        this.transactionPrefixes = List.copyOf(transactionPrefixes);
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (!routesToReplica()) {
            return primary.getConnection();
        }
        try {
            return replica.getConnection();
        } catch (SQLException exception) {
            log.warn("action=READ_REPLICA_CONNECT result=FALLBACK_PRIMARY reason={}", exception.getClass().getSimpleName());
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
