package com.team3.notification.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Durable user-ID tombstone. No automatic expiry until the retention/replay policy is confirmed. */
@Entity
@Table(name = "withdrawn_users")
public class WithdrawnUser {
    @Id @Column(name = "user_id") private Long userId;
    @Column(name = "withdrawn_at", nullable = false, updatable = false) private Instant withdrawnAt;
    @Column(name = "received_at", nullable = false, updatable = false) private Instant receivedAt;
    protected WithdrawnUser() { }
    public WithdrawnUser(Long userId, Instant withdrawnAt, Instant receivedAt) {
        this.userId = userId; this.withdrawnAt = withdrawnAt; this.receivedAt = receivedAt;
    }
    public Instant getWithdrawnAt() { return withdrawnAt; }
}
