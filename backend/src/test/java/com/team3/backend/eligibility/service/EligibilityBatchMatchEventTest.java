package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.event.EligibilityResultsRefreshed;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import java.time.OffsetDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EligibilityBatchMatchEventTest {
    private final EligibilityProfileRepository profiles=mock(EligibilityProfileRepository.class);
    private final EligibilityResultService results=mock(EligibilityResultService.class);
    private final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    private final EligibilityBatchRefresher batch=new EligibilityBatchRefresher(profiles,results,events);
    private final OffsetDateTime now=OffsetDateTime.parse("2026-10-26T09:00:00+09:00");
    @Test void successfulRefreshPublishesOnlyAfterItReturns() {
        when(profiles.findUserIdsAfter(eq(0L),any())).thenReturn(List.of(7L));
        when(results.refreshStale(7L,now)).thenReturn(3);
        assertThat(batch.refreshAllUsers(now)).isEqualTo(3);
        var order=inOrder(results,events); order.verify(results).refreshStale(7L,now);
        order.verify(events).publishEvent(new EligibilityResultsRefreshed(7));
    }
    @Test void failedUserHasNoEventButNextUserStillRefreshesAndPublishes() {
        when(profiles.findUserIdsAfter(eq(0L),any())).thenReturn(List.of(7L,8L));
        when(results.refreshStale(7L,now)).thenThrow(new IllegalStateException("test"));
        when(results.refreshStale(8L,now)).thenReturn(2);
        assertThat(batch.refreshAllUsers(now)).isEqualTo(2);
        verify(events,never()).publishEvent(new EligibilityResultsRefreshed(7));
        verify(events).publishEvent(new EligibilityResultsRefreshed(8));
    }
    @Test void zeroWritesStillPublishSoEarlierObservationFailuresCanRetry() {
        when(profiles.findUserIdsAfter(eq(0L),any())).thenReturn(List.of(7L));
        assertThat(batch.refreshAllUsers(now)).isZero();
        verify(events).publishEvent(new EligibilityResultsRefreshed(7));
    }
}
