package com.team3.dataservice.collection.service;

import com.team3.dataservice.collection.domain.CollectionSource;
import com.team3.dataservice.collection.domain.SyncJobStatus;
import com.team3.dataservice.collection.domain.repository.SyncHistoryRepository;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectionSchedulerTests {
    private final ManualSyncService sync = mock(ManualSyncService.class);
    private final SyncHistoryRepository histories = mock(SyncHistoryRepository.class);
    private final CollectionScheduler scheduler = new CollectionScheduler(sync, histories);

    @Test
    void eitherSchedulingSwitchDisablesCollectionBean() {
        var runner = new ApplicationContextRunner()
                .withBean(ManualSyncService.class, () -> sync)
                .withBean(SyncHistoryRepository.class, () -> histories)
                .withUserConfiguration(CollectionScheduler.class);
        runner.withPropertyValues("app.scheduling.enabled=false").run(context ->
                assertEquals(0, context.getBeansOfType(CollectionScheduler.class).size()));
        runner.withPropertyValues("collection.schedule.enabled=false").run(context ->
                assertEquals(0, context.getBeansOfType(CollectionScheduler.class).size()));
        runner.run(context -> assertEquals(1, context.getBeansOfType(CollectionScheduler.class).size()));
    }

    @ParameterizedTest
    @CsvSource({"collectYouthPolicy,09:44:59", "collectEmployment24,09:29:59"})
    void cronRunsAtEachSourceTimeInKorea(String method, String time) throws Exception {
        var schedule = CollectionScheduler.class.getMethod(method).getAnnotation(Scheduled.class);
        assertEquals("Asia/Seoul", schedule.zone());
        var before = ZonedDateTime.parse("2026-10-18T" + time + "+09:00[Asia/Seoul]");
        var next = CronExpression.parse(schedule.cron()).next(before);
        assertEquals(before.plusSeconds(1), next);
        assertEquals(next.plusDays(1), CronExpression.parse(schedule.cron()).next(next));
    }

    @Test
    void oneSourceFailureDoesNotPreventTheOtherSource() {
        doThrow(new IllegalStateException("active")).when(sync).startScheduled(CollectionSource.YOUTH_POLICY);
        scheduler.collectYouthPolicy();
        scheduler.collectEmployment24();
        verify(sync).startScheduled(CollectionSource.EMPLOYMENT24);
    }

    @Test
    void noCatchUpBeforeScheduledTime() {
        scheduler.recoverMissedSchedule(ZonedDateTime.parse("2026-10-18T09:29:59+09:00[Asia/Seoul]"));
        verifyNoInteractions(sync, histories);
    }

    @ParameterizedTest
    @ValueSource(strings = {"09:30:00", "09:44:59"})
    void startupBeforeNineFortyFiveOnlyCatchesUpEmployment24(String time) {
        scheduler.recoverMissedSchedule(ZonedDateTime.parse("2026-10-18T" + time + "+09:00[Asia/Seoul]"));
        verify(sync).startScheduled(CollectionSource.EMPLOYMENT24);
        verifyNoMoreInteractions(sync);
        verify(histories, never()).existsBySourceSourceCodeAndStatusAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                eq("YOUTH_POLICY"), any(), any(), any());
    }

    @Test
    void startupAtNineFortyFiveCanCatchUpBothSources() {
        scheduler.recoverMissedSchedule(ZonedDateTime.parse("2026-10-18T09:45:00+09:00[Asia/Seoul]"));
        verify(sync).startScheduled(CollectionSource.YOUTH_POLICY);
        verify(sync).startScheduled(CollectionSource.EMPLOYMENT24);
    }

    @Test
    void catchUpOnlySourcesWithoutSuccessToday() {
        var now = ZonedDateTime.parse("2026-10-18T10:00:00+09:00[Asia/Seoul]");
        var from = now.toLocalDate().atStartOfDay(now.getZone()).toOffsetDateTime();
        var until = from.plusDays(1);
        when(histories.existsBySourceSourceCodeAndStatusAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                "YOUTH_POLICY", SyncJobStatus.SUCCESS, from, until)).thenReturn(true);
        scheduler.recoverMissedSchedule(now);
        verify(sync, never()).startScheduled(CollectionSource.YOUTH_POLICY);
        verify(sync).startScheduled(CollectionSource.EMPLOYMENT24);
    }

    @Test
    void scheduledAttemptPreventsAnotherAttemptOnEveryRestart() {
        var now = ZonedDateTime.parse("2026-10-18T10:00:00+09:00[Asia/Seoul]");
        var from = now.toLocalDate().atStartOfDay(now.getZone()).toOffsetDateTime();
        var until = from.plusDays(1);
        when(histories.existsBySourceSourceCodeAndSyncTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                "YOUTH_POLICY", "SCHEDULED", from, until)).thenReturn(true);

        scheduler.recoverMissedSchedule(now);
        scheduler.recoverMissedSchedule(now.plusHours(1));

        verify(sync, never()).startScheduled(CollectionSource.YOUTH_POLICY);
        verify(sync, times(2)).startScheduled(CollectionSource.EMPLOYMENT24);
    }

    @Test
    void nextDayWithoutAnAttemptCanCatchUpAgain() {
        var firstDay = ZonedDateTime.parse("2026-10-18T10:00:00+09:00[Asia/Seoul]");
        var from = firstDay.toLocalDate().atStartOfDay(firstDay.getZone()).toOffsetDateTime();
        when(histories.existsBySourceSourceCodeAndSyncTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                "YOUTH_POLICY", "SCHEDULED", from, from.plusDays(1))).thenReturn(true);

        scheduler.recoverMissedSchedule(firstDay);
        scheduler.recoverMissedSchedule(firstDay.plusDays(1));

        verify(sync).startScheduled(CollectionSource.YOUTH_POLICY);
    }
}
