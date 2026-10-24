package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.service.EligibilityEvaluator;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityVersionRepository;
import com.team3.backend.tracking.domain.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import static com.team3.backend.tracking.domain.model.EligibilityNotificationRevalidation.Outcome.*;

/** No cache/result writes, events or Outbox. Freshness must be coordinated with the future Outbox transaction. */
@Service
@RequiredArgsConstructor
public class EligibilityNotificationRevalidationService {
    private final OpportunityVersionRepository versions;
    private final OpportunityRepository opportunities;
    private final EligibilityProfileRepository profiles;
    private final EligibilityEvaluator evaluator;
    private final ProvisionalEligibilityTransitionClassifier classifier;

    @Transactional(readOnly = true)
    public EligibilityNotificationRevalidation revalidate(EligibilityVersionComparison source, LocalDate evaluatedOn) {
        validateInput(source, evaluatedOn);
        var decision = classifier.classify(status(source.before()), status(source.after()));
        if (!decision.isNotificationCandidate()) return stopped(NO_CANDIDATE, decision, source);
        if (!EligibilityResultService.RULE_VERSION.equals(source.ruleVersion())) {
            return stopped(RULE_VERSION_CHANGED, decision, source);
        }
        var fromResult = versions.findById(source.fromVersionId());
        var toResult = versions.findById(source.toVersionId());
        if (fromResult.isEmpty() || toResult.isEmpty()) return stopped(VERSION_UNAVAILABLE, decision, source);
        var from = fromResult.orElseThrow(); var to = toResult.orElseThrow();
        if (from.getOpportunity() == null || to.getOpportunity() == null || from.getOpportunity().getId() == null
                || !from.getOpportunity().getId().equals(to.getOpportunity().getId())
                || from.getVersionNo() == null || to.getVersionNo() == null || from.getVersionNo() >= to.getVersionNo()) {
            throw new IllegalArgumentException("Ordered versions of the same policy are required");
        }
        var policy = opportunities.findById(to.getOpportunity().getId());
        if (policy.isEmpty() || policy.orElseThrow().getOpportunityType() != OpportunityType.YOUTH_POLICY
                || policy.orElseThrow().getCurrentVersion() == null) return stopped(POLICY_UNAVAILABLE, decision, source);
        var current = policy.orElseThrow().getCurrentVersion();
        if (current.getId() == null || current.getOpportunity() == null
                || !to.getOpportunity().getId().equals(current.getOpportunity().getId())
                || current.getVersionNo() == null || current.getVersionNo() < to.getVersionNo()) {
            return stopped(POLICY_UNAVAILABLE, decision, source);
        }
        if (from.getNormalizationVersion() == null || from.getNormalizationVersion().isBlank()
                || !from.getNormalizationVersion().equals(to.getNormalizationVersion())
                || !to.getNormalizationVersion().equals(current.getNormalizationVersion())) {
            return stopped(NORMALIZATION_RULE_CHANGED, decision, source);
        }
        var profileResult = profiles.findByUserId(source.userId());
        if (profileResult.isEmpty()) return stopped(PROFILE_MISSING, decision, source);
        var profile = profileResult.orElseThrow();
        // A single evaluator call freezes one profile input for all versions; do not reuse the old result cache.
        var ids = new LinkedHashSet<>(List.of(from.getId(), to.getId(), current.getId()));
        var judgements = evaluator.judge(profile, List.copyOf(ids), evaluatedOn);
        for (Long id : ids) {
            if (judgements.get(id) == null) throw new IllegalStateException("All version judgements are required");
            status(judgements.get(id));
        }
        var fresh = new EligibilityVersionComparison(source.userId(), from.getId(), to.getId(), profile.getProfileVersion(),
                EligibilityResultService.RULE_VERSION, evaluatedOn, judgements.get(from.getId()), judgements.get(to.getId()));
        var latest = judgements.get(current.getId());
        var freshDecision = classifier.classify(status(fresh.before()), status(fresh.after()));
        var outcome = freshDecision != decision ? TRANSITION_CHANGED
                : status(latest) != status(fresh.after()) ? LATEST_STATUS_CHANGED : CANDIDATE;
        return new EligibilityNotificationRevalidation(outcome, ProvisionalEligibilityTransitionClassifier.POLICY_VERSION,
                decision, source, fresh, current.getId(), latest);
    }

    private EligibilityNotificationRevalidation stopped(EligibilityNotificationRevalidation.Outcome reason,
            EligibilityTransitionDecision decision, EligibilityVersionComparison source) {
        return new EligibilityNotificationRevalidation(reason, ProvisionalEligibilityTransitionClassifier.POLICY_VERSION,
                decision, source, null, null, null);
    }

    private void validateInput(EligibilityVersionComparison source, LocalDate date) {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(date, "evaluatedOn");
        if (source.userId() == null || source.userId() <= 0 || source.fromVersionId() == null || source.fromVersionId() <= 0
                || source.toVersionId() == null || source.toVersionId() <= 0
                || source.fromVersionId().equals(source.toVersionId()) || source.evaluatedOn() == null
                || source.profileVersion() == null || source.ruleVersion() == null || source.ruleVersion().isBlank()) {
            throw new IllegalArgumentException("Complete source comparison evidence is required");
        }
    }

    private EligibilityStatus status(EligibilityEvaluator.Judgement judgement) {
        if (judgement == null || judgement.evaluation() == null || judgement.evaluation().status() == null) {
            throw new IllegalArgumentException("A real judgement is required; missing is not UNKNOWN");
        }
        return judgement.evaluation().status();
    }
}
