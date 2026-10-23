package com.team3.notification.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Checks the real PostgreSQL session before Hibernate can access or change tables.
 * Keep in sync with backend ApplicationDatabaseBoundaryConfig: only account/schema names differ,
 * and the checks array order must match the SELECT column order.
 */
@Configuration(proxyBeanMethods = false)
@Profile("database-boundary")
public class NotificationDatabaseBoundaryConfig {

    @Bean
    static BeanPostProcessor databaseBoundaryValidator() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource) {
                    validate(dataSource);
                }
                return bean;
            }
        };
    }

    private static void validate(DataSource dataSource) {
        String[] checks = {"login", "current schema", "search path", "application schema exists",
                "other schema access", "public schema access", "elevated privileges", "unmigrated public tables",
                "cross-schema foreign keys"};
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT current_user = 'notification_user',
                       current_schema() = 'notification',
                       current_schemas(false) = ARRAY['notification'::name, 'pg_catalog'::name],
                       to_regnamespace('application') IS NOT NULL,
                       CASE WHEN to_regnamespace('application') IS NULL THEN true
                            ELSE NOT has_schema_privilege(current_user, 'application', 'USAGE,CREATE') END,
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
                           AND (src.relnamespace = to_regnamespace('notification')
                                OR dst.relnamespace = to_regnamespace('notification')))
                """); var result = statement.executeQuery()) {
            result.next();
            for (int column = 1; column <= checks.length; column++) {
                if (!result.getBoolean(column)) {
                    throw new IllegalStateException("Database boundary violation (notification): "
                            + checks[column - 1] + ". Review provisioning and public migration before startup.");
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot validate notification database boundary", exception);
        }
    }
}
