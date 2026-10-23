package com.team3.dataservice.collection.service;

import com.team3.dataservice.collection.domain.CollectionSource;
import com.team3.dataservice.collection.domain.SyncJobStatus;
import com.team3.dataservice.collection.dto.response.SyncStartResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManualSyncServiceTests {
    private final SyncJobService jobs = mock(SyncJobService.class);
    private final ManualSyncWorker worker = mock(ManualSyncWorker.class);
    private final TaskExecutor executor = mock(TaskExecutor.class);
    private final ManualSyncService service = new ManualSyncService(jobs, worker, executor);

    @Test
    void scheduledCollectionUsesScheduledHistoryAndExistingWorker() {
        when(jobs.createScheduled(CollectionSource.YOUTH_POLICY)).thenReturn(
                new SyncStartResponse(2L, "YOUTH_POLICY", SyncJobStatus.PENDING, "accepted"));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        service.startScheduled(CollectionSource.YOUTH_POLICY);
        verify(worker).run(2L, CollectionSource.YOUTH_POLICY, 100, 1000);
        verify(jobs, never()).create(any());
    }

    @Test
    void invalidLimitsDoNotCreateJobs() {
        assertThrows(IllegalArgumentException.class, () -> service.start(CollectionSource.YOUTH_POLICY, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> service.start(CollectionSource.YOUTH_POLICY, 101, 1));
        assertThrows(IllegalArgumentException.class, () -> service.start(CollectionSource.YOUTH_POLICY, 1, 1001));
        verifyNoInteractions(jobs, executor);
    }

    @Test
    void rejectionLeavesAFailedJobInsteadOfBlockingTheSourceIndefinitely() {
        when(jobs.create(CollectionSource.YOUTH_POLICY)).thenReturn(
                new SyncStartResponse(1L, "YOUTH_POLICY", SyncJobStatus.PENDING, "accepted"));
        doThrow(new TaskRejectedException("executor stopped")).when(executor).execute(any(Runnable.class));
        assertThrows(TaskRejectedException.class, () -> service.start(CollectionSource.YOUTH_POLICY, 100, 1000));
        verify(jobs).fail(eq(1L), anyString(), eq(false));
        verifyNoInteractions(worker);
    }
}
