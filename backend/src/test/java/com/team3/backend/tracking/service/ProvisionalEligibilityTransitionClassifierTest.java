package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.tracking.domain.model.EligibilityTransitionDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.team3.backend.eligibility.domain.entity.EligibilityStatus.*;
import static com.team3.backend.tracking.domain.model.EligibilityTransitionDecision.*;
import static org.assertj.core.api.Assertions.*;

class ProvisionalEligibilityTransitionClassifierTest {
    private final ProvisionalEligibilityTransitionClassifier classifier = new ProvisionalEligibilityTransitionClassifier();

    @ParameterizedTest
    @CsvSource({
            "ELIGIBLE, ELIGIBLE, NO_CHANGE",
            "ELIGIBLE, NOT_ELIGIBLE, ELIGIBILITY_DOWNGRADED",
            "ELIGIBLE, POSSIBLY_ELIGIBLE, ELIGIBILITY_REVIEW_REQUIRED",
            "ELIGIBLE, UNKNOWN, DEFERRED",
            "NOT_ELIGIBLE, ELIGIBLE, BECAME_ELIGIBLE",
            "NOT_ELIGIBLE, NOT_ELIGIBLE, NO_CHANGE",
            "NOT_ELIGIBLE, POSSIBLY_ELIGIBLE, DEFERRED",
            "NOT_ELIGIBLE, UNKNOWN, DEFERRED",
            "POSSIBLY_ELIGIBLE, ELIGIBLE, DEFERRED",
            "POSSIBLY_ELIGIBLE, NOT_ELIGIBLE, DEFERRED",
            "POSSIBLY_ELIGIBLE, POSSIBLY_ELIGIBLE, NO_CHANGE",
            "POSSIBLY_ELIGIBLE, UNKNOWN, DEFERRED",
            "UNKNOWN, ELIGIBLE, DEFERRED",
            "UNKNOWN, NOT_ELIGIBLE, DEFERRED",
            "UNKNOWN, POSSIBLY_ELIGIBLE, DEFERRED",
            "UNKNOWN, UNKNOWN, NO_CHANGE"
    })
    void coversEveryStatusPairWithoutGuessingUnagreedMeaning(EligibilityStatus before, EligibilityStatus after,
                                                           EligibilityTransitionDecision expected) {
        assertThat(classifier.classify(before, after)).isEqualTo(expected);
    }

    @Test
    void onlyBecomingEligibleRequestsPushAndOtherCandidatesRequestInAppOnly() {
        assertThat(classifier.classify(NOT_ELIGIBLE, ELIGIBLE).requestedChannels()).containsExactly("IN_APP", "PUSH");
        assertThat(classifier.classify(ELIGIBLE, NOT_ELIGIBLE).requestedChannels()).containsExactly("IN_APP");
        assertThat(classifier.classify(ELIGIBLE, POSSIBLY_ELIGIBLE).requestedChannels()).containsExactly("IN_APP");
        for (var decision : EligibilityTransitionDecision.values()) {
            assertThat(decision.isNotificationCandidate()).isEqualTo(decision != NO_CHANGE && decision != DEFERRED);
        }
    }

    @Test
    void deferredIsNotNoChangeAndNeitherRequestsDelivery() {
        var unchanged = classifier.classify(UNKNOWN, UNKNOWN);
        var deferred = classifier.classify(UNKNOWN, ELIGIBLE);
        assertThat(unchanged).isNotEqualTo(deferred);
        assertThat(unchanged.requestedChannels()).isEmpty();
        assertThat(deferred.requestedChannels()).isEmpty();
    }

    @Test
    void missingProfileOrJudgementCannotBeReplacedWithAnUnknownStatus() {
        assertThatThrownBy(() -> classifier.classify(null, ELIGIBLE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> classifier.classify(ELIGIBLE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void candidateChannelListsAreImmutable() {
        assertThatThrownBy(() -> BECAME_ELIGIBLE.requestedChannels().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(BECAME_ELIGIBLE.requestedChannels()).containsExactly("IN_APP", "PUSH");
    }
}
