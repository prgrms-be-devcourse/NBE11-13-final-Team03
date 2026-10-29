package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.*;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import com.team3.backend.tracking.domain.entity.*;
import com.team3.backend.tracking.domain.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import static com.team3.backend.tracking.config.DateTrackingConfig.KST;
import static com.team3.backend.tracking.domain.model.DeadlineExtensionAssessment.Outcome.EXTENDED;

/** Explicit invocation only. No scheduler/baseline inference; does not complete other version analyses. */
@Service
@RequiredArgsConstructor
public class DeadlineExtensionTransaction {
    private final OpportunityVersionRepository versions;
    private final ApplicationWindowRepository windows;
    private final OpportunityVersionTrackingRepository tracking;
    private final OpportunityEventRepository events;
    private final OutboxEventRepository outboxes;
    private final DeadlineExtensionDetector detector;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public Optional<Long> process(Long fromVersionId, Long toVersionId) {
        if (fromVersionId == null || toVersionId == null || fromVersionId.equals(toVersionId)) {
            throw new IllegalArgumentException("Two persisted versions are required");
        }
        var to = versions.findById(toVersionId).orElseThrow();
        var policy = to.getOpportunity();
        // Serializes duplicate analyses and excludes concurrent Fan-out (which holds FOR SHARE).
        // Refresh after lock acquisition so a normalization that won the lock cannot leave stale currentVersion.
        jdbc.queryForObject("SELECT id FROM opportunities WHERE id = ? FOR UPDATE", Long.class, policy.getId());
        entityManager.refresh(policy);
        if (policy.getOpportunityType() != OpportunityType.YOUTH_POLICY
                || policy.getCurrentVersion() == null || !policy.getCurrentVersion().getId().equals(toVersionId)) {
            return Optional.empty();
        }
        var from = versions.findFirstByOpportunityIdAndVersionNoLessThanOrderByVersionNoDesc(
                policy.getId(), to.getVersionNo()).orElseThrow();
        if (!from.getId().equals(fromVersionId)) throw new IllegalArgumentException("Immediate predecessor required");
        if (!Objects.equals(from.getNormalizationVersion(), to.getNormalizationVersion())) return Optional.empty();
        var previousRecord = tracking.findByOpportunityVersionId(fromVersionId);
        if (previousRecord.isEmpty() || previousRecord.orElseThrow().getProcessedAt() == null) {
            throw new IllegalStateException("Previous version must have an established baseline or completed analysis");
        }
        if (tracking.findByOpportunityVersionId(toVersionId)
                .filter(t -> t.getStatus() == OpportunityVersionTrackingStatus.BASELINE_SKIPPED).isPresent()) {
            return Optional.empty();
        }
        String key = "DEADLINE_EXTENDED:" + policy.getId() + ":" + fromVersionId + ":" + toVersionId;
        var existing = events.findByDedupKey(key);
        if (existing.isPresent()) return Optional.of(existing.orElseThrow().getId());
        var before = windows.findByVersionId(fromVersionId);
        var after = windows.findByVersionId(toVersionId);
        // Scope is absent from ApplicationPeriod. Refuse scoped rounds rather than losing their identity.
        if (hasScope(before) || hasScope(after)) return Optional.empty();
        var assessment = detector.detect(periods(before), periods(after));
        if (assessment.outcome() != EXTENDED) return Optional.empty();
        var now = clock.instant();
        var event = OpportunityEvent.create(policy, to, OpportunityEventType.DEADLINE_EXTENDED,
                LocalDate.ofInstant(now, KST), key,
                Map.of("fromVersionId", fromVersionId, "toVersionId", toVersionId,
                        "applicationStartDate", before.getFirst().getStartDate().toString(),
                        "previousApplicationEndDate", assessment.previousEndDate().toString(),
                        "applicationEndDate", assessment.currentEndDate().toString()), true, null, now);
        events.saveAndFlush(event);
        outboxes.cancelPendingDeadlineNotifications(policy.getId(), assessment.previousEndDate().toString());
        return Optional.of(event.getId());
    }

    private boolean hasScope(List<ApplicationWindow> periods) {
        return periods.stream().anyMatch(w -> w.getScope() != null && !w.getScope().isEmpty());
    }

    private List<ApplicationPeriod> periods(List<ApplicationWindow> windows) {
        return windows.stream().map(w -> new ApplicationPeriod(w.getPeriodType(), w.getStartDate(), w.getEndDate(),
                w.getValidationStatus(), w.getPeriodText())).toList();
    }
}
