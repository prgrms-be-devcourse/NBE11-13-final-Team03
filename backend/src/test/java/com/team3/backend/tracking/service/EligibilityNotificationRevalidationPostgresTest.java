package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.engine.EligibilityEngine;
import com.team3.backend.eligibility.service.EligibilityEvaluator;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.ConditionSetRepository;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.tracking.domain.model.*;
import com.team3.backend.tracking.domain.entity.OpportunityEvent;
import com.team3.backend.tracking.domain.entity.OpportunityEventType;
import com.team3.backend.tracking.domain.entity.OpportunityEventProcessingStatus;
import com.team3.backend.tracking.domain.entity.EligibilityEventAssessment;
import com.team3.backend.tracking.domain.entity.OpportunityVersionTracking;
import com.team3.backend.tracking.domain.entity.OpportunityVersionTrackingStatus;
import com.team3.backend.tracking.domain.repository.EligibilityEventAssessmentRepository;
import com.team3.backend.tracking.domain.repository.OpportunityEventRepository;
import com.team3.backend.tracking.config.EventFanoutProperties;
import com.team3.backend.tracking.config.EligibilityFanoutProperties;
import com.team3.backend.tracking.config.EligibilityFanoutSchedulingProperties;
import com.team3.backend.tracking.config.VersionTrackingProperties;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.entity.Role;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.*;

import static com.team3.backend.eligibility.domain.entity.EligibilityStatus.*;
import static com.team3.backend.tracking.domain.model.EligibilityNotificationRevalidation.Outcome.*;
import static org.assertj.core.api.Assertions.*;

