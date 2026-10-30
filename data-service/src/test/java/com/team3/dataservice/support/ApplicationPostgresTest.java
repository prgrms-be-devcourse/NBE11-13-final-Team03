package com.team3.dataservice.support;

import org.junit.jupiter.api.Tag;

import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A fresh container per concrete class; evict its context before another class restarts the container. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Tag("postgres")
public abstract class ApplicationPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = ApplicationPostgres.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        ApplicationPostgres.register(properties, POSTGRES);
    }
}
