package com.team3.dataservice.collection.domain.repository;

import com.team3.dataservice.collection.domain.entity.SyncHistory;
import com.team3.dataservice.collection.domain.SyncJobStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncHistoryRepository extends JpaRepository<SyncHistory, Long> {
    @EntityGraph(attributePaths = "source")
    List<SyncHistory> findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtDescIdDesc(
            OffsetDateTime from, OffsetDateTime until);

    List<SyncHistory> findByStatusInAndStartedAtBefore(List<SyncJobStatus> statuses, OffsetDateTime startedBefore);

    boolean existsBySourceSourceCodeAndStatusAndStartedAtGreaterThanEqualAndStartedAtLessThan(
            String sourceCode, SyncJobStatus status, OffsetDateTime from, OffsetDateTime until);

    boolean existsBySourceSourceCodeAndSyncTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
            String sourceCode, String syncType, OffsetDateTime from, OffsetDateTime until);
    boolean existsBySourceIdAndStatusIn(Long sourceId, List<SyncJobStatus> statuses);

    List<SyncHistory> findBySyncTypeAndStatusIn(String syncType, List<SyncJobStatus> statuses);

    List<SyncHistory> findBySyncTypeAndStatusInAndStartedAtBefore(
            String syncType, List<SyncJobStatus> statuses, OffsetDateTime startedBefore);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SyncHistory s where s.id = :id")
    Optional<SyncHistory> findByIdForUpdate(@Param("id") Long id);
}
