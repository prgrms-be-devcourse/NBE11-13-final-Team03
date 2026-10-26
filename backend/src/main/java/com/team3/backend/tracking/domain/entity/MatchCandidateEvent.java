package com.team3.backend.tracking.domain.entity;

import com.team3.backend.tracking.domain.model.MatchObservation;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Durable pending intent, not proof of Outbox creation or delivery. All first matches remain here, not just a top N. */
@Entity @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
@Table(name="notification_match_candidates",uniqueConstraints={
        @UniqueConstraint(name="uk_match_candidate_occurrence",columnNames={"pair_id","reason","occurrence_no"}),
        @UniqueConstraint(name="uk_match_candidate_event",columnNames="event_id")},
        indexes=@Index(name="idx_match_candidate_pending",columnList="status,id"))
public class MatchCandidateEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="pair_id",nullable=false,updatable=false) @OnDelete(action=OnDeleteAction.CASCADE) private MatchPairState pair;
    @Column(name="event_id",nullable=false,updatable=false) private UUID eventId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,updatable=false,length=30) private MatchPairState.Reason reason;
    @Column(name="occurrence_no",nullable=false,updatable=false) private long occurrenceNo;
    @Column(nullable=false,length=20) private String status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,updatable=false,columnDefinition="jsonb") private Map<String,Object> evidence;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="outbox_id") private Long outboxId;
    @Column(name="close_reason",length=80) private String closeReason;
    @Column(name="closed_at") private Instant closedAt;

    public void enqueue(Long outboxId,Instant now) {
        requirePending();
        if (outboxId==null || outboxId<1) throw new IllegalArgumentException("Persisted Outbox ID required");
        this.outboxId=outboxId; status="ENQUEUED"; closedAt=java.util.Objects.requireNonNull(now);
    }
    public void skip(String reason,Instant now) {
        requirePending();
        if (reason==null || reason.isBlank() || reason.length()>80) throw new IllegalArgumentException("Skip reason required");
        status="SKIPPED"; closeReason=reason; closedAt=java.util.Objects.requireNonNull(now);
    }
    private void requirePending() {
        if (!"PENDING".equals(status)) throw new IllegalStateException("Match candidate is already terminal");
    }

    public static MatchCandidateEvent pending(MatchPairState pair, MatchPairState.Notice notice, MatchObservation input, Instant now) {
        var event=new MatchCandidateEvent(); event.pair=pair; event.eventId=UUID.randomUUID();
        event.reason=notice.reason(); event.occurrenceNo=notice.occurrence(); event.status="PENDING"; event.createdAt=now;
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("opportunityVersionId",input.versionId()); evidence.put("versionNo",input.versionNo());
        evidence.put("profileVersion",input.profileVersion()); evidence.put("ruleVersion",input.ruleVersion());
        evidence.put("normalizationVersion",input.normalizationVersion()); evidence.put("evaluatedOn",input.evaluatedOn().toString());
        evidence.put("before",notice.before()==null?"UNOBSERVED":notice.before().name()); evidence.put("after",input.status().name());
        evidence.put("qualification",notice.qualification().name()); evidence.put("applicationStatus",input.applicationStatus().name());
        evidence.put("trigger",notice.trigger().name());
        evidence.put("interestMatched",input.interestMatched()); evidence.put("userInfoMissing",input.userInfoMissing());
        evidence.put("checkRequired",input.checkRequired()); evidence.put("saved",input.saved());
        evidence.put("inputKey",pair.getLastInputKey()); event.evidence=Map.copyOf(evidence); return event;
    }
}
