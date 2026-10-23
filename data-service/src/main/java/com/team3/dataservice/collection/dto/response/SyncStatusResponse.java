package com.team3.dataservice.collection.dto.response;

import com.team3.dataservice.collection.domain.SyncJobStatus;
import java.time.OffsetDateTime;

public record SyncStatusResponse(
        Long jobId,
        String source,
        SyncJobStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        Integer totalCount,
        int fetchedCount,
        int successCount,
        int failedRecordCount,
        int pendingCount,
        int failedPageCount
) {}

