package com.team3.backend.tracking.domain.entity;

import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.tracking.domain.model.SnapshotComparison;
import com.team3.backend.tracking.domain.model.SnapshotDifference;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Structural change audit, not a notification/event. Business-period changes are not application-period changes. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "opportunity_changes", uniqueConstraints = @UniqueConstraint(
        name = "uk_opportunity_changes_versions", columnNames = {"from_version_id", "to_version_id"}))
public class OpportunityChange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_version_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_opportunity_changes_from"))
    private OpportunityVersion fromVersion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_version_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_opportunity_changes_to"))
    private OpportunityVersion toVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private List<SnapshotDifference> differences;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static OpportunityChange record(OpportunityVersion from, OpportunityVersion to,
            SnapshotComparison comparison, Instant now) {
        OpportunityVersionTracking.validatePair(from, to);
        Objects.requireNonNull(comparison, "comparison");
        if (comparison.differences().isEmpty()) throw new IllegalArgumentException("No structural change to record");
        var change = new OpportunityChange();
        change.fromVersion = from;
        change.toVersion = to;
        change.differences = comparison.differences();
        change.createdAt = Objects.requireNonNull(now, "now");
        return change;
    }
}
