package com.team3.backend.tracking.domain.repository;

import com.team3.backend.tracking.domain.entity.EventScanHistory;
import com.team3.backend.tracking.domain.entity.EventScanStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

public interface EventScanHistoryRepository
        extends JpaRepository<EventScanHistory, Long> {

    Optional<EventScanHistory> findFirstByOrderByScanDateAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from EventScanHistory h where h.scanDate = :date")
    Optional<EventScanHistory> lockByScanDate(@Param("date") LocalDate date);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update EventScanHistory h
               set h.executionId = :executionId, h.startedAt = :now, h.updatedAt = :now,
                   h.completedAt = null, h.retryCount = h.retryCount + 1, h.lastError = null
             where h.scanDate = :date and h.status = :running
               and h.updatedAt <= :staleBefore
            """)
    int restartExpired(@Param("date") LocalDate date, @Param("executionId") String executionId,
                       @Param("now") Instant now, @Param("staleBefore") Instant staleBefore,
                       @Param("running") EventScanStatus running);

    Optional<EventScanHistory> findByScanDate(
            LocalDate scanDate
    );

    boolean existsByScanDateAndStatus(
            LocalDate scanDate,
            EventScanStatus status
    );

    Optional<EventScanHistory> findTopByStatusOrderByScanDateDesc(
            EventScanStatus status
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    insert into event_scan_history (
                        scan_date,
                        execution_id,
                        status,
                        started_at,
                        retry_count,
                        created_at,
                        updated_at
                    )
                    values (
                        :scanDate,
                        :executionId,
                        'RUNNING',
                        :now,
                        0,
                        :now,
                        :now
                    )
                    on conflict (scan_date) do nothing
                    """,
            nativeQuery = true
    )
    int insertRunningIfAbsent(
            @Param("scanDate") LocalDate scanDate,
            @Param("executionId") String executionId,
            @Param("now") Instant now
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update EventScanHistory h
               set h.executionId = :executionId,
                   h.status = :runningStatus,
                   h.startedAt = :now,
                   h.completedAt = null,
                   h.retryCount = h.retryCount + 1,
                   h.lastError = null,
                   h.updatedAt = :now
             where h.scanDate = :scanDate
               and h.status = :failedStatus
            """)
    int restartFailed(
            @Param("scanDate") LocalDate scanDate,
            @Param("executionId") String executionId,
            @Param("now") Instant now,
            @Param("runningStatus")
            EventScanStatus runningStatus,
            @Param("failedStatus")
            EventScanStatus failedStatus
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update EventScanHistory h
               set h.status = :successStatus,
                   h.completedAt = :now,
                   h.lastError = null,
                   h.updatedAt = :now
             where h.scanDate = :scanDate
               and h.executionId = :executionId
               and h.status = :runningStatus
            """)
    int completeIfOwned(
            @Param("scanDate") LocalDate scanDate,
            @Param("executionId") String executionId,
            @Param("now") Instant now,
            @Param("runningStatus")
            EventScanStatus runningStatus,
            @Param("successStatus")
            EventScanStatus successStatus
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update EventScanHistory h
               set h.status = :failedStatus,
                   h.completedAt = :now,
                   h.lastError = :errorMessage,
                   h.updatedAt = :now
             where h.scanDate = :scanDate
               and h.executionId = :executionId
               and h.status = :runningStatus
            """)
    int failIfOwned(
            @Param("scanDate") LocalDate scanDate,
            @Param("executionId") String executionId,
            @Param("now") Instant now,
            @Param("errorMessage") String errorMessage,
            @Param("runningStatus")
            EventScanStatus runningStatus,
            @Param("failedStatus")
            EventScanStatus failedStatus
    );
}
