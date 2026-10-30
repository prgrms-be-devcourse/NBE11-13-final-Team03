package com.team3.dataservice.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Dedicated test DB only; production boundary SQL is executed by the image's native psql. */
public final class ApplicationPostgres {
    private ApplicationPostgres() { }

    public static PostgreSQLContainer create() {
        return new PostgreSQLContainer("postgres:16-alpine")
                .withCopyFileToContainer(MountableFile.forClasspathResource("db-boundary/database-boundaries.sql"),
                        "/docker-entrypoint-initdb.d/01-boundaries.sql")
                .withCopyFileToContainer(MountableFile.forClasspathResource("db-boundary/test-password.sql"),
                        "/docker-entrypoint-initdb.d/02-test-password.sql");
    }

    public static void applyCollectionOutbox(PostgreSQLContainer postgres) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "application_user", "test");
             var statement = connection.createStatement()) {
            statement.execute(new ClassPathResource("db-boundary/collection-outbox.sql")
                    .getContentAsString(StandardCharsets.UTF_8));
        }
    }

    public static void register(DynamicPropertyRegistry properties, PostgreSQLContainer postgres) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "application_user");
        properties.add("spring.datasource.password", () -> "test");
        properties.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> "application");
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO application, pg_catalog");
    }
}
