package com.team3.backend.tracking.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Version-level assessment. Completion queues user assessment; it never means that every user was notified. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "opportunity_version_analysis", uniqueConstraints = @UniqueConstraint(
        name = "uk_version_analysis_tracking", columnNames = "tracking_id"))
public class OpportunityVersionAnalysis {
    public enum DeadlineOutcome { UNCHANGED, EXTENDED, NOT_EXTENDED, DEFERRED_SCOPED, DEFERRED_PERIOD }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tracking_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_version_analysis_tracking"))
    private OpportunityVersionTracking tracking;
    @Column(name = "analysis_version", nullable = false, updatable = false, length = 60)
    private String analysisVersion;
    @Column(name = "current_version_id", nullable = false, updatable = false)
    private Long currentVersionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "deadline_outcome", nullable = false, updatable = false, length = 40)
    private DeadlineOutcome deadlineOutcome;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changed_paths", nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<String> changedPaths;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deadline_event_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_version_analysis_deadline_event"))
    private OpportunityEvent deadlineEvent;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "eligibility_event_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_version_analysis_eligibility_event"))
    private OpportunityEvent eligibilityEvent;
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;
    @Column(name = "completed_at", updatable = false)
    private Instant completedAt;

    public static OpportunityVersionAnalysis record(OpportunityVersionTracking tracking, String analysisVersion,
            Long currentVersionId, DeadlineOutcome deadlineOutcome, List<String> changedPaths,
            OpportunityEvent deadlineEvent, OpportunityEvent eligibilityEvent, Instant now) {
        Objects.requireNonNull(tracking, "tracking");
        if (tracking.getId() == null || tracking.getStatus() != OpportunityVersionTrackingStatus.READY_FOR_EVENT_ANALYSIS) {
            throw new IllegalArgumentException("Persisted pending tracking record required");
        }
        if (analysisVersion == null || analysisVersion.isBlank() || currentVersionId == null || currentVersionId <= 0) {
            throw new IllegalArgumentException("Analysis version and current policy version required");
        }
        Objects.requireNonNull(deadlineOutcome, "deadlineOutcome");
        boolean deferred = deadlineOutcome == DeadlineOutcome.DEFERRED_PERIOD || deadlineOutcome == DeadlineOutcome.DEFERRED_SCOPED;
        if ((deferred && eligibilityEvent != null) || (!deferred && eligibilityEvent == null)) {
            throw new IllegalArgumentException("Only completed assessment queues user analysis");
        }
        if (deadlineEvent != null && (deadlineOutcome != DeadlineOutcome.EXTENDED
                || deadlineEvent.getEventType() != OpportunityEventType.DEADLINE_EXTENDED)) {
            throw new IllegalArgumentException("Deadline event must match extension assessment");
        }
        if (eligibilityEvent != null && (eligibilityEvent.getEventType() != OpportunityEventType.ELIGIBILITY_CHANGED
                || !eligibilityEvent.getOpportunityVersion().getId().equals(tracking.getOpportunityVersion().getId()))) {
            throw new IllegalArgumentException("User assessment event must identify the target version");
        }
        var result = new OpportunityVersionAnalysis();
        result.tracking = tracking;
        result.analysisVersion = analysisVersion;
        result.currentVersionId = currentVersionId;
        result.deadlineOutcome = deadlineOutcome;
        result.changedPaths = List.copyOf(changedPaths);
        if (result.changedPaths.isEmpty()) throw new IllegalArgumentException("Structural change evidence required");
        result.deadlineEvent = deadlineEvent;
        result.eligibilityEvent = eligibilityEvent;
        result.recordedAt = Objects.requireNonNull(now, "now");
        if (!deferred) result.completedAt = now;
        return result;
    }
}
