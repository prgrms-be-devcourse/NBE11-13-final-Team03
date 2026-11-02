package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.service.EligibilityBatchRefresher.RunResult;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EligibilityBatchRefresherRunTest {

    private final EligibilityProfileRepository profiles = mock(EligibilityProfileRepository.class);
    private final EligibilityResultService results = mock(EligibilityResultService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final EligibilityBatchRefresher batch = new EligibilityBatchRefresher(profiles, results, events);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-02T15:00:00+09:00");

    @Test
    void tryRunReturnsCountsOfTheRun() {
        when(profiles.findUserIdsAfter(eq(0L), any())).thenReturn(List.of(7L, 8L));
        when(results.refreshStale(7L, now)).thenReturn(3);
        when(results.refreshStale(8L, now)).thenThrow(new IllegalStateException("test"));

        RunResult result = batch.tryRun(now).orElseThrow();

        assertThat(result.users()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.judged()).isEqualTo(3);
        assertThat(result.elapsedMs()).isNotNegative();
    }

    @Test
    void overlappingRunIsSkippedAndNextRunStillWorks() {
        AtomicReference<Optional<RunResult>> overlapping = new AtomicReference<>();
        when(profiles.findUserIdsAfter(eq(0L), any())).thenAnswer(invocation -> {
            overlapping.set(batch.tryRun(now));
            return List.of(7L);
        });
        when(results.refreshStale(7L, now)).thenReturn(2);

        assertThat(batch.tryRun(now)).get().extracting(RunResult::judged).isEqualTo(2);
        assertThat(overlapping.get()).isEmpty();
        verify(results, times(1)).refreshStale(7L, now);

        doReturn(List.of(7L)).when(profiles).findUserIdsAfter(eq(0L), any());
        assertThat(batch.tryRun(now)).isPresent();
    }

    @Test
    void failedRunReleasesTheGuard() {
        when(profiles.findUserIdsAfter(eq(0L), any())).thenThrow(new IllegalStateException("db down"));
        try {
            batch.tryRun(now);
        } catch (IllegalStateException ignored) {
        }

        reset(profiles);
        when(profiles.findUserIdsAfter(eq(0L), any())).thenReturn(List.of());
        assertThat(batch.tryRun(now)).isPresent();
    }
}
