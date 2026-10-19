package com.team3.backend.collection.service;

import com.team3.backend.collection.domain.CollectionSource;
import com.team3.backend.collection.dto.response.SyncStartResponse;
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
        if (pageSize < 1 || pageSize > 100 || maxPages < 1 || maxPages > 1000) {
            throw new IllegalArgumentException("페이지 크기는 1~100, 최대 페이지 수는 1~1000이어야 합니다.");
        }
        var response = jobs.create(source);
        try {
            executor.execute(() -> worker.run(response.jobId(), source, pageSize, maxPages));
        } catch (TaskRejectedException exception) {
            jobs.fail(response.jobId(), "실행기를 사용할 수 없어 최신화를 시작하지 못했습니다.", false);
            throw exception;
        }
        return response;
    }
}
