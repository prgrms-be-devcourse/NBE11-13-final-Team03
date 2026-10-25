package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.*;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.tracking.domain.entity.*;
import com.team3.backend.tracking.domain.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.team3.backend.tracking.config.DateTrackingConfig.KST;
import static com.team3.backend.tracking.domain.entity.OpportunityVersionAnalysis.DeadlineOutcome.*;

/** Explicit development path only. No scheduler or user fan-out is connected. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "yon.tracking.version-analysis.enabled", havingValue = "true")
public class OpportunityVersionAnalysisTransaction {
    public static final String ANALYSIS_VERSION = "development-version-analysis-v2";
    private final OpportunityVersionRepository versions;
    private final ApplicationWindowRepository windows;
    private final OpportunityVersionTrackingRepository tracking;
    private final OpportunityChangeRepository changes;
    private final OpportunityVersionAnalysisRepository analyses;
    private final OpportunityEventRepository events;
    private final DeadlineExtensionDetector detector;
    private final DeadlineExtensionTransaction extensions;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final Clock clock;

    public enum Outcome { COMPLETED, DEADLINE_DEFERRED, NOT_READY, POLICY_UNAVAILABLE }
    public record Result(Outcome outcome, Long analysisId) { }

    @Transactional
    public Result analyze(Long toVersionId) {
        if (toVersionId == null || toVersionId <= 0) throw new IllegalArgumentException("A persisted version ID is required");
        var to = versions.findById(toVersionId).orElseThrow();
        var policy = to.getOpportunity();
        jdbc.queryForObject("SELECT id FROM opportunities WHERE id = ? FOR UPDATE", Long.class, policy.getId());
        em.refresh(policy);
        if (policy.getOpportunityType() != OpportunityType.YOUTH_POLICY || policy.getCurrentVersion() == null
                || to.getVersionNo() > policy.getCurrentVersion().getVersionNo()) return new Result(Outcome.POLICY_UNAVAILABLE, null);
        var pending = tracking.findByOpportunityVersionId(toVersionId);
        if (pending.isEmpty()) return new Result(Outcome.NOT_READY, null);
        var record = pending.orElseThrow();
        var existing = analyses.findByTrackingIdAndAnalysisVersion(record.getId(), ANALYSIS_VERSION);
        if (existing.isPresent()) {
            var saved = existing.orElseThrow();
            boolean completed = saved.getCompletedAt() != null;
            if ((completed && record.getStatus() != OpportunityVersionTrackingStatus.PROCESSED)
                    || (!completed && record.getStatus() != OpportunityVersionTrackingStatus.PROCESSED_WITH_DEFERRED_DEADLINE)) {
                throw new IllegalStateException("Assessment and version completion disagree");
            }
            return new Result(completed ? Outcome.COMPLETED : Outcome.DEADLINE_DEFERRED, saved.getId());
        }
        if (record.getStatus() != OpportunityVersionTrackingStatus.READY_FOR_EVENT_ANALYSIS
                && record.getStatus() != OpportunityVersionTrackingStatus.PROCESSED_WITH_DEFERRED_DEADLINE) return new Result(Outcome.NOT_READY, null);
        var from = versions.findFirstByOpportunityIdAndVersionNoLessThanOrderByVersionNoDesc(policy.getId(), to.getVersionNo()).orElseThrow();
        if (!from.getId().equals(record.getFromVersion().getId())
                || !Objects.equals(from.getNormalizationVersion(), to.getNormalizationVersion())
                || tracking.findByOpportunityVersionId(from.getId()).filter(t -> t.getProcessedAt() != null).isEmpty()) {
            throw new IllegalStateException("Ordered completed predecessor under the same normalization rule required");
        }
        var change = changes.findByFromVersionIdAndToVersionId(from.getId(), toVersionId).orElseThrow();
        var paths = change.getDifferences().stream().map(difference -> difference.path()).toList();
        var before = windows.findByVersionId(from.getId());
        var after = windows.findByVersionId(toVersionId);
        var deadline = assessDeadline(before, after);
        var now = clock.instant();
        OpportunityEvent deadlineEvent = null;
        boolean deferred = deadline == DEFERRED_PERIOD || deadline == DEFERRED_SCOPED;
        if (deadline == EXTENDED) {
            // Historical evidence is retained, but the existing extension path only creates a current-version alert.
            extensions.process(from.getId(), toVersionId);
            deadlineEvent = events.findByDedupKey("DEADLINE_EXTENDED:" + policy.getId() + ":" + from.getId() + ":" + toVersionId).orElse(null);
        }
        // Deadline uncertainty must not prevent eligibility work or erase its independent evidence.
        String key = "ELIGIBILITY_CHANGED:" + policy.getId() + ":" + from.getId() + ":" + toVersionId;
        var eligibilityEvent = events.findByDedupKey(key).orElseGet(() -> {
            var requested = OpportunityEvent.create(policy, to, OpportunityEventType.ELIGIBILITY_CHANGED,
                    LocalDate.ofInstant(record.getRecordedAt(), KST), key,
                    Map.of("fromVersionId", from.getId(), "toVersionId", toVersionId,
                            "analysisVersion", ANALYSIS_VERSION, "changedPaths", paths), true, null, now);
            requested.recordOrigin(requested.getCorrelationId(), record.getRecordedAt());
            return events.saveAndFlush(requested);
        });
        if (eligibilityEvent.getEventType() != OpportunityEventType.ELIGIBILITY_CHANGED
                || !eligibilityEvent.getOpportunityVersion().getId().equals(toVersionId)
                || !Objects.equals(String.valueOf(eligibilityEvent.getPayload().get("fromVersionId")), from.getId().toString())
                || !Objects.equals(String.valueOf(eligibilityEvent.getPayload().get("toVersionId")), toVersionId.toString())) {
            throw new IllegalStateException("Existing user assessment event has a conflicting identity");
        }
        var saved = analyses.saveAndFlush(OpportunityVersionAnalysis.record(record, ANALYSIS_VERSION,
                policy.getCurrentVersion().getId(), deadline, paths, deadlineEvent, eligibilityEvent, now));
        record.recordAnalysisProgress(now, deferred);
        tracking.flush();
        return new Result(deferred ? Outcome.DEADLINE_DEFERRED : Outcome.COMPLETED, saved.getId());
    }

    private OpportunityVersionAnalysis.DeadlineOutcome assessDeadline(List<ApplicationWindow> before, List<ApplicationWindow> after) {
        // Ignore row IDs and raw text, but retain multiplicity, scope and validation. Empty -> empty is no period change.
        if (signatures(before).equals(signatures(after))) return UNCHANGED;
        if (before.stream().anyMatch(this::hasScope) || after.stream().anyMatch(this::hasScope)) return DEFERRED_SCOPED;
        var result = detector.detect(periods(before), periods(after));
        return switch (result.outcome()) {
            case EXTENDED -> EXTENDED;
            case NOT_EXTENDED -> NOT_EXTENDED;
            case INCONCLUSIVE -> DEFERRED_PERIOD;
        };
    }

    private Map<WindowSignature, Long> signatures(List<ApplicationWindow> values) {
        return values.stream().map(w -> new WindowSignature(w.getPeriodType(), w.getStartDate(), w.getEndDate(),
                w.getValidationStatus(), w.getScope() == null ? Map.of() : w.getScope()))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
    private boolean hasScope(ApplicationWindow window) { return window.getScope() != null && !window.getScope().isEmpty(); }
    private List<ApplicationPeriod> periods(List<ApplicationWindow> values) {
        return values.stream().map(w -> new ApplicationPeriod(w.getPeriodType(), w.getStartDate(), w.getEndDate(),
                w.getValidationStatus(), w.getPeriodText())).toList();
    }
    private record WindowSignature(PeriodType type, LocalDate start, LocalDate end, ValidationStatus status, Map<String, Object> scope) { }
}
