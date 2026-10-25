package com.team3.dataservice.outbox.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Data-service-owned envelope. Scalar job ID deliberately has no FK or cascading deletion. */
@Entity
@Table(name = "collection_outbox_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_collection_outbox_message_id", columnNames = "message_id"),
        @UniqueConstraint(name = "uk_collection_outbox_dedup_key", columnNames = "dedup_key")
}, indexes = @Index(name = "idx_collection_outbox_status_retry", columnList = "status, next_retry_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionOutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;
    @Column(name = "dedup_key", nullable = false, length = 120, updatable = false)
    private String dedupKey;
    @Column(name = "event_type", nullable = false, length = 40, updatable = false)
    private String eventType;
    @Column(name = "sync_job_id", nullable = false, updatable = false)
    private Long syncJobId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> payload;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CollectionOutboxStatus status;
    @Column(name = "retry_count", nullable = false)
    private int retryCount;
    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;
    @Column(name = "processing_started_at")
    private Instant processingStartedAt;
    @Column(name = "lease_token")
    private UUID leaseToken;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
