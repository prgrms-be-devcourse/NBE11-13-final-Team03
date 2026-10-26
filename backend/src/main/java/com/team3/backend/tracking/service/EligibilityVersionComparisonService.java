package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.service.EligibilityEvaluator;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.tracking.domain.model.EligibilityVersionComparison;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Does not read/write cached EligibilityResult, choose recipients, or create events/Outbox rows. */
@Service
@RequiredArgsConstructor
public class EligibilityVersionComparisonService {
    private final EligibilityProfileRepository profiles;
    private final EligibilityEvaluator evaluator;

    @Transactional(readOnly = true)
    public Optional<EligibilityVersionComparison> compare(Long userId, OpportunityVersion from,
            OpportunityVersion to, LocalDate evaluatedOn) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("A user ID is required");
        Objects.requireNonNull(evaluatedOn, "evaluatedOn");
        validatePair(from, to);
        // One profile load and one evaluator call: it snapshots the profile once for both versions.
        var profile = profiles.findByUserId(userId);
        if (profile.isEmpty()) return Optional.empty(); // Missing profile is not an UNKNOWN judgement.
        var input = profile.orElseThrow();
        var judgements = evaluator.judge(input, List.of(from.getId(), to.getId()), evaluatedOn);
        var before = judgements.get(from.getId());
        var after = judgements.get(to.getId());
        if (before == null || after == null) throw new IllegalStateException("Both version judgements are required");
        return Optional.of(new EligibilityVersionComparison(userId, from.getId(), to.getId(),
                input.getProfileVersion(), EligibilityResultService.RULE_VERSION, evaluatedOn, before, after));
    }

    private void validatePair(OpportunityVersion from, OpportunityVersion to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.getId() == null || to.getId() == null || from.getId().equals(to.getId())
                || from.getOpportunity() == null || to.getOpportunity() == null
                || from.getOpportunity().getId() == null
                || !from.getOpportunity().getId().equals(to.getOpportunity().getId())
                || from.getVersionNo() == null || to.getVersionNo() == null
                || from.getVersionNo() < 1 || from.getVersionNo() >= to.getVersionNo()) {
            throw new IllegalArgumentException("Ordered persisted versions of the same opportunity are required");
        }
        if (from.getNormalizationVersion() == null || from.getNormalizationVersion().isBlank()
                || !from.getNormalizationVersion().equals(to.getNormalizationVersion())) {
            throw new IllegalArgumentException("Normalization rule changes must be suppressed before eligibility comparison");
        }
    }
}
