package com.team3.backend.tracking.service;

import org.junit.jupiter.api.Tag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * data-service 소유 테이블은 backend 엔티티로 만들지 않으므로, 읽는 컬럼만 가진 최소 테이블을 직접 만들어 실제 SQL을 검증한다.
 */
@Testcontainers
@Tag("postgres")
class DateTrackingReadinessTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
    private static final OffsetDateTime MORNING = OffsetDateTime.parse("2026-10-20T06:00:00+09:00");

    private JdbcTemplate jdbc;
    private DateTrackingReadiness readiness;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        readiness = new DateTrackingReadiness(dataSource);
        jdbc.execute("""
                CREATE TABLE external_source (id bigint PRIMARY KEY, source_code varchar(255) NOT NULL UNIQUE);
                CREATE TABLE sync_history (id bigserial PRIMARY KEY, source_id bigint NOT NULL,
                    status varchar(255) NOT NULL, started_at timestamptz NOT NULL);
                CREATE TABLE raw_external_record (id bigserial PRIMARY KEY, source_id bigint NOT NULL);
                CREATE TABLE raw_normalization_request (id bigserial PRIMARY KEY, raw_record_id bigint NOT NULL,
                    status varchar(32) NOT NULL, created_at timestamptz NOT NULL);
                INSERT INTO external_source VALUES (1, 'YOUTH_POLICY'), (2, 'EMPLOYMENT24');
                """);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TABLE IF EXISTS raw_normalization_request, raw_external_record, sync_history, external_source");
    }

    @Test
    void requiresSuccessfulYouthPolicyCollectionStartedThatDay() {
        assertThat(readiness.ready(DATE)).isFalse();
        sync(2, "SUCCESS", MORNING);
        assertThat(readiness.ready(DATE)).isFalse();
        sync(1, "SUCCESS", MORNING.minusDays(1));
        assertThat(readiness.ready(DATE)).isFalse();
        sync(1, "SUCCESS", MORNING);
        assertThat(readiness.ready(DATE)).isTrue();
    }

    @Test
    void latestFailureOrActiveCollectionBlocksReadiness() {
        sync(1, "SUCCESS", MORNING);
        sync(1, "FAILED", MORNING.plusHours(1));
        assertThat(readiness.ready(DATE)).isFalse();
        sync(1, "SUCCESS", MORNING.plusHours(2));
        assertThat(readiness.ready(DATE)).isTrue();
        sync(1, "RUNNING", MORNING.minusDays(3));
        assertThat(readiness.ready(DATE)).isFalse();
    }

    @Test
    void onlyPendingNormalizationBlocksReadiness() {
        sync(1, "SUCCESS", MORNING);
        request(1, "FAILED", MORNING);
        request(1, "SUCCESS", MORNING);
        request(2, "PENDING", MORNING);
        request(1, "PENDING", OffsetDateTime.parse("2026-10-21T00:00:00+09:00"));
        assertThat(readiness.ready(DATE)).isTrue();
        request(1, "PENDING", MORNING.minusDays(5));
        assertThat(readiness.ready(DATE)).isFalse();
    }

    @Test
    void missingTablesMeanNotReadyInsteadOfFailingTheRun() {
        tearDown();
        assertThat(readiness.ready(DATE)).isFalse();
    }

    private void sync(long sourceId, String status, OffsetDateTime startedAt) {
        jdbc.update("INSERT INTO sync_history (source_id, status, started_at) VALUES (?, ?, ?)",
                sourceId, status, startedAt);
    }

    private void request(long sourceId, String status, OffsetDateTime createdAt) {
        Long rawId = jdbc.queryForObject("INSERT INTO raw_external_record (source_id) VALUES (?) RETURNING id",
                Long.class, sourceId);
        jdbc.update("INSERT INTO raw_normalization_request (raw_record_id, status, created_at) VALUES (?, ?, ?)",
                rawId, status, createdAt);
    }
}
