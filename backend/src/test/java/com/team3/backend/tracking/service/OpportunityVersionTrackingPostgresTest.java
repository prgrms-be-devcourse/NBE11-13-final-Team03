package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.tracking.domain.entity.*;
import com.team3.backend.tracking.domain.model.SnapshotComparison;
import com.team3.backend.tracking.domain.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static com.team3.backend.tracking.domain.entity.OpportunityVersionTrackingStatus.*;

/** Disposable PostgreSQL schema only. Exercises model persistence, not an automatic version-tracking Worker. */
@Testcontainers
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpportunityVersionTrackingPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("version_tracking_test");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired OpportunityChangeRepository changes;
    @Autowired OpportunityVersionTrackingRepository tracking;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @PersistenceContext EntityManager entityManager;
    private final OpportunitySnapshotComparator comparator = new OpportunitySnapshotComparator();

    @BeforeEach
    void resetDisposableDatabaseOnly() {
        jdbc.execute("TRUNCATE opportunity_changes, opportunity_version_tracking, opportunity_versions, opportunities RESTART IDENTITY CASCADE");
    }

    @Test
    void jsonbRoundTripPreservesPathsNullPresenceArraysAndNumericMeaning() {
        var before = new LinkedHashMap<String, Object>();
        before.put("age", Map.of("maxAge", 34));
        before.put("applicationPeriods", List.of(Map.of("endDate", "2026-10-26")));
        var after = new LinkedHashMap<String, Object>();
        after.put("age", Map.of("maxAge", 39));
        after.put("applicationPeriods", List.of(Map.of("endDate", "2026-11-01")));
        after.put("income", null);
        var versions = versions(before, "v1", after, "v1");
        var result = comparison(versions);
        inTransaction(() -> {
            changes.saveAndFlush(OpportunityChange.record(versions.from(), versions.to(), result, NOW));
            tracking.saveAndFlush(OpportunityVersionTracking.compared(versions.from(), versions.to(), result, NOW));
            return null;
        });

        // A fresh repository transaction must deserialize JSONB into SnapshotDifference records.
        var loaded = changes.findByFromVersionIdAndToVersionId(versions.from().getId(), versions.to().getId()).orElseThrow();
        assertThat(loaded.getDifferences()).extracting(change -> change.path())
                .containsExactly("/age/maxAge", "/applicationPeriods", "/income");
        var age = loaded.getDifferences().getFirst();
        assertThat(new BigDecimal(age.before().toString())).isEqualByComparingTo("34");
        assertThat(new BigDecimal(age.after().toString())).isEqualByComparingTo("39");
        var period = loaded.getDifferences().get(1);
        assertThat(((Map<?, ?>) ((List<?>) period.after()).getFirst()).get("endDate")).isEqualTo("2026-11-01");
        var income = loaded.getDifferences().getLast();
        assertThat(income.beforePresent()).isFalse(); assertThat(income.before()).isNull();
        assertThat(income.afterPresent()).isTrue(); assertThat(income.after()).isNull();
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(differences) FROM opportunity_changes", String.class)).isEqualTo("array");
        var pending = tracking.findByOpportunityVersionId(versions.to().getId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(READY_FOR_EVENT_ANALYSIS);
        assertThat(pending.getProcessedAt()).isNull();
    }

    @Test
    void versionHasOnlyOneTrackingRecordRegardlessOfOutcome() {
        var versions = versions(Map.of(), "v1", Map.of(), "v1");
        tracking.saveAndFlush(OpportunityVersionTracking.baseline(versions.to(), NOW));
        assertThatThrownBy(() -> tracking.saveAndFlush(OpportunityVersionTracking.compared(
                versions.from(), versions.to(), comparison(versions), NOW))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(tracking.count()).isEqualTo(1);
        assertThat(tracking.findByOpportunityVersionId(versions.to().getId()).orElseThrow().getStatus()).isEqualTo(BASELINE_SKIPPED);
    }

    @Test
    void sameVersionPairCannotCreateTwoChangeRows() {
        var versions = changedVersions(); var result = comparison(versions);
        changes.saveAndFlush(OpportunityChange.record(versions.from(), versions.to(), result, NOW));
        assertThatThrownBy(() -> changes.saveAndFlush(OpportunityChange.record(versions.from(), versions.to(), result, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(changes.count()).isEqualTo(1);
    }

    @Test
    void trackingInsertFailureRollsBackChangeRowInSameTransaction() {
        var versions = changedVersions(); var result = comparison(versions);
        jdbc.execute("ALTER TABLE opportunity_version_tracking ADD CONSTRAINT reject_version_tracking CHECK (status <> 'READY_FOR_EVENT_ANALYSIS')");
        try {
            assertThatThrownBy(() -> inTransaction(() -> {
                changes.saveAndFlush(OpportunityChange.record(versions.from(), versions.to(), result, NOW));
                tracking.saveAndFlush(OpportunityVersionTracking.compared(versions.from(), versions.to(), result, NOW));
                return null;
            })).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE opportunity_version_tracking DROP CONSTRAINT reject_version_tracking");
        }
        assertThat(changes.count()).isZero(); assertThat(tracking.count()).isZero();
    }

    @Test
    void duplicateChangeRollsBackNewTrackingRecordButKeepsPreviouslyCommittedHistory() {
        var versions = changedVersions(); var result = comparison(versions);
        changes.saveAndFlush(OpportunityChange.record(versions.from(), versions.to(), result, NOW));
        assertThatThrownBy(() -> inTransaction(() -> {
            tracking.saveAndFlush(OpportunityVersionTracking.compared(versions.from(), versions.to(), result, NOW));
            changes.saveAndFlush(OpportunityChange.record(versions.from(), versions.to(), result, NOW));
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(changes.count()).isEqualTo(1); assertThat(tracking.count()).isZero();
    }

    @Test
    void baselineNoChangeAndRuleSuppressionPersistAsTerminalWithoutCreatingEvents() {
        var same = versions(Map.of("title", "동일"), "v1", Map.of("title", "동일"), "v1");
        tracking.saveAndFlush(OpportunityVersionTracking.baseline(same.from(), NOW));
        tracking.saveAndFlush(OpportunityVersionTracking.compared(same.from(), same.to(), comparison(same), NOW));
        var rule = versions(Map.of("age", 34), "v1", Map.of("age", 39), "v2");
        var result = comparison(rule);
        tracking.saveAndFlush(OpportunityVersionTracking.compared(rule.from(), rule.to(), result, NOW));
        changes.saveAndFlush(OpportunityChange.record(rule.from(), rule.to(), result, NOW));
        assertThat(tracking.findByOpportunityVersionId(same.from().getId()).orElseThrow().getStatus()).isEqualTo(BASELINE_SKIPPED);
        assertThat(tracking.findByOpportunityVersionId(same.to().getId()).orElseThrow().getStatus()).isEqualTo(NO_CHANGE);
        assertThat(tracking.findByOpportunityVersionId(rule.to().getId()).orElseThrow().getStatus()).isEqualTo(NORMALIZATION_VERSION_CHANGED);
        assertThat(tracking.findAll()).allSatisfy(record -> assertThat(record.getProcessedAt()).isEqualTo(NOW));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM opportunity_events", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isZero();
    }

    @Test
    void eventAnalysisCompletionPersistsOnlyWhenTransactionCommits() {
        var versions = changedVersions(); var result = comparison(versions);
        var record = tracking.saveAndFlush(OpportunityVersionTracking.compared(versions.from(), versions.to(), result, NOW));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            tracking.findById(record.getId()).orElseThrow().completeEventAnalysis(NOW.plusSeconds(1));
            tracking.flush();
            status.setRollbackOnly();
        });
        assertThat(tracking.findById(record.getId()).orElseThrow().getStatus()).isEqualTo(READY_FOR_EVENT_ANALYSIS);
        inTransaction(() -> {
            tracking.findById(record.getId()).orElseThrow().completeEventAnalysis(NOW.plusSeconds(2));
            return null;
        });
        var completed = tracking.findById(record.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PROCESSED);
        assertThat(completed.getProcessedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void concurrentDuplicateTransactionsCommitExactlyOneHistoryAndTrackingPair() throws Exception {
        var versions = changedVersions(); var result = comparison(versions);
        var start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test start timeout");
            try {
                inTransaction(() -> {
                    changes.saveAndFlush(OpportunityChange.record(versions.from(), versions.to(), result, NOW));
                    tracking.saveAndFlush(OpportunityVersionTracking.compared(versions.from(), versions.to(), result, NOW));
                    return null;
                });
                return true;
            } catch (DataIntegrityViolationException duplicate) {
                return false; // Failed transaction has ended; counts below use fresh transactions.
            }
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(attempt); var second = pool.submit(attempt);
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(changes.count()).isEqualTo(1); assertThat(tracking.count()).isEqualTo(1);
        assertThat(tracking.findByOpportunityVersionId(versions.to().getId()).orElseThrow().getStatus())
                .isEqualTo(READY_FOR_EVENT_ANALYSIS);
    }

    private Versions changedVersions() {
        return versions(Map.of("title", "이전"), "v1", Map.of("title", "변경"), "v1");
    }
    private Versions versions(Map<String, Object> before, String beforeRule, Map<String, Object> after, String afterRule) {
        return inTransaction(() -> {
            var opportunity = Opportunity.builder().opportunityType(OpportunityType.YOUTH_POLICY).title("버전 추적 검증").build();
            entityManager.persist(opportunity);
            var from = OpportunityVersion.builder().opportunity(opportunity).versionNo(1).normalizedSnapshot(before)
                    .normalizationVersion(beforeRule).personalizationStatus(PersonalizationStatus.PERSONALIZABLE).build();
            var to = OpportunityVersion.builder().opportunity(opportunity).versionNo(2).normalizedSnapshot(after)
                    .normalizationVersion(afterRule).personalizationStatus(PersonalizationStatus.PERSONALIZABLE).build();
            entityManager.persist(from); entityManager.persist(to); entityManager.flush();
            return new Versions(from, to);
        });
    }
    private SnapshotComparison comparison(Versions versions) {
        return comparator.compare(versions.from().getNormalizedSnapshot(), versions.from().getNormalizationVersion(),
                versions.to().getNormalizedSnapshot(), versions.to().getNormalizationVersion());
    }
    private <T> T inTransaction(Supplier<T> operation) {
        return new TransactionTemplate(transactions).execute(status -> operation.get());
    }
    private record Versions(OpportunityVersion from, OpportunityVersion to) { }
}
