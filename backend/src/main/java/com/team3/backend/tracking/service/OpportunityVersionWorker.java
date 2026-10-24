package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.tracking.domain.repository.OpportunityVersionTrackingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Explicit invocation only: no scheduler, automatic baseline or eligibility event producer. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpportunityVersionWorker {
    private final OpportunityVersionTrackingRepository tracking;
    private final OpportunityVersionComparisonTransaction comparisons;

    /** Each policy commits independently. A failed step is rediscovered when a later sweep starts at zero. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Batch runOnce(long afterOpportunityId, int maxPolicies) {
        if (afterOpportunityId < 0 || maxPolicies < 1 || maxPolicies > 500) {
            throw new IllegalArgumentException("Non-negative cursor and policy limit 1..500 are required");
        }
        var ids = tracking.findSequentialPolicyIds(afterOpportunityId, OpportunityType.YOUTH_POLICY,
                PageRequest.of(0, maxPolicies + 1));
        var attempts = new ArrayList<Attempt>();
        long cursor = afterOpportunityId;
        for (Long id : ids.stream().limit(maxPolicies).toList()) {
            try {
                attempts.add(new Attempt(id, Optional.of(comparisons.compareNext(id))));
            } catch (RuntimeException failure) {
                attempts.add(new Attempt(id, Optional.empty()));
                log.warn("Version comparison failed: opportunityId={}, error={}", id, failure.getClass().getSimpleName());
            }
            cursor = id;
        }
        return new Batch(cursor, ids.size() > maxPolicies, List.copyOf(attempts));
    }

    public record Attempt(long opportunityId, Optional<OpportunityVersionComparisonTransaction.Step> step) { }
    public record Batch(long lastOpportunityId, boolean hasNext, List<Attempt> attempts) { }
}
