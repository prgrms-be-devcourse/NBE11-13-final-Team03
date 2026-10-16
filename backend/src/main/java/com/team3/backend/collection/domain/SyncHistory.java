package com.team3.backend.collection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sync_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SyncHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private ExternalSource source;

    @Column(name = "sync_type", nullable = false)
    private String syncType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncJobStatus status;

    @Column(name = "collected_count", nullable = false)
    private int collectedCount;

    @Column(name = "changed_count", nullable = false)
    private int changedCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    public SyncHistory(ExternalSource source, String syncType, OffsetDateTime startedAt) {
        this.source = source;
        this.syncType = syncType;
        this.status = SyncJobStatus.RUNNING;
        this.startedAt = startedAt;
    }
}
