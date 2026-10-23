package com.team3.backend.tracking.service;

import com.team3.backend.tracking.domain.model.EventFanoutExecution;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;

class EventFanoutWorkerTest {
    private final EventFanoutCoordinator coordinator = mock(EventFanoutCoordinator.class);
    private final EventFanoutBatch batch = mock(EventFanoutBatch.class);
    private final EventFanoutWorker worker = new EventFanoutWorker(coordinator, batch);

    @Test
    void skipsUnclaimedEventAndCommitsThroughIndependentBatchCalls() {
        when(coordinator.candidates()).thenReturn(List.of(1L, 2L));
        when(coordinator.claim(1L)).thenReturn(Optional.empty());
        var execution = new EventFanoutExecution(2L, UUID.randomUUID());
        when(coordinator.claim(2L)).thenReturn(Optional.of(execution));
        when(batch.process(execution)).thenReturn(false, false, true);
        worker.runOnce();
        verify(batch, times(3)).process(execution);
        verify(coordinator, never()).retry(any(), any());
    }

    @Test
    void recoversExhaustedFanoutsBeforeSelectingCandidates() {
        when(coordinator.candidates()).thenReturn(List.of());
        worker.runOnce();
        var order = inOrder(coordinator);
        order.verify(coordinator).recoverExhausted();
        order.verify(coordinator).candidates();
    }

    @Test
    void recordsFailureOutsideRolledBackBatchAndContinuesOtherEvents() {
        when(coordinator.candidates()).thenReturn(List.of(1L, 2L));
        var first = new EventFanoutExecution(1L, UUID.randomUUID());
        var second = new EventFanoutExecution(2L, UUID.randomUUID());
        when(coordinator.claim(1L)).thenReturn(Optional.of(first));
        when(coordinator.claim(2L)).thenReturn(Optional.of(second));
        var failure = new IllegalStateException("test");
        when(batch.process(first)).thenThrow(failure);
        when(batch.process(second)).thenReturn(true);
        worker.runOnce();
        verify(coordinator).retry(first, failure);
        verify(batch).process(second);
    }
}
