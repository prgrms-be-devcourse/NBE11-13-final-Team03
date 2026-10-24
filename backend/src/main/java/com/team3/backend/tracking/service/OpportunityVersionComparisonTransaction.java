package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.repository.OpportunityVersionRepository;
import com.team3.backend.tracking.domain.entity.OpportunityChange;
import com.team3.backend.tracking.domain.entity.OpportunityVersionTracking;
import com.team3.backend.tracking.domain.model.SnapshotComparison;
import com.team3.backend.tracking.domain.repository.OpportunityChangeRepository;
import com.team3.backend.tracking.domain.repository.OpportunityVersionTrackingRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/** Baseline-gated historical comparison. Event analysis completion remains a separate responsibility. */
@Service
@RequiredArgsConstructor
public class OpportunityVersionComparisonTransaction {
    private final OpportunityVersionRepository versions;
    private final OpportunityVersionTrackingRepository tracking;
    private final OpportunityChangeRepository changes;
    private final OpportunitySnapshotComparator comparator;
    private final DeadlineExtensionTransaction extensions;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Clock clock;

    /** Historical versions are compared too; notification generation still revalidates the current policy. */
    @Transactional
    public Optional<Long> compare(Long toVersionId) {
        if (toVersionId == null || toVersionId <= 0) throw new IllegalArgumentException("A persisted version ID is required");
        var to = versions.findById(toVersionId).orElseThrow();
        var policy = to.getOpportunity();
        jdbc.queryForObject("SELECT id FROM opportunities WHERE id = ? FOR UPDATE", Long.class, policy.getId());
        entityManager.refresh(policy);
        if (policy.getOpportunityType() != OpportunityType.YOUTH_POLICY || policy.getCurrentVersion() == null
                || to.getVersionNo() > policy.getCurrentVersion().getVersionNo()) return Optional.empty();
        var existing = tracking.findByOpportunityVersionId(toVersionId);
        // Baselines and in-progress analyses must not be replaced or implicitly completed on replay.
        if (existing.isPresent()) return Optional.of(existing.orElseThrow().getId());
        var from = versions.findFirstByOpportunityIdAndVersionNoLessThanOrderByVersionNoDesc(
                policy.getId(), to.getVersionNo()).orElseThrow(
                        () -> new IllegalStateException("First version requires an explicit baseline contract"));
        var previous = tracking.findByOpportunityVersionId(from.getId());
        if (previous.isEmpty() || previous.orElseThrow().getProcessedAt() == null) {
            throw new IllegalStateException("Previous version must have an established baseline or completed analysis");
        }
        var comparison = comparator.compare(from.getNormalizedSnapshot(), from.getNormalizationVersion(),
                to.getNormalizedSnapshot(), to.getNormalizationVersion());
        var now = clock.instant();
        if (!comparison.differences().isEmpty()) changes.save(OpportunityChange.record(from, to, comparison, now));
        var record = tracking.saveAndFlush(OpportunityVersionTracking.compared(from, to, comparison, now));
        if (comparison.outcome() == SnapshotComparison.Outcome.CHANGES_DETECTED) {
            // REQUIRED propagation: failure rolls back history, tracking, event and cancellation together.
            extensions.process(from.getId(), toVersionId);
        }
        // A structural change stays READY_FOR_EVENT_ANALYSIS even if its deadline extension was handled.
        // Eligibility meaning and inconclusive changes have not been fully assessed yet.
        return Optional.of(record.getId());
    }

    /** One bounded step per policy. The policy lock serializes discovery, comparison and concurrent callers. */
    @Transactional
    public Step compareNext(Long opportunityId) {
        if (opportunityId == null || opportunityId <= 0) throw new IllegalArgumentException("A persisted policy ID is required");
        var locked = jdbc.queryForList("SELECT id FROM opportunities WHERE id = ? FOR UPDATE", Long.class, opportunityId);
        if (locked.isEmpty()) return new Step(Outcome.POLICY_UNAVAILABLE, null, null);
        var policy = entityManager.find(Opportunity.class, opportunityId);
        entityManager.refresh(policy);
        if (policy.getOpportunityType() != OpportunityType.YOUTH_POLICY || policy.getCurrentVersion() == null) {
            return new Step(Outcome.POLICY_UNAVAILABLE, null, null);
        }
        var pending = versions.findUnfinishedTrackingVersions(opportunityId,
                policy.getCurrentVersion().getVersionNo(), PageRequest.of(0, 1));
        if (pending.isEmpty()) return new Step(Outcome.UP_TO_DATE, null, null);
        var target = pending.getFirst();
        var record = tracking.findByOpportunityVersionId(target.getId());
        if (record.isPresent()) {
            return new Step(Outcome.WAITING_FOR_EVENT_ANALYSIS, target.getId(), record.orElseThrow().getId());
        }
        var from = versions.findFirstByOpportunityIdAndVersionNoLessThanOrderByVersionNoDesc(opportunityId, target.getVersionNo());
        if (from.isEmpty()) return new Step(Outcome.WAITING_FOR_BASELINE, target.getId(), null);
        var previous = tracking.findByOpportunityVersionId(from.orElseThrow().getId());
        if (previous.isEmpty() || previous.orElseThrow().getProcessedAt() == null) {
            return new Step(Outcome.WAITING_FOR_PREDECESSOR, target.getId(), null);
        }
        // Self-invocation intentionally shares this transaction and the already acquired policy lock.
        var id = compare(target.getId()).orElseThrow();
        return new Step(Outcome.COMPARED, target.getId(), id);
    }

    public enum Outcome {
        COMPARED, WAITING_FOR_BASELINE, WAITING_FOR_PREDECESSOR,
        WAITING_FOR_EVENT_ANALYSIS, UP_TO_DATE, POLICY_UNAVAILABLE
    }

    public record Step(Outcome outcome, Long versionId, Long trackingId) { }
}
