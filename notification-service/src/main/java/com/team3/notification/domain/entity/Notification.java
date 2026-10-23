package com.team3.notification.domain.entity;

import com.team3.notification.message.NotificationRequestedMessage;
import com.team3.notification.message.NotificationType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "notifications",
        uniqueConstraints = @UniqueConstraint(name = "uk_notifications_user_source_event",
                columnNames = {"user_id", "source_event_id"}),
        indexes = @Index(name = "idx_notifications_user_created", columnList = "user_id, created_at, id"))
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;
    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;
    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;
    @Column(name = "causation_id", nullable = false, updatable = false)
    private UUID causationId;
    @Column(name = "opportunity_id", nullable = false, updatable = false)
    private Long opportunityId;
    @Column(name = "opportunity_version_id", nullable = false, updatable = false)
    private Long opportunityVersionId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private NotificationType type;
    @Column(nullable = false, length = 500, updatable = false)
    private String title;
    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String content;
    @Column(nullable = false, length = 2000, updatable = false)
    private String link;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> payload;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "read_at")
    private Instant readAt;
    @Column(name = "clicked_at")
    private Instant clickedAt;

    protected Notification() {
    }

    public static Notification inApp(NotificationRequestedMessage message, Instant now) {
        var notification = new Notification();
        notification.userId = message.userId();
        notification.sourceEventId = message.sourceEventId();
        notification.messageId = message.messageId();
        notification.correlationId = message.correlationId();
        notification.causationId = message.causationId();
        notification.opportunityId = message.opportunityId();
        notification.opportunityVersionId = message.opportunityVersionId();
        notification.type = message.type();
        notification.title = message.title();
        notification.content = message.content();
        notification.link = message.link();
        notification.payload = message.payload();
        notification.occurredAt = message.occurredAt();
        notification.expiresAt = message.expiresAt();
        notification.createdAt = now;
        return notification;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public UUID getSourceEventId() { return sourceEventId; }
    public UUID getMessageId() { return messageId; }
    public UUID getCorrelationId() { return correlationId; }
    public UUID getCausationId() { return causationId; }
    public Long getOpportunityId() { return opportunityId; }
    public Long getOpportunityVersionId() { return opportunityVersionId; }
    public NotificationType getType() { return type; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getLink() { return link; }
    public Map<String, Object> getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
    public Instant getClickedAt() { return clickedAt; }
}
