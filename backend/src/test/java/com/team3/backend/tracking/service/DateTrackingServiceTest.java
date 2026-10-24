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
        verify(batch).scan(new EventScanExecution(TODAY, "execution"), 0, TODAY);
    }

    @Test
    void cutoffUsesExistingDataWithoutClaimingCollectionSucceeded() {
        stubScan(TODAY);
        service("2026-10-20T04:00:00Z", TODAY).runDueScans();
        verifyNoInteractions(readiness);
        verify(batch).scan(new EventScanExecution(TODAY, "execution"), 0, TODAY);
    }

    @Test
    void recoversMissingDatesEvenWhileTodayIsWaitingAndSkipsSuccessfulDates() {
        var missed = TODAY.minusDays(2);
        stubScan(missed);
        when(histories.existsByScanDateAndStatus(TODAY.minusDays(1), EventScanStatus.SUCCESS)).thenReturn(true);
        service("2026-10-20T02:00:00Z", missed).runDueScans();
        // Past dates are scanned with today as the processing date, so late D-N events stay suppressed.
        verify(batch).scan(new EventScanExecution(missed, "execution"), 0, TODAY);
        verify(coordinator, never()).tryStart(eq(TODAY), any(), any());
        verify(coordinator, never()).tryStart(eq(TODAY.minusDays(1)), any(), any());
    }

    @Test
    void pagesAreIndependentAndFailureIsRecordedWithTheSameOwnership() {
        stubScan(TODAY);
        var execution = new EventScanExecution(TODAY, "execution");
        when(batch.scan(execution, 0, TODAY)).thenReturn(new DateTrackingBatch.PageResult(100, false));
        when(batch.scan(execution, 100, TODAY)).thenThrow(new IllegalStateException("failure"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service("2026-10-20T04:00:00Z", TODAY).runDueScans())
                .isInstanceOf(IllegalStateException.class);
        verify(coordinator).fail(eq(execution), any(), eq("IllegalStateException: failure"));
    }

    @Test
    void processingDateIsFixedAtRunStartEvenIfTheClockPassesMidnight() {
        var execution = new EventScanExecution(TODAY, "execution");
        when(coordinator.tryStart(eq(TODAY), any(), any())).thenReturn(Optional.of(execution));
        when(batch.scan(execution, 0, TODAY)).thenReturn(new DateTrackingBatch.PageResult(100, false));
        when(batch.scan(execution, 100, TODAY)).thenReturn(new DateTrackingBatch.PageResult(100, true));
        var clock = mock(Clock.class);
        // 23:59:59 KST at run start, then 00:00:01 KST the next day for every later read.
        when(clock.instant()).thenReturn(Instant.parse("2026-10-20T14:59:59Z"), Instant.parse("2026-10-20T15:00:01Z"));
        new DateTrackingService(readiness, histories, coordinator, batch, properties(TODAY, 7), clock).runDueScans();
        verify(batch).scan(execution, 100, TODAY);
        verify(batch, never()).scan(any(), anyLong(), eq(TODAY.plusDays(1)));
    }

    @Test
    void todayIsScannedFirstEvenWhenPastDatesKeepFailingAndExhaustTheBudget() {
        stubScan(TODAY);
        for (int days = 1; days <= 3; days++) {
            var past = new EventScanExecution(TODAY.minusDays(days), "execution");
            when(coordinator.tryStart(eq(past.scanDate()), any(), any())).thenReturn(Optional.of(past));
            when(batch.scan(past, 0, TODAY)).thenThrow(new IllegalStateException("past failure"));
        }
        var service = service("2026-10-20T04:00:00Z", TODAY.minusDays(30), 1);
        for (int poll = 0; poll < 2; poll++) {
            org.assertj.core.api.Assertions.assertThatThrownBy(service::runDueScans)
                    .isInstanceOf(IllegalStateException.class);
        }
        var order = inOrder(batch);
        order.verify(batch).scan(new EventScanExecution(TODAY, "execution"), 0, TODAY);
        order.verify(batch).scan(new EventScanExecution(TODAY.minusDays(3), "execution"), 0, TODAY);
        verify(batch, times(2)).scan(new EventScanExecution(TODAY, "execution"), 0, TODAY);
    }

    @Test
    void datesOlderThanTheRecoveryWindowAreNotRecovered() {
        stubScan(TODAY.minusDays(3));
        service("2026-10-20T02:00:00Z", TODAY.minusDays(30)).runDueScans();
        verify(coordinator, never()).tryStart(eq(TODAY.minusDays(4)), any(), any());
        verify(batch).scan(new EventScanExecution(TODAY.minusDays(3), "execution"), 0, TODAY);
    }

    @Test
    void datesWithoutAnAcquiredRunDoNotConsumeTheBudget() {
        // TODAY-3 is held by another worker, so the single budget slot goes to TODAY-2.
        stubScan(TODAY.minusDays(2));
        service("2026-10-20T02:00:00Z", TODAY.minusDays(3), 1).runDueScans();
        verify(batch).scan(new EventScanExecution(TODAY.minusDays(2), "execution"), 0, TODAY);
        verify(coordinator, never()).tryStart(eq(TODAY.minusDays(1)), any(), any());
    }

    private void stubScan(LocalDate date) {
        var execution = new EventScanExecution(date, "execution");
        when(coordinator.tryStart(eq(date), any(), any())).thenReturn(Optional.of(execution));
        when(batch.scan(execution, 0, TODAY)).thenReturn(new DateTrackingBatch.PageResult(0, true));
    }

    private DateTrackingService service(String instant, LocalDate startDate) {
        return service(instant, startDate, 7);
    }

    private DateTrackingService service(String instant, LocalDate startDate, int maxDatesPerRun) {
        return new DateTrackingService(readiness, histories, coordinator, batch,
                properties(startDate, maxDatesPerRun), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static DateTrackingProperties properties(LocalDate startDate, int maxDatesPerRun) {
        return new DateTrackingProperties(true, LocalTime.of(13, 0), Duration.ofMinutes(30), 100, maxDatesPerRun,
                startDate, 3);
    }
}
