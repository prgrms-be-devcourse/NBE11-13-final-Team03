package com.team3.notification.domain.entity;

import com.team3.notification.message.NotificationRequestedMessage;
import com.team3.notification.message.UserSessionEndedMessage;
import com.team3.notification.message.UserWithdrawnMessage;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consumed_messages",
        uniqueConstraints = @UniqueConstraint(name = "uk_consumed_messages_message_id", columnNames = "message_id"),
        indexes = @Index(name = "idx_consumed_user_source", columnList = "user_id, source_event_id"))
public class ConsumedMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;
    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;
    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private ReceptionStatus result;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id", foreignKey = @ForeignKey(name = "fk_consumed_notification"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Notification notification;
    @Column(name = "consumed_at", nullable = false, updatable = false)
    private Instant consumedAt;

    protected ConsumedMessage() {
    }

    public static ConsumedMessage record(NotificationRequestedMessage message, String fingerprint,
                                         ReceptionStatus result, Notification notification, Instant now) {
        var consumed = new ConsumedMessage();
        consumed.messageId = message.messageId();
        consumed.userId = message.userId();
        consumed.sourceEventId = message.sourceEventId();
        consumed.correlationId = message.correlationId();
        consumed.requestFingerprint = fingerprint;
        consumed.result = result;
        consumed.notification = notification;
        consumed.consumedAt = now;
        return consumed;
    }

    public Long getId() { return id; }
    public static ConsumedMessage withdrawal(UserWithdrawnMessage message, String fingerprint, Instant now) {
        var consumed = new ConsumedMessage();
        consumed.messageId = message.messageId();
        consumed.userId = message.userId();
        consumed.sourceEventId = message.sourceEventId();
        consumed.correlationId = message.correlationId();
        consumed.requestFingerprint = fingerprint;
        consumed.result = ReceptionStatus.WITHDRAWN;
        consumed.consumedAt = now;
        return consumed;
    }
    public static ConsumedMessage sessionEnd(UserSessionEndedMessage message, String fingerprint, Instant now) {
        var consumed = new ConsumedMessage();
        consumed.messageId = message.messageId();
        consumed.userId = message.userId();
        consumed.sourceEventId = message.sourceEventId();
        consumed.correlationId = message.correlationId();
        consumed.requestFingerprint = fingerprint;
        consumed.result = ReceptionStatus.SESSION_ENDED;
        consumed.consumedAt = now;
        return consumed;
    }
    public UUID getMessageId() { return messageId; }
    public Long getUserId() { return userId; }
    public UUID getSourceEventId() { return sourceEventId; }
    public UUID getCorrelationId() { return correlationId; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public ReceptionStatus getResult() { return result; }
    public Long getNotificationId() { return notification == null ? null : notification.getId(); }
    public Instant getConsumedAt() { return consumedAt; }
}
