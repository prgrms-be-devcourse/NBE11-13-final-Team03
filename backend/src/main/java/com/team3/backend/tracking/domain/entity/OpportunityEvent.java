package com.team3.backend.tracking.domain.entity;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "opportunity_events",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_opportunity_events_dedup_key",
                        columnNames = "dedup_key"
                )
        },
        indexes = {
                @Index(
                        name = "idx_opportunity_events_processing",
                        columnList = "processing_status, next_retry_at"
                ),
                @Index(
                        name = "idx_opportunity_events_opportunity",
                        columnList = "opportunity_id"
                ),
                @Index(
                        name = "idx_opportunity_events_event_date",
                        columnList = "event_date"
                )
        }
)
@Getter
@Builder(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpportunityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    /**
     * 이벤트가 발생한 정책.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "opportunity_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_opportunity_events_opportunity"
            )
    )
    private Opportunity opportunity;

    /**
     * 이벤트 생성의 기준이 된 정책 버전.
     *
     * 날짜 이벤트도 생성 시점의 현재 버전을 함께 저장한다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "opportunity_version_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_opportunity_events_version"
            )
    )
    private OpportunityVersion opportunityVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private OpportunityEventType eventType;

    /**
     * 이벤트가 의미하는 한국 시간 기준 날짜.
     *
     * 예:
     * - 모집 시작일
     * - 모집 종료일
     * - D-7 이벤트가 발생한 날짜
     * - 정책 변경을 감지한 날짜
     */
    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    /**
     * 정책 이벤트 자체의 멱등키.
     *
     * 예:
     * DEADLINE_SOON:10:D-3:2026-10-31
     */
    @Column(name = "dedup_key", nullable = false, length = 500)
    private String dedupKey;

    /**
     * 이벤트 유형별 추가 정보.
     *
     * 예:
     * {
     *   "threshold": "D-3",
     *   "applicationEndDate": "2026-10-31"
     * }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    /**
     * 이 이벤트로 사용자 Notification을 생성할 수 있는지 여부.
     *
     * 늦게 복구된 D-7 이벤트처럼 이력은 남기되
     * 사용자 알림은 만들지 않는 경우 false가 된다.
     */
    @Column(name = "notification_eligible", nullable = false)
    private boolean notificationEligible;

    @Enumerated(EnumType.STRING)
    @Column(name = "suppression_reason", length = 50)
    private OpportunityEventSuppressionReason suppressionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 30)
    private OpportunityEventProcessingStatus processingStatus;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /**
     * 신규 이벤트도 즉시 처리 대상이 되도록 생성 시각으로 초기화한다.
     */
    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "processed_at")
    private Instant processedAt;

    /**
     * 실제 사건이 발생하거나 감지된 시각.
     */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static OpportunityEvent create(
            Opportunity opportunity,
            OpportunityVersion opportunityVersion,
            OpportunityEventType eventType,
            LocalDate eventDate,
            String dedupKey,
            Map<String, Object> payload,
            boolean notificationEligible,
            OpportunityEventSuppressionReason suppressionReason,
            Instant now
    ) {
        Objects.requireNonNull(opportunity, "opportunity");
        Objects.requireNonNull(opportunityVersion, "opportunityVersion");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(eventDate, "eventDate");
        Objects.requireNonNull(now, "now");

        validateDedupKey(dedupKey);
        validateVersionOwner(opportunity, opportunityVersion);
        validateSuppression(
                notificationEligible,
                suppressionReason
        );

        return OpportunityEvent.builder()
                .eventId(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .opportunity(opportunity)
                .opportunityVersion(opportunityVersion)
                .eventType(eventType)
                .eventDate(eventDate)
                .dedupKey(dedupKey)
                .payload(payload == null ? Map.of() : Map.copyOf(payload))
                .notificationEligible(notificationEligible)
                .suppressionReason(suppressionReason)
                .processingStatus(
                        OpportunityEventProcessingStatus.PENDING
                )
                .retryCount(0)
                .nextRetryAt(now)
                .occurredAt(now)
                .createdAt(now)
                .build();
    }

    public void complete(Instant now) {
        Objects.requireNonNull(now, "now");

        this.processingStatus =
                OpportunityEventProcessingStatus.COMPLETED;
        this.processedAt = now;
        this.processingStartedAt = null;
        this.lastError = null;
    }

    /** Set once before persistence; historical events keep their original occurrence time. */
    public void recordOrigin(UUID correlationId, Instant occurredAt) {
        if (id != null) {
            throw new IllegalStateException("Persisted event origin is immutable");
        }
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public void scheduleRetry(
            Instant now,
            Duration delay,
            String errorMessage
    ) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(delay, "delay");

        if (delay.isNegative()) {
            throw new IllegalArgumentException(
                    "재시도 지연시간은 음수일 수 없습니다."
            );
        }

        this.processingStatus =
                OpportunityEventProcessingStatus.PENDING;
        this.processingStartedAt = null;
        this.retryCount++;
        this.nextRetryAt = now.plus(delay);
        this.lastError = errorMessage;
    }

    public void fail(
            Instant now,
            String errorMessage
    ) {
        Objects.requireNonNull(now, "now");

        this.processingStatus =
                OpportunityEventProcessingStatus.FAILED;
        this.processingStartedAt = null;
        this.processedAt = now;
        this.lastError = errorMessage;
    }

    private static void validateDedupKey(String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) {
            throw new IllegalArgumentException(
                    "이벤트 멱등키는 필수입니다."
            );
        }

        if (dedupKey.length() > 500) {
            throw new IllegalArgumentException(
                    "이벤트 멱등키는 500자를 초과할 수 없습니다."
            );
        }
    }

    private static void validateVersionOwner(
            Opportunity opportunity,
            OpportunityVersion opportunityVersion
    ) {
        Opportunity owner = opportunityVersion.getOpportunity();

        boolean sameOwner = owner == opportunity
                || (
                opportunity.getId() != null
                        && owner != null
                        && opportunity.getId().equals(owner.getId())
        );

        if (!sameOwner) {
            throw new IllegalArgumentException(
                    "정책 버전이 해당 정책에 속하지 않습니다."
            );
        }
    }

    private static void validateSuppression(
            boolean notificationEligible,
            OpportunityEventSuppressionReason suppressionReason
    ) {
        if (notificationEligible && suppressionReason != null) {
            throw new IllegalArgumentException(
                    "알림 가능한 이벤트에는 억제 사유를 설정할 수 없습니다."
            );
        }

        if (!notificationEligible && suppressionReason == null) {
            throw new IllegalArgumentException(
                    "알림을 억제한 이벤트에는 억제 사유가 필요합니다."
            );
        }
    }
}
