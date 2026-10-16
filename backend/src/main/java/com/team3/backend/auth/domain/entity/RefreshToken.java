package com.team3.backend.auth.domain.entity;

import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "refresh_tokens",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_refresh_tokens_session_id",
                        columnNames = "session_id"
                ),
                @UniqueConstraint(
                        name = "uk_refresh_tokens_user_device",
                        columnNames = {"user_id", "device_id"}
                ),
                @UniqueConstraint(
                        name = "uk_refresh_tokens_token_hash",
                        columnNames = "token_hash"
                )
        },
        indexes = {
                @Index(
                        name = "idx_refresh_tokens_user_last_used",
                        columnList = "user_id,last_used_at"
                )
        }
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_refresh_tokens_user"
            )
    )
    private User user;

    @Column(
            name = "session_id",
            nullable = false,
            length = 36
    )
    private String sessionId;

    @Column(
            name = "device_id",
            nullable = false,
            length = 36
    )
    private String deviceId;

    @Column(
            name = "token_hash",
            nullable = false,
            length = 64
    )
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_used_at", nullable = false)
    private LocalDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();

        this.createdAt = now;
        this.updatedAt = now;

        if (this.lastUsedAt == null) {
            this.lastUsedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void rotate(
            String tokenHash,
            LocalDateTime expiresAt
    ) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.lastUsedAt = LocalDateTime.now();
    }
}