package com.team3.backend.tracking.service;

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
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({DeadlineExtensionTransaction.class, DeadlineExtensionDetector.class, OpportunityVersionComparisonTransaction.class,
        OpportunitySnapshotComparator.class, DeadlineExtensionPostgresTest.TimeConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
    void comparisonPreservesExplicitTargetBaselineAndIgnoresSupersededTargets() {
        var pair = seed(true, "v1", null);
        Long baselineId = tx(() -> tracking.saveAndFlush(OpportunityVersionTracking.baseline(
                em.find(OpportunityVersion.class, pair.toId()), NOW)).getId());
        assertThat(comparisons.compare(pair.toId())).contains(baselineId);
        assertThat(tracking.findById(baselineId).orElseThrow().getStatus()).isEqualTo(OpportunityVersionTrackingStatus.BASELINE_SKIPPED);
        assertThat(comparisons.compare(pair.fromId())).isEmpty();
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
