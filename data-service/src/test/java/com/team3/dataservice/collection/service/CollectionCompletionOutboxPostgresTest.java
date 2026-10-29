package com.team3.dataservice.collection.service;

import com.team3.dataservice.collection.domain.SyncJobStatus;
import com.team3.dataservice.collection.domain.entity.ExternalSource;
import com.team3.dataservice.collection.domain.entity.SyncHistory;
import com.team3.dataservice.collection.domain.repository.ExternalSourceRepository;
import com.team3.dataservice.collection.domain.repository.SyncHistoryRepository;
import com.team3.dataservice.outbox.domain.entity.CollectionOutboxStatus;
import com.team3.dataservice.outbox.domain.repository.CollectionOutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Executes the production DDL first, then real service transactions on PostgreSQL as application_user. */
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.grpc.server.port=0", "yon.normalization.condition-set-backfill.enabled=false",
        "spring.jpa.hibernate.ddl-auto=update", "spring.jpa.properties.hibernate.default_schema=application",
        "spring.datasource.hikari.connection-init-sql=SET search_path TO application, pg_catalog"})
class CollectionCompletionOutboxPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-26T14:50:00Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE application_user LOGIN PASSWORD 'test'; CREATE SCHEMA application AUTHORIZATION application_user");
            statement.execute("SET ROLE application_user");
            var ddl = new ClassPathResource("db-boundary/collection-outbox.sql").getContentAsString(StandardCharsets.UTF_8);
            statement.execute(ddl);
        }
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "application_user");
        properties.add("spring.datasource.password", () -> "test");
        properties.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired SyncJobService jobs;
    @Autowired CollectionCompletionOutbox completion;
    @Autowired ExternalSourceRepository sources;
    @Autowired SyncHistoryRepository histories;
    @Autowired CollectionOutboxEventRepository outboxes;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean ManualSyncWorker worker;

    @BeforeEach
    void clean() {
        db.execute("TRUNCATE collection_outbox_events, sync_history, external_source RESTART IDENTITY CASCADE");
    }

    @Test
    void commitsScheduledCompletionAndPendingEnvelopeTogether() {
        var history = history("EMPLOYMENT24", "SCHEDULED", START);
        jobs.complete(history.getId(), true);
        assertThat(histories.findById(history.getId()).orElseThrow().getStatus()).isEqualTo(SyncJobStatus.SUCCESS);
        var event = outboxes.findAll().getFirst();
        assertThat(event.getSyncJobId()).isEqualTo(history.getId());
        assertThat(event.getDedupKey()).isEqualTo("COLLECTION_COMPLETED:" + history.getId());
        assertThat(event.getStatus()).isEqualTo(CollectionOutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getLeaseToken()).isNull();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPayload().get("messageId")).isEqualTo(event.getMessageId().toString());
        assertThat(event.getPayload().get("occurredAt")).isEqualTo(
                histories.findById(history.getId()).orElseThrow().getFinishedAt().toInstant().toString());
    }

    @Test
    void sameDayFailedCollectionAndSuccessfulRetryHaveSeparateImmutableMessages() {
        var first = history("YOUTH_POLICY", "SCHEDULED", START);
        jobs.fail(first.getId(), "upstream failed", false);
        var original = outboxes.findAll().getFirst();
        var second = jobs.createRetry(first.getId());
        jobs.start(second.jobId());
        jobs.complete(second.jobId(), true);
        var kept = outboxes.findById(original.getId()).orElseThrow();
        assertThat(outboxes.count()).isEqualTo(2);
        assertThat(kept.getMessageId()).isEqualTo(original.getMessageId());
        assertThat(kept.getPayload()).isEqualTo(original.getPayload());
        assertThat(kept.getSyncJobId()).isEqualTo(first.getId());
        assertThat(histories.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(SyncJobStatus.FAILED);
        var retry = histories.findById(second.jobId()).orElseThrow();
        assertThat(retry.getStatus()).isEqualTo(SyncJobStatus.SUCCESS);
        assertThat(retry.getSyncType()).isEqualTo("MANUAL_RETRY");
        assertThat(outboxes.findAll()).extracting(event -> event.getMessageId()).doesNotHaveDuplicates();
    }

    @Test
    void concurrentCallbacksForOneJobCommitOneMessage() throws Exception {
        var first = history("YOUTH_POLICY", "SCHEDULED", START);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(first, first).stream().map(job -> executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                jobs.fail(job.getId(), "upstream failed", false);
                return job.getId();
            })).toList();
            for (var result : results) {
                assertThat(histories.findById(result.get(20, TimeUnit.SECONDS)).orElseThrow().getStatus())
                        .isEqualTo(SyncJobStatus.FAILED);
            }
        }
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @Test
    void separatesSourcesAndKoreanCollectionDates() {
        for (var job : List.of(history("YOUTH_POLICY", "SCHEDULED", START),
                history("EMPLOYMENT24", "SCHEDULED", START),
                history("EMPLOYMENT24", "SCHEDULED", START.withHour(15).withMinute(0)))) {
            jobs.complete(job.getId(), true);
        }
        assertThat(outboxes.count()).isEqualTo(3);
        assertThat(outboxes.findAll()).extracting(event -> event.getDedupKey()).doesNotHaveDuplicates();
    }

    @Test
    void manualInterruptedAndStartupRecoveryEachRecordTheirFinalResult() {
        var manual = history("YOUTH_POLICY", "MANUAL", START);
        jobs.complete(manual.getId(), true);
        var interrupted = history("YOUTH_POLICY", "SCHEDULED", START);
        jobs.fail(interrupted.getId(), "shutdown", true);
        var recoverable = history("EMPLOYMENT24", "SCHEDULED", START.minusYears(1));
        jobs.recoverInterrupted();
        assertThat(histories.findById(recoverable.getId()).orElseThrow().getStatus()).isEqualTo(SyncJobStatus.INTERRUPTED);
        assertThat(outboxes.count()).isEqualTo(3);
        jobs.recoverInterrupted();
        assertThat(outboxes.count()).isEqualTo(3);
    }

    @Test
    void noOpFailureDoesNotBackfillAnAlreadyFinishedJob() {
        var old = history("YOUTH_POLICY", "SCHEDULED", START);
        old.finish(true, START.plusMinutes(1));
        histories.saveAndFlush(old);
        jobs.fail(old.getId(), "late callback", false);
        jobs.complete(old.getId(), true);
        assertThat(outboxes.count()).isZero();
        assertThat(histories.findById(old.getId()).orElseThrow().getStatus()).isEqualTo(SyncJobStatus.SUCCESS);
    }

    @Test
    void rollingBackCompletionRemovesTheMessageAndRestoresRunningStatus() {
        var history = history("YOUTH_POLICY", "SCHEDULED", START);
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            jobs.complete(history.getId(), true);
            assertThat(outboxes.count()).isEqualTo(1);
            transaction.setRollbackOnly();
        });
        assertThat(outboxes.count()).isZero();
        assertThat(histories.findById(history.getId()).orElseThrow().getStatus()).isEqualTo(SyncJobStatus.RUNNING);
    }

    @Test
    void outboxStorageFailureAlsoRollsBackCompletion() {
        var history = history("YOUTH_POLICY", "SCHEDULED", START);
        db.execute("ALTER TABLE collection_outbox_events ADD CONSTRAINT test_reject_outbox CHECK(sync_job_id < 0)");
        try {
            assertThatThrownBy(() -> jobs.complete(history.getId(), true)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(histories.findById(history.getId()).orElseThrow().getStatus()).isEqualTo(SyncJobStatus.RUNNING);
            assertThat(outboxes.count()).isZero();
        } finally {
            db.execute("ALTER TABLE collection_outbox_events DROP CONSTRAINT test_reject_outbox");
        }
    }

    @Test
    void recorderRequiresTheCallersTransaction() {
        var history = history("YOUTH_POLICY", "SCHEDULED", START);
        history.finish(true, START.plusMinutes(1));
        assertThatThrownBy(() -> completion.recordIfCompletion(history))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxes.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = SyncJobStatus.class, names = {"FAILED", "PARTIAL_SUCCESS", "INTERRUPTED"})
    void createsFreshRetryWithoutChangingPriorOutcomeAndRejectsOverlappingRuns(SyncJobStatus status) {
        var previous = history("YOUTH_POLICY", "MANUAL", START);
        if (status == SyncJobStatus.PARTIAL_SUCCESS) {
            previous.recordSuccess(com.team3.dataservice.collection.domain.RecordResultType.CREATED);
            previous.finish(false, START.plusMinutes(1));
        } else previous.fail("test", status == SyncJobStatus.INTERRUPTED, START.plusMinutes(1));
        histories.saveAndFlush(previous);
        var retry = jobs.createRetry(previous.getId());
        assertThat(retry.jobId()).isNotEqualTo(previous.getId());
        assertThat(histories.findById(retry.jobId()).orElseThrow().getSyncType()).isEqualTo("MANUAL_RETRY");
        assertThat(histories.findById(previous.getId()).orElseThrow().getStatus()).isEqualTo(status);
        assertThatThrownBy(() -> jobs.createRetry(previous.getId())).isInstanceOf(IllegalStateException.class);
        jobs.fail(retry.jobId(), "retry exhausted", false);
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @Test
    void rejectsSuccessfulActiveMissingAndInvalidRetryTargets() {
        var running = history("YOUTH_POLICY", "MANUAL", START);
        assertThatThrownBy(() -> jobs.createRetry(running.getId())).isInstanceOf(IllegalStateException.class);
        jobs.complete(running.getId(), true);
        assertThatThrownBy(() -> jobs.createRetry(running.getId())).isInstanceOf(IllegalStateException.class);
        var pending = jobs.create(com.team3.dataservice.collection.domain.CollectionSource.YOUTH_POLICY);
        assertThatThrownBy(() -> jobs.createRetry(pending.jobId())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> jobs.createRetry(99999L)).isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
        assertThatThrownBy(() -> jobs.createRetry(0L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void migrationPreservesLegacyDailyMessageAndDeduplicatesItsJob() throws Exception {
        var history = history("YOUTH_POLICY", "SCHEDULED", START);
        jobs.complete(history.getId(), true);
        db.update("UPDATE collection_outbox_events SET dedup_key='COLLECTION_COMPLETED:YOUTH_POLICY:2026-10-26'");
        var original = outboxes.findAll().getFirst();
        db.execute("ALTER TABLE collection_outbox_events DROP CONSTRAINT uk_collection_outbox_job");
        var sql = new ClassPathResource("db-boundary/collection-outbox-per-job.sql").getContentAsString(StandardCharsets.UTF_8);
        db.execute(sql);
        db.execute(sql);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                completion.recordIfCompletion(histories.findById(history.getId()).orElseThrow()));
        assertThat(outboxes.count()).isEqualTo(1);
        var kept = outboxes.findAll().getFirst();
        assertThat(kept.getPayload()).isEqualTo(original.getPayload());
        assertThat(kept.getMessageId()).isEqualTo(original.getMessageId());
        assertThat(kept.getDedupKey()).isEqualTo(original.getDedupKey());
    }

    private SyncHistory history(String sourceCode, String type, OffsetDateTime at) {
        var source = sources.findBySourceCode(sourceCode).orElseGet(() -> sources.save(
                new ExternalSource(sourceCode, sourceCode, "https://example.test", true, at)));
        return histories.saveAndFlush(new SyncHistory(source, type, at));
    }
}
