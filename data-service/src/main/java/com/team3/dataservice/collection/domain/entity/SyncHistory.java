package com.team3.dataservice.collection.domain.entity;

import com.team3.dataservice.collection.domain.RecordResultType;
import com.team3.dataservice.collection.domain.SyncJobStatus;
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
import jakarta.persistence.Index;
import com.team3.dataservice.collection.dto.response.Employment24PageFailureResponse;
import com.team3.dataservice.collection.dto.response.SyncPageFailureResponse;
import com.team3.dataservice.collection.dto.response.SyncRecordResultResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sync_history", indexes = {
        @Index(name = "idx_sync_source_status", columnList = "source_id, status")
})
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

    @Column(name = "new_count", nullable = false)
    private int newCount;

    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    @Column(name = "unchanged_count", nullable = false)
    private int unchangedCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "page_failures", nullable = false, columnDefinition = "jsonb")
    private List<SyncPageFailureResponse> pageFailures = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "employment24_page_failures", nullable = false, columnDefinition = "jsonb")
    private List<Employment24PageFailureResponse> employment24PageFailures = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "incomplete_ncs_codes", nullable = false, columnDefinition = "jsonb")
    private List<String> incompleteNcsCodes = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "record_failures", nullable = false, columnDefinition = "jsonb")
    private List<SyncRecordResultResponse> recordFailures = new ArrayList<>();

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

    public static SyncHistory pendingManual(ExternalSource source, OffsetDateTime acceptedAt) {
        return pending(source, "MANUAL", acceptedAt);
    }

    public static SyncHistory pending(ExternalSource source, String syncType, OffsetDateTime acceptedAt) {
        var history = new SyncHistory(source, syncType, acceptedAt);
        history.status = SyncJobStatus.PENDING;
        return history;
    }

    public void start() {
        if (status != SyncJobStatus.PENDING) {
            throw new IllegalStateException("대기 중인 수집 실행만 시작할 수 있습니다.");
        }
        status = SyncJobStatus.RUNNING;
    }

    public void recordCollection(int fetchedCount, List<SyncPageFailureResponse> pageFailures,
                                 List<Employment24PageFailureResponse> employment24PageFailures,
                                 List<String> incompleteNcsCodes) {
        requireRunning();
        this.collectedCount = fetchedCount;
        this.pageFailures = new ArrayList<>(pageFailures);
        this.employment24PageFailures = new ArrayList<>(employment24PageFailures);
        this.incompleteNcsCodes = new ArrayList<>(incompleteNcsCodes);
    }

    public void recordFetchedPage(int count) {
        requireRunning();
        collectedCount += count;
    }

    public void recordSuccess(RecordResultType result) {
        requireRunning();
        switch (result) {
            case CREATED -> newCount++;
            case UPDATED -> updatedCount++;
            case UNCHANGED -> unchangedCount++;
        }
        changedCount = newCount + updatedCount;
    }

    public void recordFailure(SyncRecordResultResponse failure) {
        requireRunning();
        recordFailures.add(failure);
        failedCount++;
    }

    public int successCount() {
        return newCount + updatedCount + unchangedCount;
    }

    public int pendingCount() {
        return Math.max(0, collectedCount - successCount() - failedCount);
    }

    public int failedPageCount() {
        return pageFailures.size() + employment24PageFailures.size();
    }

    public void finish(boolean completed, OffsetDateTime finishedAt) {
        requireRunning();
        boolean clean = completed && failedCount == 0 && failedPageCount() == 0 && pendingCount() == 0;
        status = clean ? SyncJobStatus.SUCCESS
                : successCount() > 0 ? SyncJobStatus.PARTIAL_SUCCESS : SyncJobStatus.FAILED;
        failureReason = clean ? null : "수집 미완료 또는 항목·페이지 실패가 있습니다.";
        this.finishedAt = finishedAt;
    }

    public void fail(String safeReason, boolean interrupted, OffsetDateTime finishedAt) {
        if (status != SyncJobStatus.PENDING && status != SyncJobStatus.RUNNING) {
            return;
        }
        status = interrupted ? SyncJobStatus.INTERRUPTED : SyncJobStatus.FAILED;
        failureReason = safeReason;
        this.finishedAt = finishedAt;
    }

    private void requireRunning() {
        if (status != SyncJobStatus.RUNNING) {
            throw new IllegalStateException("진행 중인 수집 실행이 아닙니다.");
        }
    }
}
