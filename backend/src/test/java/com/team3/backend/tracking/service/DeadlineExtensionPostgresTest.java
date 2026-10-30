package com.team3.backend.tracking.service;

import org.junit.jupiter.api.Tag;

import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.outbox.domain.entity.*;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import com.team3.backend.tracking.domain.entity.*;
import com.team3.backend.tracking.domain.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "yon.tracking.version-analysis.enabled=true"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({DeadlineExtensionTransaction.class, DeadlineExtensionDetector.class, OpportunityVersionComparisonTransaction.class,
        OpportunitySnapshotComparator.class, OpportunityVersionWorker.class, OpportunityVersionAnalysisTransaction.class,
        DeadlineExtensionPostgresTest.TimeConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("postgres")
class DeadlineExtensionPostgresTest {
    static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    static final LocalDate START = LocalDate.of(2026, 10, 1);
    static final LocalDate OLD_END = LocalDate.of(2026, 10, 26);
    static final LocalDate NEW_END = LocalDate.of(2026, 11, 1);
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean @Primary Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("deadline_extension_test");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
    @Autowired DeadlineExtensionTransaction service;
    @Autowired OpportunityVersionComparisonTransaction comparisons;
    @Autowired OpportunityVersionWorker worker;
    @Autowired OpportunityVersionAnalysisTransaction analysis;
    @Autowired OpportunityVersionAnalysisRepository analyses;
    @Autowired OpportunityChangeRepository changes;
    @Autowired OpportunityEventRepository events;
    @Autowired OpportunityVersionTrackingRepository tracking;
    @Autowired OutboxEventRepository outboxes;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void resetDisposableDatabase() {
        jdbc.execute("TRUNCATE outbox_events, opportunity_events, opportunity_changes, opportunity_version_tracking, application_windows, opportunity_versions, opportunities RESTART IDENTITY CASCADE");
    }

    @Test
    void createsOneEventAndCancelsOnlyMatchingPendingDeadlineRequests() {
        var pair = seed(true, "v1", null);
        var matching = pending(pair.policyId(), "DEADLINE_SOON", OLD_END.toString());
        var otherDate = pending(pair.policyId(), "DEADLINE_SOON", NEW_END.toString());
        var otherType = pending(pair.policyId(), "APPLICATION_OPENED", OLD_END.toString());
        var otherPolicy = pending(pair.policyId() + 999, "DEADLINE_SOON", OLD_END.toString());
        var publishing = pending(pair.policyId(), "DEADLINE_SOON", OLD_END.toString());
        var published = pending(pair.policyId(), "DEADLINE_SOON", OLD_END.toString());
        var failed = pending(pair.policyId(), "DEADLINE_SOON", OLD_END.toString());
        var skipped = pending(pair.policyId(), "DEADLINE_SOON", OLD_END.toString());
        var control = outboxes.saveAndFlush(OutboxEvent.userWithdrawn(7L, NOW)).getId();
        jdbc.update("UPDATE outbox_events SET status = 'PUBLISHING' WHERE id = ?", publishing);
        jdbc.update("UPDATE outbox_events SET status = 'PUBLISHED' WHERE id = ?", published);
        jdbc.update("UPDATE outbox_events SET status = 'FAILED' WHERE id = ?", failed);
        jdbc.update("UPDATE outbox_events SET status = 'SKIPPED' WHERE id = ?", skipped);
        var id = service.process(pair.fromId(), pair.toId()).orElseThrow();
        assertThat(events.findById(id).orElseThrow().getEventType()).isEqualTo(OpportunityEventType.DEADLINE_EXTENDED);
        assertThat(state(matching)).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE id = ?", String.class, matching))
                .isEqualTo("POLICY_DEADLINE_CHANGED");
        for (var unchanged : List.of(otherDate, otherType, otherPolicy)) assertThat(state(unchanged)).isEqualTo("PENDING");
        assertThat(state(publishing)).isEqualTo("PUBLISHING");
        assertThat(state(published)).isEqualTo("PUBLISHED");
        assertThat(state(failed)).isEqualTo("FAILED");
        assertThat(state(skipped)).isEqualTo("SKIPPED");
        assertThat(state(control)).isEqualTo("PENDING");
        assertThat(tracking.findByOpportunityVersionId(pair.toId())).isEmpty(); // Other analyses are not marked complete.
    }

    @Test
    void replayReturnsOriginalEventIdentity() {
        var pair = seed(true, "v1", null);
        var first = service.process(pair.fromId(), pair.toId());
        assertThat(service.process(pair.fromId(), pair.toId())).isEqualTo(first);
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void cancellationFailureRollsBackEventInsert() {
        var pair = seed(true, "v1", null);
        var row = pending(pair.policyId(), "DEADLINE_SOON", OLD_END.toString());
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT reject_extension_cancel CHECK (last_error IS DISTINCT FROM 'POLICY_DEADLINE_CHANGED')");
        try {
            assertThatThrownBy(() -> service.process(pair.fromId(), pair.toId())).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT reject_extension_cancel");
        }
        assertThat(events.count()).isZero();
        assertThat(state(row)).isEqualTo("PENDING");
    }

    @Test
    void unestablishedBaselineCannotProduceEventsOrCancelRequests() {
        var pair = seed(false, "v1", null);
        var row = pending(pair.policyId(), "DEADLINE_SOON", OLD_END.toString());
        assertThatThrownBy(() -> service.process(pair.fromId(), pair.toId())).isInstanceOf(IllegalStateException.class);
        assertThat(events.count()).isZero(); assertThat(state(row)).isEqualTo("PENDING");
    }

    @Test
    void normalizationRuleChangesAndScopedRoundsDoNotProduceExtensionEvents() {
        var changedRule = seed(true, "v2", null);
        assertThat(service.process(changedRule.fromId(), changedRule.toId())).isEmpty();
        var scoped = seed(true, "v1", Map.of("round", "2"));
        assertThat(service.process(scoped.fromId(), scoped.toId())).isEmpty();
        assertThat(events.count()).isZero();
    }

    @Test
    void targetBaselineAndSupersededTargetAreNotNotified() {
        var baseline = seed(true, "v1", null);
        tx(() -> { tracking.save(OpportunityVersionTracking.baseline(em.find(OpportunityVersion.class, baseline.toId()), NOW)); return null; });
        assertThat(service.process(baseline.fromId(), baseline.toId())).isEmpty();
        var superseded = seed(true, "v1", null);
        tx(() -> {
            var policy = em.find(Opportunity.class, superseded.policyId());
            var third = version(policy, 3, "v1"); em.persist(third); policy.changeCurrentVersion(third); return null;
        });
        assertThat(service.process(superseded.fromId(), superseded.toId())).isEmpty();
        assertThat(events.count()).isZero();
    }

    @Test
    void concurrentCallsCommitTheSameSingleEvent() throws Exception {
        var pair = seed(true, "v1", null);
        var start = new CountDownLatch(1);
        Callable<Optional<Long>> call = () -> { start.await(); return service.process(pair.fromId(), pair.toId()); };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS)).isPresent();
        }
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void comparisonCommitsHistoryTrackingExtensionAndCancellationTogether() {
        var pair = seed(true, "v1", null);
        var pendingId = pending(pair.policyId(), "DEADLINE_SOON", OLD_END.toString());
        var id = comparisons.compare(pair.toId()).orElseThrow();
        var record = tracking.findById(id).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(OpportunityVersionTrackingStatus.READY_FOR_EVENT_ANALYSIS);
        assertThat(record.getProcessedAt()).isNull();
        assertThat(changes.count()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
        assertThat(state(pendingId)).isEqualTo("SKIPPED");
        assertThat(comparisons.compare(pair.toId())).contains(id);
        assertThat(changes.count()).isEqualTo(1); assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void comparisonFailureRollsBackHistoryTrackingEventAndCancellation() {
        var pair = seed(true, "v1", null);
        var row = pending(pair.policyId(), "DEADLINE_SOON", OLD_END.toString());
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT reject_bundle_cancel CHECK (last_error IS DISTINCT FROM 'POLICY_DEADLINE_CHANGED')");
        try {
            assertThatThrownBy(() -> comparisons.compare(pair.toId())).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT reject_bundle_cancel");
        }
        assertThat(changes.count()).isZero(); assertThat(events.count()).isZero();
        assertThat(tracking.findByOpportunityVersionId(pair.toId())).isEmpty();
        assertThat(tracking.count()).isEqualTo(1); // Existing predecessor baseline survives.
        assertThat(state(row)).isEqualTo("PENDING");
    }

    @Test
    void unchangedSnapshotRecordsTerminalNoChangeWithoutAnEvent() {
        var pair = seed(true, "v1", null);
        jdbc.update("UPDATE opportunity_versions SET normalized_snapshot = (SELECT normalized_snapshot FROM opportunity_versions WHERE id = ?) WHERE id = ?",
                pair.fromId(), pair.toId());
        var id = comparisons.compare(pair.toId()).orElseThrow();
        var record = tracking.findById(id).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(OpportunityVersionTrackingStatus.NO_CHANGE);
        assertThat(record.getProcessedAt()).isEqualTo(NOW);
        assertThat(changes.count()).isZero(); assertThat(events.count()).isZero();
    }

    @Test
    void ruleChangeKeepsAuditButSuppressesExtensionAndCancellation() {
        var pair = seed(true, "v2", null);
        var row = pending(pair.policyId(), "DEADLINE_SOON", OLD_END.toString());
        var id = comparisons.compare(pair.toId()).orElseThrow();
        var record = tracking.findById(id).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(OpportunityVersionTrackingStatus.NORMALIZATION_VERSION_CHANGED);
        assertThat(record.getProcessedAt()).isEqualTo(NOW);
        assertThat(changes.count()).isEqualTo(1); assertThat(events.count()).isZero();
        assertThat(state(row)).isEqualTo("PENDING");
    }

    @Test
    void comparisonDoesNotInventABaselineOrAdvancePastPendingAnalysis() {
        var pair = seed(false, "v1", null);
        assertThatThrownBy(() -> comparisons.compare(pair.toId())).isInstanceOf(IllegalStateException.class);
        assertThat(tracking.count()).isZero(); assertThat(changes.count()).isZero();
        var ready = seed(true, "v1", null);
        comparisons.compare(ready.toId());
        Long thirdId = tx(() -> {
            var policy = em.find(Opportunity.class, ready.policyId());
            var third = version(policy, 3, "v1"); em.persist(third); policy.changeCurrentVersion(third); em.flush();
            return third.getId();
        });
        assertThatThrownBy(() -> comparisons.compare(thirdId)).isInstanceOf(IllegalStateException.class);
        assertThat(tracking.findByOpportunityVersionId(thirdId)).isEmpty();
    }

    @Test
    void comparisonPreservesExplicitBaselinesIncludingHistoricalTargets() {
        var pair = seed(true, "v1", null);
        Long baselineId = tx(() -> tracking.saveAndFlush(OpportunityVersionTracking.baseline(
                em.find(OpportunityVersion.class, pair.toId()), NOW)).getId());
        assertThat(comparisons.compare(pair.toId())).contains(baselineId);
        assertThat(tracking.findById(baselineId).orElseThrow().getStatus()).isEqualTo(OpportunityVersionTrackingStatus.BASELINE_SKIPPED);
        assertThat(comparisons.compare(pair.fromId())).contains(
                tracking.findByOpportunityVersionId(pair.fromId()).orElseThrow().getId());
        assertThat(changes.count()).isZero(); assertThat(events.count()).isZero();
    }

    @Test
    void concurrentComparisonsCreateOneHistoryRecordTrackingRecordAndEvent() throws Exception {
        var pair = seed(true, "v1", null);
        var start = new CountDownLatch(1);
        Callable<Optional<Long>> call = () -> { start.await(); return comparisons.compare(pair.toId()); };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS)).isPresent();
        }
        assertThat(changes.count()).isEqualTo(1); assertThat(events.count()).isEqualTo(1);
        assertThat(tracking.count()).isEqualTo(2); // Baseline plus target comparison.
    }

    @Test
    void ambiguousPeriodComparisonRemainsPendingWithoutEvents() {
        var pair = seed(true, "v1", Map.of("round", "unmatched"));
        var id = comparisons.compare(pair.toId()).orElseThrow();
        assertThat(tracking.findById(id).orElseThrow().getStatus()).isEqualTo(OpportunityVersionTrackingStatus.READY_FOR_EVENT_ANALYSIS);
        assertThat(changes.count()).isEqualTo(1); assertThat(events.count()).isZero();
    }

    @Test
    void sequentialStepCreatesOnlyFirstVersionBaselineWithoutEvents() {
        var pair = seed(false, "v1", null);
        var result = comparisons.compareNext(pair.policyId());
        assertThat(result.outcome()).isEqualTo(OpportunityVersionComparisonTransaction.Outcome.BASELINE_RECORDED);
        assertThat(result.versionId()).isEqualTo(pair.fromId());
        assertThat(tracking.count()).isEqualTo(1); assertThat(events.count()).isZero(); assertThat(changes.count()).isZero();
        assertThat(tracking.findById(result.trackingId()).orElseThrow().getStatus()).isEqualTo(OpportunityVersionTrackingStatus.BASELINE_SKIPPED);
        assertThat(comparisons.compareNext(pair.policyId()).versionId()).isEqualTo(pair.toId());
    }

    @Test
    void directFirstVersionBaselineIsIdempotentAndCreatesNoNotificationWork() {
        var pair=seed(false,"v1",null);
        var first=comparisons.compare(pair.fromId()).orElseThrow();
        assertThat(comparisons.compare(pair.fromId())).contains(first);
        assertThat(tracking.count()).isEqualTo(1); assertThat(changes.count()).isZero();
        assertThat(events.count()).isZero(); assertThat(outboxes.count()).isZero();
        assertThat(tracking.findById(first).orElseThrow().getProcessedAt()).isEqualTo(NOW);
    }

    @Test
    void missingHistoricalVersionsAreNotSilentlyReclassifiedAsFirstVersion() {
        var pair=seed(false,"v1",null);
        jdbc.update("UPDATE opportunity_versions SET version_no=version_no+4 WHERE opportunity_id=?",pair.policyId());
        assertThat(comparisons.compareNext(pair.policyId()).outcome())
                .isEqualTo(OpportunityVersionComparisonTransaction.Outcome.WAITING_FOR_BASELINE);
        assertThatThrownBy(()->comparisons.compare(pair.fromId())).isInstanceOf(IllegalStateException.class);
        assertThat(tracking.count()).isZero(); assertThat(events.count()).isZero();
    }

    @Test
    void concurrentFirstVersionBaselinesPersistOnlyOneRecord() throws Exception {
        var pair=seed(false,"v1",null); var gate=new CountDownLatch(1);
        Callable<Optional<Long>> operation=()->{gate.await();return comparisons.compare(pair.fromId());};
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=pool.submit(operation); var b=pool.submit(operation); gate.countDown();
            assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(b.get(20,TimeUnit.SECONDS));
        }
        assertThat(tracking.count()).isEqualTo(1); assertThat(events.count()).isZero();
    }

    @Test
    void failedBaselineWriteDoesNotMarkVersionAsProcessed() {
        var pair=seed(false,"v1",null);
        jdbc.execute("ALTER TABLE opportunity_version_tracking ADD CONSTRAINT reject_baseline CHECK(status<>'BASELINE_SKIPPED')");
        try { assertThatThrownBy(()->comparisons.compareNext(pair.policyId())).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE opportunity_version_tracking DROP CONSTRAINT reject_baseline"); }
        assertThat(tracking.count()).isZero();
        assertThat(comparisons.compareNext(pair.policyId()).outcome())
                .isEqualTo(OpportunityVersionComparisonTransaction.Outcome.BASELINE_RECORDED);
    }

    @Test
    void sequentialStepRecordsHistoricalChangeAndWaitsBeforeNextVersion() {
        var pair = seed(true, "v1", null);
        var third = appendThird(pair, "v1");
        var first = comparisons.compareNext(pair.policyId());
        assertThat(first.outcome()).isEqualTo(OpportunityVersionComparisonTransaction.Outcome.COMPARED);
        assertThat(first.versionId()).isEqualTo(pair.toId());
        var record = tracking.findById(first.trackingId()).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(OpportunityVersionTrackingStatus.READY_FOR_EVENT_ANALYSIS);
        assertThat(record.getProcessedAt()).isNull();
        var replay = comparisons.compareNext(pair.policyId());
        assertThat(replay.outcome()).isEqualTo(OpportunityVersionComparisonTransaction.Outcome.WAITING_FOR_EVENT_ANALYSIS);
        assertThat(replay.trackingId()).isEqualTo(first.trackingId());
        assertThat(tracking.findByOpportunityVersionId(third)).isEmpty();
        assertThat(changes.count()).isEqualTo(1);
        // Historical comparison preserves evidence but does not publish an obsolete extension.
        assertThat(events.count()).isZero(); assertThat(outboxes.count()).isZero();
    }

    @Test
    void sequentialStepAdvancesThroughRuleSuppressionThenNoChangeWithoutSkippingVersions() {
        var pair = seed(true, "v2", null);
        var third = appendThird(pair, "v2");
        var secondStep = comparisons.compareNext(pair.policyId());
        assertThat(secondStep.versionId()).isEqualTo(pair.toId());
        assertThat(tracking.findById(secondStep.trackingId()).orElseThrow().getStatus())
                .isEqualTo(OpportunityVersionTrackingStatus.NORMALIZATION_VERSION_CHANGED);
        var thirdStep = comparisons.compareNext(pair.policyId());
        assertThat(thirdStep.versionId()).isEqualTo(third);
        assertThat(tracking.findById(thirdStep.trackingId()).orElseThrow().getStatus())
                .isEqualTo(OpportunityVersionTrackingStatus.NO_CHANGE);
        assertThat(comparisons.compareNext(pair.policyId()).outcome())
                .isEqualTo(OpportunityVersionComparisonTransaction.Outcome.UP_TO_DATE);
        assertThat(tracking.count()).isEqualTo(3); assertThat(events.count()).isZero();
    }

    @Test
    void sequentialFailureRollsBackAndNextInvocationRediscoversSameVersion() {
        var pair = seed(true, "v1", null);
        appendThird(pair, "v1");
        jdbc.execute("ALTER TABLE opportunity_version_tracking ADD CONSTRAINT reject_sequential_step CHECK (status <> 'READY_FOR_EVENT_ANALYSIS')");
        try {
            assertThatThrownBy(() -> comparisons.compareNext(pair.policyId())).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE opportunity_version_tracking DROP CONSTRAINT reject_sequential_step");
        }
        assertThat(changes.count()).isZero(); assertThat(events.count()).isZero();
        assertThat(tracking.count()).isEqualTo(1);
        var recovered = comparisons.compareNext(pair.policyId());
        assertThat(recovered.versionId()).isEqualTo(pair.toId());
        assertThat(recovered.outcome()).isEqualTo(OpportunityVersionComparisonTransaction.Outcome.COMPARED);
        assertThat(changes.count()).isEqualTo(1);
    }

    @Test
    void concurrentSequentialStepsDoNotDuplicateOrAdvancePastPendingAnalysis() throws Exception {
        var pair = seed(true, "v1", null); var third = appendThird(pair, "v1");
        var start = new CountDownLatch(1);
        Callable<OpportunityVersionComparisonTransaction.Step> call = () -> {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test start timeout");
            return comparisons.compareNext(pair.policyId());
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results).extracting(OpportunityVersionComparisonTransaction.Step::outcome)
                    .containsExactlyInAnyOrder(OpportunityVersionComparisonTransaction.Outcome.COMPARED,
                            OpportunityVersionComparisonTransaction.Outcome.WAITING_FOR_EVENT_ANALYSIS);
            assertThat(results).extracting(OpportunityVersionComparisonTransaction.Step::versionId)
                    .containsOnly(pair.toId());
        }
        assertThat(tracking.count()).isEqualTo(2); assertThat(changes.count()).isEqualTo(1);
        assertThat(tracking.findByOpportunityVersionId(third)).isEmpty();
    }

    @Test
    void unfinishedVersionsBeyondCurrentPointerAreNotAnalyzed() {
        var pair = seed(true, "v2", null); var third = appendThird(pair, "v2");
        tx(() -> {
            em.find(Opportunity.class, pair.policyId()).changeCurrentVersion(em.find(OpportunityVersion.class, pair.toId()));
            return null;
        });
        comparisons.compareNext(pair.policyId());
        assertThat(comparisons.compareNext(pair.policyId()).outcome())
                .isEqualTo(OpportunityVersionComparisonTransaction.Outcome.UP_TO_DATE);
        assertThat(comparisons.compare(third)).isEmpty();
        assertThat(tracking.findByOpportunityVersionId(third)).isEmpty();
    }

    private Long appendThird(Pair pair, String rule) {
        return tx(() -> {
            var policy = em.find(Opportunity.class, pair.policyId());
            var third = version(policy, 3, rule); em.persist(third); policy.changeCurrentVersion(third); em.flush();
            return third.getId();
        });
    }

    @Test
    void realKeysetDiscoveryRecordsBaselineAndStillVisitsFollowingPolicies() {
        var waiting = seed(false, "v1", null);
        var ready = seed(true, "v2", null);
        var firstPage = worker.runOnce(0, 1);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.lastOpportunityId()).isEqualTo(waiting.policyId());
        assertThat(firstPage.attempts().getFirst().step().orElseThrow().outcome())
                .isEqualTo(OpportunityVersionComparisonTransaction.Outcome.BASELINE_RECORDED);
        var nextPage = worker.runOnce(firstPage.lastOpportunityId(), 1);
        assertThat(nextPage.hasNext()).isFalse();
        assertThat(nextPage.attempts().getFirst().step().orElseThrow().versionId()).isEqualTo(ready.toId());
        assertThat(tracking.findByOpportunityVersionId(waiting.fromId())).isPresent();
        assertThat(tracking.findByOpportunityVersionId(ready.toId())).isPresent();
    }

    @Test
    void nonexistentPolicyIsReportedWithoutCreatingRecords() {
        assertThat(comparisons.compareNext(Long.MAX_VALUE).outcome())
                .isEqualTo(OpportunityVersionComparisonTransaction.Outcome.POLICY_UNAVAILABLE);
        assertThat(tracking.count()).isZero();
    }

    @Test
    void analysisStoresEvidenceAndUserAssessmentEventBeforeCompletingVersion() {
        var pair = seed(true, "v1", null);
        comparisons.compare(pair.toId());
        var result = analysis.analyze(pair.toId());
        assertThat(result.outcome()).isEqualTo(OpportunityVersionAnalysisTransaction.Outcome.COMPLETED);
        var stored = analyses.findById(result.analysisId()).orElseThrow();
        assertThat(stored.getDeadlineOutcome()).isEqualTo(OpportunityVersionAnalysis.DeadlineOutcome.EXTENDED);
        assertThat(stored.getChangedPaths()).containsExactly("/applicationEndDate");
        assertThat(stored.getAnalysisVersion()).isEqualTo(OpportunityVersionAnalysisTransaction.ANALYSIS_VERSION);
        assertThat(stored.getCurrentVersionId()).isEqualTo(pair.toId());
        assertThat(stored.getCompletedAt()).isEqualTo(NOW);
        var task = events.findByDedupKey("ELIGIBILITY_CHANGED:" + pair.policyId() + ":" + pair.fromId() + ":" + pair.toId()).orElseThrow();
        assertThat(task.getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.PENDING);
        assertThat(task.getPayload()).containsEntry("analysisVersion", OpportunityVersionAnalysisTransaction.ANALYSIS_VERSION);
        assertThat(task.getOccurredAt()).isEqualTo(NOW);
        assertThat(tracking.findByOpportunityVersionId(pair.toId()).orElseThrow().getStatus())
                .isEqualTo(OpportunityVersionTrackingStatus.PROCESSED);
        assertThat(events.count()).isEqualTo(2); assertThat(outboxes.count()).isZero();
    }

    @Test
    void analysisReplayPreservesOriginalEvidenceAndEventIdentity() {
        var pair = seed(true, "v1", null); comparisons.compare(pair.toId());
        var first = analysis.analyze(pair.toId());
        var original = jdbc.queryForList("SELECT event_id FROM opportunity_events ORDER BY id");
        assertThat(analysis.analyze(pair.toId())).isEqualTo(first);
        assertThat(analyses.count()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_id FROM opportunity_events ORDER BY id")).isEqualTo(original);
    }

    @Test
    void scopedPeriodDeferralKeepsEvidenceButQueuesEligibilityAndAdvancesTheSequence() {
        var pair = seed(true, "v1", Map.of("round", "not-matched")); comparisons.compare(pair.toId());
        var result = analysis.analyze(pair.toId());
        assertThat(result.outcome()).isEqualTo(OpportunityVersionAnalysisTransaction.Outcome.DEADLINE_DEFERRED);
        var saved = analyses.findById(result.analysisId()).orElseThrow();
        assertThat(saved.getDeadlineOutcome()).isEqualTo(OpportunityVersionAnalysis.DeadlineOutcome.DEFERRED_SCOPED);
        assertThat(saved.getCompletedAt()).isNull();
        assertThat(analysis.analyze(pair.toId())).isEqualTo(result);
        assertThat(tracking.findByOpportunityVersionId(pair.toId()).orElseThrow().getStatus())
                .isEqualTo(OpportunityVersionTrackingStatus.PROCESSED_WITH_DEFERRED_DEADLINE);
        assertThat(events.findAll()).extracting(OpportunityEvent::getEventType).containsExactly(OpportunityEventType.ELIGIBILITY_CHANGED);
        var third = appendThird(pair, "v1");
        assertThat(comparisons.compareNext(pair.policyId()).versionId()).isEqualTo(third);
    }

    @Test
    void invalidChangedPeriodIsDeferredInsteadOfGuessed() {
        var pair = seed(true, "v1", null);
        jdbc.update("UPDATE application_windows SET validation_status='INVALID' WHERE version_id=?", pair.toId());
        comparisons.compare(pair.toId());
        var result = analysis.analyze(pair.toId());
        assertThat(result.outcome()).isEqualTo(OpportunityVersionAnalysisTransaction.Outcome.DEADLINE_DEFERRED);
        assertThat(analyses.findById(result.analysisId()).orElseThrow().getDeadlineOutcome())
                .isEqualTo(OpportunityVersionAnalysis.DeadlineOutcome.DEFERRED_PERIOD);
        assertThat(events.findAll()).extracting(OpportunityEvent::getEventType).containsExactly(OpportunityEventType.ELIGIBILITY_CHANGED);
    }

    @Test
    void historicalAnalysisCompletesAndUnblocksNextVersionWithoutObsoleteExtensionAlert() {
        var pair = seed(true, "v1", null); var third = appendThird(pair, "v1");
        comparisons.compareNext(pair.policyId());
        var result = analysis.analyze(pair.toId());
        assertThat(result.outcome()).isEqualTo(OpportunityVersionAnalysisTransaction.Outcome.COMPLETED);
        assertThat(analyses.findById(result.analysisId()).orElseThrow().getCurrentVersionId()).isEqualTo(third);
        assertThat(events.findAll()).extracting(OpportunityEvent::getEventType).containsExactly(OpportunityEventType.ELIGIBILITY_CHANGED);
        assertThat(comparisons.compareNext(pair.policyId()).versionId()).isEqualTo(third);
        assertThat(tracking.findByOpportunityVersionId(third).orElseThrow().getStatus()).isEqualTo(OpportunityVersionTrackingStatus.NO_CHANGE);
        assertThat(events.findAll().getFirst().getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.PENDING);
    }

    @Test
    void analysisInsertFailureRollsBackNewEventAndCompletionThenRetrySucceeds() {
        var pair = seed(true, "v1", null); appendThird(pair, "v1"); comparisons.compareNext(pair.policyId());
        jdbc.execute("ALTER TABLE opportunity_version_analysis ADD CONSTRAINT reject_analysis CHECK (completed_at IS NULL)");
        try {
            assertThatThrownBy(() -> analysis.analyze(pair.toId())).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE opportunity_version_analysis DROP CONSTRAINT reject_analysis");
        }
        assertThat(analyses.count()).isZero(); assertThat(events.count()).isZero();
        assertThat(tracking.findByOpportunityVersionId(pair.toId()).orElseThrow().getProcessedAt()).isNull();
        assertThat(analysis.analyze(pair.toId()).outcome()).isEqualTo(OpportunityVersionAnalysisTransaction.Outcome.COMPLETED);
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void concurrentAnalysesKeepOneAssessmentAndOneUserAssessmentEvent() throws Exception {
        var pair = seed(true, "v1", null); appendThird(pair, "v1"); comparisons.compareNext(pair.policyId());
        var start = new CountDownLatch(1);
        Callable<OpportunityVersionAnalysisTransaction.Result> call = () -> {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test start timeout");
            return analysis.analyze(pair.toId());
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(analyses.count()).isEqualTo(1); assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void analysisDoesNotOverwriteBaselineOrCreateComparisonImplicitly() {
        var pair = seed(true, "v1", null);
        assertThat(analysis.analyze(pair.fromId()).outcome()).isEqualTo(OpportunityVersionAnalysisTransaction.Outcome.NOT_READY);
        assertThat(analysis.analyze(pair.toId()).outcome()).isEqualTo(OpportunityVersionAnalysisTransaction.Outcome.NOT_READY);
        assertThat(analyses.count()).isZero(); assertThat(events.count()).isZero();
        assertThat(tracking.count()).isEqualTo(1);
    }

    @Test
    void unchangedScopedPeriodsDoNotCreateAFalsePeriodDeferral() {
        var pair = seed(true, "v1", Map.of("round", "same"));
        jdbc.update("UPDATE application_windows SET end_date=? WHERE version_id=?", java.sql.Date.valueOf(OLD_END), pair.toId());
        jdbc.update("UPDATE opportunity_versions SET normalized_snapshot=?::jsonb WHERE id=?", "{\"title\":\"before\"}", pair.fromId());
        jdbc.update("UPDATE opportunity_versions SET normalized_snapshot=?::jsonb WHERE id=?", "{\"title\":\"after\"}", pair.toId());
        comparisons.compare(pair.toId());
        var result = analysis.analyze(pair.toId());
        assertThat(result.outcome()).isEqualTo(OpportunityVersionAnalysisTransaction.Outcome.COMPLETED);
        assertThat(analyses.findById(result.analysisId()).orElseThrow().getDeadlineOutcome())
                .isEqualTo(OpportunityVersionAnalysis.DeadlineOutcome.UNCHANGED);
        assertThat(events.findAll()).extracting(OpportunityEvent::getEventType).containsExactly(OpportunityEventType.ELIGIBILITY_CHANGED);
    }

    @Test
    void completionFailureRollsBackAssessmentAndNewEventTogether() {
        var pair = seed(true, "v1", null); appendThird(pair, "v1"); comparisons.compareNext(pair.policyId());
        jdbc.execute("ALTER TABLE opportunity_version_tracking ADD CONSTRAINT reject_analysis_completion CHECK (status <> 'PROCESSED')");
        try {
            assertThatThrownBy(() -> analysis.analyze(pair.toId())).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE opportunity_version_tracking DROP CONSTRAINT reject_analysis_completion");
        }
        assertThat(analyses.count()).isZero(); assertThat(events.count()).isZero();
        assertThat(tracking.findByOpportunityVersionId(pair.toId()).orElseThrow().getStatus())
                .isEqualTo(OpportunityVersionTrackingStatus.READY_FOR_EVENT_ANALYSIS);
        assertThat(analysis.analyze(pair.toId()).outcome()).isEqualTo(OpportunityVersionAnalysisTransaction.Outcome.COMPLETED);
    }

    @Test
    void conflictingExistingEventDoesNotAllowVersionCompletion() {
        var pair = seed(true, "v1", null); appendThird(pair, "v1"); comparisons.compareNext(pair.policyId());
        tx(() -> events.saveAndFlush(OpportunityEvent.create(em.find(Opportunity.class, pair.policyId()),
                em.find(OpportunityVersion.class, pair.toId()), OpportunityEventType.ELIGIBILITY_CHANGED,
                LocalDate.of(2026, 10, 25), "ELIGIBILITY_CHANGED:" + pair.policyId() + ":" + pair.fromId() + ":" + pair.toId(),
                Map.of("fromVersionId", 999L, "toVersionId", pair.toId()), true, null, NOW)));
        assertThatThrownBy(() -> analysis.analyze(pair.toId())).isInstanceOf(IllegalStateException.class);
        assertThat(analyses.count()).isZero(); assertThat(events.count()).isEqualTo(1);
        assertThat(tracking.findByOpportunityVersionId(pair.toId()).orElseThrow().getProcessedAt()).isNull();
    }

    @Test
    void newAnalysisVersionAppendsEvidenceAndResolvesDeadlineWithoutDuplicatingEligibilityWork() {
        var pair = seed(true, "v1", Map.of("round", "unmatched")); appendThird(pair, "v1");
        comparisons.compareNext(pair.policyId());
        var original = analysis.analyze(pair.toId());
        // Model an older deployed analyzer's durable result; domain versions remain untouched in production.
        jdbc.update("UPDATE opportunity_version_analysis SET analysis_version='older-analysis-rule' WHERE id=?", original.analysisId());
        var trackingId = tracking.findByOpportunityVersionId(pair.toId()).orElseThrow().getId();
        var eligibilityId = events.findAll().getFirst().getEventId();
        // Simulate repaired immutable fixture input so the newer analyzer can reach a conclusive result.
        jdbc.update("UPDATE application_windows SET scope=NULL WHERE version_id IN (?, ?)", pair.fromId(), pair.toId());
        var resolved = analysis.analyze(pair.toId());
        assertThat(resolved.outcome()).isEqualTo(OpportunityVersionAnalysisTransaction.Outcome.COMPLETED);
        assertThat(resolved.analysisId()).isNotEqualTo(original.analysisId());
        assertThat(analyses.findByTrackingIdOrderByIdAsc(trackingId)).hasSize(2);
        assertThat(analyses.findById(original.analysisId()).orElseThrow().getCompletedAt()).isNull();
        assertThat(analyses.findByTrackingIdAndAnalysisVersion(trackingId, OpportunityVersionAnalysisTransaction.ANALYSIS_VERSION))
                .isPresent();
        assertThat(tracking.findById(trackingId).orElseThrow().getStatus()).isEqualTo(OpportunityVersionTrackingStatus.PROCESSED);
        assertThat(events.findAll()).extracting(OpportunityEvent::getEventId).containsExactly(eligibilityId);
        assertThat(analysis.analyze(pair.toId())).isEqualTo(resolved);
    }

    @Test
    void failedDeadlineReanalysisKeepsPreviousEvidenceAndSequenceCheckpoint() {
        var pair = seed(true, "v1", Map.of("round", "unmatched")); comparisons.compare(pair.toId());
        var original = analysis.analyze(pair.toId());
        jdbc.update("UPDATE opportunity_version_analysis SET analysis_version='older-analysis-rule' WHERE id=?", original.analysisId());
        jdbc.execute("ALTER TABLE opportunity_version_analysis ADD CONSTRAINT reject_new_analysis CHECK (analysis_version = 'older-analysis-rule')");
        try {
            assertThatThrownBy(() -> analysis.analyze(pair.toId())).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE opportunity_version_analysis DROP CONSTRAINT reject_new_analysis");
        }
        assertThat(analyses.count()).isEqualTo(1); assertThat(events.count()).isEqualTo(1);
        assertThat(tracking.findByOpportunityVersionId(pair.toId()).orElseThrow().getStatus())
                .isEqualTo(OpportunityVersionTrackingStatus.PROCESSED_WITH_DEFERRED_DEADLINE);
        assertThat(analysis.analyze(pair.toId()).outcome()).isEqualTo(OpportunityVersionAnalysisTransaction.Outcome.DEADLINE_DEFERRED);
        assertThat(analyses.count()).isEqualTo(2); assertThat(events.count()).isEqualTo(1);
    }

    private Pair seed(boolean baseline, String toRule, Map<String, Object> scope) {
        return tx(() -> {
            var policy = Opportunity.builder().opportunityType(OpportunityType.YOUTH_POLICY).title("Extension test").build();
            em.persist(policy);
            var from = version(policy, 1, "v1"); var to = version(policy, 2, toRule);
            em.persist(from); em.persist(to); policy.changeCurrentVersion(to);
            em.persist(ApplicationWindow.builder().version(from).periodType(PeriodType.FIXED).startDate(START)
                    .endDate(OLD_END).validationStatus(ValidationStatus.VALID).scope(scope).build());
            em.persist(ApplicationWindow.builder().version(to).periodType(PeriodType.FIXED).startDate(START)
                    .endDate(NEW_END).validationStatus(ValidationStatus.VALID).scope(scope).build());
            em.flush();
            if (baseline) tracking.saveAndFlush(OpportunityVersionTracking.baseline(from, NOW));
            return new Pair(policy.getId(), from.getId(), to.getId());
        });
    }

    private OpportunityVersion version(Opportunity policy, int number, String rule) {
        return OpportunityVersion.builder().opportunity(policy).versionNo(number).normalizationVersion(rule)
                .normalizedSnapshot(Map.of("applicationEndDate", (number == 1 ? OLD_END : NEW_END).toString()))
                .personalizationStatus(PersonalizationStatus.PERSONALIZABLE).build();
    }

    private Long pending(Long policyId, String type, String end) {
        return outboxes.saveAndFlush(OutboxEvent.notification(UUID.randomUUID(), UUID.randomUUID(), 7L,
                policyId, type, Map.of("payload", Map.of("applicationEndDate", end)), NOW)).getId();
    }

    private String state(Long id) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, id);
    }

    private <T> T tx(Supplier<T> operation) {
        return new TransactionTemplate(transactions).execute(status -> operation.get());
    }

    private record Pair(Long policyId, Long fromId, Long toId) { }
}
