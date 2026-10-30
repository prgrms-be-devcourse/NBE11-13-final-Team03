package com.team3.backend.auth.unlink;

import jakarta.persistence.*;
import java.time.Instant;

/** No User FK: this durable job must survive withdrawal. Never store OAuth tokens or profile data. */
@Entity
@Table(name = "kakao_unlink_tasks", indexes = @Index(name = "idx_kakao_unlink_retry", columnList = "status,next_attempt_at"))
public class KakaoUnlinkTask {
    public enum Status { PENDING, PROCESSING, COMPLETED, FAILED }
    @Id
    @Column(name = "kakao_id")
    private Long kakaoId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "last_error", length = 80)
    private String lastError;
    protected KakaoUnlinkTask() { }
}
