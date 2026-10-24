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
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "app.tracking.eligibility-outbox-enabled=true"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({EligibilityNotificationRevalidationService.class, ProvisionalEligibilityTransitionClassifier.class,
        EligibilityVersionComparisonService.class, EligibilityEvaluator.class, EligibilityEngine.class,
        ApplicationStatusCalculator.class, RegionDirectory.class, EligibilityOutboxTransaction.class,
        EligibilityNotificationMessageFactory.class, EligibilityNotificationRevalidationPostgresTest.TimeConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EligibilityNotificationRevalidationPostgresTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final Long USER = 1L;
    private static final String REGION = "1168000000";
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean Clock eligibilityTestClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
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

    private Request request(Fixture fixture, Instant occurredAt) {
        var source = source(fixture);
        return tx(() -> {
            var to = em.find(OpportunityVersion.class, fixture.toId());
            var preference = UserOpportunityPreference.builder().user(em.find(User.class, USER)).opportunity(to.getOpportunity()).build();
            preference.markSaved(); preference.markHidden(); em.persist(preference);
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
