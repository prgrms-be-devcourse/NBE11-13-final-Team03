package com.team3.backend.tracking.domain.entity;

import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.tracking.domain.model.SnapshotComparison;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.Objects;
import static com.team3.backend.tracking.domain.entity.OpportunityVersionTrackingStatus.*;

/** Application-owned audit/state. First-version baseline is recorded by the comparison transaction. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "opportunity_version_tracking", uniqueConstraints = @UniqueConstraint(
        name = "uk_version_tracking_version", columnNames = "opportunity_version_id"),
        indexes = @Index(name = "idx_version_tracking_status", columnList = "status,opportunity_version_id"))
public class OpportunityVersionTracking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "opportunity_version_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_version_tracking_version"))
    private OpportunityVersion opportunityVersion;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_version_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_version_tracking_from"))
    private OpportunityVersion fromVersion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OpportunityVersionTrackingStatus status;
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;
    @Column(name = "processed_at")
    private Instant processedAt;

    public static OpportunityVersionTracking baseline(OpportunityVersion version, Instant now) {
        requirePersistedVersion(version);
        var record = new OpportunityVersionTracking();
        record.opportunityVersion = version;
        record.status = BASELINE_SKIPPED;
        record.recordedAt = Objects.requireNonNull(now, "now");
        record.processedAt = now;
        return record;
    }

    public static OpportunityVersionTracking compared(OpportunityVersion from, OpportunityVersion to,
            SnapshotComparison comparison, Instant now) {
        validatePair(from, to);
        Objects.requireNonNull(comparison, "comparison");
        boolean changedRule = !from.getNormalizationVersion().equals(to.getNormalizationVersion());
        if (changedRule != (comparison.outcome() == SnapshotComparison.Outcome.NORMALIZATION_VERSION_CHANGED)) {
            throw new IllegalArgumentException("Normalization version and comparison outcome disagree");
        }
        var record = new OpportunityVersionTracking();
        record.opportunityVersion = to;
        record.fromVersion = from;
        record.status = switch (comparison.outcome()) {
            case NO_CHANGE -> NO_CHANGE;
            case NORMALIZATION_VERSION_CHANGED -> NORMALIZATION_VERSION_CHANGED;
            case CHANGES_DETECTED -> READY_FOR_EVENT_ANALYSIS;
        };
        record.recordedAt = Objects.requireNonNull(now, "now");
        if (record.status != READY_FOR_EVENT_ANALYSIS) record.processedAt = now;
        return record;
    }

    /** Call only in the transaction that durably stores the event assessment and any generated events. */
    public void completeEventAnalysis(Instant now) {
        if (status != READY_FOR_EVENT_ANALYSIS) throw new IllegalStateException("Version is not awaiting event analysis");
        recordAnalysisProgress(now, false);
    }

    /** Only after eligibility work and deadline assessment have been persisted in this transaction. */
    public void recordAnalysisProgress(Instant now, boolean deadlineDeferred) {
        if (status != READY_FOR_EVENT_ANALYSIS && status != PROCESSED_WITH_DEFERRED_DEADLINE) {
            throw new IllegalStateException("Version is not awaiting analysis or deadline resolution");
        }
        var completedAt = Objects.requireNonNull(now, "now");
        if (completedAt.isBefore(recordedAt)) throw new IllegalArgumentException("Completion cannot precede comparison");
        status = deadlineDeferred ? PROCESSED_WITH_DEFERRED_DEADLINE : PROCESSED;
        // Sequence checkpoint, not a claim that the deferred deadline was resolved.
        processedAt = completedAt;
    }

    static void validatePair(OpportunityVersion from, OpportunityVersion to) {
        requirePersistedVersion(from);
        requirePersistedVersion(to);
        if (!from.getOpportunity().getId().equals(to.getOpportunity().getId())
                || from.getId().equals(to.getId()) || from.getVersionNo() >= to.getVersionNo()) {
            throw new IllegalArgumentException("Comparison needs ordered versions of the same opportunity");
        }
    }

    private static void requirePersistedVersion(OpportunityVersion version) {
        Objects.requireNonNull(version, "version");
        if (version.getId() == null || version.getOpportunity() == null || version.getOpportunity().getId() == null
                || version.getVersionNo() == null || version.getVersionNo() < 1
                || version.getNormalizationVersion() == null || version.getNormalizationVersion().isBlank()) {
            throw new IllegalArgumentException("A persisted opportunity version with normalization metadata is required");
        }
    }
}
