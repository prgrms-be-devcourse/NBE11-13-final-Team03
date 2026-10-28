package com.team3.backend.global.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Checks the real PostgreSQL session before Hibernate can access or change tables.
 * Keep in sync with notification-service NotificationDatabaseBoundaryConfig: only account/schema names differ,
 * and the checks array order must match the SELECT column order.
 * 예외(backend만): DB 읽기 복제본 풀(ReadReplicaDataSourceConfig.REPLICA_BEAN)은 연결 실패를 경고로만 남긴다.
 * 복제본이 꺼져 있어도 backend는 떠야 하고(조회는 primary로 대체), 같은 연결 검증은 primary·라우팅 빈에서 이미 한다.
 * 연결은 됐는데 경계 위반이면 primary와 똑같이 기동을 멈춘다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("database-boundary")
public class ApplicationDatabaseBoundaryConfig {

    private static final Logger log = LoggerFactory.getLogger(ApplicationDatabaseBoundaryConfig.class);

    @Bean
    static BeanPostProcessor databaseBoundaryValidator() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource) {
                    validate(dataSource, ReadReplicaDataSourceConfig.REPLICA_BEAN.equals(beanName));
                }
                return bean;
            }
        };
    }

    static void validate(DataSource dataSource, boolean replica) {
        String[] checks = {"login", "current schema", "search path", "notification schema exists",
                "other schema access", "public schema access", "elevated privileges", "unmigrated public tables",
                "cross-schema foreign keys"};
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT current_user = 'application_user',
                       current_schema() = 'application',
                       current_schemas(false) = ARRAY['application'::name, 'pg_catalog'::name],
                       to_regnamespace('notification') IS NOT NULL,
                       CASE WHEN to_regnamespace('notification') IS NULL THEN true
                            ELSE NOT has_schema_privilege(current_user, 'notification', 'USAGE,CREATE') END,
                       NOT has_schema_privilege(current_user, 'public', 'USAGE,CREATE'),
                       NOT EXISTS (
                           SELECT 1 FROM pg_roles WHERE rolname = current_user
                           AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
                       AND NOT EXISTS (
                           SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member
                           WHERE r.rolname = current_user)
                       AND NOT has_database_privilege(current_user, current_database(), 'CREATE'),
                       NOT EXISTS (
                           SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                           WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')),
                       NOT EXISTS (
                           SELECT 1 FROM pg_constraint fk
                           JOIN pg_class src ON src.oid = fk.conrelid
                           JOIN pg_class dst ON dst.oid = fk.confrelid
                           WHERE fk.contype = 'f' AND src.relnamespace <> dst.relnamespace
                           AND (src.relnamespace = to_regnamespace('application')
                                OR dst.relnamespace = to_regnamespace('application')))
                """); var result = statement.executeQuery()) {
            result.next();
            for (int column = 1; column <= checks.length; column++) {
                if (!result.getBoolean(column)) {
                    throw new IllegalStateException("Database boundary violation (application): "
                            + checks[column - 1] + ". Review provisioning and public migration before startup.");
                }
            }
        } catch (SQLException exception) {
            if (replica) {
                log.warn("action=READ_REPLICA_BOUNDARY_CHECK result=SKIPPED reason={}", exception.getClass().getSimpleName());
                return;
            }
            throw new IllegalStateException("Cannot validate application database boundary", exception);
        }
    }
}
