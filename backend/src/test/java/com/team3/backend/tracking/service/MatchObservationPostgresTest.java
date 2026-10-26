package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.interest.domain.entity.UserInterest;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.tracking.config.MatchTrackingConfig;
import com.team3.backend.tracking.domain.entity.MatchPairState;
import com.team3.backend.tracking.domain.repository.MatchCandidateEventRepository;
import com.team3.backend.tracking.domain.repository.MatchPairStateRepository;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
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
import static com.team3.backend.eligibility.domain.entity.EligibilityStatus.*;

/** Current cached result is input only; previous state lives in the new pair table. No actual DB or senders. */
@Testcontainers
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","app.scheduling.enabled=false",
        "yon.tracking.match.enabled=true","yon.tracking.match.summary-link=/test-recommendations",
        "yon.tracking.match.outbox.enabled=true","yon.tracking.match.outbox.max-summary-candidates=2",
        "yon.tracking.eligibility-outbox.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({MatchTrackingConfig.class,MatchObservationTransaction.class,MatchCandidatePage.class,
        MatchBatchObservationListener.class,
        com.team3.backend.tracking.config.MatchOutboxConfig.class,MatchOutboxTransaction.class,MatchNotificationMessageFactory.class,
        ApplicationStatusCalculator.class,MatchObservationPostgresTest.Beans.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MatchObservationPostgresTest {
    static final Instant NOW=Instant.parse("2026-10-26T00:00:00Z");
    static final LocalDate TODAY=LocalDate.of(2026,10,26);
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword); r.add("spring.datasource.driver-class-name",POSTGRES::getDriverClassName);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Beans {
        @Bean @Primary TestClock matchClock() { return new TestClock(); }
    }
    static class TestClock extends Clock {
        private volatile Instant now=NOW;
        void set(Instant value) { now=value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now,zone); }
        @Override public Instant instant() { return now; }
    }
    @Autowired TestClock clock;
    @Autowired MatchObservationTransaction observations;
    @Autowired MatchCandidatePage pages;
    @Autowired MatchPairStateRepository pairs;
    @Autowired MatchCandidateEventRepository candidates;
    @Autowired MatchOutboxTransaction matchOutbox;
    @Autowired com.team3.backend.outbox.domain.repository.OutboxEventRepository outboxes;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.context.ApplicationEventPublisher applicationEvents;
    private long userId;

    @BeforeEach void resetDisposableContainer() {
        clock.set(NOW);
        jdbc.execute("TRUNCATE outbox_events,notification_match_candidates,notification_match_pairs,eligibility_set_results,eligibility_profiles,"
                +"user_opportunity_preferences,user_interests,application_windows,opportunity_versions,opportunities,users,regions RESTART IDENTITY CASCADE");
        userId=tx(()->{
            var user=User.builder().nickname("match-test").role(Role.USER).build(); em.persist(user);
            var region=Region.builder().regionCode("1168000000").regionName("test-region").build(); em.persist(region);
            em.persist(EligibilityProfile.builder().userId(user.getId()).birthDate(LocalDate.of(1990,1,1))
                    .residenceRegion(region).employmentStatus(EmploymentStatus.UNEMPLOYED).educationLevel(EducationLevel.UNIVERSITY).build());
            em.flush(); return user.getId();
        });
    }
    private long policy(EligibilityStatus status) {
        return tx(()->{
            var p=Opportunity.builder().opportunityType(OpportunityType.YOUTH_POLICY).title("match-policy")
                    .category("employment").subCategory("job-search").build(); em.persist(p);
            var v=OpportunityVersion.builder().opportunity(p).versionNo(1).conditionSetId(p.getId()+100)
                    .normalizationVersion("v1").normalizedSnapshot(Map.of()).personalizationStatus(PersonalizationStatus.PERSONALIZABLE).build();
            em.persist(v); p.changeCurrentVersion(v);
            em.persist(ApplicationWindow.builder().version(v).periodType(PeriodType.ALWAYS).validationStatus(ValidationStatus.VALID).build());
            em.persist(EligibilityResult.builder().userId(userId).conditionSetId(v.getConditionSetId()).profileVersion(1L)
                    .ruleVersion(EligibilityResultService.RULE_VERSION).eligibilityStatus(status)
                    .conditionResults(List.of()).userInfoMissingCount(status==POSSIBLY_ELIGIBLE?1:0)
                    .evaluatedAt(LocalDateTime.ofInstant(NOW,ZoneOffset.UTC)).validUntil(TODAY.plusYears(1)).build());
            em.flush(); return p.getId();
        });
    }
    private void status(long policy,EligibilityStatus status) {
        jdbc.update("""
                UPDATE eligibility_set_results r SET eligibility_status=?,user_info_missing_count=?,check_required_count=0
                FROM opportunities o JOIN opportunity_versions v ON v.id=o.current_version_id
                WHERE o.id=? AND r.user_id=? AND r.condition_set_id=v.condition_set_id
                """,status.name(),status==POSSIBLY_ELIGIBLE?1:0,policy,userId);
    }
    private void unavailable(long policy) {
        jdbc.update("UPDATE opportunity_versions v SET personalization_status='SPLIT_REQUIRED' FROM opportunities o WHERE o.id=? AND v.id=o.current_version_id",policy);
    }
    private void interest() {
        tx(()->{em.persist(UserInterest.create(em.find(User.class,userId),InterestType.CATEGORY,"employment")); return null;});
    }
    private void upcoming(long policy,boolean saved) {
        jdbc.update("""
                UPDATE application_windows w SET period_type='FIXED',start_date=?,end_date=?
                FROM opportunities o WHERE o.id=? AND w.version_id=o.current_version_id
                """,TODAY.plusDays(2),TODAY.plusDays(5),policy);
        if (saved) tx(()->{
            var preference=UserOpportunityPreference.builder().user(em.find(User.class,userId)).opportunity(em.find(Opportunity.class,policy)).build();
            preference.markSaved(em.find(Opportunity.class,policy).getCurrentVersion().getId()); em.persist(preference); return null;
        });
    }
    @Test void initialEligibleCreatesOnePendingIntentAndReplayDoesNotDuplicate() {
        long p=policy(ELIGIBLE); var result=observations.observe(userId,p);
        assertThat(result.reason()).isEqualTo(MatchPairState.Reason.FIRST_MATCH);
        assertThat(candidates.findById(result.candidateId()).orElseThrow().getStatus()).isEqualTo("PENDING");
        var id=candidates.findAll().getFirst().getEventId();
        assertThat(observations.observe(userId,p).status()).isEqualTo(MatchObservationTransaction.Status.DUPLICATE);
        assertThat(candidates.count()).isEqualTo(1); assertThat(candidates.findAll().getFirst().getEventId()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events",Long.class)).isZero();
    }
    @Test void repeatedReturnToEligibleProducesDistinctTransitionsButNoLossIntents() {
        long p=policy(ELIGIBLE); observations.observe(userId,p);
        status(p,NOT_ELIGIBLE); assertThat(observations.observe(userId,p).candidateId()).isNull();
        status(p,ELIGIBLE); assertThat(observations.observe(userId,p).reason()).isEqualTo(MatchPairState.Reason.TRANSITION);
        observations.observe(userId,p);
        status(p,NOT_ELIGIBLE); observations.observe(userId,p);
        status(p,ELIGIBLE); observations.observe(userId,p);
        var pair=pairs.findByUserIdAndOpportunityId(userId,p).orElseThrow();
        assertThat(pair.getTransitionCount()).isEqualTo(2);
        var rows=candidates.findByPairIdOrderById(pair.getId());
        assertThat(rows).extracting(e->e.getReason().name()).containsExactly("FIRST_MATCH","TRANSITION","TRANSITION");
        assertThat(rows).extracting(e->e.getOccurrenceNo()).containsExactly(0L,1L,2L);
        assertThat(rows.stream().map(e->e.getEventId()).distinct().count()).isEqualTo(3);
    }
    @Test void possiblyNeedsRealInterestAndCombinedCountAtMostOne() {
        long p=policy(POSSIBLY_ELIGIBLE);
        assertThat(observations.observe(userId,p).candidateId()).isNull();
        interest(); assertThat(observations.observe(userId,p).reason()).isEqualTo(MatchPairState.Reason.FIRST_MATCH);
        assertThat(candidates.findAll().getFirst().getEvidence()).containsEntry("userInfoMissing",1).containsEntry("checkRequired",0);
    }
    @Test void twoUnresolvedConditionsExcludePossiblyEvenWithInterest() {
        long p=policy(POSSIBLY_ELIGIBLE); interest(); jdbc.update("UPDATE eligibility_set_results SET check_required_count=1");
        assertThat(observations.observe(userId,p).candidateId()).isNull(); assertThat(candidates.count()).isZero();
    }
    @Test void unknownIsRecordedButNeverCreatesAnIntent() {
        long p=policy(UNKNOWN); assertThat(observations.observe(userId,p).candidateId()).isNull();
        assertThat(pairs.findByUserIdAndOpportunityId(userId,p).orElseThrow().getLastStatus()).isEqualTo(UNKNOWN);
        assertThat(candidates.count()).isZero();
    }
    @Test void unsavedUpcomingIsExcludedButSavedUpcomingCanQualify() {
        long p=policy(ELIGIBLE); upcoming(p,false); assertThat(observations.observe(userId,p).candidateId()).isNull();
        upcoming(p,true); assertThat(observations.observe(userId,p).reason()).isEqualTo(MatchPairState.Reason.FIRST_MATCH);
    }
    @Test void trainingNeverCreatesPairOrIntent() {
        long p=policy(ELIGIBLE); jdbc.update("UPDATE opportunities SET opportunity_type='VOCATIONAL_TRAINING' WHERE id=?",p);
        assertThat(observations.observe(userId,p).status()).isEqualTo(MatchObservationTransaction.Status.POLICY_UNAVAILABLE);
        assertThat(pairs.count()).isZero(); assertThat(candidates.count()).isZero();
    }
    @Test void staleProfileOrRuleDoesNotCreateOrAdvancePair() {
        long p=policy(ELIGIBLE); jdbc.update("UPDATE eligibility_profiles SET profile_version=2");
        assertThat(observations.observe(userId,p).status()).isEqualTo(MatchObservationTransaction.Status.RESULT_STALE_OR_MISSING);
        jdbc.update("UPDATE eligibility_set_results SET profile_version=2,rule_version='obsolete'");
        assertThat(observations.observe(userId,p).status()).isEqualTo(MatchObservationTransaction.Status.RESULT_STALE_OR_MISSING);
        assertThat(pairs.count()).isZero();
    }
    @Test void expiryOnTodayIsStaleAndAbsentResultIsNotNotEligible() {
        long p=policy(ELIGIBLE); jdbc.update("UPDATE eligibility_set_results SET valid_until=?",TODAY);
        assertThat(observations.observe(userId,p).status()).isEqualTo(MatchObservationTransaction.Status.RESULT_STALE_OR_MISSING);
        jdbc.update("DELETE FROM eligibility_set_results");
        assertThat(observations.observe(userId,p).status()).isEqualTo(MatchObservationTransaction.Status.RESULT_STALE_OR_MISSING);
        assertThat(pairs.count()).isZero();
    }
    @Test void profileMissingDoesNotCreatePair() {
        long p=policy(ELIGIBLE); jdbc.update("DELETE FROM eligibility_profiles");
        assertThat(observations.observe(userId,p).status()).isEqualTo(MatchObservationTransaction.Status.PROFILE_MISSING);
        assertThat(pairs.count()).isZero();
    }
    @Test void failedCandidateInsertRollsBackPairCounterAndAllowsRetry() {
        long p=policy(NOT_ELIGIBLE); observations.observe(userId,p); status(p,ELIGIBLE);
        jdbc.execute("ALTER TABLE notification_match_candidates ADD CONSTRAINT reject_transition CHECK(reason<>'TRANSITION')");
        try { assertThatThrownBy(()->observations.observe(userId,p)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE notification_match_candidates DROP CONSTRAINT reject_transition"); }
        var pair=pairs.findByUserIdAndOpportunityId(userId,p).orElseThrow();
        assertThat(pair.getLastStatus()).isEqualTo(NOT_ELIGIBLE); assertThat(pair.getTransitionCount()).isZero();
        assertThat(candidates.count()).isZero();
        assertThat(observations.observe(userId,p).reason()).isEqualTo(MatchPairState.Reason.TRANSITION);
        assertThat(pairs.findByUserIdAndOpportunityId(userId,p).orElseThrow().getTransitionCount()).isEqualTo(1);
    }
    @Test void concurrentFirstObservationCreatesOnePairAndOneIntent() throws Exception {
        long p=policy(ELIGIBLE); var gate=new CountDownLatch(1);
        Callable<MatchObservationTransaction.Result> work=()->{gate.await();return observations.observe(userId,p);};
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=pool.submit(work); var b=pool.submit(work); gate.countDown();
            var statuses=List.of(a.get(20,TimeUnit.SECONDS).status(),b.get(20,TimeUnit.SECONDS).status());
            assertThat(statuses).containsExactlyInAnyOrder(MatchObservationTransaction.Status.CANDIDATE_CREATED,MatchObservationTransaction.Status.DUPLICATE);
        }
        assertThat(pairs.count()).isEqualTo(1); assertThat(candidates.count()).isEqualTo(1);
    }
    @Test void keysetPagesRecordEveryFirstMatchRatherThanTopN() {
        long a=policy(ELIGIBLE); long b=policy(ELIGIBLE); long c=policy(ELIGIBLE);
        var first=pages.scanUser(userId,0,2);
        assertThat(first.visited()).isEqualTo(2); assertThat(first.created()).isEqualTo(2); assertThat(first.hasNext()).isTrue();
        assertThat(first.lastOpportunityId()).isEqualTo(b);
        var last=pages.scanUser(userId,first.lastOpportunityId(),2);
        assertThat(last.visited()).isEqualTo(1); assertThat(last.lastOpportunityId()).isEqualTo(c); assertThat(last.hasNext()).isFalse();
        assertThat(candidates.count()).isEqualTo(3); assertThat(pairs.findByUserIdAndOpportunityId(userId,a)).isPresent();
        assertThat(pages.scanUser(userId,0,3).created()).isZero(); assertThat(candidates.count()).isEqualTo(3);
    }
    @Test void unchangedPoliciesSkipTheLockedObservationOnRescan() {
        policy(ELIGIBLE); policy(NOT_ELIGIBLE); policy(POSSIBLY_ELIGIBLE);
        var first=pages.scanUser(userId,0,10);
        assertThat(first.visited()).isEqualTo(3); assertThat(first.unchanged()).isZero(); assertThat(first.created()).isEqualTo(1);
        assertThat(pairs.count()).isEqualTo(3);
        var again=pages.scanUser(userId,0,10);
        assertThat(again.visited()).isEqualTo(3); assertThat(again.unchanged()).isEqualTo(3);
        assertThat(again.created()).isZero(); assertThat(again.failed()).isZero();
        assertThat(candidates.count()).isEqualTo(1);
    }
    @Test void prefilterDetectsInterestSaveAndDateBoundaryChanges() {
        long possibly=policy(POSSIBLY_ELIGIBLE), later=policy(ELIGIBLE); upcoming(later,false);
        assertThat(pages.scanUser(userId,0,10).created()).isZero(); assertThat(candidates.count()).isZero();
        assertThat(pages.scanUser(userId,0,10).unchanged()).isEqualTo(2);
        interest();
        var interestRun=pages.scanUser(userId,0,10);
        // Both policies share the category, so the interest flag changes both keys; only the POSSIBLY one qualifies.
        assertThat(interestRun.unchanged()).isZero(); assertThat(interestRun.created()).isEqualTo(1);
        assertThat(candidates.findAll().getFirst().getPair().getId())
                .isEqualTo(pairs.findByUserIdAndOpportunityId(userId,possibly).orElseThrow().getId());
        tx(()->{
            var preference=UserOpportunityPreference.builder().user(em.find(User.class,userId)).opportunity(em.find(Opportunity.class,later)).build();
            preference.markSaved(em.find(Opportunity.class,later).getCurrentVersion().getId()); em.persist(preference); return null;
        });
        var savedRun=pages.scanUser(userId,0,10);
        assertThat(savedRun.unchanged()).isEqualTo(1); assertThat(savedRun.created()).isEqualTo(1);
        assertThat(candidates.count()).isEqualTo(2);
        jdbc.update("DELETE FROM user_opportunity_preferences"); jdbc.update("DELETE FROM notification_match_candidates");
        jdbc.update("UPDATE notification_match_pairs SET ever_matched=false");
        assertThat(pages.scanUser(userId,0,10).unchanged()).isEqualTo(1);
        clock.set(NOW.plus(Duration.ofDays(3)));
        var openRun=pages.scanUser(userId,0,10);
        assertThat(openRun.unchanged()).isEqualTo(1); assertThat(openRun.created()).isEqualTo(1);
        assertThat(pages.scanUser(userId,0,10).unchanged()).isEqualTo(2);
    }
    @Test void prefilterMismatchStillRevalidatesUnderLockWithoutDuplicateCandidate() {
        long p=policy(ELIGIBLE); assertThat(pages.scanUser(userId,0,10).created()).isEqualTo(1);
        jdbc.update("UPDATE notification_match_pairs SET last_input_key='stale-prefilter-key'");
        var run=pages.scanUser(userId,0,10);
        assertThat(run.unchanged()).isZero(); assertThat(run.created()).isZero(); assertThat(run.failed()).isZero();
        assertThat(candidates.count()).isEqualTo(1);
        assertThat(pairs.findByUserIdAndOpportunityId(userId,p).orElseThrow().getLastInputKey()).isNotEqualTo("stale-prefilter-key");
        assertThat(pages.scanUser(userId,0,10).unchanged()).isEqualTo(1);
    }
    @Test void prefilterRowFailureIsIsolatedPerPolicy() {
        long broken=policy(ELIGIBLE); policy(ELIGIBLE); policy(ELIGIBLE);
        jdbc.update("UPDATE opportunity_versions v SET normalization_version='' FROM opportunities o WHERE o.id=? AND v.id=o.current_version_id",broken);
        var run=pages.scanUser(userId,0,10);
        assertThat(run.visited()).isEqualTo(3); assertThat(run.failed()).isEqualTo(1); assertThat(run.created()).isEqualTo(2);
        assertThat(run.lastOpportunityId()).isGreaterThan(broken);
        assertThat(pairs.findByUserIdAndOpportunityId(userId,broken)).isEmpty();
    }
    @Test void pageIncludesNegativeObservationsForFutureTransitionDetection() {
        long p=policy(NOT_ELIGIBLE); assertThat(pages.scanUser(userId,0,10).created()).isZero();
        assertThat(pairs.count()).isEqualTo(1); status(p,ELIGIBLE);
        assertThat(pages.scanUser(userId,0,10).created()).isEqualTo(1);
        assertThat(candidates.findAll().getFirst().getReason()).isEqualTo(MatchPairState.Reason.TRANSITION);
    }
    @Test void userDeletionCascadesPersonalPairAndIntentAndLateWorkCannotRecreateThem() {
        long p=policy(ELIGIBLE); observations.observe(userId,p);
        jdbc.update("DELETE FROM users WHERE id=?",userId);
        assertThat(pairs.count()).isZero(); assertThat(candidates.count()).isZero();
        assertThat(observations.observe(userId,p).status()).isEqualTo(MatchObservationTransaction.Status.USER_UNAVAILABLE);
    }
    @Test void currentVersionChangeUsesFreshResultsNotOldVersionStatus() {
        long p=policy(NOT_ELIGIBLE); observations.observe(userId,p);
        tx(()->{
            var policy=em.find(Opportunity.class,p); long setId=p+1000;
            var v=OpportunityVersion.builder().opportunity(policy).versionNo(2).normalizationVersion("v1")
                    .personalizationStatus(PersonalizationStatus.PERSONALIZABLE).normalizedSnapshot(Map.of()).conditionSetId(setId).build();
            em.persist(v); policy.changeCurrentVersion(v);
            em.persist(ApplicationWindow.builder().version(v).periodType(PeriodType.ALWAYS).validationStatus(ValidationStatus.VALID).build());
            em.persist(EligibilityResult.builder().userId(userId).conditionSetId(setId).profileVersion(1L)
                    .ruleVersion(EligibilityResultService.RULE_VERSION).eligibilityStatus(ELIGIBLE).conditionResults(List.of())
                    .evaluatedAt(LocalDateTime.ofInstant(NOW,ZoneOffset.UTC)).build()); return null;
        });
        assertThat(observations.observe(userId,p).reason()).isEqualTo(MatchPairState.Reason.TRANSITION);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eligibility_set_results",Long.class)).isEqualTo(2);
    }
    @Test void invalidPageBudgetIsRejected() {
        assertThatThrownBy(()->pages.scanUser(userId,0,501)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->pages.scanUser(userId,-1,1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void summaryContainsEveryPendingMatchAndReplayDoesNotConsumeNewCandidates() {
        long a=policy(ELIGIBLE), b=policy(POSSIBLY_ELIGIBLE); interest();
        observations.observe(userId,a); observations.observe(userId,b);
        UUID run=UUID.randomUUID(); var result=matchOutbox.summarize(userId,run,NOW.plusSeconds(3600));
        assertThat(result.status()).isEqualTo(MatchOutboxTransaction.Status.ENQUEUED); assertThat(result.members()).isEqualTo(2);
        var outbox=outboxes.findById(result.outboxId()).orElseThrow();
        assertThat(outbox.getNotificationType()).isEqualTo("NEW_MATCH");
        assertThat(outbox.getPayload()).containsEntry("link","/test-recommendations");
        assertThat(((Map<?,?>)outbox.getPayload().get("payload")).get("matchCount")).isEqualTo(2);
        assertThat(candidates.findAll()).allSatisfy(c->{assertThat(c.getStatus()).isEqualTo("ENQUEUED"); assertThat(c.getOutboxId()).isEqualTo(outbox.getId());});
        long c=policy(ELIGIBLE); var next=observations.observe(userId,c);
        assertThat(matchOutbox.summarize(userId,run,NOW.minusSeconds(1)).status()).isEqualTo(MatchOutboxTransaction.Status.DUPLICATE);
        assertThat(candidates.findById(next.candidateId()).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(outboxes.count()).isEqualTo(1);
    }
    @Test void automaticDailySummaryUsesOneRunForAllPendingPolicies() {
        long a=policy(ELIGIBLE), b=policy(ELIGIBLE);
        observations.observe(userId,a); observations.observe(userId,b);
        var morning=NOW.minus(Duration.ofMinutes(25));
        var worker=new MatchAutomaticWorker(jdbc,matchOutbox,
                new com.team3.backend.tracking.config.MatchAutomaticProperties(true,false,25,25,60000),
                Clock.fixed(morning,ZoneOffset.UTC));
        assertThat(worker.runOnce().summaryCalls()).isEqualTo(1);
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(candidates.findAll()).allMatch(c->c.getStatus().equals("ENQUEUED"));
        var payload=(Map<?,?>)outboxes.findAll().getFirst().getPayload().get("payload");
        assertThat(payload.get("summaryRunId")).isEqualTo(MatchSummaryWindow.current(morning).runId(userId).toString());
        assertThat(worker.runOnce().summaryCalls()).isZero();
    }
    @Test void summaryDoesNotPartiallyConsumeWhenOneResultIsStale() {
        long a=policy(ELIGIBLE), b=policy(ELIGIBLE); observations.observe(userId,a); observations.observe(userId,b);
        jdbc.update("UPDATE eligibility_set_results SET profile_version=2 WHERE condition_set_id=?",b+100);
        assertThat(matchOutbox.summarize(userId,UUID.randomUUID(),NOW.plusSeconds(3600)).status()).isEqualTo(MatchOutboxTransaction.Status.DEFERRED);
        assertThat(outboxes.count()).isZero(); assertThat(candidates.findAll()).allMatch(c->c.getStatus().equals("PENDING"));
    }
    @Test void changedCurrentInputWaitsForObservationBeforeSummary() {
        long p=policy(ELIGIBLE); observations.observe(userId,p); status(p,NOT_ELIGIBLE);
        UUID run=UUID.randomUUID();
        assertThat(matchOutbox.summarize(userId,run,NOW.plusSeconds(3600)).status()).isEqualTo(MatchOutboxTransaction.Status.DEFERRED);
        observations.observe(userId,p);
        assertThat(matchOutbox.summarize(userId,run,NOW.plusSeconds(3600)).status()).isEqualTo(MatchOutboxTransaction.Status.EMPTY);
        assertThat(candidates.findAll().getFirst().getStatus()).isEqualTo("SKIPPED"); assertThat(outboxes.count()).isZero();
    }
    @Test void summaryClosesOnlyUnavailablePolicyAndStillSendsTheRest() {
        long gone=policy(ELIGIBLE), live=policy(ELIGIBLE);
        long goneCandidate=observations.observe(userId,gone).candidateId(); long liveCandidate=observations.observe(userId,live).candidateId();
        unavailable(gone);
        var result=matchOutbox.summarize(userId,UUID.randomUUID(),NOW.plusSeconds(3600));
        assertThat(result.status()).isEqualTo(MatchOutboxTransaction.Status.ENQUEUED); assertThat(result.members()).isEqualTo(1);
        var closed=candidates.findById(goneCandidate).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo("SKIPPED"); assertThat(closed.getCloseReason()).isEqualTo("POLICY_UNAVAILABLE"); assertThat(closed.getOutboxId()).isNull();
        assertThat(candidates.findById(liveCandidate).orElseThrow().getOutboxId()).isEqualTo(result.outboxId());
        assertThat(((Map<?,?>)outboxes.findById(result.outboxId()).orElseThrow().getPayload().get("payload")).get("matchCount")).isEqualTo(1);
    }
    @Test void summaryWithOnlyUnavailablePoliciesClosesThemWithoutOutbox() {
        long gone=policy(ELIGIBLE); long c=observations.observe(userId,gone).candidateId(); unavailable(gone);
        assertThat(matchOutbox.summarize(userId,UUID.randomUUID(),NOW.plusSeconds(3600)).status()).isEqualTo(MatchOutboxTransaction.Status.EMPTY);
        assertThat(candidates.findById(c).orElseThrow().getCloseReason()).isEqualTo("POLICY_UNAVAILABLE"); assertThat(outboxes.count()).isZero();
    }
    @Test void missingConditionSetDefersSummaryInsteadOfClosingCandidates() {
        long a=policy(ELIGIBLE), b=policy(ELIGIBLE); observations.observe(userId,a); observations.observe(userId,b);
        jdbc.update("UPDATE opportunity_versions v SET condition_set_id=NULL FROM opportunities o WHERE o.id=? AND v.id=o.current_version_id",b);
        assertThat(matchOutbox.summarize(userId,UUID.randomUUID(),NOW.plusSeconds(3600)).status()).isEqualTo(MatchOutboxTransaction.Status.DEFERRED);
        assertThat(candidates.findAll()).allMatch(c->c.getStatus().equals("PENDING")); assertThat(outboxes.count()).isZero();
    }
    @Test void invalidSummaryExpiryLeavesAllMembersPending() {
        observations.observe(userId,policy(ELIGIBLE));
        assertThatThrownBy(()->matchOutbox.summarize(userId,UUID.randomUUID(),NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(outboxes.count()).isZero(); assertThat(candidates.findAll().getFirst().getStatus()).isEqualTo("PENDING");
    }
    @Test void summaryCandidateUpdateFailureRollsBackOutboxAndEveryMember() {
        observations.observe(userId,policy(ELIGIBLE));
        jdbc.execute("ALTER TABLE notification_match_candidates ADD CONSTRAINT test_no_enqueue CHECK (status <> 'ENQUEUED')");
        try {
            assertThatThrownBy(()->matchOutbox.summarize(userId,UUID.randomUUID(),NOW.plusSeconds(3600))).isInstanceOf(RuntimeException.class);
            assertThat(outboxes.count()).isZero(); assertThat(candidates.findAll().getFirst().getStatus()).isEqualTo("PENDING");
        } finally { jdbc.execute("ALTER TABLE notification_match_candidates DROP CONSTRAINT test_no_enqueue"); }
    }
    @Test void repeatedActualTransitionsHaveDifferentOutboxesButRetriesDoNot() {
        long p=policy(NOT_ELIGIBLE); observations.observe(userId,p); status(p,ELIGIBLE);
        long first=observations.observe(userId,p).candidateId();
        assertThat(matchOutbox.transition(userId,first).status()).isEqualTo(MatchOutboxTransaction.Status.ENQUEUED);
        assertThat(matchOutbox.transition(userId,first).status()).isEqualTo(MatchOutboxTransaction.Status.DUPLICATE);
        status(p,NOT_ELIGIBLE); observations.observe(userId,p); status(p,ELIGIBLE);
        long second=observations.observe(userId,p).candidateId();
        assertThat(matchOutbox.transition(userId,second).status()).isEqualTo(MatchOutboxTransaction.Status.ENQUEUED);
        assertThat(outboxes.count()).isEqualTo(2);
        assertThat(outboxes.findAll()).allMatch(o->o.getNotificationType().equals("BECAME_ELIGIBLE"));
    }
    @Test void automaticTransitionPageUsesPendingCandidateAndDoesNotRepublishIt() {
        long p=policy(NOT_ELIGIBLE); observations.observe(userId,p); status(p,ELIGIBLE);
        long candidate=observations.observe(userId,p).candidateId();
        var worker=new MatchAutomaticWorker(jdbc,matchOutbox,
                new com.team3.backend.tracking.config.MatchAutomaticProperties(true,false,25,25,60000),
                Clock.fixed(NOW,ZoneOffset.UTC));
        assertThat(worker.runOnce().transitionCalls()).isEqualTo(1);
        assertThat(candidates.findById(candidate).orElseThrow().getStatus()).isEqualTo("ENQUEUED");
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(worker.runOnce().transitionCalls()).isZero();
        assertThat(outboxes.count()).isEqualTo(1);
    }
    @Test void olderUnsentTransitionCannotBecomeTheNewOccurrence() {
        long p=policy(NOT_ELIGIBLE); observations.observe(userId,p); status(p,ELIGIBLE);
        long old=observations.observe(userId,p).candidateId();
        status(p,NOT_ELIGIBLE); observations.observe(userId,p); status(p,ELIGIBLE);
        long current=observations.observe(userId,p).candidateId();
        assertThat(matchOutbox.transition(userId,old).status()).isEqualTo(MatchOutboxTransaction.Status.SKIPPED);
        assertThat(matchOutbox.transition(userId,current).status()).isEqualTo(MatchOutboxTransaction.Status.ENQUEUED);
        assertThat(outboxes.count()).isEqualTo(1);
    }
    @Test void transitionForUnavailablePolicyIsClosedInsteadOfRetriedForever() {
        long p=policy(NOT_ELIGIBLE); observations.observe(userId,p); status(p,ELIGIBLE);
        long candidate=observations.observe(userId,p).candidateId(); unavailable(p);
        assertThat(matchOutbox.transition(userId,candidate).status()).isEqualTo(MatchOutboxTransaction.Status.SKIPPED);
        assertThat(candidates.findById(candidate).orElseThrow().getCloseReason()).isEqualTo("POLICY_UNAVAILABLE");
        assertThat(matchOutbox.transition(userId,candidate).status()).isEqualTo(MatchOutboxTransaction.Status.SKIPPED);
        assertThat(outboxes.count()).isZero();
    }
    @Test void expiredTransitionDoesNotGetNewExpiryOnRetry() {
        long p=policy(NOT_ELIGIBLE); observations.observe(userId,p); status(p,ELIGIBLE);
        long candidate=observations.observe(userId,p).candidateId();
        jdbc.update("UPDATE notification_match_candidates SET created_at=? WHERE id=?",java.sql.Timestamp.from(NOW.minus(Duration.ofDays(7))),candidate);
        assertThat(matchOutbox.transition(userId,candidate).status()).isEqualTo(MatchOutboxTransaction.Status.SKIPPED);
        assertThat(candidates.findById(candidate).orElseThrow().getCloseReason()).isEqualTo("EXPIRED");
        assertThat(outboxes.count()).isZero();
    }
    @Test void concurrentSummaryRunCreatesOnlyOneOutbox() throws Exception {
        observations.observe(userId,policy(ELIGIBLE)); UUID run=UUID.randomUUID();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->matchOutbox.summarize(userId,run,NOW.plusSeconds(3600)));
            var b=pool.submit(()->matchOutbox.summarize(userId,run,NOW.plusSeconds(3600)));
            a.get(20,TimeUnit.SECONDS); b.get(20,TimeUnit.SECONDS);
        }
        assertThat(outboxes.count()).isEqualTo(1); assertThat(candidates.findAll().getFirst().getStatus()).isEqualTo("ENQUEUED");
    }
    @Test void firstMatchCannotBeUsedAsTransitionAndOtherUserCannotConsumeIt() {
        long id=observations.observe(userId,policy(ELIGIBLE)).candidateId();
        assertThatThrownBy(()->matchOutbox.transition(userId,id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->matchOutbox.transition(userId+1,id)).isInstanceOf(IllegalArgumentException.class);
        assertThat(outboxes.count()).isZero();
    }
    @Test void overBudgetSummaryProgressesInOrderAcrossDaysWithoutSameDayDuplicate() {
        clock.set(NOW.minus(Duration.ofMinutes(25)));
        long first=observations.observe(userId,policy(ELIGIBLE)).candidateId();
        long second=observations.observe(userId,policy(ELIGIBLE)).candidateId();
        long remainder=observations.observe(userId,policy(ELIGIBLE)).candidateId();
        var worker=new MatchAutomaticWorker(jdbc,matchOutbox,
                new com.team3.backend.tracking.config.MatchAutomaticProperties(true,false,25,25,60000),clock);
        worker.runOnce();
        assertThat(outboxes.count()).isEqualTo(1);
        Long firstOutbox=candidates.findById(first).orElseThrow().getOutboxId();
        assertThat(candidates.findById(second).orElseThrow().getOutboxId()).isEqualTo(firstOutbox);
        assertThat(candidates.findById(remainder).orElseThrow().getStatus()).isEqualTo("PENDING");
        worker.runOnce();
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(candidates.findById(remainder).orElseThrow().getStatus()).isEqualTo("PENDING");

        clock.set(clock.instant().plus(Duration.ofDays(1)));
        worker.runOnce();
        assertThat(outboxes.count()).isEqualTo(2);
        var last=candidates.findById(remainder).orElseThrow();
        assertThat(last.getStatus()).isEqualTo("ENQUEUED");
        assertThat(last.getOutboxId()).isNotNull().isNotEqualTo(firstOutbox);
        assertThat(candidates.findAll()).allMatch(c->c.getStatus().equals("ENQUEUED"));
        worker.runOnce();
        assertThat(outboxes.count()).isEqualTo(2);
    }
    @Test void nextDaySummaryRevalidatesRemainderInsteadOfSendingOldQualification() {
        clock.set(NOW.minus(Duration.ofMinutes(25)));
        observations.observe(userId,policy(ELIGIBLE)); observations.observe(userId,policy(ELIGIBLE));
        long policyId=policy(ELIGIBLE);
        long remainder=observations.observe(userId,policyId).candidateId();
        var worker=new MatchAutomaticWorker(jdbc,matchOutbox,
                new com.team3.backend.tracking.config.MatchAutomaticProperties(true,false,25,25,60000),clock);
        worker.runOnce();
        clock.set(clock.instant().plus(Duration.ofDays(1)));
        status(policyId,NOT_ELIGIBLE);
        worker.runOnce(); // A stale observation defers the page without consuming it.
        assertThat(candidates.findById(remainder).orElseThrow().getStatus()).isEqualTo("PENDING");
        observations.observe(userId,policyId);
        worker.runOnce();
        assertThat(candidates.findById(remainder).orElseThrow().getStatus()).isEqualTo("SKIPPED");
        assertThat(outboxes.count()).isEqualTo(1);
    }
    @Test void transitionCandidateUpdateFailureRollsBackOutbox() {
        long p=policy(NOT_ELIGIBLE); observations.observe(userId,p); status(p,ELIGIBLE);
        long id=observations.observe(userId,p).candidateId();
        jdbc.execute("ALTER TABLE notification_match_candidates ADD CONSTRAINT test_no_transition_enqueue CHECK (status <> 'ENQUEUED')");
        try {
            assertThatThrownBy(()->matchOutbox.transition(userId,id)).isInstanceOf(RuntimeException.class);
            assertThat(outboxes.count()).isZero(); assertThat(candidates.findById(id).orElseThrow().getStatus()).isEqualTo("PENDING");
        } finally { jdbc.execute("ALTER TABLE notification_match_candidates DROP CONSTRAINT test_no_transition_enqueue"); }
    }
    @Test void concurrentTransitionCreatesOneOutboxAndOneBinding() throws Exception {
        long p=policy(NOT_ELIGIBLE); observations.observe(userId,p); status(p,ELIGIBLE);
        long id=observations.observe(userId,p).candidateId();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->matchOutbox.transition(userId,id)); var b=pool.submit(()->matchOutbox.transition(userId,id));
            a.get(20,TimeUnit.SECONDS); b.get(20,TimeUnit.SECONDS);
        }
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(candidates.findById(id).orElseThrow().getOutboxId()).isEqualTo(outboxes.findAll().getFirst().getId());
    }
    @Test void normalizationRuleChangeDoesNotReleaseOldFirstMatch() {
        long p=policy(ELIGIBLE); observations.observe(userId,p);
        jdbc.update("UPDATE opportunity_versions SET normalization_version='v2' WHERE opportunity_id=?",p);
        observations.observe(userId,p);
        assertThat(matchOutbox.summarize(userId,UUID.randomUUID(),NOW.plusSeconds(3600)).status()).isEqualTo(MatchOutboxTransaction.Status.EMPTY);
        assertThat(outboxes.count()).isZero(); assertThat(candidates.findAll().getFirst().getStatus()).isEqualTo("SKIPPED");
    }
    @Test void batchEventWaitsForCommitAndDoesNotCreateOutboxAutomatically() {
        policy(ELIGIBLE);
        tx(()->{
            applicationEvents.publishEvent(new com.team3.backend.eligibility.event.EligibilityResultsRefreshed(userId));
            assertThat(pairs.count()).isZero(); return null;
        });
        assertThat(candidates.count()).isEqualTo(1); assertThat(outboxes.count()).isZero();
        applicationEvents.publishEvent(new com.team3.backend.eligibility.event.EligibilityResultsRefreshed(userId));
        assertThat(candidates.count()).isEqualTo(1);
    }
    @Test void rolledBackBatchEventDoesNotObserveUntilLaterSuccessfulRefresh() {
        policy(ELIGIBLE);
        new TransactionTemplate(transactions).execute(status->{
            applicationEvents.publishEvent(new com.team3.backend.eligibility.event.EligibilityResultsRefreshed(userId));
            status.setRollbackOnly(); return null;
        });
        assertThat(pairs.count()).isZero();
        applicationEvents.publishEvent(new com.team3.backend.eligibility.event.EligibilityResultsRefreshed(userId));
        assertThat(candidates.count()).isEqualTo(1); assertThat(outboxes.count()).isZero();
    }
    @Test void savedPolicyChangeFromBeforeSaveDoesNotCreatePositiveCandidate() {
        long p=policy(NOT_ELIGIBLE); saveCurrent(p); observations.observe(userId,p);
        eligibleNextVersion(p); resaveCurrent(p);
        assertThat(observations.observe(userId,p).candidateId()).isNull();
        assertThat(pairs.findByUserIdAndOpportunityId(userId,p).orElseThrow().getLastStatus()).isEqualTo(ELIGIBLE);
        assertThat(candidates.count()).isZero();
    }
    @Test void policyCandidateRechecksResaveBeforeOutbox() {
        long p=policy(NOT_ELIGIBLE); saveCurrent(p); observations.observe(userId,p);
        eligibleNextVersion(p); long id=observations.observe(userId,p).candidateId();
        assertThat(candidates.findById(id).orElseThrow().getEvidence()).containsEntry("trigger","POLICY_CHANGED");
        resaveCurrent(p);
        assertThat(matchOutbox.transition(userId,id).status()).isEqualTo(MatchOutboxTransaction.Status.SKIPPED);
        assertThat(candidates.findById(id).orElseThrow().getCloseReason()).isEqualTo("NOT_SAVED_BEFORE_SOURCE_VERSION");
        assertThat(outboxes.count()).isZero();
    }
    @Test void savedSameVersionStillAllowsRealProfileTransition() {
        long p=policy(NOT_ELIGIBLE); saveCurrent(p); observations.observe(userId,p);
        jdbc.update("UPDATE eligibility_profiles SET profile_version=2 WHERE user_id=?",userId);
        status(p,ELIGIBLE); jdbc.update("UPDATE eligibility_set_results SET profile_version=2 WHERE user_id=?",userId);
        long id=observations.observe(userId,p).candidateId();
        assertThat(candidates.findById(id).orElseThrow().getEvidence()).containsEntry("trigger","PROFILE_CHANGED");
        assertThat(matchOutbox.transition(userId,id).status()).isEqualTo(MatchOutboxTransaction.Status.ENQUEUED);
        assertThat(outboxes.count()).isEqualTo(1);
    }
    private void saveCurrent(long policyId) {
        tx(()->{
            var p=em.find(Opportunity.class,policyId);
            var preference=UserOpportunityPreference.builder().user(em.find(User.class,userId)).opportunity(p).build();
            preference.markSaved(p.getCurrentVersion().getId()); em.persist(preference); return null;
        });
    }
    private void resaveCurrent(long policyId) {
        tx(()->{
            var preference=em.createQuery("select p from UserOpportunityPreference p where p.user.id=:user and p.opportunity.id=:policy",UserOpportunityPreference.class)
                    .setParameter("user",userId).setParameter("policy",policyId).getSingleResult();
            preference.clearSaved(); preference.markSaved(em.find(Opportunity.class,policyId).getCurrentVersion().getId()); return null;
        });
    }
    private void eligibleNextVersion(long policyId) {
        tx(()->{
            var p=em.find(Opportunity.class,policyId); long setId=policyId+1000;
            var v=OpportunityVersion.builder().opportunity(p).versionNo(2).normalizationVersion("v1").conditionSetId(setId)
                    .normalizedSnapshot(Map.of()).personalizationStatus(PersonalizationStatus.PERSONALIZABLE).build();
            em.persist(v); p.changeCurrentVersion(v);
            em.persist(ApplicationWindow.builder().version(v).periodType(PeriodType.ALWAYS).validationStatus(ValidationStatus.VALID).build());
            em.persist(EligibilityResult.builder().userId(userId).conditionSetId(setId).profileVersion(1L)
                    .ruleVersion(EligibilityResultService.RULE_VERSION).eligibilityStatus(ELIGIBLE).conditionResults(List.of())
                    .evaluatedAt(LocalDateTime.ofInstant(NOW,ZoneOffset.UTC)).build()); return null;
        });
    }
    private <T>T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status->action.get()); }
}
