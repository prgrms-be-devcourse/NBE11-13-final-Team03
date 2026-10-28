package com.team3.notification.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Schema validation mapping; registration/deactivation use atomic JDBC statements in NotificationUserService. */
@Entity
@Table(name = "notification_endpoints", uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_endpoints_fcm_token", columnNames = "fcm_token"))
public class NotificationEndpoint {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "fcm_token", nullable = false, length = 2048) private String fcmToken;
    /** Login session that registered the token (Application session ID). NULL for rows registered before session binding. */
    @Column(name = "session_id", length = 36) private String sessionId;
    @Column(nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected NotificationEndpoint() { }
}
