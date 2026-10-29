package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.backend.outbox.domain.entity.*;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.tracking.config.EventFanoutProperties;
import com.team3.backend.tracking.domain.entity.*;
import com.team3.backend.tracking.domain.model.EventFanoutExecution;
import com.team3.backend.tracking.domain.repository.OpportunityEventRepository;
import com.team3.backend.user.domain.entity.*;
import com.team3.backend.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({EventFanoutCoordinator.class, EventFanoutBatch.class, DateNotificationMessageFactory.class,
        ApplicationStatusCalculator.class, DeadlineSoonDetector.class, PreferenceService.class, EventFanoutIntegrationTest.Beans.class})
class EventFanoutIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("fanout_test");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired private EventFanoutCoordinator coordinator;
    @Autowired private EventFanoutBatch batch;
    @Autowired private OpportunityEventRepository events;
    @Autowired private OutboxEventRepository outboxes;
    @Autowired private UserOpportunityPreferenceRepository preferences;
    @Autowired private UserRepository users;
    @Autowired private PreferenceService preferenceService;
    @jakarta.persistence.PersistenceContext private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private MutableClock clock;
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDisposableContainer() {
        clock.set(NOW);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE outbox_events, opportunity_events, user_opportunity_preferences, "
                + "application_windows, opportunity_versions, opportunities, users RESTART IDENTITY CASCADE");
    }

    @Test
    void resumesAfterCommittedPageAndIncludesSavedHiddenButNotHiddenOnly() {
        var fixture = event(OpportunityEventType.DEADLINE_SOON, true);
        subscriber(fixture.policyId(), true, false);
        subscriber(fixture.policyId(), true, true);
        subscriber(fixture.policyId(), false, true);
        var last = subscriber(fixture.policyId(), true, false);
        var old = coordinator.claim(fixture.eventId()).orElseThrow();
        assertThat(batch.process(old)).isFalse();
        assertThat(outboxes.count()).isEqualTo(2);
        var firstIds = outboxes.findAll().stream().map(OutboxEvent::getMessageId).toList();
        assertThat(events.findById(fixture.eventId()).orElseThrow().getLastUserId()).isEqualTo(2);
        clock.set(NOW.plus(Duration.ofMinutes(31)));
        var resumed = coordinator.claim(fixture.eventId()).orElseThrow();
        assertThat(resumed.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThatThrownBy(() -> batch.process(old)).hasMessageContaining("ownership lost");
        assertThat(coordinator.retry(old, new IllegalStateException())).isFalse();
        assertThat(batch.process(resumed)).isFalse();
        assertThat(batch.process(resumed)).isTrue();
        assertThat(outboxes.count()).isEqualTo(3);
        assertThat(outboxes.findAll().stream().map(OutboxEvent::getMessageId).toList()).containsAll(firstIds);
        assertThat(outboxes.findAll()).allSatisfy(o -> {
            assertThat(o.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(o.getNextRetryAt()).isNotNull();
            assertThat(o.getLeaseToken()).isNull();
            assertThat(o.getPayload().get("messageId")).isEqualTo(o.getMessageId().toString());
            assertThat(o.getPayload().get("sourceEventId")).isEqualTo(o.getSourceEventId().toString());
            assertThat(((Number) o.getPayload().get("userId")).longValue()).isEqualTo(o.getUserId());
            assertThat(o.getPayload()).containsKeys("schemaVersion", "correlationId", "causationId",
                    "opportunityVersionId", "type", "channels", "title", "content", "link",
                    "occurredAt", "expiresAt", "payload");
        });
        var completed = events.findById(fixture.eventId()).orElseThrow();
        assertThat(completed.getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.COMPLETED);
        assertThat(completed.getLastUserId()).isEqualTo(last);
        assertThat(completed.getLeaseToken()).isNull();
        assertThat(coordinator.claim(fixture.eventId())).isEmpty();
    }

    @Test
    void rollsBackWholePageAndCursorOnIntegrityFailureThenRetriesSafely() {
        var fixture = event(OpportunityEventType.DEADLINE_SOON, true);
        subscriber(fixture.policyId(), true, false);
        subscriber(fixture.policyId(), true, false);
        var execution = coordinator.claim(fixture.eventId()).orElseThrow();
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT reject_second_test_user CHECK(user_id <> 2)");
        try {
            assertThatThrownBy(() -> batch.process(execution)).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("reject_second_test_user");
            assertThat(outboxes.count()).isZero();
            assertThat(events.findById(fixture.eventId()).orElseThrow().getLastUserId()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT reject_second_test_user");
        }
        assertThat(coordinator.retry(execution, new IllegalStateException("test"))).isTrue();
        assertThat(coordinator.claim(fixture.eventId())).isEmpty();
        clock.set(NOW.plusSeconds(60));
        var retry = coordinator.claim(fixture.eventId()).orElseThrow();
        batch.process(retry);
        batch.process(retry);
        assertThat(outboxes.count()).isEqualTo(2);
    }

    @Test
    void replayDoesNotChangeExistingMessageIdsOrCreateDuplicates() {
        var fixture = event(OpportunityEventType.DEADLINE_SOON, true);
        subscriber(fixture.policyId(), true, false);
        var execution = coordinator.claim(fixture.eventId()).orElseThrow();
        batch.process(execution);
        var message = outboxes.findAll().getFirst().getMessageId();
        // Simulate an operator replay of the cursor, not a normal restart.
        jdbc.update("UPDATE opportunity_events SET last_user_id=0 WHERE id=?", fixture.eventId());
        batch.process(execution);
        batch.process(execution);
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(outboxes.findAll().getFirst().getMessageId()).isEqualTo(message);
    }

    @Test
    void revalidatesSaveStateAndDoesNotCascadeOutboxOnUserDeletion() {
        var fixture = event(OpportunityEventType.DEADLINE_SOON, true);
        var first = subscriber(fixture.policyId(), true, false);
        subscriber(fixture.policyId(), true, false);
        var last = subscriber(fixture.policyId(), true, false);
        var execution = coordinator.claim(fixture.eventId()).orElseThrow();
        batch.process(execution);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                preferences.findByUserIdAndOpportunityId(last, fixture.policyId()).orElseThrow().clearSaved());
        assertThat(batch.process(execution)).isTrue();
        assertThat(outboxes.count()).isEqualTo(2);
        users.deleteById(first);
        assertThat(outboxes.count()).isEqualTo(2);
    }

    @Test
    void suppressionAndChangedDeadlineCompleteWithoutOutboxAndKeepReason() {
        var suppressed = event(OpportunityEventType.DEADLINE_SOON, false);
        var execution = coordinator.claim(suppressed.eventId()).orElseThrow();
        assertThat(batch.process(execution)).isTrue();
        assertThat(events.findById(suppressed.eventId()).orElseThrow().getFanoutSkipReason())
                .isEqualTo("EVENT_SUPPRESSED");
        var changed = event(OpportunityEventType.DEADLINE_SOON, true);
        subscriber(changed.policyId(), true, false);
        jdbc.update("UPDATE application_windows SET end_date=? WHERE version_id=?",
                java.sql.Date.valueOf(TODAY.plusDays(4)), changed.versionId());
        batch.process(coordinator.claim(changed.eventId()).orElseThrow());
        assertThat(outboxes.count()).isZero();
        assertThat(events.findById(changed.eventId()).orElseThrow().getFanoutSkipReason())
                .isEqualTo("POLICY_NOT_RELEVANT_OR_EXPIRED");
    }

    @Test
    void concurrentClaimsHaveOneOwnerAndFreshHeartbeatPreventsTakeover() throws Exception {
        var fixture = event(OpportunityEventType.DEADLINE_SOON, true);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> claim = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                return coordinator.claim(fixture.eventId()).isPresent();
            };
            var a = executor.submit(claim);
            var b = executor.submit(claim);
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(coordinator.claim(fixture.eventId())).isEmpty();
    }

    @Test
    void crashedLastAttemptIsFailedAfterLeaseExpiryAndNeverReclaimed() {
        var fixture = event(OpportunityEventType.DEADLINE_SOON, true);
        coordinator.claim(fixture.eventId()).orElseThrow();
        jdbc.update("UPDATE opportunity_events SET retry_count=3 WHERE id=?", fixture.eventId());
        assertThat(coordinator.candidates()).doesNotContain(fixture.eventId());
        clock.set(NOW.plus(Duration.ofMinutes(30)));
        assertThat(coordinator.recoverExhausted()).isEqualTo(1);
        assertThat(coordinator.candidates()).doesNotContain(fixture.eventId());
        assertThat(events.findById(fixture.eventId()).orElseThrow().getProcessingStatus())
                .isEqualTo(OpportunityEventProcessingStatus.FAILED);
        assertThat(coordinator.claim(fixture.eventId())).isEmpty();
    }

    @Test
    void reclaimWaitsForLockedPageAndRechecksCommittedHeartbeat() throws Exception {
        var fixture = event(OpportunityEventType.DEADLINE_SOON, true);
        coordinator.claim(fixture.eventId()).orElseThrow();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        clock.set(NOW.plus(Duration.ofMinutes(31)));
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var page = executor.submit(() ->
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            var event = events.lockForFanout(fixture.eventId()).orElseThrow();
                            locked.countDown();
                            try {
                                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(failure);
                            }
                            event.advanceFanout(0, clock.instant());
                        }));
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var claim = executor.submit(() -> coordinator.claim(fixture.eventId()));
                assertThatThrownBy(() -> claim.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                page.get(10, TimeUnit.SECONDS);
                assertThat(claim.get(10, TimeUnit.SECONDS)).isEmpty();
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void closedEventRequestsInAppOnlyAndOtherTypesRemainUnclaimed() {
        var closed = event(OpportunityEventType.APPLICATION_CLOSED, true);
        subscriber(closed.policyId(), true, false);
        batch.process(coordinator.claim(closed.eventId()).orElseThrow());
        assertThat(outboxes.findAll().getFirst().getPayload().get("channels")).isEqualTo(List.of("IN_APP"));
        var unsupported = event(OpportunityEventType.ELIGIBILITY_CHANGED, true);
        assertThat(coordinator.claim(unsupported.eventId())).isEmpty();
        var untouched = events.findById(unsupported.eventId()).orElseThrow();
        assertThat(untouched.getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.PENDING);
        assertThat(untouched.getLastError()).isNull();
        assertThat(untouched.getRetryCount()).isZero();
    }

    @Test
    void extensionFanoutProducesOneRequestForSavedHiddenUserAndSkipsChangedScope() {
        var extension = event(OpportunityEventType.DEADLINE_EXTENDED, true);
        subscriber(extension.policyId(), true, true);
        var execution = coordinator.claim(extension.eventId()).orElseThrow();
        batch.process(execution);
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(outboxes.findAll().getFirst().getNotificationType()).isEqualTo("DEADLINE_EXTENDED");
        assertThat(outboxes.findAll().getFirst().getPayload().get("channels")).isEqualTo(List.of("IN_APP", "PUSH"));
        var scoped = event(OpportunityEventType.DEADLINE_EXTENDED, true);
        subscriber(scoped.policyId(), true, false);
        jdbc.update("UPDATE application_windows SET scope = '{\"round\":\"changed\"}'::jsonb WHERE version_id = ?", scoped.versionId());
        assertThat(batch.process(coordinator.claim(scoped.eventId()).orElseThrow())).isTrue();
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @Test
    void candidateSelectionAndDirectClaimUseTheSameSupportedTypeSet() {
        var expected = new ArrayList<Long>();
        var excluded = new ArrayList<Long>();
        for (var type : OpportunityEventType.values()) {
            var fixture = event(type, true);
            if (DateNotificationMessageFactory.supportedTypes().contains(type)) expected.add(fixture.eventId());
            else excluded.add(fixture.eventId());
        }
        assertThat(coordinator.candidates()).containsExactlyElementsOf(expected);
        for (Long id : excluded) {
            assertThat(coordinator.claim(id)).isEmpty();
            assertThat(events.findById(id).orElseThrow().getRetryCount()).isZero();
        }
        for (Long id : expected) assertThat(coordinator.claim(id)).isPresent();
    }

    @Test
    void staleOtherTypeCannotBeReclaimedOrRetriedEvenWithItsLeaseToken() {
        for (var type : List.of(OpportunityEventType.ELIGIBILITY_CHANGED, OpportunityEventType.OPPORTUNITY_CREATED)) {
            var fixture = event(type, true); var token = UUID.randomUUID();
            jdbc.update("UPDATE opportunity_events SET processing_status='PROCESSING', retry_count=1, "
                    + "processing_started_at=?, lease_token=? WHERE id=?", java.sql.Timestamp.from(NOW.minus(Duration.ofMinutes(31))), token, fixture.eventId());
            var before = jdbc.queryForObject("SELECT to_jsonb(e)::text FROM opportunity_events e WHERE id=?", String.class, fixture.eventId());
            assertThat(coordinator.candidates()).doesNotContain(fixture.eventId());
            assertThat(coordinator.claim(fixture.eventId())).isEmpty();
            assertThat(coordinator.retry(new EventFanoutExecution(fixture.eventId(), token),
                    new IllegalStateException("foreign worker failure"))).isFalse();
            assertThat(jdbc.queryForObject("SELECT to_jsonb(e)::text FROM opportunity_events e WHERE id=?", String.class, fixture.eventId()))
                    .isEqualTo(before);
        }
    }

    @Test
    void exhaustedRecoveryDoesNotFailOtherTypesPendingOrStaleProcessing() {
        var excluded = new ArrayList<Long>();
        for (var type : List.of(OpportunityEventType.ELIGIBILITY_CHANGED, OpportunityEventType.OPPORTUNITY_CREATED)) {
            for (String status : List.of("PENDING", "PROCESSING")) {
                var fixture = event(type, true); excluded.add(fixture.eventId());
                jdbc.update("UPDATE opportunity_events SET processing_status=?, retry_count=3, processing_started_at=? WHERE id=?",
                        status, java.sql.Timestamp.from(NOW.minus(Duration.ofMinutes(31))), fixture.eventId());
            }
        }
        var snapshots = excluded.stream().map(id -> jdbc.queryForObject(
                "SELECT to_jsonb(e)::text FROM opportunity_events e WHERE id=?", String.class, id)).toList();
        var date = event(OpportunityEventType.DEADLINE_SOON, true);
        jdbc.update("UPDATE opportunity_events SET retry_count=3 WHERE id=?", date.eventId());
        assertThat(coordinator.recoverExhausted()).isEqualTo(1);
        assertThat(events.findById(date.eventId()).orElseThrow().getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.FAILED);
        for (int i = 0; i < excluded.size(); i++) {
            assertThat(jdbc.queryForObject("SELECT to_jsonb(e)::text FROM opportunity_events e WHERE id=?", String.class, excluded.get(i)))
                    .isEqualTo(snapshots.get(i));
        }
    }

    @Test
    void versionChangeCandidateQueryRejectsNullSameFutureAndForeignSavedVersions() {
        var fixture=event(OpportunityEventType.DEADLINE_EXTENDED,true);
        var before=subscriber(fixture.policyId(),true,false);
        var unknown=subscriber(fixture.policyId(),true,false);
        var same=subscriber(fixture.policyId(),true,false);
        var future=subscriber(fixture.policyId(),true,false);
        var foreign=subscriber(fixture.policyId(),true,false);
        var other=event(OpportunityEventType.DEADLINE_SOON,true);
        Long futureId=new TransactionTemplate(transactionManager).execute(status->{
            var v=OpportunityVersion.builder().opportunity(entityManager.getReference(Opportunity.class,fixture.policyId()))
                    .versionNo(3).normalizationVersion("test").normalizedSnapshot(Map.of())
                    .personalizationStatus(PersonalizationStatus.PERSONALIZABLE).build();
            entityManager.persist(v); entityManager.flush(); return v.getId();
        });
        jdbc.update("UPDATE user_opportunity_preferences SET saved_version_id=NULL WHERE user_id=?",unknown);
        jdbc.update("UPDATE user_opportunity_preferences SET saved_version_id=? WHERE user_id=?",fixture.versionId(),same);
        jdbc.update("UPDATE user_opportunity_preferences SET saved_version_id=? WHERE user_id=?",futureId,future);
        jdbc.update("UPDATE user_opportunity_preferences SET saved_version_id=? WHERE user_id=?",other.versionId(),foreign);
        var ids=preferences.findSavedUserIdsForVersionChangeAfter(fixture.policyId(),2,0L,
                org.springframework.data.domain.PageRequest.of(0,10));
        assertThat(ids).containsExactly(before);
        assertThat(preferences.findSavedUserIdsForVersionChangeAfter(fixture.policyId(),2,before,
                org.springframework.data.domain.PageRequest.of(0,10))).isEmpty();
        var execution=coordinator.claim(fixture.eventId()).orElseThrow();
        assertThat(batch.process(execution)).isFalse();
        assertThat(batch.process(execution)).isTrue();
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(outboxes.findAll().getFirst().getUserId()).isEqualTo(before);
    }

    @Test
    void lockedSavedVersionCheckRejectsResaveAfterCandidateDiscovery() {
        var fixture=event(OpportunityEventType.DEADLINE_EXTENDED,true);
        var user=subscriber(fixture.policyId(),true,false);
        assertThat(preferences.findSavedUserIdsForVersionChangeAfter(fixture.policyId(),2,0L,
                org.springframework.data.domain.PageRequest.of(0,10))).containsExactly(user);
        jdbc.update("UPDATE user_opportunity_preferences SET saved_version_id=? WHERE user_id=?",fixture.versionId(),user);
        new TransactionTemplate(transactionManager).execute(status->{
            assertThat(preferences.lockSavedBeforeVersionForNotification(fixture.policyId(),2,List.of(user))).isEmpty();
            return null;
        });
    }

    @Test
    void dateThresholdStillNotifiesSavedCurrentVersionAndLegacyNullSave() {
        var fixture=event(OpportunityEventType.DEADLINE_SOON,true);
        subscriber(fixture.policyId(),true,false);
        var legacy=subscriber(fixture.policyId(),true,false);
        jdbc.update("UPDATE user_opportunity_preferences SET saved_version_id=NULL WHERE user_id=?",legacy);
        var execution=coordinator.claim(fixture.eventId()).orElseThrow();
        assertThat(batch.process(execution)).isFalse();
        assertThat(batch.process(execution)).isTrue();
        assertThat(outboxes.count()).isEqualTo(2);
    }

    @Test
    void mergedSaveContractKeepsOriginalVersionOnDuplicateSaveAndNotifiesLaterChange() {
        var fixture=event(OpportunityEventType.DEADLINE_EXTENDED,true);
        long previous=firstVersion(fixture.policyId());
        long user=users.saveAndFlush(User.builder().nickname("real-save").role(Role.USER).build()).getId();
        selectCurrentVersion(fixture.policyId(),previous);
        assertThat(preferenceService.save(user,fixture.policyId())).isTrue();
        selectCurrentVersion(fixture.policyId(),fixture.versionId());
        assertThat(preferenceService.save(user,fixture.policyId())).isFalse();
        assertThat(preferences.findByUserIdAndOpportunityId(user,fixture.policyId()).orElseThrow().getSavedVersionId()).isEqualTo(previous);
        assertThat(preferences.findSavedUserIdsForVersionChangeAfter(fixture.policyId(),2,0L,
                org.springframework.data.domain.PageRequest.of(0,10))).containsExactly(user);
        var execution=coordinator.claim(fixture.eventId()).orElseThrow();
        assertThat(batch.process(execution)).isFalse();
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(batch.process(execution)).isTrue();
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @Test
    void mergedResaveContractExcludesPreResaveChangeAndClearsVersionWhileHidden() {
        var fixture=event(OpportunityEventType.DEADLINE_EXTENDED,true);
        long user=users.saveAndFlush(User.builder().nickname("real-resave").role(Role.USER).build()).getId();
        selectCurrentVersion(fixture.policyId(),firstVersion(fixture.policyId()));
        preferenceService.save(user,fixture.policyId()); preferenceService.hide(user,fixture.policyId());
        assertThat(preferences.findSavedUserIdsForVersionChangeAfter(fixture.policyId(),2,0L,
                org.springframework.data.domain.PageRequest.of(0,10))).containsExactly(user);
        selectCurrentVersion(fixture.policyId(),fixture.versionId());
        preferenceService.unsave(user,fixture.policyId());
        var hidden=preferences.findByUserIdAndOpportunityId(user,fixture.policyId()).orElseThrow();
        assertThat(hidden.isHidden()).isTrue(); assertThat(hidden.getSavedVersionId()).isNull();
        preferenceService.save(user,fixture.policyId());
        assertThat(preferences.findByUserIdAndOpportunityId(user,fixture.policyId()).orElseThrow().getSavedVersionId()).isEqualTo(fixture.versionId());
        new TransactionTemplate(transactionManager).execute(status->{
            assertThat(preferences.lockSavedBeforeVersionForNotification(fixture.policyId(),2,List.of(user))).isEmpty(); return null;
        });
        assertThat(batch.process(coordinator.claim(fixture.eventId()).orElseThrow())).isTrue();
        assertThat(outboxes.count()).isZero();
    }

    @Test
    void mergedUnversionedSaveRemainsNullWithoutGuessingAfterVersionAppears() {
        var fixture=event(OpportunityEventType.DEADLINE_EXTENDED,true);
        long user=users.saveAndFlush(User.builder().nickname("unversioned-save").role(Role.USER).build()).getId();
        selectCurrentVersion(fixture.policyId(),null);
        preferenceService.save(user,fixture.policyId());
        selectCurrentVersion(fixture.policyId(),fixture.versionId());
        assertThat(preferenceService.save(user,fixture.policyId())).isFalse();
        assertThat(preferences.findByUserIdAndOpportunityId(user,fixture.policyId()).orElseThrow().getSavedVersionId()).isNull();
        assertThat(preferences.findSavedUserIdsForVersionChangeAfter(fixture.policyId(),2,0L,
                org.springframework.data.domain.PageRequest.of(0,10))).isEmpty();
        assertThat(batch.process(coordinator.claim(fixture.eventId()).orElseThrow())).isTrue();
        assertThat(outboxes.count()).isZero();
    }

    private long firstVersion(long policyId) {
        return jdbc.queryForObject("SELECT id FROM opportunity_versions WHERE opportunity_id=? AND version_no=1",Long.class,policyId);
    }
    private void selectCurrentVersion(long policyId,Long versionId) {
        // Disposable test DB only: simulate a policy version arriving before/after the real preference service call.
        jdbc.update("UPDATE opportunities SET current_version_id=? WHERE id=?",versionId,policyId);
    }

    private Fixture event(OpportunityEventType type, boolean eligible) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var policy = Opportunity.builder().opportunityType(OpportunityType.YOUTH_POLICY).title("Policy").build();
            entityManager.persist(policy);
            boolean extension = type == OpportunityEventType.DEADLINE_EXTENDED;
            if (extension) entityManager.persist(OpportunityVersion.builder().opportunity(policy).versionNo(1)
                    .normalizationVersion("test").normalizedSnapshot(Map.of())
                    .personalizationStatus(PersonalizationStatus.PERSONALIZABLE).build());
            var version = OpportunityVersion.builder().opportunity(policy).versionNo(extension ? 2 : 1)
                    .normalizationVersion("test").normalizedSnapshot(Map.of())
                    .personalizationStatus(PersonalizationStatus.PERSONALIZABLE).build();
            entityManager.persist(version);
            policy.changeCurrentVersion(version);
            LocalDate end = type == OpportunityEventType.APPLICATION_CLOSED ? TODAY.minusDays(1) : TODAY.plusDays(3);
            entityManager.persist(ApplicationWindow.builder().version(version).periodType(PeriodType.FIXED)
                    .startDate(TODAY.minusDays(7)).endDate(end).validationStatus(ValidationStatus.VALID).build());
            Map<String, Object> payload = type == OpportunityEventType.APPLICATION_CLOSED
                    ? Map.of("endDate", end.toString())
                    : Map.of("threshold", "D-3", "applicationEndDate", end.toString());
            if (type == OpportunityEventType.DEADLINE_EXTENDED) {
                payload = Map.of("applicationStartDate", TODAY.minusDays(7).toString(),
                        "previousApplicationEndDate", end.minusDays(1).toString(), "applicationEndDate", end.toString());
            }
            var event = OpportunityEvent.create(policy, version, type, TODAY,
                    type + ":" + policy.getId(), payload, eligible,
                    eligible ? null : OpportunityEventSuppressionReason.THRESHOLD_ALREADY_PASSED, NOW);
            entityManager.persist(event);
            entityManager.flush();
            return new Fixture(event.getId(), policy.getId(), version.getId());
        });
    }

    private Long subscriber(Long policyId, boolean saved, boolean hidden) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var user = users.saveAndFlush(User.builder().nickname("test").role(Role.USER).build());
            var savedVersion = entityManager.createQuery("select v.id from OpportunityVersion v where v.opportunity.id=:id order by v.versionNo",Long.class)
                    .setParameter("id",policyId).setMaxResults(1).getSingleResult();
            var preference = UserOpportunityPreference.builder().user(user)
                    .opportunity(entityManager.getReference(Opportunity.class, policyId)).build();
            if (saved) preference.markSaved(savedVersion);
            if (hidden) preference.markHidden();
            preferences.saveAndFlush(preference);
            return user.getId();
        });
    }

    record Fixture(Long eventId, Long policyId, Long versionId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean EventFanoutProperties properties() {
            return new EventFanoutProperties(false, 2, 10, Duration.ofMinutes(30), Duration.ofMinutes(1), 3);
        }
    }

    static class MutableClock extends Clock {
        private volatile Instant now = NOW;
        void set(Instant value) { now = value; }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
