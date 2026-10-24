package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.repository.OpportunityVersionRepository;
import com.team3.backend.tracking.domain.entity.OpportunityChange;
import com.team3.backend.tracking.domain.entity.OpportunityVersionTracking;
import com.team3.backend.tracking.domain.model.SnapshotComparison;
import com.team3.backend.tracking.domain.repository.OpportunityChangeRepository;
import com.team3.backend.tracking.domain.repository.OpportunityVersionTrackingRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/** Explicit, baseline-gated comparison. Automatic discovery and eligibility event mapping remain unconnected. */
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

    /** Returns the existing/new tracking row ID; empty means the target is no longer a supported current version. */
    @Transactional
    public Optional<Long> compare(Long toVersionId) {
        if (toVersionId == null || toVersionId <= 0) throw new IllegalArgumentException("A persisted version ID is required");
        var to = versions.findById(toVersionId).orElseThrow();
        var policy = to.getOpportunity();
        jdbc.queryForObject("SELECT id FROM opportunities WHERE id = ? FOR UPDATE", Long.class, policy.getId());
        entityManager.refresh(policy);
        if (policy.getOpportunityType() != OpportunityType.YOUTH_POLICY || policy.getCurrentVersion() == null
                || !policy.getCurrentVersion().getId().equals(toVersionId)) return Optional.empty();
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
}
