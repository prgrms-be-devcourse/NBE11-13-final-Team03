package com.team3.notification.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notification_settings")
public class NotificationSetting {
    @Id @Column(name = "user_id") private Long userId;
    @Column(name = "in_app_enabled", nullable = false) private boolean inAppEnabled;
    @Column(name = "push_enabled", nullable = false) private boolean pushEnabled;
    @Column(name = "push_consent_at") private Instant pushConsentAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected NotificationSetting() { }
    public boolean isInAppEnabled() { return inAppEnabled; }
    public boolean isPushEnabled() { return pushEnabled; }
    public Instant getPushConsentAt() { return pushConsentAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
