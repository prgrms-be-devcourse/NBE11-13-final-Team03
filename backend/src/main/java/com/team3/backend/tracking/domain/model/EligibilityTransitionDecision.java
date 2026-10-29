package com.team3.backend.tracking.domain.model;

import java.util.List;

/** Development-provisional assessment. Candidate channels are requests, not effective consent or permission to send. */
public enum EligibilityTransitionDecision {
    BECAME_ELIGIBLE(List.of("IN_APP", "PUSH")),
    ELIGIBILITY_DOWNGRADED(List.of("IN_APP")),
    ELIGIBILITY_REVIEW_REQUIRED(List.of("IN_APP")),
    NO_CHANGE(List.of()),
    DEFERRED(List.of());

    private final List<String> requestedChannels;

    EligibilityTransitionDecision(List<String> requestedChannels) {
        this.requestedChannels = requestedChannels;
    }

    public List<String> requestedChannels() {
        return requestedChannels;
    }

    public boolean isNotificationCandidate() {
        return !requestedChannels.isEmpty();
    }
}
