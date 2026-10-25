package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.EligibilityFanoutSchedulingProperties;
import com.team3.backend.tracking.domain.model.EventFanoutExecution;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EligibilityFanoutWorkerTest {
    private final EligibilityFanoutCoordinator coordinator = mock(EligibilityFanoutCoordinator.class);
    private final EligibilityFanoutBatch batch = mock(EligibilityFanoutBatch.class);

    private EligibilityFanoutWorker worker(int total, int perEvent) {
        return new EligibilityFanoutWorker(coordinator, batch, new EligibilityFanoutSchedulingProperties(false, total, perEvent, 60000));
    }
    private EventFanoutExecution claim(long id) {
        var execution = new EventFanoutExecution(id, UUID.randomUUID());
        when(coordinator.claim(id)).thenReturn(Optional.of(execution));
        when(coordinator.yieldExecution(execution)).thenReturn(true);
        return execution;
    }

    @Test
    void totalBudgetStopsBeforeClaimingMoreEvents() {
        when(coordinator.candidates()).thenReturn(List.of(1L, 2L, 3L));
        var first = claim(1); var second = claim(2);
        var result = worker(2, 1).runOnce();
        assertThat(result.pageCalls()).isEqualTo(2); assertThat(result.yieldedEvents()).isEqualTo(2);
        verify(batch).process(first); verify(batch).process(second);
        verify(coordinator, never()).claim(3L);
        verify(coordinator, never()).retry(any(), any());
    }

    @Test
    void perEventBudgetAllowsFollowingCandidateAndTerminalPageIsNotYielded() {
        when(coordinator.candidates()).thenReturn(List.of(1L, 2L));
        var first = claim(1); var second = claim(2);
        when(batch.process(second)).thenReturn(true);
        var result = worker(4, 2).runOnce();
        assertThat(result.pageCalls()).isEqualTo(3); assertThat(result.claimedEvents()).isEqualTo(2);
        verify(batch, times(2)).process(first); verify(coordinator).yieldExecution(first);
        verify(coordinator, never()).yieldExecution(second);
    }

    @Test
    void lostClaimsDoNotSpendPageBudget() {
        when(coordinator.candidates()).thenReturn(List.of(1L, 2L));
        when(coordinator.claim(1L)).thenReturn(Optional.empty());
        var second = claim(2); when(batch.process(second)).thenReturn(true);
        var result = worker(1, 1).runOnce();
        assertThat(result.claimedEvents()).isEqualTo(1); assertThat(result.pageCalls()).isEqualTo(1);
    }

    @Test
    void failedPageAndFailedRetryPersistenceDoNotAbortOtherEvents() {
        when(coordinator.candidates()).thenReturn(List.of(1L, 2L));
        var first = claim(1); var second = claim(2);
        when(batch.process(first)).thenThrow(new IllegalStateException("private failure"));
        when(coordinator.retry(eq(first), any())).thenThrow(new IllegalStateException("storage unavailable"));
        when(batch.process(second)).thenReturn(true);
        var result = worker(2, 1).runOnce();
        assertThat(result.failures()).isEqualTo(1); assertThat(result.pageCalls()).isEqualTo(2);
        verify(batch).process(second); verify(coordinator, never()).yieldExecution(first);
    }

    @Test
    void failedYieldUsesFailureRetryRatherThanRefundingLocally() {
        when(coordinator.candidates()).thenReturn(List.of(1L)); var first = claim(1);
        when(coordinator.yieldExecution(first)).thenThrow(new IllegalStateException("storage unavailable"));
        assertThat(worker(1, 1).runOnce().failures()).isEqualTo(1);
        verify(coordinator).retry(eq(first), any());
    }

    @Test
    void discoveryFailureReleasesTheExecutionGuard() {
        when(coordinator.candidates()).thenThrow(new IllegalStateException("unavailable")).thenReturn(List.of());
        var worker = worker(1, 1);
        assertThatThrownBy(worker::runOnce).isInstanceOf(IllegalStateException.class);
        assertThat(worker.runOnce().alreadyRunning()).isFalse();
    }

    @Test
    void overlappingInvocationDoesNotClaimTwice() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(coordinator.candidates()).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return List.of();
        });
        var worker = worker(1, 1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(worker::runOnce);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(worker.runOnce().alreadyRunning()).isTrue();
            } finally { release.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS).alreadyRunning()).isFalse();
        }
        verify(coordinator, times(1)).candidates();
    }
}
