package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.tracking.config.MatchTrackingProperties;
import com.team3.backend.tracking.domain.entity.MatchPairState;
import com.team3.backend.tracking.domain.model.MatchObservation;
import com.team3.backend.user.domain.entity.User;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.team3.backend.eligibility.domain.entity.EligibilityStatus.*;

class MatchPairStateTest {
    private final Instant now=Instant.parse("2026-10-26T00:00:00Z");
    private final MatchTrackingProperties.Possibly settings=new MatchTrackingProperties.Possibly(1,true);
    private MatchPairState pair() {
        var user=mock(User.class); when(user.getId()).thenReturn(7L);
        var policy=mock(Opportunity.class); when(policy.getId()).thenReturn(10L);
        return MatchPairState.forPair(user,policy);
    }
    private MatchObservation input(EligibilityStatus status,int profile) { return input(status,profile,"4",true); }
    private MatchObservation input(EligibilityStatus status,int profile,String rule,boolean interest) {
        return new MatchObservation(1,1,1,profile,rule,"v1",status,OpportunityType.YOUTH_POLICY,
                PersonalizationStatus.PERSONALIZABLE,ApplicationStatus.OPEN,false,interest,
                status==POSSIBLY_ELIGIBLE?1:0,0,LocalDate.of(2026,10,26));
    }
    @Test void firstEligibleIsOneFirstMatchNotATransition() {
        var pair=pair(); var notice=pair.observe(input(ELIGIBLE,1),settings,now);
        assertThat(notice.reason()).isEqualTo(MatchPairState.Reason.FIRST_MATCH); assertThat(notice.occurrence()).isZero();
        assertThat(pair.observe(input(ELIGIBLE,1),settings,now)).isNull();
        assertThat(pair.observe(input(ELIGIBLE,2),settings,now)).isNull();
        assertThat(pair.getTransitionCount()).isZero();
    }
    @Test void eachActualReturnFromNotEligibleGetsANewOccurrenceAndNoLossNotice() {
        var pair=pair(); pair.observe(input(ELIGIBLE,1),settings,now);
        assertThat(pair.observe(input(NOT_ELIGIBLE,2),settings,now)).isNull();
        var first=pair.observe(input(ELIGIBLE,3),settings,now);
        assertThat(first.reason()).isEqualTo(MatchPairState.Reason.TRANSITION); assertThat(first.occurrence()).isEqualTo(1);
        assertThat(pair.observe(input(ELIGIBLE,3),settings,now)).isNull();
        pair.observe(input(NOT_ELIGIBLE,4),settings,now);
        assertThat(pair.observe(input(ELIGIBLE,5),settings,now).occurrence()).isEqualTo(2);
    }
    @Test void firstObservedNotEligibleThenEligibleIsATransitionNotInitialSummary() {
        var pair=pair(); assertThat(pair.observe(input(NOT_ELIGIBLE,1),settings,now)).isNull();
        assertThat(pair.observe(input(ELIGIBLE,2),settings,now).reason()).isEqualTo(MatchPairState.Reason.TRANSITION);
    }
    @Test void qualifiedPossiblyIsInitialMatchAndDoesNotBecomeAnotherInitialMatch() {
        var pair=pair();
        assertThat(pair.observe(input(POSSIBLY_ELIGIBLE,1),settings,now).reason()).isEqualTo(MatchPairState.Reason.FIRST_MATCH);
        assertThat(pair.observe(input(ELIGIBLE,2),settings,now)).isNull();
    }
    @Test void interestChangeCanAdmitPreviouslyExcludedPossiblyOnce() {
        var pair=pair(); assertThat(pair.observe(input(POSSIBLY_ELIGIBLE,1,"4",false),settings,now)).isNull();
        assertThat(pair.observe(input(POSSIBLY_ELIGIBLE,1,"4",true),settings,now).reason()).isEqualTo(MatchPairState.Reason.FIRST_MATCH);
    }
    @Test void unknownDoesNotPretendToBeExplicitNotEligible() {
        var pair=pair(); pair.observe(input(UNKNOWN,1),settings,now);
        assertThat(pair.observe(input(ELIGIBLE,2),settings,now).reason()).isEqualTo(MatchPairState.Reason.FIRST_MATCH);
    }
    @Test void changedJudgementRuleIsNeverATransitionButStillAllowsTheOneFirstMatch() {
        var pair=pair(); pair.observe(input(NOT_ELIGIBLE,1,"3",true),settings,now);
        var notice=pair.observe(input(ELIGIBLE,1,"4",true),settings,now);
        assertThat(notice.reason()).isEqualTo(MatchPairState.Reason.FIRST_MATCH); assertThat(pair.getTransitionCount()).isZero();
        assertThat(pair.getLastStatus()).isEqualTo(ELIGIBLE);
        assertThat(pair.observe(input(ELIGIBLE,1,"4",true),settings,now)).isNull();
        assertThat(pair.observe(input(NOT_ELIGIBLE,1,"5",true),settings,now)).isNull();
        assertThat(pair.observe(input(ELIGIBLE,1,"6",true),settings,now)).isNull();
    }
    @Test void changedJudgementRuleAfterAnEarlierMatchRebaselinesWithoutAnAlert() {
        var pair=pair(); pair.observe(input(ELIGIBLE,1,"3",true),settings,now);
        pair.observe(input(NOT_ELIGIBLE,1,"3",true),settings,now);
        assertThat(pair.observe(input(ELIGIBLE,1,"4",true),settings,now)).isNull();
        assertThat(pair.getTransitionCount()).isZero();
    }
    @Test void olderProfileObservationCannotRewindOrInventAnotherTransition() {
        var pair=pair(); pair.observe(input(ELIGIBLE,2),settings,now);
        assertThatThrownBy(()->pair.observe(input(NOT_ELIGIBLE,1),settings,now)).isInstanceOf(IllegalArgumentException.class);
        assertThat(pair.getLastStatus()).isEqualTo(ELIGIBLE);
    }
    @Test void triggerDistinguishesPolicyChangeFromSameVersionProfileAndResultChanges() {
        var policyPair=pair(); policyPair.observe(input(NOT_ELIGIBLE,1),settings,now);
        var changedPolicy=new MatchObservation(2,2,1,1,"4","v1",ELIGIBLE,OpportunityType.YOUTH_POLICY,
                PersonalizationStatus.PERSONALIZABLE,ApplicationStatus.OPEN,true,true,0,0,LocalDate.of(2026,10,26));
        assertThat(policyPair.observe(changedPolicy,settings,now).trigger()).isEqualTo(MatchPairState.Trigger.POLICY_CHANGED);
        var profilePair=pair(); profilePair.observe(input(NOT_ELIGIBLE,1),settings,now);
        assertThat(profilePair.observe(input(ELIGIBLE,2),settings,now).trigger()).isEqualTo(MatchPairState.Trigger.PROFILE_CHANGED);
        var resultPair=pair(); resultPair.observe(input(NOT_ELIGIBLE,1),settings,now);
        assertThat(resultPair.observe(input(ELIGIBLE,1),settings,now).trigger()).isEqualTo(MatchPairState.Trigger.RESULT_CHANGED);
    }
}
