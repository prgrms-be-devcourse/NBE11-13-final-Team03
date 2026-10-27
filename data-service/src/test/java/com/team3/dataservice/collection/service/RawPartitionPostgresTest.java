package com.team3.dataservice.collection.service;

import com.team3.dataservice.DataServiceApplication;
import com.team3.dataservice.collection.domain.entity.ExternalSource;
import com.team3.dataservice.collection.domain.entity.SyncHistory;
import com.team3.dataservice.collection.domain.repository.ExternalSourceRepository;
import com.team3.dataservice.collection.domain.repository.SyncHistoryRepository;
import com.team3.dataservice.collection.dto.request.RawRecordSaveRequest;
import com.team3.dataservice.opportunity.ingest.RawRecordReader;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.*;

/** Real application_user ownership, Hibernate update, partitioned CRUD, rollback and re-application. */
@Testcontainers
class RawPartitionPostgresTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
    private final OffsetDateTime started = OffsetDateTime.parse("2026-10-27T12:00:00+09:00");

    @Test
    void migratesReadsWritesAndRollsBackWithoutLosingSnapshotsOrRequests() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql("database-boundaries.sql"));
            statement.execute("ALTER ROLE application_user PASSWORD 'partition-test'");
        }
        var db = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), "application_user", "partition-test"));
        long first;
        long youth;
        long employment;
        // Seed ordinary tables, then reproduce the request schema used by the previous version.
        try (var context = start()) {
            youth = source(context, "YOUTH_POLICY");
            employment = source(context, "EMPLOYMENT24");
            collect(context, youth, "v1", 1);
            collect(context, employment, "v1", 1); // Identical external IDs in different sources.
            first = db.queryForObject("SELECT min(id) FROM raw_external_record", Long.class);
        }
        db.execute("ALTER TABLE raw_normalization_request DROP COLUMN source_id");
        try (var input = getClass().getResourceAsStream("/db/scripts/normalization-notifications.sql")) {
            assertNotNull(input);
            db.execute("SET search_path TO application, pg_catalog; " + new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        db.execute(sql("raw-external-record-partition.sql"));
        assertEquals("p", db.queryForObject("SELECT relkind::text FROM pg_class WHERE oid='raw_external_record'::regclass", String.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM raw_normalization_request WHERE source_id IS NULL", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM raw_external_record_default", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='application' AND tablename LIKE 'raw_external_record%' AND tableowner<>'application_user'", Integer.class));
        assertEquals(2, db.queryForObject("SELECT count(DISTINCT tableoid) FROM raw_external_record", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid='raw_normalization_request'::regclass AND tgname='raw_normalization_request_notify' AND tgenabled='O'", Integer.class));

        // halt_on_error rejects incompatible DDL attempted by Hibernate on the migrated schema.
        try (var context = start()) {
            assertEquals(first, context.getBean(RawRecordService.class).get(first).rawRecordId());
            collect(context, youth, "v1", 2); // UNCHANGED updates last_seen_at through JPA.
            assertEquals(2, db.queryForObject("SELECT count(*) FROM raw_external_record", Integer.class));
            collect(context, youth, "v2", 3); // UPDATED inserts snapshot + composite-FK request.
            assertEquals(3, db.queryForObject("SELECT count(*) FROM raw_external_record", Integer.class));
            assertEquals(3, db.queryForObject("SELECT count(*) FROM raw_normalization_request", Integer.class));
            assertEquals(2, db.queryForObject("SELECT count(*) FROM raw_external_record_youth_policy", Integer.class));
            assertEquals(1, db.queryForObject("SELECT count(*) FROM raw_external_record_employment24", Integer.class));
            assertThrows(DataIntegrityViolationException.class, () -> db.update(
                    "UPDATE raw_normalization_request SET source_id=? WHERE raw_record_id=?", employment, first));
            assertThrows(DataIntegrityViolationException.class, () -> db.update(
                    "UPDATE raw_normalization_request SET previous_raw_record_id=(SELECT min(id) FROM raw_external_record WHERE source_id=?) WHERE event_type='UPDATED'", employment));
            var reader = context.getBean(RawRecordReader.class);
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.executeWithoutResult(status -> {
                var rows = reader.claimPending(10);
                assertEquals(3, rows.size());
                assertEquals(2, rows.stream().filter(row -> row.latest()).count());
                reader.markStatus(rows.stream().map(row -> row.requestId()).toList(), RawRecordReader.SUCCESS);
            });
            assertEquals(2, reader.findOutOfSync(0, 10).size());
            // Existing backend DateTrackingReadiness uses this ID-only join contract.
            assertEquals(0, db.queryForObject("SELECT count(*) FROM raw_normalization_request q JOIN raw_external_record r ON r.id=q.raw_record_id JOIN external_source s ON s.id=r.source_id WHERE s.source_code='YOUTH_POLICY' AND q.status='PENDING'", Integer.class));
        }
        var before = db.queryForList("SELECT * FROM raw_external_record ORDER BY id");
        var requests = db.queryForList("SELECT * FROM raw_normalization_request ORDER BY id");
        db.execute(sql("raw-external-record-partition-check.sql"));
        db.execute(sql("rollback-raw-external-record-partition.sql"));
        assertEquals("r", db.queryForObject("SELECT relkind::text FROM pg_class WHERE oid='raw_external_record'::regclass", String.class));
        assertEquals(before, db.queryForList("SELECT * FROM raw_external_record ORDER BY id"));
        assertEquals(requests, db.queryForList("SELECT * FROM raw_normalization_request ORDER BY id"));
        // Simulate old application writes, including a request that omits source_id, after rollback.
        db.update("INSERT INTO raw_external_record(source_id,sync_id,external_id,payload_hash,payload,first_seen_at,last_seen_at) SELECT source_id,sync_id,'legacy-after-rollback',payload_hash,payload,first_seen_at,last_seen_at FROM raw_external_record WHERE id=?", first);
        db.update("INSERT INTO raw_normalization_request(raw_record_id,event_type,status,created_at) SELECT id,'CREATED','PENDING',now() FROM raw_external_record WHERE external_id='legacy-after-rollback'");
        db.execute("DROP TABLE raw_external_record_p");
        db.execute(sql("raw-external-record-partition.sql"));
        try (var context = start()) {
            collect(context, employment, "v2", 4);
            assertEquals(5, db.queryForObject("SELECT count(*) FROM raw_external_record", Integer.class));
            assertEquals(5, db.queryForObject("SELECT count(DISTINCT id) FROM raw_external_record", Integer.class));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM raw_normalization_request WHERE source_id IS NULL", Integer.class));
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(DataServiceApplication.class).profiles("test", "database-boundary").run(
                "--DB_URL=" + postgres.getJdbcUrl(), "--APPLICATION_DB_PASSWORD=partition-test",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=application_user", "--spring.datasource.password=partition-test",
                "--spring.datasource.driver-class-name=org.postgresql.Driver", "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
                "--yon.normalization.condition-set-backfill.enabled=false", "--region-code-api.sync-on-startup=false",
                "--spring.main.banner-mode=off");
    }

    private long source(ConfigurableApplicationContext context, String code) {
        return context.getBean(ExternalSourceRepository.class).save(new ExternalSource(
                code, code, "https://example.test", true, started)).getId();
    }

    private void collect(ConfigurableApplicationContext context, long sourceId, String title, int minute) {
        var source = context.getBean(ExternalSourceRepository.class).findById(sourceId).orElseThrow();
        long job = context.getBean(SyncHistoryRepository.class).save(new SyncHistory(source, "MANUAL", started)).getId();
        var payload = JsonNodeFactory.instance.objectNode().put("title", title);
        context.getBean(RawRecordService.class).saveBatchForSync(List.of(new RawRecordSaveRequest(
                job, "shared-id", "ignored-client-hash", payload, started.plusMinutes(minute))));
    }

    private String sql(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/db-boundary/" + name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
