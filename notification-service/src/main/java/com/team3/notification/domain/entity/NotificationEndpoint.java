package com.team3.notification.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notification_endpoints", uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_endpoints_fcm_token", columnNames = "fcm_token"))
public class NotificationEndpoint {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "fcm_token", nullable = false, length = 2048) private String fcmToken;
    @Column(nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected NotificationEndpoint() { }
}