/** Real repositories and evaluator. Fixtures use domain rows, not collection/normalization implementation classes. */
@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "app.scheduling.enabled=false", "yon.tracking.eligibility-outbox.enabled=true",
        "yon.tracking.eligibility-fanout.enabled=true", "yon.tracking.version.enabled=true", "yon.tracking.version-analysis.enabled=true"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({EligibilityNotificationRevalidationService.class, ProvisionalEligibilityTransitionClassifier.class,
        EligibilityVersionComparisonService.class, EligibilityEvaluator.class, EligibilityEngine.class,
        ApplicationStatusCalculator.class, RegionDirectory.class, EligibilityOutboxTransaction.class,
        EligibilityNotificationMessageFactory.class, EligibilityFanoutCoordinator.class, EligibilityFanoutBatch.class,
        EligibilityFanoutWorker.class, EventFanoutCoordinator.class, VersionTrackingPipeline.class,
        OpportunityVersionWorker.class, OpportunityVersionComparisonTransaction.class, OpportunityVersionAnalysisTransaction.class,
        OpportunitySnapshotComparator.class, DeadlineExtensionTransaction.class, DeadlineExtensionDetector.class,
        EligibilityNotificationRevalidationPostgresTest.TimeConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EligibilityNotificationRevalidationPostgresTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final Long USER = 1L;
    private static final String REGION = "1168000000";
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean Clock eligibilityTestClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean EventFanoutProperties fanoutProperties() {
            return new EventFanoutProperties(false, 1, 10, Duration.ofMinutes(30), Duration.ofMinutes(1), 5);
        }
        @Bean EligibilityFanoutProperties eligibilityFanoutProperties() {
            return new EligibilityFanoutProperties(true, 1, 10, Duration.ofMinutes(30), Duration.ofMinutes(1), 5);
        }
        @Bean VersionTrackingProperties versionTrackingProperties() { return new VersionTrackingProperties(true, 1, 60000); }
        @Bean EligibilityFanoutSchedulingProperties eligibilitySchedulingProperties() {
            return new EligibilityFanoutSchedulingProperties(false, 10, 10, 60000);
        }
    }
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("eligibility_revalidation_test");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired EligibilityNotificationRevalidationService service;
    @Autowired EligibilityVersionComparisonService comparisons;
    @Autowired ConditionSetRepository conditionSets;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RegionDirectory regions;
    @Autowired EligibilityOutboxTransaction outboxService;
    @Autowired OutboxEventRepository outboxes;
    @Autowired EligibilityFanoutCoordinator fanout;
    @Autowired EligibilityFanoutBatch fanoutBatch;
    @Autowired EligibilityFanoutWorker fanoutWorker;
    @Autowired EventFanoutCoordinator dateFanout;
    @Autowired OpportunityEventRepository events;
    @Autowired EligibilityEventAssessmentRepository assessments;
    @Autowired VersionTrackingPipeline pipeline;

    @BeforeEach
    void resetDisposableContainerDataOnly() {
        jdbc.execute("TRUNCATE eligibility_set_results, eligibility_profiles, opportunity_conditions, application_windows, "
                + "opportunity_versions, opportunities, condition_sets, regions, outbox_events, opportunity_events, "
                + "opportunity_changes, opportunity_version_tracking, users RESTART IDENTITY CASCADE");
        tx(() -> {
            var user = User.builder().nickname("Eligibility test").role(Role.USER).build();
            em.persist(user); assertThat(user.getId()).isEqualTo(USER);
            var region = Region.builder().regionCode(REGION).regionName("강남구").build();
            em.persist(region);
            em.persist(EligibilityProfile.builder().userId(USER).birthDate(LocalDate.of(1990, 5, 15))
                    .residenceRegion(region).employmentStatus(EmploymentStatus.UNEMPLOYED)
                    .educationLevel(EducationLevel.UNIVERSITY).build());
            return null;
        });
        regions.refresh();
    }

    @Test
    void evaluatesRealVersionConditionsAndPreservesAnIntentionallyDifferentCachedResult() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var source = source(fixture);
        assertThat(source.before().evaluation().status()).isEqualTo(NOT_ELIGIBLE);
        assertThat(source.after().evaluation().status()).isEqualTo(ELIGIBLE);
        tx(() -> {
            // The cache intentionally disagrees with fresh evaluation. It must neither drive nor be overwritten by tracking.
            var current = em.find(OpportunityVersion.class, fixture.currentId());
            em.persist(EligibilityResult.builder().userId(USER).conditionSetId(current.getConditionSetId())
                    .profileVersion(777L).ruleVersion("old-cache-rule").eligibilityStatus(NOT_ELIGIBLE)
                    .reason("cache sentinel").conditionResults(List.of(Map.of("sentinel", true)))
                    .evaluatedAt(LocalDateTime.of(2025, 1, 1, 0, 0)).validUntil(LocalDate.of(2025, 1, 2)).build());
            return null;
        });
        var cacheBefore = cacheRows();

        var result = service.revalidate(source, TODAY);

        assertThat(result.outcome()).isEqualTo(CANDIDATE);
        assertThat(result.freshComparison().profileVersion()).isEqualTo(1L);
        assertThat(result.revalidatedVersionId()).isEqualTo(fixture.currentId());
        assertThat(result.latestJudgement().evaluation().status()).isEqualTo(ELIGIBLE);
        assertThat(result.latestJudgement().applicationStatus()).isEqualTo(ApplicationStatus.OPEN);
        assertThat(result.latestJudgement().evaluation().conditions()).isNotEmpty();
        assertThat(cacheRows()).isEqualTo(cacheBefore);
        for (String table : List.of("outbox_events", "opportunity_events", "opportunity_changes", "opportunity_version_tracking")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
        }
    }

    @Test
    void latestCommittedProfileCanRemoveThePolicyChangeMeaning() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var source = source(fixture);
        jdbc.update("UPDATE eligibility_profiles SET birth_date = ?, profile_version = profile_version + 1 WHERE user_id = ?",
                LocalDate.of(2000, 5, 15), USER);

        var result = service.revalidate(source, TODAY);

        assertThat(result.outcome()).isEqualTo(TRANSITION_CHANGED);
        assertThat(result.sourceComparison().profileVersion()).isEqualTo(1L);
        assertThat(result.freshComparison().profileVersion()).isEqualTo(2L);
        assertThat(result.freshComparison().before().evaluation().status()).isEqualTo(ELIGIBLE);
        assertThat(result.freshComparison().after().evaluation().status()).isEqualTo(ELIGIBLE);
    }

    @Test
    void newerPolicyCanInvalidateAnOtherwisePreservedTransition() {
        var fixture = fixture(age(34), age(39), age(34), ValidationStatus.VALID, "v1");
        var result = service.revalidate(source(fixture), TODAY);
        assertThat(result.outcome()).isEqualTo(LATEST_STATUS_CHANGED);
        assertThat(result.freshComparison().after().evaluation().status()).isEqualTo(ELIGIBLE);
        assertThat(result.latestJudgement().evaluation().status()).isEqualTo(NOT_ELIGIBLE);
    }

    @Test
    void realUnstructuredConditionProducesReviewRequiredWithoutInventingIneligibility() {
        var text = Map.<String, Object>of("status", "UNSTRUCTURED");
        var fixture = fixture(age(39), text, text, ValidationStatus.VALID, "v1");
        var source = source(fixture);
        assertThat(source.before().evaluation().status()).isEqualTo(ELIGIBLE);
        assertThat(source.after().evaluation().status()).isEqualTo(POSSIBLY_ELIGIBLE);
        var result = service.revalidate(source, TODAY);
        assertThat(result.outcome()).isEqualTo(CANDIDATE);
        assertThat(result.decision()).isEqualTo(EligibilityTransitionDecision.ELIGIBILITY_REVIEW_REQUIRED);
        assertThat(result.decision().requestedChannels()).containsExactly("IN_APP");
    }

    @Test
    void conflictingLatestConditionIsUnknownAndDoesNotKeepAnEligibleCandidate() {
        var fixture = fixture(age(34), age(39), Map.of("status", "UNKNOWN"), ValidationStatus.DATA_CONFLICT, "v1");
        var result = service.revalidate(source(fixture), TODAY);
        assertThat(result.outcome()).isEqualTo(LATEST_STATUS_CHANGED);
        assertThat(result.latestJudgement().evaluation().status()).isEqualTo(UNKNOWN);
    }

    @Test
    void newerNormalizationRuleIsExplicitlySuppressed() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v2");
        var result = service.revalidate(source(fixture), TODAY);
        assertThat(result.outcome()).isEqualTo(NORMALIZATION_RULE_CHANGED);
        assertThat(result.freshComparison()).isNull();
        assertThat(result.latestJudgement()).isNull();
    }

    @Test
    void removedProfileIsReportedMissingRatherThanUnknown() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var source = source(fixture);
        jdbc.update("DELETE FROM eligibility_profiles WHERE user_id = ?", USER);
        var result = service.revalidate(source, TODAY);
        assertThat(result.outcome()).isEqualTo(PROFILE_MISSING);
        assertThat(result.freshComparison()).isNull();
    }

    @Test
    void birthdayRevalidationUsesOneNewDateForEveryVersionInsteadOfTheSourceDate() {
        jdbc.update("UPDATE eligibility_profiles SET birth_date = ? WHERE user_id = ?", LocalDate.of(1990, 10, 26), USER);
        var fixture = fixture(age(34), age(35), age(35), ValidationStatus.VALID, "v1");
        var source = source(fixture); // Age 35: from is NOT_ELIGIBLE, to is ELIGIBLE.
        assertThat(source.after().evaluation().status()).isEqualTo(ELIGIBLE);

        var result = service.revalidate(source, TODAY.plusDays(1)); // Age 36: both are NOT_ELIGIBLE.

        assertThat(result.outcome()).isEqualTo(TRANSITION_CHANGED);
        assertThat(result.freshComparison().evaluatedOn()).isEqualTo(TODAY.plusDays(1));
        assertThat(result.freshComparison().before().evaluation().status()).isEqualTo(NOT_ELIGIBLE);
        assertThat(result.freshComparison().after().evaluation().status()).isEqualTo(NOT_ELIGIBLE);
        assertThat(result.latestJudgement().evaluation().status()).isEqualTo(NOT_ELIGIBLE);
    }

    @Test
    void savedHiddenUserGetsOneDurableRequestWithFreshEvidence() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW);
        var result = outboxService.create(request.eventId(), request.source());
        assertThat(result.status()).isEqualTo(EligibilityOutboxTransaction.Status.CREATED);
        var row = outboxes.findById(result.outboxId()).orElseThrow();
        assertThat(row.getNotificationType()).isEqualTo("BECAME_ELIGIBLE");
        assertThat(row.getPayload()).containsEntry("channels", List.of("IN_APP", "PUSH"));
        var evidence = (Map<?, ?>) row.getPayload().get("payload");
        assertThat(((Number) evidence.get("profileVersion")).longValue()).isEqualTo(1);
        assertThat(((Number) evidence.get("revalidatedVersionId")).longValue()).isEqualTo(fixture.currentId());
        assertThat(outboxService.create(request.eventId(), request.source()).status()).isEqualTo(EligibilityOutboxTransaction.Status.EXISTING);
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @Test
    void unsavedOrMissingUserDoesNotGetARequest() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW);
        jdbc.update("UPDATE user_opportunity_preferences SET is_saved = false WHERE user_id = ?", USER);
        assertThat(outboxService.create(request.eventId(), request.source()).status()).isEqualTo(EligibilityOutboxTransaction.Status.NOT_SAVED);
        jdbc.update("DELETE FROM users WHERE id = ?", USER);
        assertThat(outboxService.create(request.eventId(), request.source()).status()).isEqualTo(EligibilityOutboxTransaction.Status.USER_UNAVAILABLE);
        assertThat(outboxes.count()).isZero();
    }

    @Test
    void expiredSourceAndChangedProfileCannotCreateRequests() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW.minus(Duration.ofDays(7)));
        assertThat(outboxService.create(request.eventId(), request.source()).status()).isEqualTo(EligibilityOutboxTransaction.Status.EXPIRED);
        jdbc.update("UPDATE eligibility_profiles SET birth_date = ?, profile_version = 2 WHERE user_id = ?", LocalDate.of(2000, 5, 15), USER);
        assertThat(outboxService.create(request.eventId(), request.source()).status()).isEqualTo(EligibilityOutboxTransaction.Status.REVALIDATION_REJECTED);
        assertThat(outboxes.count()).isZero();
    }

    @Test
    void insertFailureLeavesNoPartialEvidenceAndCanBeRetried() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW);
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT reject_eligibility_request CHECK (notification_type <> 'BECAME_ELIGIBLE')");
        try {
            assertThatThrownBy(() -> outboxService.create(request.eventId(), request.source())).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT reject_eligibility_request");
        }
        assertThat(outboxes.count()).isZero();
        assertThat(outboxService.create(request.eventId(), request.source()).status()).isEqualTo(EligibilityOutboxTransaction.Status.CREATED);
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @Test
    void simultaneousRequestsKeepOneMessageIdentity() throws Exception {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW); var start = new CountDownLatch(1);
        Callable<EligibilityOutboxTransaction.Result> task = () -> { start.await(); return outboxService.create(request.eventId(), request.source()); };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(task); var second = pool.submit(task); start.countDown();
            var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
            assertThat(a.outboxId()).isEqualTo(b.outboxId());
            assertThat(List.of(a.status(), b.status())).containsExactlyInAnyOrder(
                    EligibilityOutboxTransaction.Status.CREATED, EligibilityOutboxTransaction.Status.EXISTING);
        }
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"profile", "policy", "user", "saved"})
    void protectedInputsCannotChangeBetweenRevalidationAndOutboxCommit(String target) throws Exception {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW);
        String sql = switch (target) {
            case "profile" -> "UPDATE eligibility_profiles SET profile_version = profile_version + 1 WHERE user_id = ?";
            case "policy" -> "UPDATE opportunities SET title = 'concurrent' WHERE id = (SELECT opportunity_id FROM opportunity_versions WHERE id = ?)";
            case "user" -> "UPDATE users SET nickname = 'concurrent' WHERE id = ?";
            case "saved" -> "UPDATE user_opportunity_preferences SET is_saved = false WHERE user_id = ?";
            default -> throw new IllegalArgumentException();
        };
        var inserted = new CountDownLatch(1); var release = new CountDownLatch(1); var writerStarted = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var insertion = pool.submit(() -> tx(() -> {
                var result = outboxService.create(request.eventId(), request.source());
                inserted.countDown(); await(release); return result;
            }));
            try {
                assertThat(inserted.await(15, TimeUnit.SECONDS)).isTrue();
                var mutation = pool.submit(() -> { writerStarted.countDown(); return jdbc.update(sql, target.equals("policy") ? fixture.toId() : USER); });
                assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> mutation.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(insertion.get(15, TimeUnit.SECONDS).status()).isEqualTo(EligibilityOutboxTransaction.Status.CREATED);
                assertThat(mutation.get(15, TimeUnit.SECONDS)).isEqualTo(1);
            } finally { release.countDown(); }
        }
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @Test
    void repeatedBudgetYieldsResumeAllUsersWithoutExhaustingFailureAttempts() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW);
        for (int i = 0; i < 6; i++) savedUser(fixture, LocalDate.of(1990, 5, 15));
        var worker = boundedWorker(1, 1);
        for (int i = 0; i < 7; i++) {
            var result = worker.runOnce();
            assertThat(result.pageCalls()).isEqualTo(1); assertThat(result.yieldedEvents()).isEqualTo(1);
            var pending = events.findById(request.eventId()).orElseThrow();
            assertThat(pending.getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.PENDING);
            assertThat(pending.getRetryCount()).isZero(); assertThat(pending.getLeaseToken()).isNull();
            assertThat(pending.getProcessedAt()).isNull();
            assertThat(pending.getNextRetryAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(assessments.count()).isEqualTo(i + 1); assertThat(outboxes.count()).isEqualTo(i + 1);
            assertThat(fanout.recoverExhausted()).isZero();
            assertThat(worker.runOnce().pageCalls()).isZero(); // Not due yet, even for manual callers.
            makeDue(request.eventId());
        }
        assertThat(worker.runOnce().pageCalls()).isEqualTo(1); // Terminal empty page also spends budget.
        var complete = events.findById(request.eventId()).orElseThrow();
        assertThat(complete.getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.COMPLETED);
        assertThat(complete.getRetryCount()).isEqualTo(1); // Final successful claim retains existing counting convention.
        assertThat(assessments.count()).isEqualTo(7); assertThat(outboxes.count()).isEqualTo(7);
    }

    @Test
    void yieldedEventDoesNotBlockFollowingDueEventWithOnePageBudget() {
        var firstFixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var first = request(firstFixture, NOW);
        var secondFixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var second = request(secondFixture, NOW);
        var worker = boundedWorker(1, 1);
        worker.runOnce();
        assertThat(fanout.candidates()).containsExactly(second.eventId());
        worker.runOnce();
        assertThat(assessments.findByEventIdAndUserId(first.eventId(), USER)).isPresent();
        assertThat(assessments.findByEventIdAndUserId(second.eventId(), USER)).isPresent();
        assertThat(outboxes.count()).isEqualTo(2);
    }

    @Test
    void yieldingRejectsOldOwnerAndPreservesPreviouslyConsumedFailureAttempts() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW);
        jdbc.update("UPDATE opportunity_events SET retry_count=2 WHERE id=?", request.eventId());
        var first = fanout.claim(request.eventId()).orElseThrow();
        assertThat(fanoutBatch.process(first)).isFalse();
        assertThat(fanout.yieldExecution(first)).isTrue();
        var pending = events.findById(request.eventId()).orElseThrow();
        assertThat(pending.getRetryCount()).isEqualTo(2); assertThat(pending.getLastUserId()).isEqualTo(USER);
        assertThat(fanout.yieldExecution(first)).isFalse();
        assertThat(fanout.retry(first, new IllegalStateException("old lease"))).isFalse();
        makeDue(request.eventId());
        var recovered = fanout.claim(request.eventId()).orElseThrow();
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(fanout.yieldExecution(first)).isFalse();
        assertThatThrownBy(() -> fanoutBatch.process(first)).isInstanceOf(IllegalStateException.class);
        assertThat(fanoutBatch.process(recovered)).isTrue();
        assertThat(outboxes.count()).isEqualTo(1); assertThat(assessments.count()).isEqualTo(1);
    }

    @Test
    void boundedWorkerFailureRetainsPriorPageAndConsumesOnlyItsFailedClaim() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW); var second = savedUser(fixture, LocalDate.of(1990, 5, 15));
        var worker = boundedWorker(3, 3);
        jdbc.execute("ALTER TABLE eligibility_event_assessments ADD CONSTRAINT reject_budget_second CHECK (user_id <> " + second + ")");
        try {
            var result = worker.runOnce();
            assertThat(result.failures()).isEqualTo(1); assertThat(result.pageCalls()).isEqualTo(2);
            assertThat(result.yieldedEvents()).isZero();
        } finally { jdbc.execute("ALTER TABLE eligibility_event_assessments DROP CONSTRAINT reject_budget_second"); }
        var pending = events.findById(request.eventId()).orElseThrow();
        assertThat(pending.getLastUserId()).isEqualTo(USER); assertThat(pending.getRetryCount()).isEqualTo(1);
        assertThat(pending.getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.PENDING);
        assertThat(pending.getLastError()).isNotBlank();
        assertThat(assessments.count()).isEqualTo(1); assertThat(outboxes.count()).isEqualTo(1);
        makeDue(request.eventId());
        assertThat(worker.runOnce().failures()).isZero();
        assertThat(events.findById(request.eventId()).orElseThrow().getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.COMPLETED);
        assertThat(assessments.count()).isEqualTo(2); assertThat(outboxes.count()).isEqualTo(2);
    }

    private EligibilityFanoutWorker boundedWorker(int total, int perEvent) {
        return new EligibilityFanoutWorker(fanout, fanoutBatch, new EligibilityFanoutSchedulingProperties(false, total, perEvent, 60000));
    }

    @Test
    void deferredEvidenceRemainsPendingAcrossBudgetsUntilTerminalPageMovesToPolicyHold() {
        var text = Map.<String, Object>of("status", "UNSTRUCTURED");
        var fixture = fixture(age(39), text, text, ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW);
        var older = savedUser(fixture, LocalDate.of(1980, 5, 15));
        var worker = boundedWorker(1, 1);
        worker.runOnce(); makeDue(request.eventId()); worker.runOnce();
        assertThat(assessments.findByEventIdAndUserId(request.eventId(), older).orElseThrow().getStatus())
                .isEqualTo(EligibilityEventAssessment.Status.DEFERRED);
        assertThat(events.findById(request.eventId()).orElseThrow().getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.PENDING);
        makeDue(request.eventId()); worker.runOnce();
        var held = events.findById(request.eventId()).orElseThrow();
        assertThat(held.getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.WAITING_POLICY);
        assertThat(held.getProcessedAt()).isNull(); assertThat(held.getLeaseToken()).isNull();
        assertThat(worker.runOnce().pageCalls()).isZero();
        assertThat(assessments.count()).isEqualTo(2); assertThat(outboxes.count()).isEqualTo(1);
    }

    private void makeDue(Long id) {
        // Only disposable test rows: simulate reaching the due time without sleeping or changing event expiry.
        jdbc.update("UPDATE opportunity_events SET next_retry_at=? WHERE id=?", java.sql.Timestamp.from(NOW), id);
    }

    @Test
    void dedicatedConsumerCreatesOneAssessmentAndOutboxForSavedHiddenUser() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW);
        fanoutWorker.runOnce(); fanoutWorker.runOnce();
        assertThat(outboxes.count()).isEqualTo(1); assertThat(assessments.count()).isEqualTo(1);
        var assessed = assessments.findByEventIdAndUserId(request.eventId(), USER).orElseThrow();
        assertThat(assessed.getStatus()).isEqualTo(EligibilityEventAssessment.Status.OUTBOX_CREATED);
        assertThat(assessed.getEvidence()).containsKeys("source", "fresh", "latest", "revalidatedVersionId");
        assertThat(assessed.getEvidence()).doesNotContainKeys("birthDate", "residenceRegion");
        assertThat(events.findById(request.eventId()).orElseThrow().getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.COMPLETED);
    }

    @Test
    void noChangeIsDurablyRecordedWithoutOutbox() {
        var fixture = fixture(age(39), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW); fanoutWorker.runOnce();
        assertThat(outboxes.count()).isZero();
        assertThat(assessments.findByEventIdAndUserId(request.eventId(), USER).orElseThrow().getStatus())
                .isEqualTo(EligibilityEventAssessment.Status.NO_CHANGE);
        assertThat(events.findById(request.eventId()).orElseThrow().getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.COMPLETED);
    }

    @Test
    void mixedCandidateAndDeferredUsersCommitSeparatelyWithoutCallingDeferredComplete() {
        var text = Map.<String, Object>of("status", "UNSTRUCTURED");
        var fixture = fixture(age(39), text, text, ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW);
        var older = savedUser(fixture, LocalDate.of(1980, 5, 15));
        fanoutWorker.runOnce();
        assertThat(outboxes.count()).isEqualTo(1); assertThat(assessments.count()).isEqualTo(2);
        assertThat(assessments.findByEventIdAndUserId(request.eventId(), older).orElseThrow().getStatus())
                .isEqualTo(EligibilityEventAssessment.Status.DEFERRED);
        var event = events.findById(request.eventId()).orElseThrow();
        assertThat(event.getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.WAITING_POLICY);
        assertThat(event.getProcessedAt()).isNull(); assertThat(event.getLeaseToken()).isNull();
        assertThat(event.getLastUserId()).isEqualTo(older);
        assertThat(fanout.claim(request.eventId())).isEmpty();
        assertThat(fanout.recoverExhausted()).isZero();
        fanoutWorker.runOnce();
        assertThat(assessments.count()).isEqualTo(2); assertThat(outboxes.count()).isEqualTo(1);
        assertThat(events.findById(request.eventId()).orElseThrow().getRetryCount()).isEqualTo(1);
    }

    @Test
    void missingProfileIsSkippedWithoutPuttingTheEventOnPolicyHold() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW);
        jdbc.update("DELETE FROM eligibility_profiles WHERE user_id=?", USER);
        fanoutWorker.runOnce();
        var result = assessments.findByEventIdAndUserId(request.eventId(), USER).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(EligibilityEventAssessment.Status.SKIPPED);
        assertThat(result.getReason()).isEqualTo("PROFILE_MISSING");
        assertThat(result.getEvidence()).doesNotContainKey("source");
        assertThat(events.findById(request.eventId()).orElseThrow().getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.COMPLETED);
        assertThat(outboxes.count()).isZero();
    }

    @Test
    void latestUnknownEvidenceIsDeferredAndNotConvertedIntoUserNotification() {
        var fixture = fixture(age(34), age(39), Map.of("status", "UNKNOWN"), ValidationStatus.DATA_CONFLICT, "v1");
        var request = request(fixture, NOW); fanoutWorker.runOnce();
        var result = assessments.findByEventIdAndUserId(request.eventId(), USER).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(EligibilityEventAssessment.Status.DEFERRED);
        assertThat(result.getEvidence()).containsEntry("revalidationOutcome", "LATEST_STATUS_CHANGED");
        assertThat(outboxes.count()).isZero();
        assertThat(events.findById(request.eventId()).orElseThrow().getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.WAITING_POLICY);
    }

    @Test
    void latestProfileIsReadUnderLockAndCanTurnOriginalCandidateIntoNoChange() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW);
        jdbc.update("UPDATE eligibility_profiles SET birth_date=?, profile_version=2 WHERE user_id=?", LocalDate.of(2000, 5, 15), USER);
        fanoutWorker.runOnce();
        var result = assessments.findByEventIdAndUserId(request.eventId(), USER).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(EligibilityEventAssessment.Status.NO_CHANGE);
        assertThat(((Map<?, ?>) result.getEvidence().get("source")).get("profileVersion").toString()).isEqualTo("2");
        assertThat(outboxes.count()).isZero();
    }

    @Test
    void pageFailureRollsBackOnlyThatPageAndStaleLeaseResumesFromCommittedCursor() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW); var secondUser = savedUser(fixture, LocalDate.of(1990, 5, 15));
        var first = fanout.claim(request.eventId()).orElseThrow();
        assertThat(fanoutBatch.process(first)).isFalse();
        assertThat(events.findById(request.eventId()).orElseThrow().getLastUserId()).isEqualTo(USER);
        jdbc.execute("ALTER TABLE eligibility_event_assessments ADD CONSTRAINT reject_second_user CHECK (user_id <> " + secondUser + ")");
        try {
            assertThatThrownBy(() -> fanoutBatch.process(first)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE eligibility_event_assessments DROP CONSTRAINT reject_second_user");
        }
        assertThat(assessments.count()).isEqualTo(1); assertThat(outboxes.count()).isEqualTo(1);
        assertThat(events.findById(request.eventId()).orElseThrow().getLastUserId()).isEqualTo(USER);
        jdbc.update("UPDATE opportunity_events SET processing_started_at=? WHERE id=?", java.sql.Timestamp.from(NOW.minus(Duration.ofMinutes(31))), request.eventId());
        var recovered = fanout.claim(request.eventId()).orElseThrow();
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThatThrownBy(() -> fanoutBatch.process(first)).isInstanceOf(IllegalStateException.class);
        assertThat(fanout.retry(first, new IllegalStateException("old owner"))).isFalse();
        assertThat(fanoutBatch.process(recovered)).isFalse();
        assertThat(fanoutBatch.process(recovered)).isTrue();
        assertThat(assessments.count()).isEqualTo(2); assertThat(outboxes.count()).isEqualTo(2);
        assertThat(events.findById(request.eventId()).orElseThrow().getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.COMPLETED);
    }

    @Test
    void existingExplicitOutboxIsNotDuplicatedByNewConsumer() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW);
        var original = outboxService.create(request.eventId(), request.source()).outboxId();
        fanoutWorker.runOnce();
        var result = assessments.findByEventIdAndUserId(request.eventId(), USER).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(EligibilityEventAssessment.Status.OUTBOX_EXISTING);
        assertThat(result.getOutboxId()).isEqualTo(original); assertThat(outboxes.count()).isEqualTo(1);
    }

    @Test
    void dateAndEligibilityClaimsAreMutuallyExclusiveIncludingExhaustionRecovery() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW);
        var dateId = tx(() -> {
            var to = em.find(OpportunityVersion.class, fixture.toId());
            var date = OpportunityEvent.create(to.getOpportunity(), to, OpportunityEventType.DEADLINE_SOON,
                    TODAY, "DEADLINE_SOON:test", Map.of(), true, null, NOW);
            em.persist(date); em.flush(); return date.getId();
        });
        assertThat(dateFanout.candidates()).containsExactly(dateId);
        assertThat(fanout.candidates()).containsExactly(request.eventId());
        assertThat(dateFanout.claim(request.eventId())).isEmpty(); assertThat(fanout.claim(dateId)).isEmpty();
        jdbc.update("UPDATE opportunity_events SET retry_count=5 WHERE id=?", dateId);
        assertThat(fanout.recoverExhausted()).isZero();
        assertThat(events.findById(dateId).orElseThrow().getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.PENDING);
    }

    @Test
    void expiredEventDoesNotEvaluateUsersOrRenewExpiry() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW.minus(Duration.ofDays(8))); fanoutWorker.runOnce();
        assertThat(assessments.count()).isZero(); assertThat(outboxes.count()).isZero();
        assertThat(events.findById(request.eventId()).orElseThrow().getFanoutSkipReason()).isEqualTo("EXPIRED");
    }

    @Test
    void withdrawnUserEvidenceIsDeletedByUserForeignKeyCascade() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        request(fixture, NOW); fanoutWorker.runOnce(); assertThat(assessments.count()).isEqualTo(1);
        jdbc.update("DELETE FROM user_opportunity_preferences WHERE user_id=?", USER);
        jdbc.update("DELETE FROM eligibility_profiles WHERE user_id=?", USER);
        jdbc.update("DELETE FROM users WHERE id=?", USER);
        assertThat(assessments.count()).isZero();
    }

    @Test
    void concurrentEligibilityClaimsGrantOneLeaseOnly() throws Exception {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW); var start = new CountDownLatch(1);
        Callable<Boolean> claim = () -> { await(start); return fanout.claim(request.eventId()).isPresent(); };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(claim); var second = pool.submit(claim); start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(events.findById(request.eventId()).orElseThrow().getRetryCount()).isEqualTo(1);
    }

    @Test
    void failedPageRetryUsesDelayAndExhaustedRecoveryDoesNotConsumeOtherTypes() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW); var execution = fanout.claim(request.eventId()).orElseThrow();
        assertThat(fanout.retry(execution, new IllegalStateException("retry"))).isTrue();
        var retried = events.findById(request.eventId()).orElseThrow();
        assertThat(retried.getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.PENDING);
        assertThat(retried.getNextRetryAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(fanout.claim(request.eventId())).isEmpty();
        jdbc.update("UPDATE opportunity_events SET retry_count=5, next_retry_at=? WHERE id=?", java.sql.Timestamp.from(NOW), request.eventId());
        assertThat(fanout.recoverExhausted()).isEqualTo(1);
        assertThat(events.findById(request.eventId()).orElseThrow().getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.FAILED);
        assertThat(assessments.count()).isZero(); assertThat(outboxes.count()).isZero();
    }

    @Test
    void missingProfileDoesNotPreventFollowingSavedUserFromReceivingTheirCandidate() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var request = request(fixture, NOW); var following = savedUser(fixture, LocalDate.of(1990, 5, 15));
        jdbc.update("DELETE FROM eligibility_profiles WHERE user_id=?", USER);
        fanoutWorker.runOnce();
        assertThat(assessments.findByEventIdAndUserId(request.eventId(), USER).orElseThrow().getStatus())
                .isEqualTo(EligibilityEventAssessment.Status.SKIPPED);
        assertThat(assessments.findByEventIdAndUserId(request.eventId(), following).orElseThrow().getStatus())
                .isEqualTo(EligibilityEventAssessment.Status.OUTBOX_CREATED);
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(events.findById(request.eventId()).orElseThrow().getProcessingStatus()).isEqualTo(OpportunityEventProcessingStatus.COMPLETED);
    }

    @Test
    void pipelineConnectsComparisonAndAnalysisThenManualUserConsumerCreatesOneOutbox() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        baselineAndSave(fixture);
        var first = pipeline.runOnce();
        assertThat(first.visitedPolicies()).isEqualTo(1); assertThat(first.failures()).isZero();
        assertThat(events.findAll()).extracting(OpportunityEvent::getEventType).containsExactly(OpportunityEventType.ELIGIBILITY_CHANGED);
        assertThat(outboxes.count()).isZero(); // Pipeline never publishes or automatically starts user fan-out.
        fanoutWorker.runOnce();
        assertThat(outboxes.count()).isEqualTo(1); assertThat(assessments.count()).isEqualTo(1);
        pipeline.runOnce(); // v3 has the same snapshot: NO_CHANGE and no new event.
        fanoutWorker.runOnce();
        assertThat(events.count()).isEqualTo(1); assertThat(outboxes.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM opportunity_version_tracking WHERE opportunity_version_id=?", String.class, fixture.currentId()))
                .isEqualTo("NO_CHANGE");
    }

    @Test
    void pipelineWithoutExplicitBaselineDoesNotGuessInitialIngestionOrCreateEvents() {
        fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        var result = pipeline.runOnce();
        assertThat(result.analysisCalls()).isZero(); assertThat(result.failures()).isZero();
        assertThat(events.count()).isZero(); assertThat(outboxes.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM opportunity_version_tracking", Long.class)).isZero();
    }

    @Test
    void pipelineRetainsCommittedComparisonWhenAnalysisFailsAndRetriesReadyVersion() {
        var fixture = fixture(age(34), age(39), age(39), ValidationStatus.VALID, "v1");
        baselineAndSave(fixture);
        jdbc.execute("ALTER TABLE opportunity_version_analysis ADD CONSTRAINT reject_pipeline_analysis CHECK (completed_at IS NULL)");
        try { assertThat(pipeline.runOnce().failures()).isEqualTo(1); }
        finally { jdbc.execute("ALTER TABLE opportunity_version_analysis DROP CONSTRAINT reject_pipeline_analysis"); }
        assertThat(jdbc.queryForObject("SELECT status FROM opportunity_version_tracking WHERE opportunity_version_id=?", String.class, fixture.toId()))
                .isEqualTo(OpportunityVersionTrackingStatus.READY_FOR_EVENT_ANALYSIS.name());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM opportunity_changes", Long.class)).isEqualTo(1);
        assertThat(events.count()).isZero();
        assertThat(pipeline.runOnce().failures()).isZero();
        assertThat(events.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM opportunity_changes", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM opportunity_version_tracking WHERE opportunity_version_id=?", String.class, fixture.toId()))
                .isEqualTo("PROCESSED");
    }

    private void baselineAndSave(Fixture fixture) {
        tx(() -> {
            var from = em.find(OpportunityVersion.class, fixture.fromId());
            em.persist(OpportunityVersionTracking.baseline(from, NOW));
            var saved = UserOpportunityPreference.builder().user(em.find(User.class, USER)).opportunity(from.getOpportunity()).build();
            saved.markSaved(from.getOpportunity().getCurrentVersion().getId()); em.persist(saved); return null;
        });
    }

    private Long savedUser(Fixture fixture, LocalDate birthDate) {
        return tx(() -> {
            var user = User.builder().nickname("Additional saved user").role(Role.USER).build(); em.persist(user);
            em.persist(EligibilityProfile.builder().userId(user.getId()).birthDate(birthDate).residenceRegion(em.find(Region.class, REGION))
                    .employmentStatus(EmploymentStatus.UNEMPLOYED).educationLevel(EducationLevel.UNIVERSITY).build());
            var preference = UserOpportunityPreference.builder().user(user)
                    .opportunity(em.find(OpportunityVersion.class, fixture.toId()).getOpportunity()).build();
            preference.markSaved(fixture.currentId()); em.persist(preference); em.flush(); return user.getId();
        });
    }

    private Request request(Fixture fixture, Instant occurredAt) {
        var source = source(fixture);
        return tx(() -> {
            var to = em.find(OpportunityVersion.class, fixture.toId());
            var preference = UserOpportunityPreference.builder().user(em.find(User.class, USER)).opportunity(to.getOpportunity()).build();
            preference.markSaved(to.getOpportunity().getCurrentVersion().getId()); preference.markHidden(); em.persist(preference);
            var event = OpportunityEvent.create(to.getOpportunity(), to, OpportunityEventType.ELIGIBILITY_CHANGED,
                    LocalDate.ofInstant(occurredAt, ZoneId.of("Asia/Seoul")), "ELIGIBILITY_CHANGED:" + fixture.fromId() + ":" + fixture.toId(),
                    Map.of("fromVersionId", fixture.fromId(), "toVersionId", fixture.toId()), true, null, occurredAt);
            em.persist(event); em.flush(); return new Request(event.getId(), source);
        });
    }

    private void await(CountDownLatch latch) {
        try { if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Test coordination timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private record Request(Long eventId, EligibilityVersionComparison source) { }

    private Fixture fixture(Map<String, Object> before, Map<String, Object> after, Map<String, Object> current,
                            ValidationStatus currentValidation, String currentRule) {
        return tx(() -> {
            var policy = Opportunity.builder().opportunityType(OpportunityType.YOUTH_POLICY).title("Real eligibility comparison").build();
            em.persist(policy);
            var from = version(policy, 1, before, ValidationStatus.VALID, "v1");
            var to = version(policy, 2, after, ValidationStatus.VALID, "v1");
            var latest = version(policy, 3, current, currentValidation, currentRule);
            policy.changeCurrentVersion(latest); em.flush();
            return new Fixture(from.getId(), to.getId(), latest.getId());
        });
    }

    private OpportunityVersion version(Opportunity policy, int number, Map<String, Object> expression,
                                       ValidationStatus validation, String rule) {
        Long setId = conditionSets.resolveId(Integer.toString(number).repeat(64));
        var version = OpportunityVersion.builder().opportunity(policy).versionNo(number).normalizationVersion(rule)
                .normalizedSnapshot(Map.of("age", expression)).conditionSetId(setId)
                .personalizationStatus(PersonalizationStatus.PERSONALIZABLE).build();
        em.persist(version);
        em.persist(OpportunityCondition.builder().version(version).conditionType(ConditionType.AGE)
                .requirementType(RequirementType.REQUIRED).conditionField("birthDate")
                .expression(expression).validationStatus(validation).build());
        em.persist(OpportunityCondition.builder().version(version).conditionType(ConditionType.REGION)
                .requirementType(RequirementType.REQUIRED).conditionField("residenceRegionCode")
                .expression(Map.of("status", "NATIONAL")).validationStatus(ValidationStatus.VALID).build());
        em.persist(ApplicationWindow.builder().version(version).periodType(PeriodType.FIXED)
                .startDate(TODAY.minusDays(7)).endDate(TODAY.plusDays(20)).validationStatus(ValidationStatus.VALID).build());
        return version;
    }

    private EligibilityVersionComparison source(Fixture fixture) {
        return tx(() -> comparisons.compare(USER, em.find(OpportunityVersion.class, fixture.fromId()),
                em.find(OpportunityVersion.class, fixture.toId()), TODAY).orElseThrow());
    }

    private Map<String, Object> age(int max) {
        return Map.of("status", "MAX_ONLY", "max", max);
    }

    private List<String> cacheRows() {
        return jdbc.queryForList("SELECT to_jsonb(r)::text FROM eligibility_set_results r ORDER BY id", String.class);
    }

    private <T> T tx(Supplier<T> operation) {
        return new TransactionTemplate(transactions).execute(status -> operation.get());
    }

    private record Fixture(Long fromId, Long toId, Long currentId) { }
}
