package com.team3.backend.collection.domain.repository;

import com.team3.backend.collection.domain.entity.SyncHistory;
import com.team3.backend.collection.domain.SyncJobStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncHistoryRepository extends JpaRepository<SyncHistory, Long> {
    boolean existsBySourceIdAndStatusIn(Long sourceId, List<SyncJobStatus> statuses);

    List<SyncHistory> findBySyncTypeAndStatusIn(String syncType, List<SyncJobStatus> statuses);

    List<SyncHistory> findBySyncTypeAndStatusInAndStartedAtBefore(
            String syncType, List<SyncJobStatus> statuses, OffsetDateTime startedBefore);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SyncHistory s where s.id = :id")
    Optional<SyncHistory> findByIdForUpdate(@Param("id") Long id);
}
