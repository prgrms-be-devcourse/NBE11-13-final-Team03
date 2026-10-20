package com.team3.backend.tracking.domain.repository;

import com.team3.backend.tracking.domain.entity.EventScanHistory;
import com.team3.backend.tracking.domain.entity.EventScanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface EventScanHistoryRepository
        extends JpaRepository<EventScanHistory, Long> {

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
}