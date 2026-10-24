package com.team3.backend.tracking.domain.repository;

import com.team3.backend.tracking.domain.entity.OpportunityEvent;
import com.team3.backend.tracking.domain.entity.OpportunityEventProcessingStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

public interface OpportunityEventRepository
        extends JpaRepository<OpportunityEvent, Long> {

    boolean existsByDedupKey(String dedupKey);

    Optional<OpportunityEvent> findByDedupKey(String dedupKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OpportunityEvent e where e.id = :id")
    Optional<OpportunityEvent> lockForFanout(@Param("id") Long id);

    @Query("""
            select e.id from OpportunityEvent e
            where e.retryCount < :maxAttempts
              and ((e.processingStatus = :pending and e.nextRetryAt <= :now)
                or (e.processingStatus = :processing and e.processingStartedAt <= :staleBefore))
            order by e.nextRetryAt, e.id
            """)
    List<Long> findFanoutCandidates(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore,
            @Param("maxAttempts") int maxAttempts, @Param("pending") OpportunityEventProcessingStatus pending,
            @Param("processing") OpportunityEventProcessingStatus processing, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OpportunityEvent e set e.processingStatus = :processing,
                e.processingStartedAt = :now, e.leaseToken = :token,
                e.retryCount = e.retryCount + 1, e.lastError = null,
                e.fanoutSkipReason = null, e.processedAt = null
            where e.id = :id and e.retryCount < :maxAttempts
              and ((e.processingStatus = :pending and e.nextRetryAt <= :now)
                or (e.processingStatus = :processing and e.processingStartedAt <= :staleBefore))
            """)
    int claimFanout(@Param("id") Long id, @Param("token") UUID token, @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore, @Param("maxAttempts") int maxAttempts,
            @Param("pending") OpportunityEventProcessingStatus pending,
            @Param("processing") OpportunityEventProcessingStatus processing);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OpportunityEvent e set e.processingStatus = :failed, e.processedAt = :now,
                e.processingStartedAt = null, e.leaseToken = null, e.lastError = 'FANOUT_ATTEMPTS_EXHAUSTED'
            where e.retryCount >= :maxAttempts
              and ((e.processingStatus = :pending and e.nextRetryAt <= :now)
                or (e.processingStatus = :processing and e.processingStartedAt <= :staleBefore))
            """)
    int failExhaustedFanouts(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore,
            @Param("maxAttempts") int maxAttempts, @Param("pending") OpportunityEventProcessingStatus pending,
            @Param("processing") OpportunityEventProcessingStatus processing,
            @Param("failed") OpportunityEventProcessingStatus failed);

    /**
     * 현재 처리 가능한 이벤트 ID를 조회한다.
     *
     * 여기서 조회됐다고 처리 권한을 얻은 것은 아니다.
     * 여러 Worker가 같은 ID를 조회할 수 있으므로,
     * 반드시 claim()에 성공한 Worker만 처리해야 한다.
     */
    @Query("""
            select e.id
            from OpportunityEvent e
            where e.processingStatus = :status
              and e.nextRetryAt <= :now
            order by e.nextRetryAt asc, e.id asc
            """)
    List<Long> findReadyEventIds(
            @Param("status")
            OpportunityEventProcessingStatus status,

            @Param("now")
            Instant now,

            Pageable pageable
    );

    /**
     * PENDING이면서 처리 예정 시각이 지난 이벤트만
     * PROCESSING으로 원자적으로 변경한다.
     *
     * 반환값:
     * 1 -> 현재 Worker가 점유 성공
     * 0 -> 다른 Worker가 이미 점유했거나 아직 처리 시각이 아님
     */
    @Transactional
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update OpportunityEvent e
               set e.processingStatus = :processingStatus,
                   e.processingStartedAt = :now
             where e.id = :eventId
               and e.processingStatus = :pendingStatus
               and e.nextRetryAt <= :now
            """)
    int claimIfReady(
            @Param("eventId")
            Long eventId,

            @Param("pendingStatus")
            OpportunityEventProcessingStatus pendingStatus,

            @Param("processingStatus")
            OpportunityEventProcessingStatus processingStatus,

            @Param("now")
            Instant now
    );

    default boolean claim(
            Long eventId,
            Instant now
    ) {
        int updated = claimIfReady(
                eventId,
                OpportunityEventProcessingStatus.PENDING,
                OpportunityEventProcessingStatus.PROCESSING,
                now
        );

        return updated == 1;
    }
}
