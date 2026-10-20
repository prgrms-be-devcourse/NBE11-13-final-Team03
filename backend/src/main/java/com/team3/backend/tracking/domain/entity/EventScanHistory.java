package com.team3.backend.tracking.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(
        name = "event_scan_history",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_event_scan_history_scan_date",
                        columnNames = "scan_date"
                )
        },
        indexes = {
                @Index(
                        name = "idx_event_scan_history_status_date",
                        columnList = "status, scan_date"
                )
        }
)
@Getter
@Builder(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventScanHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Asia/Seoul 기준으로 추적한 날짜.
     */
    @Column(name = "scan_date", nullable = false)
    private LocalDate scanDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EventScanStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static EventScanHistory start(
            LocalDate scanDate,
            Instant now
    ) {
        Objects.requireNonNull(scanDate, "scanDate");
        Objects.requireNonNull(now, "now");

        return EventScanHistory.builder()
                .scanDate(scanDate)
                .status(EventScanStatus.RUNNING)
                .startedAt(now)
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public void succeed(Instant now) {
        Objects.requireNonNull(now, "now");
        requireRunning();

        this.status = EventScanStatus.SUCCESS;
        this.completedAt = now;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void fail(
            Instant now,
            String errorMessage
    ) {
        Objects.requireNonNull(now, "now");
        requireRunning();

        this.status = EventScanStatus.FAILED;
        this.completedAt = now;
        this.lastError = errorMessage;
        this.updatedAt = now;
    }

    public void restart(Instant now) {
        Objects.requireNonNull(now, "now");

        if (status == EventScanStatus.SUCCESS) {
            throw new IllegalStateException(
                    "성공한 날짜 스캔은 다시 시작할 수 없습니다."
            );
        }

        this.status = EventScanStatus.RUNNING;
        this.startedAt = now;
        this.completedAt = null;
        this.retryCount++;
        this.lastError = null;
        this.updatedAt = now;
    }

    private void requireRunning() {
        if (status != EventScanStatus.RUNNING) {
            throw new IllegalStateException(
                    "실행 중인 날짜 스캔만 상태를 변경할 수 있습니다."
            );
        }
    }
}