package com.team3.backend.global.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadReplicaDataSourceConfigTest {

    private static final String REPLICA_URL = "jdbc:postgresql://127.0.0.1:1/yon";

    @Test
    void 복제본_풀은_공통_hikari_설정_뒤에_전용_값을_덮어쓴다() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.username", "application_user")
                .withProperty("spring.datasource.hikari.connection-timeout", "30000")
                .withProperty("spring.datasource.hikari.read-only", "false")
                .withProperty("spring.datasource.hikari.initialization-fail-timeout", "1")
                .withProperty("spring.datasource.hikari.connection-init-sql", "SET search_path TO application, pg_catalog");

        try (HikariDataSource replica = new ReadReplicaDataSourceConfig().replicaDataSource(environment, REPLICA_URL, 2000)) {
            assertThat(replica.getConnectionTimeout()).isEqualTo(2000);
            assertThat(replica.isReadOnly()).isTrue();
            assertThat(replica.getInitializationFailTimeout()).isEqualTo(-1);
            assertThat(replica.getConnectionInitSql()).isEqualTo("SET search_path TO application, pg_catalog");
            assertThat(replica.getUsername()).isEqualTo("application_user");
            assertThat(replica.getPoolName()).isEqualTo("replica");
        }
    }

    @Test
    void 복제본이_꺼진_채_기동_검증을_하면_경고만_남기고_넘어간다() {
        assertThatCode(() -> ApplicationDatabaseBoundaryConfig.databaseBoundaryValidator()
                .postProcessAfterInitialization(new DownDataSource(), ReadReplicaDataSourceConfig.REPLICA_BEAN))
                .doesNotThrowAnyException();
    }

    @Test
    void primary가_꺼져_있으면_기동_검증은_그대로_실패한다() {
        assertThatThrownBy(() -> ApplicationDatabaseBoundaryConfig.databaseBoundaryValidator()
                .postProcessAfterInitialization(new DownDataSource(), "primaryDataSource"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot validate application database boundary");
    }

    private static final class DownDataSource extends AbstractDataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLTransientConnectionException("replica - Connection is not available");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }
}
