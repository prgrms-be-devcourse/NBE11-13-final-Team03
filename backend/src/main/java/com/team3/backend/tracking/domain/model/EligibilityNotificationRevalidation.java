package com.team3.backend.tracking.domain.model;

import com.team3.backend.eligibility.service.EligibilityEvaluator.Judgement;
import java.util.Objects;

/** Read-time evidence, not a durable send authorization. Fresh evidence is absent when validation stopped early. */
public record EligibilityNotificationRevalidation(
        Outcome outcome,
        String mappingPolicyVersion,
        EligibilityTransitionDecision decision,
        EligibilityVersionComparison sourceComparison,
        EligibilityVersionComparison freshComparison,
        Long revalidatedVersionId,
        Judgement latestJudgement
) {
    public enum Outcome {
        CANDIDATE, NO_CANDIDATE, VERSION_UNAVAILABLE, POLICY_UNAVAILABLE, PROFILE_MISSING,
        RULE_VERSION_CHANGED, NORMALIZATION_RULE_CHANGED, TRANSITION_CHANGED, LATEST_STATUS_CHANGED
    }

    public EligibilityNotificationRevalidation {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(mappingPolicyVersion, "mappingPolicyVersion");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(sourceComparison, "sourceComparison");
        boolean hasFreshEvidence = freshComparison != null;
        if (hasFreshEvidence != (revalidatedVersionId != null) || hasFreshEvidence != (latestJudgement != null)) {
            throw new IllegalArgumentException("Fresh comparison, latest version and judgement must be supplied together");
        }
        if (outcome == Outcome.CANDIDATE && (!hasFreshEvidence || !decision.isNotificationCandidate())) {
            throw new IllegalArgumentException("Candidate requires a supported decision and fresh evidence");
        }
    }
}
