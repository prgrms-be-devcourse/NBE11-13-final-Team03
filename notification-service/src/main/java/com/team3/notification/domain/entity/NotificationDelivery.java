package com.team3.notification.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;
import java.util.UUID;

/** JPA validates the manual schema; atomic claim/completion is implemented by NotificationDeliveryStore. */
@Entity
@Table(name = "notification_deliveries", uniqueConstraints = @UniqueConstraint(
        name = "uk_delivery_notification_endpoint", columnNames = {"notification_id", "endpoint_id"}))
public class NotificationDelivery {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false, foreignKey = @ForeignKey(name = "fk_delivery_notification"))
    @OnDelete(action = OnDeleteAction.CASCADE) private Notification notification;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false, foreignKey = @ForeignKey(name = "fk_delivery_endpoint"))
    @OnDelete(action = OnDeleteAction.CASCADE) private NotificationEndpoint endpoint;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private DeliveryStatus status;
    @Column(name = "retry_count", nullable = false) private int retryCount;
    @Column(name = "next_retry_at", nullable = false) private Instant nextRetryAt;
    @Column(name = "processing_started_at") private Instant processingStartedAt;
    @Column(name = "lease_token") private UUID leaseToken;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "fcm_message_id", length = 500) private String fcmMessageId;
    @Column(name = "last_error", length = 80) private String lastError;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected NotificationDelivery() { }
}
