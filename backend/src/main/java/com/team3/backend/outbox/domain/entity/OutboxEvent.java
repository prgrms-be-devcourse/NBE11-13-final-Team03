package com.team3.backend.outbox.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Application-owned durable message, with scalar IDs and no User cascade/FK. */
@Entity
@Table(name = "outbox_events",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_outbox_message_id", columnNames = "message_id"),
                @UniqueConstraint(name = "uk_outbox_dedup_key", columnNames = "dedup_key")
        },
        indexes = @Index(name = "idx_outbox_status_retry", columnList = "status, next_retry_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;
    @Column(name = "dedup_key", nullable = false, length = 200, updatable = false)
    private String dedupKey;
    @Column(name = "event_type", nullable = false, length = 40, updatable = false)
    private String eventType;
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "source_event_id", updatable = false)
    private UUID sourceEventId;
    @Column(name = "opportunity_id", updatable = false)
    private Long opportunityId;
    @Column(name = "notification_type", length = 40, updatable = false)
    private String notificationType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> payload;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OutboxStatus status;
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

    public static String notificationDedupKey(UUID eventId, Long userId) {
        return "NOTIFICATION_REQUESTED:" + Objects.requireNonNull(eventId) + ":" + Objects.requireNonNull(userId);
    }

    /** No User FK and no expiry: this control message must survive account deletion. */
    public static OutboxEvent userWithdrawn(Long userId, Instant now) {
        var event = new OutboxEvent();
        event.messageId = UUID.randomUUID();
        event.sourceEventId = UUID.randomUUID();
        event.userId = Objects.requireNonNull(userId);
        event.eventType = "USER_WITHDRAWN";
        event.dedupKey = "USER_WITHDRAWN:" + userId;
        event.payload = Map.of("schemaVersion", 1, "messageId", event.messageId.toString(),
                "eventType", event.eventType, "sourceEventId", event.sourceEventId.toString(),
                "correlationId", UUID.randomUUID().toString(), "causationId", event.sourceEventId.toString(),
                "userId", userId, "occurredAt", Objects.requireNonNull(now).toString());
        event.status = OutboxStatus.PENDING;
        event.nextRetryAt = now;
        event.createdAt = now;
        return event;
    }

    /**
     * Logout control message: Notification deactivates the push Endpoints that this login session registered.
     * Session IDs are reused per device across logins, so the dedup key is per message, not per session.
     */
    public static OutboxEvent userSessionEnded(Long userId, String sessionId, Instant now) {
        var event = new OutboxEvent();
        event.messageId = UUID.randomUUID();
        event.sourceEventId = UUID.randomUUID();
        event.userId = Objects.requireNonNull(userId);
        event.eventType = "USER_SESSION_ENDED";
        event.dedupKey = "USER_SESSION_ENDED:" + event.messageId;
        event.payload = Map.of("schemaVersion", 1, "messageId", event.messageId.toString(),
                "eventType", event.eventType, "sourceEventId", event.sourceEventId.toString(),
                "correlationId", UUID.randomUUID().toString(), "causationId", event.sourceEventId.toString(),
                "userId", userId, "sessionId", Objects.requireNonNull(sessionId),
                "occurredAt", Objects.requireNonNull(now).toString());
        event.status = OutboxStatus.PENDING;
        event.nextRetryAt = now;
        event.createdAt = now;
        return event;
    }

    public static OutboxEvent notification(UUID messageId, UUID sourceEventId, Long userId, Long opportunityId,
            String notificationType, Map<String, Object> envelope, Instant now) {
        var event = new OutboxEvent();
        event.messageId = Objects.requireNonNull(messageId);
        event.sourceEventId = Objects.requireNonNull(sourceEventId);
        event.userId = Objects.requireNonNull(userId);
        event.opportunityId = Objects.requireNonNull(opportunityId);
        event.notificationType = Objects.requireNonNull(notificationType);
        event.eventType = "NOTIFICATION_REQUESTED";
        event.dedupKey = notificationDedupKey(sourceEventId, userId);
        event.payload = Map.copyOf(envelope);
        event.status = OutboxStatus.PENDING;
        event.nextRetryAt = Objects.requireNonNull(now);
        event.createdAt = now;
        return event;
    }
}
