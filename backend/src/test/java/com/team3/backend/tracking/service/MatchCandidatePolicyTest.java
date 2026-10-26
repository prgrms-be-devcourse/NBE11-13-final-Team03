package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.tracking.config.MatchTrackingProperties;
import com.team3.backend.tracking.domain.model.MatchObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static com.team3.backend.tracking.domain.model.MatchObservation.Qualification.*;

class MatchCandidatePolicyTest {
    private final MatchTrackingProperties.Possibly policy=new MatchTrackingProperties.Possibly(1,true);
    private MatchObservation input(EligibilityStatus status,ApplicationStatus period,boolean saved,boolean interest,int missing,int check) {
        return new MatchObservation(1,1,1,1,"4","v1",status,OpportunityType.YOUTH_POLICY,
                PersonalizationStatus.PERSONALIZABLE,period,saved,interest,missing,check,LocalDate.of(2026,10,26));
    }
    @Test void eligibleDoesNotRequireInterest() {
        assertThat(input(EligibilityStatus.ELIGIBLE,ApplicationStatus.OPEN,false,false,0,0).qualify(policy)).isEqualTo(ELIGIBLE);
    }
    @Test void oneMissingOrOneCheckQualifiesButTwoCombinedDoNot() {
        assertThat(input(EligibilityStatus.POSSIBLY_ELIGIBLE,ApplicationStatus.OPEN,false,true,1,0).qualify(policy)).isEqualTo(POSSIBLY);
        assertThat(input(EligibilityStatus.POSSIBLY_ELIGIBLE,ApplicationStatus.OPEN,false,true,0,1).qualify(policy)).isEqualTo(POSSIBLY);
        assertThat(input(EligibilityStatus.POSSIBLY_ELIGIBLE,ApplicationStatus.OPEN,false,true,1,1).qualify(policy)).isEqualTo(NONE);
    }
    @Test void missingInterestIsNotUnrestrictedMatchForPossibly() {
        assertThat(input(EligibilityStatus.POSSIBLY_ELIGIBLE,ApplicationStatus.OPEN,false,false,0,1).qualify(policy)).isEqualTo(NONE);
    }
    @Test void configuredThresholdAndInterestRequirementAreApplied() {
        var source=input(EligibilityStatus.POSSIBLY_ELIGIBLE,ApplicationStatus.OPEN,false,false,1,1);
        assertThat(source.qualify(new MatchTrackingProperties.Possibly(2,false))).isEqualTo(POSSIBLY);
        assertThat(source.qualify(new MatchTrackingProperties.Possibly(1,false))).isEqualTo(NONE);
    }
    @Test void unresolvedSumCannotOverflow() {
        assertThat(input(EligibilityStatus.POSSIBLY_ELIGIBLE,ApplicationStatus.OPEN,false,true,Integer.MAX_VALUE,Integer.MAX_VALUE)
                .qualify(policy)).isEqualTo(NONE);
    }
    @ParameterizedTest @EnumSource(value=EligibilityStatus.class,names={"NOT_ELIGIBLE","UNKNOWN"})
    void excludedEligibilityStatusesAreNeverCandidates(EligibilityStatus status) {
        assertThat(input(status,ApplicationStatus.OPEN,true,true,0,0).qualify(policy)).isEqualTo(NONE);
    }
    @ParameterizedTest @EnumSource(value=ApplicationStatus.class,names={"CLOSED","UNKNOWN"})
    void unavailableApplicationPeriodNeverQualifies(ApplicationStatus status) {
        assertThat(input(EligibilityStatus.ELIGIBLE,status,true,true,0,0).qualify(policy)).isEqualTo(NONE);
    }
    @Test void upcomingRequiresSavedPolicy() {
        assertThat(input(EligibilityStatus.ELIGIBLE,ApplicationStatus.UPCOMING,false,true,0,0).qualify(policy)).isEqualTo(NONE);
        assertThat(input(EligibilityStatus.ELIGIBLE,ApplicationStatus.UPCOMING,true,true,0,0).qualify(policy)).isEqualTo(ELIGIBLE);
    }
    @Test void trainingIsExcluded() {
        var source=new MatchObservation(1,1,1,1,"4","v1",EligibilityStatus.ELIGIBLE,OpportunityType.VOCATIONAL_TRAINING,
                PersonalizationStatus.PERSONALIZABLE,ApplicationStatus.OPEN,false,true,0,0,LocalDate.of(2026,10,26));
        assertThat(source.qualify(policy)).isEqualTo(NONE);
    }
    @ParameterizedTest @EnumSource(value=PersonalizationStatus.class,names={"SPLIT_REQUIRED","NON_PERSONAL","UNKNOWN"})
    void nonPersonalizableVersionIsExcluded(PersonalizationStatus status) {
        var source=new MatchObservation(1,1,1,1,"4","v1",EligibilityStatus.ELIGIBLE,OpportunityType.YOUTH_POLICY,
                status,ApplicationStatus.OPEN,false,true,0,0,LocalDate.of(2026,10,26));
        assertThat(source.qualify(policy)).isEqualTo(NONE);
    }
    @Test void negativeCountsAreRejected() {
        assertThatThrownBy(()->input(EligibilityStatus.POSSIBLY_ELIGIBLE,ApplicationStatus.OPEN,false,true,-1,0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
