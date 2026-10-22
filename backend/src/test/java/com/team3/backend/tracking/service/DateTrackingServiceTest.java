package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.DateTrackingProperties;
import com.team3.backend.tracking.domain.entity.EventScanStatus;
import com.team3.backend.tracking.domain.model.EventScanExecution;
import com.team3.backend.tracking.domain.repository.EventScanHistoryRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.Mockito.*;

class DateTrackingServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private final DateTrackingReadiness readiness = mock(DateTrackingReadiness.class);
    private final EventScanHistoryRepository histories = mock(EventScanHistoryRepository.class);
    private final EventScanCoordinator coordinator = mock(EventScanCoordinator.class);
    private final DateTrackingBatch batch = mock(DateTrackingBatch.class);

    @Test
    void waitsBeforeCutoffUntilCollectionAndNormalizationAreComplete() {
        service("2026-10-20T02:00:00Z", TODAY).runDueScans();
        verify(readiness).ready(TODAY);
        verifyNoInteractions(coordinator, batch);
    }

    @Test
    void scansImmediatelyAfterReadinessBeforeCutoff() {
        when(readiness.ready(TODAY)).thenReturn(true);
        stubScan(TODAY);
        service("2026-10-20T02:00:00Z", TODAY).runDueScans();
        verify(batch).scan(new EventScanExecution(TODAY, "execution"), 0);
    }

    @Test
    void cutoffUsesExistingDataWithoutClaimingCollectionSucceeded() {
        stubScan(TODAY);
        service("2026-10-20T04:00:00Z", TODAY).runDueScans();
        verifyNoInteractions(readiness);
        verify(batch).scan(new EventScanExecution(TODAY, "execution"), 0);
    }

    @Test
    void recoversMissingDatesEvenWhileTodayIsWaitingAndSkipsSuccessfulDates() {
        var missed = TODAY.minusDays(2);
        stubScan(missed);
        when(histories.existsByScanDateAndStatus(TODAY.minusDays(1), EventScanStatus.SUCCESS)).thenReturn(true);
        service("2026-10-20T02:00:00Z", missed).runDueScans();
        verify(batch).scan(new EventScanExecution(missed, "execution"), 0);
        verify(coordinator, never()).tryStart(eq(TODAY), any(), any());
        verify(coordinator, never()).tryStart(eq(TODAY.minusDays(1)), any(), any());
    }

    @Test
    void pagesAreIndependentAndFailureIsRecordedWithTheSameOwnership() {
        stubScan(TODAY);
        var execution = new EventScanExecution(TODAY, "execution");
        when(batch.scan(execution, 0)).thenReturn(new DateTrackingBatch.PageResult(100, false));
        when(batch.scan(execution, 100)).thenThrow(new IllegalStateException("failure"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service("2026-10-20T04:00:00Z", TODAY).runDueScans())
                .isInstanceOf(IllegalStateException.class);
        verify(coordinator).fail(eq(execution), any(), eq("IllegalStateException"));
    }

    private void stubScan(LocalDate date) {
        var execution = new EventScanExecution(date, "execution");
        when(coordinator.tryStart(eq(date), any(), any())).thenReturn(Optional.of(execution));
        when(batch.scan(execution, 0)).thenReturn(new DateTrackingBatch.PageResult(0, true));
    }

    private DateTrackingService service(String instant, LocalDate startDate) {
        return new DateTrackingService(readiness, histories, coordinator, batch,
                new DateTrackingProperties(true, LocalTime.of(13, 0), Duration.ofMinutes(30), 100, 7, startDate),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
}
