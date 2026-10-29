package com.team3.backend.tracking.service;

import com.team3.backend.tracking.domain.entity.EventScanStatus;
import com.team3.backend.tracking.domain.model.EventScanExecution;
import com.team3.backend.tracking.domain.repository.EventScanHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventScanCoordinatorTest {

    private static final LocalDate SCAN_DATE =
            LocalDate.of(2026, 10, 20);

    private static final Instant NOW =
            Instant.parse("2026-10-20T02:00:00Z");

    private EventScanHistoryRepository historyRepository;
    private EventScanCoordinator coordinator;

    @BeforeEach
    void setUp() {
        historyRepository =
                mock(EventScanHistoryRepository.class);

        coordinator =
                new EventScanCoordinator(historyRepository);
    }

    @Test
    void 이력이_없으면_새로운_스캔_실행권을_획득한다() {
        when(historyRepository.insertRunningIfAbsent(
                eq(SCAN_DATE),
                anyString(),
                eq(NOW)
        )).thenReturn(1);

        Optional<EventScanExecution> result =
                coordinator.tryStart(SCAN_DATE, NOW);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().scanDate())
                .isEqualTo(SCAN_DATE);
        assertThat(result.orElseThrow().executionId())
                .isNotBlank();

        verify(historyRepository, never())
                .restartFailed(
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any()
                );
    }

    @Test
    void 이미_실행_중이면_실행권을_획득하지_못한다() {
        when(historyRepository.insertRunningIfAbsent(
                eq(SCAN_DATE),
                anyString(),
                eq(NOW)
        )).thenReturn(0);

        when(historyRepository.restartFailed(
                eq(SCAN_DATE),
                anyString(),
                eq(NOW),
                eq(EventScanStatus.RUNNING),
                eq(EventScanStatus.FAILED)
        )).thenReturn(0);

        Optional<EventScanExecution> result =
                coordinator.tryStart(SCAN_DATE, NOW);

        assertThat(result).isEmpty();
    }

    @Test
    void 실패한_스캔은_새로운_실행권으로_재시작한다() {
        when(historyRepository.insertRunningIfAbsent(
                eq(SCAN_DATE),
                anyString(),
                eq(NOW)
        )).thenReturn(0);

        when(historyRepository.restartFailed(
                eq(SCAN_DATE),
                anyString(),
                eq(NOW),
                eq(EventScanStatus.RUNNING),
                eq(EventScanStatus.FAILED)
        )).thenReturn(1);

        Optional<EventScanExecution> result =
                coordinator.tryStart(SCAN_DATE, NOW);

        assertThat(result).isPresent();
    }

    @Test
    void 실행권을_가진_작업만_성공_처리할_수_있다() {
        EventScanExecution execution =
                new EventScanExecution(
                        SCAN_DATE,
                        "execution-1"
                );

        when(historyRepository.completeIfOwned(
                SCAN_DATE,
                "execution-1",
                NOW,
                EventScanStatus.RUNNING,
                EventScanStatus.SUCCESS
        )).thenReturn(1);

        boolean completed =
                coordinator.complete(execution, NOW);

        assertThat(completed).isTrue();
    }

    @Test
    void 실행권이_바뀌면_이전_작업은_완료할_수_없다() {
        EventScanExecution oldExecution =
                new EventScanExecution(
                        SCAN_DATE,
                        "old-execution"
                );

        when(historyRepository.completeIfOwned(
                SCAN_DATE,
                "old-execution",
                NOW,
                EventScanStatus.RUNNING,
                EventScanStatus.SUCCESS
        )).thenReturn(0);

        boolean completed =
                coordinator.complete(oldExecution, NOW);

        assertThat(completed).isFalse();
    }

    @Test
    void 실행권을_가진_작업은_실패_상태를_기록한다() {
        EventScanExecution execution =
                new EventScanExecution(
                        SCAN_DATE,
                        "execution-1"
                );

        when(historyRepository.failIfOwned(
                SCAN_DATE,
                "execution-1",
                NOW,
                "정책 조회 실패",
                EventScanStatus.RUNNING,
                EventScanStatus.FAILED
        )).thenReturn(1);

        boolean failed =
                coordinator.fail(
                        execution,
                        NOW,
                        "정책 조회 실패"
                );

        assertThat(failed).isTrue();
    }
}
