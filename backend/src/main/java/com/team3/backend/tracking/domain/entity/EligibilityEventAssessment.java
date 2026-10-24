package com.team3.backend.tracking.domain.entity;

import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Durable per-user result, including no-change and deferred results. Does not store raw profile fields. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "eligibility_event_assessments", uniqueConstraints = @UniqueConstraint(
        name = "uk_eligibility_assessment_event_user", columnNames = {"event_id", "user_id"}),
        indexes = @Index(name = "idx_eligibility_assessment_status", columnList = "event_id,status"))
public class EligibilityEventAssessment {
    public enum Status { OUTBOX_CREATED, OUTBOX_EXISTING, NO_CHANGE, SKIPPED, DEFERRED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_eligibility_assessment_event"))
    private OpportunityEvent event;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_eligibility_assessment_user"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private Status status;
    @Column(nullable = false, updatable = false, length = 80)
    private String reason;
    @Column(name = "outbox_id", updatable = false)
    private Long outboxId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> evidence;
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    public static EligibilityEventAssessment record(OpportunityEvent event, User user, Status status, String reason,
            Long outboxId, Map<String, Object> evidence, Instant now) {
        if (event == null || event.getId() == null || event.getEventType() != OpportunityEventType.ELIGIBILITY_CHANGED
                || user == null || user.getId() == null) throw new IllegalArgumentException("Persisted event and user required");
        Objects.requireNonNull(status, "status");
        boolean hasOutbox = status == Status.OUTBOX_CREATED || status == Status.OUTBOX_EXISTING;
        if (hasOutbox != (outboxId != null)) throw new IllegalArgumentException("Outbox reference must match result");
        if (reason == null || reason.isBlank() || reason.length() > 80) throw new IllegalArgumentException("Result reason required");
        var result = new EligibilityEventAssessment();
        result.event = event; result.user = user; result.status = status; result.reason = reason; result.outboxId = outboxId;
        result.evidence = Map.copyOf(evidence); result.recordedAt = Objects.requireNonNull(now, "now");
        return result;
    }
}
