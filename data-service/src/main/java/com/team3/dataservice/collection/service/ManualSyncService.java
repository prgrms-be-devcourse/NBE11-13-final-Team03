package com.team3.dataservice.collection.service;

import com.team3.dataservice.collection.domain.CollectionSource;
import com.team3.dataservice.collection.dto.response.SyncStartResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

@Service
public class ManualSyncService {
    private final SyncJobService jobs;
    private final ManualSyncWorker worker;
    private final TaskExecutor executor;

    public ManualSyncService(SyncJobService jobs, ManualSyncWorker worker,
                             @Qualifier("collectionExecutor") TaskExecutor executor) {
        this.jobs = jobs;
        this.worker = worker;
        this.executor = executor;
    }

    public SyncStartResponse start(CollectionSource source, int pageSize, int maxPages) {
        return start(source, pageSize, maxPages, false);
    }

    public SyncStartResponse startScheduled(CollectionSource source) {
        return start(source, 100, 1000, true);
    }

    public SyncStartResponse retry(Long jobId) {
        var response = jobs.createRetry(jobId);
        return submit(response, CollectionSource.valueOf(response.source()), 100, 1000);
    }

    private SyncStartResponse start(CollectionSource source, int pageSize, int maxPages, boolean scheduled) {
        if (pageSize < 1 || pageSize > 100 || maxPages < 1 || maxPages > 1000) {
            throw new IllegalArgumentException("페이지 크기는 1~100, 최대 페이지 수는 1~1000이어야 합니다.");
        }
        var response = scheduled ? jobs.createScheduled(source) : jobs.create(source);
        return submit(response, source, pageSize, maxPages);
    }

    private SyncStartResponse submit(SyncStartResponse response, CollectionSource source, int pageSize, int maxPages) {
        try {
            executor.execute(() -> worker.run(response.jobId(), source, pageSize, maxPages));
        } catch (TaskRejectedException exception) {
            jobs.fail(response.jobId(), "실행기를 사용할 수 없어 최신화를 시작하지 못했습니다.", false);
            throw exception;
        }
        return response;
    }
}
