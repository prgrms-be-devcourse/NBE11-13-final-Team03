package com.team3.backend.fixture;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Test-owned primary with the production account boundary and generated search title columns. */
public final class BackendPostgres {
    public static final String PASSWORD = "test-only-application-password";
    private BackendPostgres() { }

    public static PostgreSQLContainer create() {
        return new PostgreSQLContainer("postgres:16-alpine")
                .withCopyFileToContainer(MountableFile.forClasspathResource("db-boundary/database-boundaries.sql"),
                        "/docker-entrypoint-initdb.d/01-boundaries.sql")
                .withCopyFileToContainer(MountableFile.forClasspathResource("db-test/application-password.sql"),
                        "/docker-entrypoint-initdb.d/02-test-password.sql");
    }

    public static void register(DynamicPropertyRegistry registry, PostgreSQLContainer postgres) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "application_user");
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO application, pg_catalog");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "application");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.import_files", () -> "search-title-columns-hibernate-create.sql");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.halt_on_error", () -> "true");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.charset_name", () -> "UTF-8");
        // Real replication is covered separately; local fixtures must never route to an operator's replica.
        registry.add("DB_REPLICA_URL", () -> "");
    }
}
