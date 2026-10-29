package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.engine.EligibilityEvaluation;
import com.team3.backend.eligibility.service.*;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.*;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.tracking.domain.model.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static com.team3.backend.eligibility.domain.entity.EligibilityStatus.*;
import static com.team3.backend.tracking.domain.model.EligibilityNotificationRevalidation.Outcome.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EligibilityNotificationRevalidationServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private final OpportunityVersionRepository versions = mock(OpportunityVersionRepository.class);
    private final OpportunityRepository opportunities = mock(OpportunityRepository.class);
    private final EligibilityProfileRepository profiles = mock(EligibilityProfileRepository.class);
    private final EligibilityEvaluator evaluator = mock(EligibilityEvaluator.class);
    private final EligibilityNotificationRevalidationService service = new EligibilityNotificationRevalidationService(
            versions, opportunities, profiles, evaluator, new ProvisionalEligibilityTransitionClassifier());
    private final Opportunity policy = Opportunity.builder().id(1L).opportunityType(OpportunityType.YOUTH_POLICY).build();
    private final EligibilityProfile profile = EligibilityProfile.builder().userId(7L).profileVersion(9L).build();

    @Test
    void reevaluatesThreeVersionsWithOneLatestProfileAndPreservesBothEvidenceSets() {
        fixture(false, "v1"); evaluate(NOT_ELIGIBLE, ELIGIBLE, ELIGIBLE, false);
        var original = source(NOT_ELIGIBLE, ELIGIBLE);
        var result = service.revalidate(original, TODAY);
        assertThat(result.outcome()).isEqualTo(CANDIDATE);
        assertThat(result.sourceComparison()).isSameAs(original);
        assertThat(result.sourceComparison().profileVersion()).isEqualTo(1L);
        assertThat(result.freshComparison().profileVersion()).isEqualTo(9L);
        assertThat(result.freshComparison().evaluatedOn()).isEqualTo(TODAY);
        assertThat(result.freshComparison().ruleVersion()).isEqualTo(EligibilityResultService.RULE_VERSION);
        assertThat(result.revalidatedVersionId()).isEqualTo(12L);
        assertThat(result.latestJudgement().evaluation().status()).isEqualTo(ELIGIBLE);
        assertThat(result.mappingPolicyVersion()).isEqualTo(ProvisionalEligibilityTransitionClassifier.POLICY_VERSION);
        verify(profiles).findByUserId(7L);
        verify(evaluator).judge(same(profile), eq(List.of(10L, 11L, 12L)), eq(TODAY));
        verifyNoMoreInteractions(profiles, evaluator);
        verify(versions, never()).save(any()); verify(opportunities, never()).save(any());
    }

    @Test
    void currentVersionEqualToChangedVersionIsEvaluatedOnlyOnce() {
        fixture(true, "v1"); evaluate(NOT_ELIGIBLE, ELIGIBLE, ELIGIBLE, true);
        assertThat(service.revalidate(source(NOT_ELIGIBLE, ELIGIBLE), TODAY).outcome()).isEqualTo(CANDIDATE);
        verify(evaluator).judge(same(profile), eq(List.of(10L, 11L)), eq(TODAY));
    }

    @Test
    void latestPolicyCanInvalidateAnOtherwiseUnchangedCandidate() {
        fixture(false, "v1"); evaluate(NOT_ELIGIBLE, ELIGIBLE, NOT_ELIGIBLE, false);
        var result = service.revalidate(source(NOT_ELIGIBLE, ELIGIBLE), TODAY);
        assertThat(result.outcome()).isEqualTo(LATEST_STATUS_CHANGED);
        assertThat(result.freshComparison()).isNotNull();
        assertThat(result.latestJudgement().evaluation().status()).isEqualTo(NOT_ELIGIBLE);
    }

    @Test
    void changedProfileMustStillShowTheSameTransitionNotMerelyTheSameLatestStatus() {
        fixture(false, "v1"); evaluate(ELIGIBLE, ELIGIBLE, ELIGIBLE, false);
        assertThat(service.revalidate(source(NOT_ELIGIBLE, ELIGIBLE), TODAY).outcome()).isEqualTo(TRANSITION_CHANGED);
    }

    @Test
    void downgradeAndReviewCandidatesRemainValidOnlyWithMatchingLatestStatus() {
        fixture(false, "v1");
        evaluate(ELIGIBLE, NOT_ELIGIBLE, NOT_ELIGIBLE, false);
        assertThat(service.revalidate(source(ELIGIBLE, NOT_ELIGIBLE), TODAY).outcome()).isEqualTo(CANDIDATE);
        evaluate(ELIGIBLE, POSSIBLY_ELIGIBLE, POSSIBLY_ELIGIBLE, false);
        assertThat(service.revalidate(source(ELIGIBLE, POSSIBLY_ELIGIBLE), TODAY).outcome()).isEqualTo(CANDIDATE);
        evaluate(ELIGIBLE, POSSIBLY_ELIGIBLE, UNKNOWN, false);
        assertThat(service.revalidate(source(ELIGIBLE, POSSIBLY_ELIGIBLE), TODAY).outcome()).isEqualTo(LATEST_STATUS_CHANGED);
    }

    @Test
    void missingProfileIsNotReplacedWithUnknownJudgements() {
        fixture(false, "v1"); when(profiles.findByUserId(7L)).thenReturn(Optional.empty());
        var result = service.revalidate(source(NOT_ELIGIBLE, ELIGIBLE), TODAY);
        assertThat(result.outcome()).isEqualTo(PROFILE_MISSING);
        assertThat(result.freshComparison()).isNull(); verifyNoInteractions(evaluator);
    }

    @Test
    void deferredAndUnchangedSourceComparisonsDoNotTriggerDatabaseReads() {
        assertThat(service.revalidate(source(UNKNOWN, ELIGIBLE), TODAY).outcome()).isEqualTo(NO_CANDIDATE);
        assertThat(service.revalidate(source(ELIGIBLE, ELIGIBLE), TODAY).outcome()).isEqualTo(NO_CANDIDATE);
        verifyNoInteractions(versions, opportunities, profiles, evaluator);
    }

    @Test
    void differentNormalizationRuleSuppressesRevalidationWithoutGuessingEquivalence() {
        fixture(false, "v2");
        assertThat(service.revalidate(source(NOT_ELIGIBLE, ELIGIBLE), TODAY).outcome()).isEqualTo(NORMALIZATION_RULE_CHANGED);
        verifyNoInteractions(profiles, evaluator);
    }

    @Test
    void changedEvaluationRuleRequiresNewSourceAssessment() {
        var original = source(NOT_ELIGIBLE, ELIGIBLE);
        var oldRule = new EligibilityVersionComparison(7L, 10L, 11L, 1L, "old-rule", TODAY.minusDays(1), original.before(), original.after());
        assertThat(service.revalidate(oldRule, TODAY).outcome()).isEqualTo(RULE_VERSION_CHANGED);
        verifyNoInteractions(versions, opportunities, profiles, evaluator);
    }

    @Test
    void missingVersionOrPolicyIsExplicitlyUnavailable() {
        when(versions.findById(10L)).thenReturn(Optional.empty());
        when(versions.findById(11L)).thenReturn(Optional.empty());
        assertThat(service.revalidate(source(NOT_ELIGIBLE, ELIGIBLE), TODAY).outcome()).isEqualTo(VERSION_UNAVAILABLE);
        fixture(false, "v1"); when(opportunities.findById(1L)).thenReturn(Optional.empty());
        assertThat(service.revalidate(source(NOT_ELIGIBLE, ELIGIBLE), TODAY).outcome()).isEqualTo(POLICY_UNAVAILABLE);
        verifyNoInteractions(evaluator);
    }

    @Test
    void mismatchedPolicyOwnershipAndIncompleteJudgementsAreRejected() {
        fixture(false, "v1");
        when(versions.findById(10L)).thenReturn(Optional.of(OpportunityVersion.builder().id(10L).versionNo(1)
                .opportunity(Opportunity.builder().id(99L).build()).normalizationVersion("v1").build()));
        assertThatThrownBy(() -> service.revalidate(source(NOT_ELIGIBLE, ELIGIBLE), TODAY)).isInstanceOf(IllegalArgumentException.class);
        fixture(false, "v1"); when(evaluator.judge(profile, List.of(10L, 11L, 12L), TODAY)).thenReturn(Map.of());
        assertThatThrownBy(() -> service.revalidate(source(NOT_ELIGIBLE, ELIGIBLE), TODAY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAbsentSourceAndDateRatherThanInventingEvidence() {
        assertThatThrownBy(() -> service.revalidate(null, TODAY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.revalidate(source(NOT_ELIGIBLE, ELIGIBLE), null)).isInstanceOf(NullPointerException.class);
    }

    private void fixture(boolean currentIsTo, String currentRule) {
        var from = version(10, 1, "v1"); var to = version(11, 2, "v1");
        policy.changeCurrentVersion(currentIsTo ? to : version(12, 3, currentRule));
        when(versions.findById(10L)).thenReturn(Optional.of(from));
        when(versions.findById(11L)).thenReturn(Optional.of(to));
        when(opportunities.findById(1L)).thenReturn(Optional.of(policy));
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
    }

    private void evaluate(EligibilityStatus before, EligibilityStatus after, EligibilityStatus latest, boolean currentIsTo) {
        var values = new HashMap<Long, EligibilityEvaluator.Judgement>();
        values.put(10L, judgement(before)); values.put(11L, judgement(after));
        if (!currentIsTo) values.put(12L, judgement(latest));
        when(evaluator.judge(profile, currentIsTo ? List.of(10L, 11L) : List.of(10L, 11L, 12L), TODAY)).thenReturn(values);
    }

    private OpportunityVersion version(long id, int number, String rule) {
        return OpportunityVersion.builder().id(id).versionNo(number).opportunity(policy).normalizationVersion(rule).build();
    }

    private EligibilityVersionComparison source(EligibilityStatus before, EligibilityStatus after) {
        return new EligibilityVersionComparison(7L, 10L, 11L, 1L, EligibilityResultService.RULE_VERSION,
                TODAY.minusDays(1), judgement(before), judgement(after));
    }

    private EligibilityEvaluator.Judgement judgement(EligibilityStatus status) {
        return new EligibilityEvaluator.Judgement(new EligibilityEvaluation(status, List.of(), "test evidence"), ApplicationStatus.OPEN);
    }
}
